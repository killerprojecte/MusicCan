package dev.rgbmc.musiccan.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.luna.music.R
import dev.rgbmc.musiccan.MainActivity
import dev.rgbmc.musiccan.MusicCanApp
import dev.rgbmc.musiccan.mirror.MediaMirrorManager

class MirrorForegroundService : Service(), MediaMirrorManager.MirrorNotificationListener {
    private val channelId = "mirror_media_channel"
    private val notifId = 1001

    // 通知缓存和复用
    private var lastNotification: Notification? = null
    private var lastTitle: String? = null
    private var lastSubtitle: String? = null
    private var lastIsPlaying: Boolean? = null

    // 性能统计
    private var notificationBuildCount = 0
    private var notificationReuseCount = 0
    private var cooldownSkipCount = 0

    // 冷却机制
    private var lastUpdateTime = 0L
    private val updateCooldownMs = 500L // 1秒冷却间隔

    // 应用包名缓存
    private var cachedPackageName: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        resetCooldown() // 重置冷却时间，确保首次更新能立即执行
        getCurrentPackageName() // 初始化包名缓存
        (application as? MusicCanApp)?.mediaMirrorManager?.registerNotificationListener(this)
        startForeground(notifId, buildNotification(null, null, null))
    }

    override fun onDestroy() {
        (application as? MusicCanApp)?.mediaMirrorManager?.unregisterNotificationListener(this)
        clearNotificationCache()
        super.onDestroy()
    }

    private fun clearNotificationCache() {
        lastNotification = null
        lastTitle = null
        lastSubtitle = null
        lastIsPlaying = null
        cachedPackageName = null
    }

    private fun resetCooldown() {
        lastUpdateTime = 0L
    }

    /**
     * 获取并缓存当前应用的包名
     */
    private fun getCurrentPackageName(): String {
        if (cachedPackageName == null) {
            cachedPackageName = packageName
            Log.d("ForegroundSrv", "Cached package name: $cachedPackageName")
        }
        return cachedPackageName!!
    }

    /**
     * 检查我们的会话是否是最活跃的
     * 现在activeController应该是我们自己的控制器
     */
    private fun isOurSessionTheActiveOne(): Boolean {
        return try {
            val app = (application as? MusicCanApp)
            val mediaMirrorManager = app?.mediaMirrorManager ?: return false

            // 获取当前活跃的控制器
            val activeController = mediaMirrorManager.getActiveController()
            if (activeController == null) {
                Log.d("ForegroundSrv", "No active media controller")
                return false
            }

            // 获取当前应用的包名
            val currentPackageName = getCurrentPackageName()

            // 检查活跃控制器的包名是否是我们应用的包名
            val isOurApp = activeController.packageName == currentPackageName

            Log.d(
                "ForegroundSrv", "Active controller package: ${activeController.packageName}, " +
                        "Our package: $currentPackageName, Is our app: $isOurApp"
            )

            // 现在activeController应该是我们自己的控制器
            isOurApp
        } catch (e: Exception) {
            Log.e("ForegroundSrv", "Error checking session activity", e)
            false
        }
    }


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 初次启动时如果已有会话，立即刷新一次通知
        val app = (application as? MusicCanApp)
        if (app != null) {
            val token = app.mediaMirrorManager.getSessionToken()
            val metadata = app.mediaMirrorManager.getCurrentMetadata()
            val state = app.mediaMirrorManager.getCurrentPlaybackState()
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notifId, buildNotification(token, metadata, state))
        }
        return START_STICKY
    }

    @SuppressLint("WrongConstant")
    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            channelId,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_MAX
        )
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(
        token: MediaSession.Token?,
        metadata: MediaMetadata?,
        state: PlaybackState?,
    ): Notification {
        val ts = System.currentTimeMillis()
        val compatToken = token?.let { MediaSessionCompat.Token.fromToken(it) }
        val title =
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: getString(R.string.app_name)
        val subtitle = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val app = (application as? MusicCanApp)
        val extractedSmallIcon = app?.mediaMirrorManager?.getExtractedSmallIcon()

        // 检查是否可以复用上一条通知
        if (canReuseLastNotification(title, subtitle, isPlaying)) {
            // 检查我们的媒体会话是否在最前面
            if (isOurSessionTheActiveOne()) {
                notificationReuseCount++
                Log.d(
                    "ForegroundSrv",
                    "Reusing last notification: " + (System.currentTimeMillis() - ts) +
                            " (Reuse: $notificationReuseCount, Build: $notificationBuildCount)"
                )
                return lastNotification!!
            } else {
                Log.d("ForegroundSrv", "Media session not on top, rebuilding notification")
            }
        }

        val contentIntent = app?.mediaMirrorManager?.getBestContentIntent(this)
            ?: PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentIntent)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setStyle(MediaStyle().setMediaSession(compatToken))
            .setPriority(NotificationCompat.PRIORITY_HIGH) // 设置高优先级
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT) // 设置为传输类别
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 设置为公开可见

        if (extractedSmallIcon != null) {
            builder.setSmallIcon(IconCompat.createWithBitmap(extractedSmallIcon))
        } else {
            builder.setSmallIcon(R.mipmap.ic_launcher_foreground)
        }

        val notification = builder.build()

        // 缓存当前通知信息
        cacheNotificationInfo(notification, title, subtitle, isPlaying)

        notificationBuildCount++
        Log.d(
            "ForegroundSrv", "Build new notification: " + (System.currentTimeMillis() - ts) +
                    " (Reuse: $notificationReuseCount, Build: $notificationBuildCount)"
        )
        return notification
    }

    private fun canReuseLastNotification(
        title: String,
        subtitle: String,
        isPlaying: Boolean,
    ): Boolean {
        if (lastNotification == null) return false

        // 检查关键属性是否发生变化
        val titleChanged = lastTitle != title
        val subtitleChanged = lastSubtitle != subtitle
        val playingStateChanged = lastIsPlaying != isPlaying

        // 如果任何关键属性发生变化，不能复用
        return !titleChanged && !subtitleChanged && !playingStateChanged
    }

    private fun cacheNotificationInfo(
        notification: Notification,
        title: String,
        subtitle: String,
        isPlaying: Boolean
    ) {
        lastNotification = notification
        lastTitle = title
        lastSubtitle = subtitle
        lastIsPlaying = isPlaying
    }

    override fun onMirrorSessionUpdated(
        token: MediaSession.Token?,
        metadata: MediaMetadata?,
        playbackState: PlaybackState?
    ) {
        val currentTime = System.currentTimeMillis()

        // 检查冷却间隔
        /*if (currentTime - lastUpdateTime < updateCooldownMs) {
            cooldownSkipCount++
            Log.d("ForegroundSrv", "Update cooldown active, skipping update (Skip: $cooldownSkipCount)")
            return
        }*/

        val ts = System.currentTimeMillis()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (token == null || playbackState == null) {
            // 无活动会话，停止前台并移除通知
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        nm.notify(notifId, buildNotification(token, metadata, playbackState))
        lastUpdateTime = currentTime

        Log.d("ForegroundSrv", "Notify: " + (System.currentTimeMillis() - ts))
    }
}

