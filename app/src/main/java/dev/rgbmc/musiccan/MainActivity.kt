package dev.rgbmc.musiccan

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import dev.rgbmc.musiccan.data.SettingsRepository
import dev.rgbmc.musiccan.service.MusicNotificationListenerService
import dev.rgbmc.musiccan.ui.AppPickerScreen
import dev.rgbmc.musiccan.ui.theme.MusicCanTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var settingsRepository: SettingsRepository
    private val requestPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settingsRepository = SettingsRepository(applicationContext)
        setContent {
            MusicCanTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text("MusicCan") },
                            actions = {
                                IconButton(
                                    onClick = {
                                        val intent = Intent(
                                            Intent.ACTION_VIEW,
                                            "https://github.com/killerprojecte/MusicCan".toUri()
                                        )
                                        startActivity(intent)
                                    }
                                ) {
                                    Icon(
                                        imageVector = ExtendIcons.Github,
                                        contentDescription = "GitHub项目地址"
                                    )
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    val selected by settingsRepository.targetPackagesFlow.collectAsState(initial = emptySet())
                    Column(Modifier.padding(innerPadding)) {
                        PermissionSection(
                            onOpenNotificationAccess = {
                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            onRequestPostNotifications = {
                                requestPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        )
                        AppPickerScreen(
                            packageManager = packageManager,
                            selectedPackages = selected,
                            onToggle = { pkg ->
                                lifecycleScope.launch {
                                    settingsRepository.toggleTargetPackage(pkg)
                                }
                            },
                            onClearAll = {
                                lifecycleScope.launch {
                                    settingsRepository.setTargetPackages(emptySet())
                                }
                            }
                        )
                    }
                }
            }
        }
        // 启动镜像（使用已保存的包名）
        lifecycleScope.launch {
            settingsRepository.targetPackagesFlow.collect { packages ->
                // 异步更新目标包，避免阻塞UI线程
                launch {
                    try {
                        // 仅在监听服务已连接时刷新，避免 SecurityException
                        val app = (application as MusicCanApp)
                        app.updateTargetPackages(packages)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error updating target packages", e)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val cn = ComponentName(this, MusicNotificationListenerService::class.java)
        (application as MusicCanApp).stopMirror(cn)
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Composable
private fun PermissionSection(
    onOpenNotificationAccess: () -> Unit,
    onRequestPostNotifications: () -> Unit,
) {
    val context = LocalContext.current
    val hasNotificationAccess = rememberNotificationAccessEnabled(context)
    val postNotifGranted = rememberPostNotificationsGranted(context)
    val showListenerDialog = remember { mutableStateOf(!hasNotificationAccess) }
    val showPostNotifDialog = remember { mutableStateOf(!postNotifGranted) }

    LaunchedEffect(hasNotificationAccess) {
        showListenerDialog.value = !hasNotificationAccess
    }
    LaunchedEffect(postNotifGranted) {
        showPostNotifDialog.value = !postNotifGranted
    }

    if (showListenerDialog.value) {
        AlertDialog(
            onDismissRequest = { showListenerDialog.value = false },
            title = { Text("需要通知监听权限") },
            text = { Text("请授予通知监听，以读取其他应用的媒体会话并进行镜像。") },
            confirmButton = {
                Button(onClick = {
                    showListenerDialog.value = false
                    onOpenNotificationAccess()
                }) { Text("前往开启") }
            },
            dismissButton = {
                Button(onClick = { showListenerDialog.value = false }) { Text("暂不") }
            }
        )
    }

    if (showPostNotifDialog.value) {
        AlertDialog(
            onDismissRequest = { showPostNotifDialog.value = false },
            title = { Text("请求通知权限") },
            text = { Text("为保持前台提示与更稳定运行，请授予通知权限。") },
            confirmButton = {
                Button(onClick = {
                    showPostNotifDialog.value = false
                    onRequestPostNotifications()
                }) { Text("允许") }
            },
            dismissButton = {
                Button(onClick = { showPostNotifDialog.value = false }) { Text("拒绝") }
            }
        )
    }
}

@Composable
private fun rememberNotificationAccessEnabled(context: Context): Boolean {
    val cn = ComponentName(context, MusicNotificationListenerService::class.java)
    // 异步检查权限，避免阻塞UI线程
    return remember {
        try {
            val enabled =
                Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            enabled?.contains(cn.flattenToString()) == true
        } catch (e: Exception) {
            Log.e("MainActivity", "Error checking notification access", e)
            false
        }
    }
}

@Composable
private fun rememberPostNotificationsGranted(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MusicCanTheme {
        Greeting("Android")
    }
}

object ExtendIcons {

    val Github: ImageVector
        get() {
            if (_Github != null) return _Github!!

            _Github = ImageVector.Builder(
                name = "Github",
                defaultWidth = 16.dp,
                defaultHeight = 16.dp,
                viewportWidth = 16f,
                viewportHeight = 16f
            ).apply {
                path(
                    fill = SolidColor(Color.Black)
                ) {
                    moveTo(8f, 0f)
                    curveTo(3.58f, 0f, 0f, 3.58f, 0f, 8f)
                    curveToRelative(0f, 3.54f, 2.29f, 6.53f, 5.47f, 7.59f)
                    curveToRelative(0.4f, 0.07f, 0.55f, -0.17f, 0.55f, -0.38f)
                    curveToRelative(0f, -0.19f, -0.01f, -0.82f, -0.01f, -1.49f)
                    curveToRelative(-2.01f, 0.37f, -2.53f, -0.49f, -2.69f, -0.94f)
                    curveToRelative(-0.09f, -0.23f, -0.48f, -0.94f, -0.82f, -1.13f)
                    curveToRelative(-0.28f, -0.15f, -0.68f, -0.52f, -0.01f, -0.53f)
                    curveToRelative(0.63f, -0.01f, 1.08f, 0.58f, 1.23f, 0.82f)
                    curveToRelative(0.72f, 1.21f, 1.87f, 0.87f, 2.33f, 0.66f)
                    curveToRelative(0.07f, -0.52f, 0.28f, -0.87f, 0.51f, -1.07f)
                    curveToRelative(-1.78f, -0.2f, -3.64f, -0.89f, -3.64f, -3.95f)
                    curveToRelative(0f, -0.87f, 0.31f, -1.59f, 0.82f, -2.15f)
                    curveToRelative(-0.08f, -0.2f, -0.36f, -1.02f, 0.08f, -2.12f)
                    curveToRelative(0f, 0f, 0.67f, -0.21f, 2.2f, 0.82f)
                    curveToRelative(0.64f, -0.18f, 1.32f, -0.27f, 2f, -0.27f)
                    reflectiveCurveToRelative(1.36f, 0.09f, 2f, 0.27f)
                    curveToRelative(1.53f, -1.04f, 2.2f, -0.82f, 2.2f, -0.82f)
                    curveToRelative(0.44f, 1.1f, 0.16f, 1.92f, 0.08f, 2.12f)
                    curveToRelative(0.51f, 0.56f, 0.82f, 1.27f, 0.82f, 2.15f)
                    curveToRelative(0f, 3.07f, -1.87f, 3.75f, -3.65f, 3.95f)
                    curveToRelative(0.29f, 0.25f, 0.54f, 0.73f, 0.54f, 1.48f)
                    curveToRelative(0f, 1.07f, -0.01f, 1.93f, -0.01f, 2.2f)
                    curveToRelative(0f, 0.21f, 0.15f, 0.46f, 0.55f, 0.38f)
                    arcTo(
                        8.01f, 8.01f, 0f,
                        isMoreThanHalf = false,
                        isPositiveArc = false,
                        x1 = 16f,
                        y1 = 8f
                    )
                    curveToRelative(0f, -4.42f, -3.58f, -8f, -8f, -8f)
                }
            }.build()

            return _Github!!
        }

    private var _Github: ImageVector? = null

}