package dev.rgbmc.musiccan

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import dev.rgbmc.musiccan.mirror.MediaMirrorManager
import dev.rgbmc.musiccan.service.MirrorForegroundService

class MusicCanApp : Application() {
    lateinit var mediaMirrorManager: MediaMirrorManager
        private set

    override fun onCreate() {
        super.onCreate()
        mediaMirrorManager = MediaMirrorManager(applicationContext)
    }

    fun startMirror(componentName: ComponentName, targetPackages: Set<String>?) {
        mediaMirrorManager.start(componentName, targetPackages)
        // 启动前台服务以展示镜像媒体通知
        val intent = Intent(this, MirrorForegroundService::class.java)
        try {
            startForegroundService(intent)
        } catch (_: Throwable) {
            startService(intent)
        }
    }

    fun stopMirror(componentName: ComponentName) {
        mediaMirrorManager.stop(componentName)
        // 停止前台服务
        stopService(Intent(this, MirrorForegroundService::class.java))
    }

    fun updateTargetPackages(packages: Set<String>) {
        mediaMirrorManager.updateTargetPackages(packages)
    }

    fun refreshSessionsFromService(componentName: ComponentName) {
        mediaMirrorManager.refreshActiveSessions(componentName)
    }
}



