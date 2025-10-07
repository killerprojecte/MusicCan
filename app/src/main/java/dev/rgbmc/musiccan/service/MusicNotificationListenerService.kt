package dev.rgbmc.musiccan.service

import android.app.Notification
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.graphics.createBitmap
import dev.rgbmc.musiccan.MusicCanApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicNotificationListenerService : NotificationListenerService() {
    // 协程作用域，用于异步操作
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onListenerConnected() {
        super.onListenerConnected()
        (application as? MusicCanApp)?.startMirror(ComponentName(this, javaClass), null)
        (application as? MusicCanApp)?.refreshSessionsFromService(ComponentName(this, javaClass))
        suppressTargetNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        Log.d("Notif", "Posted: " + sbn?.packageName)
        (application as? MusicCanApp)?.refreshSessionsFromService(ComponentName(this, javaClass))
        maybeCancelIfFromTarget(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        Log.d("Notif", "Removed: " + sbn?.packageName)
        (application as? MusicCanApp)?.refreshSessionsFromService(ComponentName(this, javaClass))
    }

    override fun onDestroy() {
        super.onDestroy()
        // 取消所有协程，确保资源清理
        coroutineScope.coroutineContext.cancel()
    }

    private fun suppressTargetNotifications() {
        val targets =
            (application as? MusicCanApp)?.mediaMirrorManager?.getTargetPackages() ?: return
        activeNotifications?.forEach { n ->
            if (targets.contains(n.packageName) && n.notification.category == Notification.CATEGORY_TRANSPORT) {
                cancelNotification(n.key)
            }
        }
    }

    private fun maybeCancelIfFromTarget(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val targets =
            (application as? MusicCanApp)?.mediaMirrorManager?.getTargetPackages() ?: return
        if (targets.contains(pkg) && sbn.notification.category == Notification.CATEGORY_TRANSPORT) {
            // 在取消通知之前，提取图标信息
            extractNotificationIcons(sbn)
            cancelNotification(sbn.key)
        }
    }

    private fun extractNotificationIcons(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val app = (application as? MusicCanApp)

        // 异步处理图标提取，避免阻塞主线程
        coroutineScope.launch {
            try {
                // 在IO线程中提取图标
                val smallIconBitmap = withContext(Dispatchers.IO) {
                    val smallIcon = notification.smallIcon
                    smallIcon?.let { drawableToBitmap(it.loadDrawable(this@MusicNotificationListenerService)) }
                }

                // 在主线程中更新UI
                withContext(Dispatchers.Main) {
                    // 将图标信息传递给MediaMirrorManager
                    app?.mediaMirrorManager?.updateNotificationIcons(smallIconBitmap)

                    Log.d(
                        "NotifIcons",
                        "Extracted icons for ${sbn.packageName}: small=${smallIconBitmap != null}"
                    )
                }
            } catch (e: Exception) {
                Log.e("NotifIcons", "Failed to extract icons", e)
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null

        return if (drawable is BitmapDrawable) {
            drawable.bitmap
        } else {
            val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }
    }
}



