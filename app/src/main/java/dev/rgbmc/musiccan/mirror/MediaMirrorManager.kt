package dev.rgbmc.musiccan.mirror

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

class MediaMirrorManager(
    private val appContext: Context,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mediaSessionManager =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private var mirroredSession: MediaSession? = null
    private var targetPackages: Set<String> = emptySet()
    private var activeController: MediaController? = null
    private var targetController: MediaController? = null // 保存目标应用的控制器
    private var registeredComponent: ComponentName? = null
    private var listenerRegistered: Boolean = false
    private var callbackRegistered: Boolean = false // 跟踪回调注册状态

    // 移除主动进度计算，改为被动监听
    // private var progressTickerRunning: Boolean = false
    private var overrideTickerRunning: Boolean = false
    private val notificationListeners = mutableSetOf<MirrorNotificationListener>()

    // 存储从目标应用通知中提取的图标
    private var extractedSmallIcon: android.graphics.Bitmap? = null

    // 节流：仅在状态/速率/主要元数据变化时通知，忽略仅 position 更新
    private var lastNotifiedStateCode: Int? = null
    private var lastNotifiedSpeed: Float? = null
    private var lastNotifiedMetadataKey: Int? = null

    // 本地拖动进度处理
    private var isLocalSeeking: Boolean = false
    private var localSeekPosition: Long = 0L
    private var localSeekStartTime: Long = 0L
    // 移除主动进度计算，改为被动监听目标应用的状态变化
    // private val progressTicker = object : Runnable { ... } // 已移除

    // 定时覆盖检测器
    private val overrideTicker = Runnable {
        if (overrideTickerRunning) {
            checkAndOverrideTargetController()
            // 同时同步最新的状态
            syncLatestTargetState()
            scheduleNextOverrideCheck()
        }
    }

    // 快速覆盖检测器 - 用于用户操作后的立即检测
    private val quickOverrideTicker = Runnable {
        if (overrideTickerRunning) {
            checkAndOverrideTargetController()
            syncLatestTargetState()
            // 快速检测后，延迟启动常规检测
            mainHandler.postDelayed({
                if (overrideTickerRunning) {
                    scheduleNextOverrideCheck()
                }
            }, 1500) // 1秒后启动常规检测
        }
    }

    // 会话刷新检测器 - 定期刷新我们的会话状态
    private val sessionRefreshTicker: Runnable = Runnable {
        if (overrideTickerRunning) {
            refreshSessionState()
            // 每3秒刷新一次会话状态
            mainHandler.postDelayed(sessionRefreshTicker, 3000)
        }
    }

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            updateActiveController(controllers)
        }

    fun start(componentName: ComponentName, packageNames: Set<String>?) {
        ensureLocalSession()
        targetPackages = packageNames ?: targetPackages
        if (!listenerRegistered || registeredComponent != componentName) {
            if (listenerRegistered) {
                mediaSessionManager.removeOnActiveSessionsChangedListener(sessionListener)
            }
            mediaSessionManager.addOnActiveSessionsChangedListener(
                sessionListener,
                componentName,
                mainHandler
            )
            listenerRegistered = true
            registeredComponent = componentName
        }
        updateActiveController(mediaSessionManager.getActiveSessions(componentName))
        // 通知监听者会话已就绪
        notifyAllListeners()
    }

    @Suppress("unused")
    fun stop(componentName: ComponentName) {
        if (listenerRegistered) {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionListener)
            listenerRegistered = false
            registeredComponent = null
        }
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        releaseLocalSession()
    }

    fun updateTargetPackages(packages: Set<String>) {
        targetPackages = packages
        val cn = registeredComponent ?: return
        try {
            updateActiveController(mediaSessionManager.getActiveSessions(cn))
        } catch (_: SecurityException) {
            // 权限未授予或组件未启用，等待服务连接后再刷新
        }
        notifyAllListeners()
    }

    fun refreshActiveSessions(componentName: ComponentName) {
        try {
            updateActiveController(mediaSessionManager.getActiveSessions(componentName))
        } catch (se: SecurityException) {
            Log.d("MirrorMan", "Refresh", se)
            // 忽略：未授予通知监听时不可获取，会在授权后恢复
        }
    }

    private fun updateActiveController(controllers: List<MediaController>?) {
        val target = targetPackages

        // 查找目标应用的控制器
        val targetController = controllers
            ?.sortedByDescending { c -> (c.playbackState?.state == PlaybackState.STATE_PLAYING) }
            ?.firstOrNull { c ->
                Log.d("MirrorMan", "UpdateActive List: ${c.packageName}")
                target.isEmpty() || target.contains(c.packageName)
            }

        // 如果找到目标控制器，保存它并创建我们自己的控制器来覆盖
        if (targetController != null) {
            // 保存目标控制器用于操作代理
            this.targetController = targetController

            val ourController = createOverrideController(targetController)
            if (ourController != activeController) {
                activeController?.unregisterCallback(controllerCallback)
                activeController = ourController

                // 注册目标控制器的回调来监听状态变化（避免重复注册）
                if (!callbackRegistered) {
                    targetController.registerCallback(controllerCallback, mainHandler)
                    callbackRegistered = true
                    Log.d("MediaMirror", "Registered callback for target controller")
                }
                controllerCallback.onMetadataChanged(targetController.metadata)
                controllerCallback.onPlaybackStateChanged(targetController.playbackState)
                controllerCallback.onQueueChanged(targetController.queue)

                // 移除主动进度计算
                // 更新会话跳转意图，利于系统展示
                mirroredSession?.setSessionActivity(getBestContentIntent(appContext))
                notifyAllListeners()

                Log.d("MediaMirror", "Overrode target controller: ${targetController.packageName}")
                // 启动覆盖检测
                startOverrideCheck()
            }
        } else {
            // 如果没有目标控制器，清空活跃控制器
            // 注销目标控制器的回调
            targetController?.unregisterCallback(controllerCallback)
            callbackRegistered = false
            Log.d("MediaMirror", "Unregistered callback for target controller")
            this.targetController = null
            if (activeController != null) {
                activeController?.unregisterCallback(controllerCallback)
                activeController = null
                mirroredSession?.setMetadata(null)
                mirroredSession?.setPlaybackState(null)
                notifyAllListeners(force = true)
            }
            // 停止覆盖检测
            stopOverrideCheck()
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureLocalSession() {
        if (mirroredSession == null) {
            mirroredSession = MediaSession(appContext, "MusicCanMirrorSession").apply {
                // 设置媒体会话标志，确保我们的会话能够处理媒体控制
                setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                            MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                setPlaybackToLocal(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setSessionActivity(getBestContentIntent(appContext))
                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        targetController?.transportControls?.play()
                        // 用户操作后立即启动快速覆盖检测
                        startQuickOverrideCheck()
                        Log.d(
                            "MediaMirror",
                            "Proxied play to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onPause() {
                        targetController?.transportControls?.pause()
                        // 用户操作后立即启动快速覆盖检测
                        startQuickOverrideCheck()
                        Log.d(
                            "MediaMirror",
                            "Proxied pause to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onStop() {
                        targetController?.transportControls?.stop()
                        Log.d(
                            "MediaMirror",
                            "Proxied stop to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onSkipToNext() {
                        targetController?.transportControls?.skipToNext()
                        // 切换曲目后立即启动快速覆盖检测
                        startQuickOverrideCheck()
                        Log.d(
                            "MediaMirror",
                            "Proxied skip to next to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onSkipToPrevious() {
                        targetController?.transportControls?.skipToPrevious()
                        // 切换曲目后立即启动快速覆盖检测
                        startQuickOverrideCheck()
                        Log.d(
                            "MediaMirror",
                            "Proxied skip to previous to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onFastForward() {
                        targetController?.transportControls?.fastForward()
                        Log.d(
                            "MediaMirror",
                            "Proxied fast forward to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onRewind() {
                        targetController?.transportControls?.rewind()
                        Log.d(
                            "MediaMirror",
                            "Proxied rewind to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onSeekTo(pos: Long) {
                        // 开始本地拖动
                        startLocalSeek(pos)
                        // 同时发送到目标应用
                        targetController?.transportControls?.seekTo(pos)
                        // 调整进度后立即启动快速覆盖检测
                        startQuickOverrideCheck()
                        Log.d(
                            "MediaMirror",
                            "Proxied seek to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                        targetController?.transportControls?.playFromMediaId(mediaId, extras)
                        Log.d(
                            "MediaMirror",
                            "Proxied play from media ID to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                        targetController?.transportControls?.playFromSearch(query, extras)
                        Log.d(
                            "MediaMirror",
                            "Proxied play from search to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onPlayFromUri(uri: Uri?, extras: Bundle?) {
                        targetController?.transportControls?.playFromUri(uri, extras)
                        Log.d(
                            "MediaMirror",
                            "Proxied play from URI to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onPrepare() {
                        targetController?.transportControls?.prepare()
                        Log.d(
                            "MediaMirror",
                            "Proxied prepare to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
                        targetController?.transportControls?.prepareFromMediaId(mediaId, extras)
                        Log.d(
                            "MediaMirror",
                            "Proxied prepare from media ID to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
                        targetController?.transportControls?.prepareFromSearch(query, extras)
                        Log.d(
                            "MediaMirror",
                            "Proxied prepare from search to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onPrepareFromUri(uri: Uri?, extras: Bundle?) {
                        targetController?.transportControls?.prepareFromUri(uri, extras)
                        Log.d(
                            "MediaMirror",
                            "Proxied prepare from URI to target: ${targetController?.packageName}"
                        )
                    }

                    // repeat/shuffle 映射移除
                    override fun onSetRating(rating: Rating) {
                        targetController?.transportControls?.setRating(rating)
                        Log.d(
                            "MediaMirror",
                            "Proxied set rating to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onSkipToQueueItem(id: Long) {
                        targetController?.transportControls?.skipToQueueItem(id)
                        Log.d(
                            "MediaMirror",
                            "Proxied skip to queue item to target: ${targetController?.packageName}"
                        )
                    }

                    override fun onCustomAction(action: String, extras: Bundle?) {
                        targetController?.transportControls?.sendCustomAction(action, extras)
                        Log.d(
                            "MediaMirror",
                            "Proxied custom action to target: ${targetController?.packageName}"
                        )
                    }
                })
                isActive = true
            }
            notifyAllListeners(force = true)
        }
    }

    private fun releaseLocalSession() {
        mirroredSession?.run {
            isActive = false
            release()
        }
        mirroredSession = null
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val session = mirroredSession ?: return
            session.setMetadata(metadata)
            session.setSessionActivity(getBestContentIntent(appContext))
            notifyAllListeners()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val session = mirroredSession ?: return

            // 如果正在本地拖动，忽略来自后端的进度更新
            if (isLocalSeeking) {
                Log.d("MediaMirror", "Ignoring backend state update during local seek")
                return
            }

            // 检查状态是否真的发生了变化，避免循环更新
            val currentState = session.controller.playbackState
            if (currentState?.state == state?.state &&
                currentState?.position == state?.position &&
                currentState?.playbackSpeed == state?.playbackSpeed
            ) {
                Log.d("MediaMirror", "Playback state unchanged, skipping update")
                return
            }

            // 使用被动进度更新机制
            updateProgressPassively(state)
            Log.d("MirrorMan", "PlayState changed from target: ${targetController?.packageName}")

            // 在播放时启动覆盖检测
            if (state?.state == PlaybackState.STATE_PLAYING && state.playbackSpeed > 0f) {
                Log.d("MirrorMan", "PS Force Notify")
                notifyAllListeners(force = true)
                startOverrideCheck()
            }

            notifyAllListeners()
        }

        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
            val session = mirroredSession ?: return
            session.setQueue(queue)
        }

        override fun onQueueTitleChanged(title: CharSequence?) {
            val session = mirroredSession ?: return
            session.setQueueTitle(title)
        }

        override fun onExtrasChanged(extras: Bundle?) {
            val session = mirroredSession ?: return
            session.setExtras(extras)
        }

        override fun onSessionEvent(event: String, extras: Bundle?) {
            // 透传事件为可选，这里不需要设置到本地 session
        }

        override fun onAudioInfoChanged(playbackInfo: MediaController.PlaybackInfo) {
            // 可根据需要设置音频属性到本地会话（此处保持默认）
        }

        // repeat/shuffle 变更回调移除
    }

    fun getSessionToken(): MediaSession.Token? = mirroredSession?.sessionToken
    fun getCurrentMetadata(): MediaMetadata? = mirroredSession?.controller?.metadata
    fun getCurrentPlaybackState(): PlaybackState? = mirroredSession?.controller?.playbackState
    fun getTargetPackages(): Set<String> = targetPackages

    fun getBestContentIntent(context: Context): PendingIntent? {
        // 优先从目标控制器获取内容意图
        val targetController = this.targetController
        if (targetController != null) {
            val sessionActivity = targetController.sessionActivity
            if (sessionActivity != null) {
                Log.d(
                    "MediaMirror",
                    "Using target controller content intent: ${targetController.packageName}"
                )
                return sessionActivity
            }
        }

        // 如果目标控制器没有内容意图，使用包启动意图
        val pkg = targetController?.packageName ?: return null
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: run {
                // 尝试显式 MAIN/LAUNCHER
                val intent = Intent(Intent.ACTION_MAIN)
                intent.addCategory(Intent.CATEGORY_LAUNCHER)
                intent.`package` = pkg
                intent
            }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun updateNotificationIcons(smallIcon: android.graphics.Bitmap?) {
        extractedSmallIcon = smallIcon
        Log.d("MediaMirror", "Updated notification icons: small=${smallIcon != null}")
    }

    fun getExtractedSmallIcon(): android.graphics.Bitmap? = extractedSmallIcon

    fun getActiveController(): MediaController? = activeController

    /**
     * 检查并覆盖目标控制器
     */
    private fun checkAndOverrideTargetController() {
        try {
            val componentName = registeredComponent ?: return
            val controllers = mediaSessionManager.getActiveSessions(componentName)
            val target = targetPackages

            // 查找目标应用的控制器
            val currentTargetController = controllers
                .sortedByDescending { c -> (c.playbackState?.state == PlaybackState.STATE_PLAYING) }
                .firstOrNull { c ->
                    target.isEmpty() || target.contains(c.packageName)
                }

            // 检查我们的会话是否仍然处于主导地位
            val ourSessionIsActive = isOurSessionActive(controllers)
            
            // 如果找到目标控制器且与当前不同，或者当前没有目标控制器
            if (currentTargetController != null && currentTargetController != targetController) {
                Log.d(
                    "MediaMirror",
                    "Detected new target controller, overriding: ${currentTargetController.packageName}"
                )
                updateActiveController(controllers)
            } else if (currentTargetController == null && targetController != null) {
                Log.d("MediaMirror", "Target controller disappeared, clearing")
                updateActiveController(controllers)
            } else if (!ourSessionIsActive && currentTargetController != null) {
                // 我们的会话被覆盖了，需要重新获取控制权
                Log.d("MediaMirror", "Our session was overridden, recovering control")
                recoverSessionControl(controllers)
            }
        } catch (e: Exception) {
            Log.e("MediaMirror", "Error checking override", e)
        }
    }

    /**
     * 检查我们的会话是否仍然处于活跃状态
     */
    private fun isOurSessionActive(controllers: List<MediaController>): Boolean {
        val ourPackageName = appContext.packageName
        return controllers.any { controller ->
            controller.packageName == ourPackageName &&
                    controller.playbackState?.state == PlaybackState.STATE_PLAYING
        }
    }

    /**
     * 恢复会话控制权
     */
    private fun recoverSessionControl(controllers: List<MediaController>) {
        try {
            // 重新激活我们的会话
            mirroredSession?.isActive = true

            // 重新同步目标状态
            val targetController = controllers
                .sortedByDescending { c -> (c.playbackState?.state == PlaybackState.STATE_PLAYING) }
                .firstOrNull { c ->
                    targetPackages.isEmpty() || targetPackages.contains(c.packageName)
                }

            if (targetController != null) {
                this.targetController = targetController
                syncTargetStateToOurSession(targetController)
                Log.d(
                    "MediaMirror",
                    "Recovered session control for: ${targetController.packageName}"
                )
            }
        } catch (e: Exception) {
            Log.e("MediaMirror", "Error recovering session control", e)
        }
    }

    /**
     * 调度下一次覆盖检测
     * 由于无法使用系统级优先级，通过更频繁的检测来维持控制权
     */
    private fun scheduleNextOverrideCheck() {
        if (overrideTickerRunning) {
            mainHandler.postDelayed(overrideTicker, 1500) // 每1秒检测一次，更频繁的检测
        }
    }

    /**
     * 启动快速覆盖检测 - 用于用户操作后的立即检测
     */
    private fun startQuickOverrideCheck() {
        if (overrideTickerRunning) {
            mainHandler.removeCallbacks(quickOverrideTicker)
            mainHandler.post(quickOverrideTicker)
            Log.d("MediaMirror", "Started quick override check")
        }
    }

    /**
     * 启动覆盖检测
     */
    private fun startOverrideCheck() {
        if (!overrideTickerRunning) {
            overrideTickerRunning = true
            scheduleNextOverrideCheck()
            // 同时启动会话刷新检测
            startSessionRefresh()
            Log.d("MediaMirror", "Started override check and session refresh")
        }
    }

    /**
     * 停止覆盖检测
     */
    private fun stopOverrideCheck() {
        if (overrideTickerRunning) {
            overrideTickerRunning = false
            mainHandler.removeCallbacks(overrideTicker)
            mainHandler.removeCallbacks(sessionRefreshTicker)
            Log.d("MediaMirror", "Stopped override check and session refresh")
        }
    }

    /**
     * 启动会话刷新检测
     */
    private fun startSessionRefresh() {
        if (overrideTickerRunning) {
            mainHandler.removeCallbacks(sessionRefreshTicker)
            mainHandler.post(sessionRefreshTicker)
            Log.d("MediaMirror", "Started session refresh")
        }
    }

    /**
     * 刷新会话状态
     * 通过定期更新会话状态来维持其活跃性和优先级
     */
    private fun refreshSessionState() {
        try {
            val session = mirroredSession ?: return
            if (!session.isActive) {
                Log.d("MediaMirror", "Session became inactive, reactivating")
                session.isActive = true
            }

            // 获取当前状态并重新设置，强化会话存在感
            val currentState = session.controller.playbackState
            val currentMetadata = session.controller.metadata

            if (currentState != null) {
                session.setPlaybackState(currentState)
            }

            if (currentMetadata != null) {
                session.setMetadata(currentMetadata)
            }

            Log.d("MediaMirror", "Refreshed session state")
        } catch (e: Exception) {
            Log.e("MediaMirror", "Error refreshing session state", e)
        }
    }

    /**
     * 创建覆盖控制器，基于目标控制器的信息但使用我们的会话
     * 这样我们的会话就会成为主导的媒体会话
     */
    private fun createOverrideController(targetController: MediaController): MediaController? {
        return try {
            // 获取我们的会话token
            val ourSessionToken = mirroredSession?.sessionToken
            if (ourSessionToken == null) {
                Log.d("MediaMirror", "Our session token is null, cannot create override controller")
                return null
            }

            // 创建基于我们会话的控制器
            val ourController = MediaController(appContext, ourSessionToken)

            // 同步目标控制器的状态到我们的会话
            syncTargetStateToOurSession(targetController)

            Log.d(
                "MediaMirror",
                "Created override controller for target: ${targetController.packageName}"
            )
            ourController
        } catch (e: Exception) {
            Log.e("MediaMirror", "Error creating override controller", e)
            null
        }
    }

    /**
     * 将目标控制器的状态同步到我们的会话
     */
    private fun syncTargetStateToOurSession(targetController: MediaController) {
        try {
            val session = mirroredSession ?: return

            // 同步元数据
            val metadata = targetController.metadata
            if (metadata != null) {
                session.setMetadata(metadata)
            }

            // 同步播放状态
            val playbackState = targetController.playbackState
            if (playbackState != null) {
                session.setPlaybackState(ensureControlActions(playbackState))
            }

            // 同步队列
            val queue = targetController.queue
            if (queue != null) {
                session.setQueue(queue)
            }

            Log.d("MediaMirror", "Synced target state to our session")
        } catch (e: Exception) {
            Log.e("MediaMirror", "Error syncing target state", e)
        }
    }

    private fun startLocalSeek(position: Long) {
        isLocalSeeking = true
        localSeekPosition = position
        localSeekStartTime = SystemClock.elapsedRealtime()

        // 立即更新本地会话状态
        val session = mirroredSession
        val controller = activeController
        if (session != null && controller != null) {
            val state = controller.playbackState
            if (state != null) {
                val newState = PlaybackState.Builder(state)
                    .setState(
                        state.state,
                        position,
                        state.playbackSpeed,
                        SystemClock.elapsedRealtime()
                    )
                    .build()
                session.setPlaybackState(newState)
                notifyAllListeners()
            }
        }

        // 设置一个定时器，在一定时间后停止本地拖动模式
        mainHandler.postDelayed({
            stopLocalSeek()
        }, 2000) // 2秒后停止本地拖动模式
    }

    private fun stopLocalSeek() {
        isLocalSeeking = false
        // 当停止本地拖动时，让后端状态重新同步
        val controller = activeController
        if (controller != null) {
            controllerCallback.onPlaybackStateChanged(controller.playbackState)
        }
    }

    /*fun performTransport(action: String) {
        val tc = activeController?.transportControls ?: return
        when (action) {
            "ACTION_PREV" -> tc.skipToPrevious()
            "ACTION_NEXT" -> tc.skipToNext()
            "ACTION_TOGGLE" -> {
                val state = activeController?.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
            }
        }
    }*/

    interface MirrorNotificationListener {
        fun onMirrorSessionUpdated(
            token: MediaSession.Token?,
            metadata: MediaMetadata?,
            playbackState: PlaybackState?
        )
    }

    fun registerNotificationListener(listener: MirrorNotificationListener) {
        notificationListeners.add(listener)
        listener.onMirrorSessionUpdated(
            mirroredSession?.sessionToken,
            mirroredSession?.controller?.metadata,
            mirroredSession?.controller?.playbackState
        )
    }

    fun unregisterNotificationListener(listener: MirrorNotificationListener) {
        notificationListeners.remove(listener)
    }

    private fun notifyAllListeners(force: Boolean = false) {
        val session = mirroredSession
        val token = session?.sessionToken
        val metadata = session?.controller?.metadata
        val state = session?.controller?.playbackState

        if (!force) {
            val stateCode = state?.state
            val speed = state?.playbackSpeed
            val metaKey = if (metadata == null) null else (
                    (metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "").hashCode() * 31 +
                            (metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                                ?: "").hashCode() * 17 +
                            (metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "").hashCode()
                    )
            val unchanged =
                (stateCode == lastNotifiedStateCode && speed == lastNotifiedSpeed && metaKey == lastNotifiedMetadataKey)
            if (unchanged) return
            lastNotifiedStateCode = stateCode
            lastNotifiedSpeed = speed
            lastNotifiedMetadataKey = metaKey
        } else {
            lastNotifiedStateCode = state?.state
            lastNotifiedSpeed = state?.playbackSpeed
            lastNotifiedMetadataKey = if (metadata == null) null else (
                    (metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "").hashCode() * 31 +
                            (metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                                ?: "").hashCode() * 17 +
                            (metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "").hashCode()
                    )
        }
        notificationListeners.forEach { it.onMirrorSessionUpdated(token, metadata, state) }
    }

    private fun ensureControlActions(src: PlaybackState?): PlaybackState {
        val builder = if (src != null) PlaybackState.Builder(src) else PlaybackState.Builder()
        val stateCode = src?.state ?: PlaybackState.STATE_PAUSED
        val position = src?.position ?: 0L
        val speed = src?.playbackSpeed ?: 1.0f
        var actions = src?.actions ?: 0L
        actions =
            actions or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE
        actions = actions or PlaybackState.ACTION_SEEK_TO

        // 检查目标应用是否支持上一首/下一首，如果支持才添加
        val targetController = this.targetController
        if (targetController != null) {
            val targetState = targetController.playbackState
            val targetActions = targetState?.actions ?: 0L

            // 只有当目标应用支持上一首/下一首时才添加这些控件
            if (targetActions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) {
                actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                Log.d("MediaMirror", "Added skip to previous action")
            } else {
                Log.d("MediaMirror", "Target app does not support skip to previous")
            }

            if (targetActions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) {
                actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
                Log.d("MediaMirror", "Added skip to next action")
            } else {
                Log.d("MediaMirror", "Target app does not support skip to next")
            }
        } else {
            // 如果没有目标控制器，默认添加所有控件
            actions =
                actions or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            Log.d("MediaMirror", "No target controller, added default skip actions")
        }

        return builder
            .setState(stateCode, position, speed, SystemClock.elapsedRealtime())
            .setActions(actions)
            .build()
    }

    /**
     * 被动进度更新机制
     * 通过监听目标应用的状态变化来更新进度，确保精确度和准确性
     */
    private fun updateProgressPassively(targetState: PlaybackState?) {
        val session = mirroredSession ?: return
        if (targetState == null) return

        // 直接使用目标应用的状态，不进行任何计算
        val newState = ensureControlActions(targetState)
        session.setPlaybackState(newState)

        Log.d(
            "MediaMirror", "Passive progress update: position=${targetState.position}, " +
                    "state=${targetState.state}, speed=${targetState.playbackSpeed}"
        )
    }

    /**
     * 获取目标应用的最新状态
     * 用于确保我们始终使用最新的状态信息
     */
    private fun getLatestTargetState(): PlaybackState? {
        return targetController?.playbackState
    }

    /**
     * 同步目标应用的最新状态
     * 确保我们的会话始终反映目标应用的最新状态
     */
    private fun syncLatestTargetState() {
        try {
            val latestState = getLatestTargetState()
            if (latestState != null) {
                updateProgressPassively(latestState)
                Log.d("MediaMirror", "Synced latest target state: position=${latestState.position}")

                // 如果检测到状态变化，确保我们的会话仍然活跃
                ensureSessionDominance()
            }
        } catch (e: Exception) {
            Log.e("MediaMirror", "Error syncing latest target state", e)
        }
    }

    /**
     * 确保我们的会话处于主导地位
     * 通过更频繁的状态更新和积极的会话管理来维持优先级
     */
    private fun ensureSessionDominance() {
        try {
            val session = mirroredSession ?: return
            if (!session.isActive) {
                Log.d("MediaMirror", "Reactivating our session to maintain dominance")
                session.isActive = true
            }

            // 强制更新会话状态，确保系统识别我们的会话为活跃状态
            val currentState = session.controller.playbackState
            if (currentState != null) {
                // 通过重新设置状态来强化我们的会话优先级
                session.setPlaybackState(currentState)

                // 同时更新元数据，进一步强化会话存在感
                val metadata = session.controller.metadata
                if (metadata != null) {
                    session.setMetadata(metadata)
                }

                Log.d("MediaMirror", "Reinforced session dominance")
            }

            // 如果检测到我们的会话可能被覆盖，立即启动快速检测
            if (targetController != null) {
                startQuickOverrideCheck()
            }
        } catch (e: Exception) {
            Log.e("MediaMirror", "Error ensuring session dominance", e)
        }
    }
}



