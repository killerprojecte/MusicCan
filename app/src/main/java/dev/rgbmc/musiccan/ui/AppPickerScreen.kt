package dev.rgbmc.musiccan.ui

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppPickerScreen(
    packageManager: PackageManager,
    selectedPackages: Set<String>,
    onToggle: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    val context = LocalContext.current
    val packageName = context.packageName
    val showSystemApps = remember { mutableStateOf(false) }
    val apps = remember {
        mutableStateOf(
            listInstalledApps(
                packageManager,
                includeSystemApps = false,
                excludePackageName = packageName
            )
        )
    }
    val iconCache = remember { mutableStateMapOf<String, ImageBitmap>() }

    LaunchedEffect(Unit) {
        apps.value = listInstalledApps(
            packageManager,
            includeSystemApps = showSystemApps.value,
            excludePackageName = packageName
        )
    }

    LaunchedEffect(showSystemApps.value) {
        apps.value = listInstalledApps(
            packageManager,
            includeSystemApps = showSystemApps.value,
            excludePackageName = packageName
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "选择需要镜像的媒体应用",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onClearAll, enabled = selectedPackages.isNotEmpty()) {
                Text("清空已选 (${selectedPackages.size})")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "显示系统应用", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = showSystemApps.value, onCheckedChange = { showSystemApps.value = it })
        }
        Spacer(Modifier.height(12.dp))
        ElevatedCard(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(apps.value) { app ->
                    val label = packageManager.getApplicationLabel(app).toString()
                    val cached = iconCache[app.packageName]
                    LaunchedEffect(app.packageName) {
                        if (iconCache[app.packageName] == null) {
                            val bmp = withContext(Dispatchers.IO) {
                                packageManager.getApplicationIcon(app).toBitmap(64, 64)
                                    .asImageBitmap()
                            }
                            iconCache[app.packageName] = bmp
                        }
                    }
                    val isSelected = selectedPackages.contains(app.packageName)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(app.packageName) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (cached != null) {
                            Image(
                                bitmap = cached,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(MaterialTheme.shapes.small)
                            )
                        } else {
                            Spacer(Modifier.size(36.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = label,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggle(app.packageName) })
                    }
                    HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)
                }
            }
        }
    }
}

@SuppressLint("QueryPermissionsNeeded")
private fun listInstalledApps(
    pm: PackageManager,
    includeSystemApps: Boolean,
    excludePackageName: String?
): List<ApplicationInfo> {
    val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES
    return pm.getInstalledApplications(flags)
        .asSequence()
        .filter { info ->
            if (!includeSystemApps) {
                (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            } else {
                true
            }
        }
        .filter { info -> excludePackageName == null || info.packageName != excludePackageName }
        .sortedBy { pm.getApplicationLabel(it).toString() }
        .toList()
}



