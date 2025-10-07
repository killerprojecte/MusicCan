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

class MusicNotificationListenerService : NotificationListenerService() {
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

        try {
            // 提取小图标
            val smallIcon = notification.smallIcon
            val smallIconBitmap = smallIcon?.let { drawableToBitmap(it.loadDrawable(this)) }

            // 将图标信息传递给MediaMirrorManager
            app?.mediaMirrorManager?.updateNotificationIcons(smallIconBitmap)

            Log.d(
                "NotifIcons",
                "Extracted icons for ${sbn.packageName}: small=${smallIconBitmap != null}"
            )
        } catch (e: Exception) {
            Log.e("NotifIcons", "Failed to extract icons", e)
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



