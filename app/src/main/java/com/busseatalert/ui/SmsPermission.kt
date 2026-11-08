package com.busseatalert.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal fun hasSmsPermission(context: Context) =
    context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

/** Opens this app's App info page, where restricted settings and permissions are changed. */
internal fun openAppInfo(context: Context) = context.startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

/**
 * Shows whether SMS may be sent and helps fix it. Android 13+ treats SMS as a "restricted
 * setting" for apps installed outside the Play Store: the permission request is refused, or the
 * switch is greyed out, until "Allow restricted settings" is turned on in the app's App info.
 */
@Composable
internal fun SmsPermissionRow() {
    val context = LocalContext.current
    val tick = rememberTicker(2000) // picks up changes made in Android settings
    val granted = remember(tick) { hasSmsPermission(context) }
    var showHelp by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) showHelp = true
    }

    Row(
        Modifier.fillMaxWidth()
            .background(if (granted) Color(0xFFE8F5E9) else Color(0xFFFFF3E0), RoundedCornerShape(8.dp))
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (granted) "SMS permission: allowed ✓" else "SMS permission: not allowed",
            color = if (granted) Color(0xFF2E7D32) else Color(0xFF8D4B00),
            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (!granted) {
            TextButton(onClick = { ask.launch(Manifest.permission.SEND_SMS) }) { Text("Allow") }
            TextButton(onClick = { showHelp = true }) { Text("Help") }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("Allowing SMS") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Android blocks SMS permission for apps installed from outside the Play Store " +
                        "(it shows \"Restricted setting\"). To allow it:")
                    Text("1. Tap Open App info below.")
                    Text("2. Tap ⋮ (top right) → Allow restricted settings, and confirm with your PIN or fingerprint.")
                    Text("   If you don't see that option: go to Permissions → SMS, tap Allow once (it will be " +
                        "refused), come back to App info, and it appears in ⋮.")
                    Text("3. Go to Permissions → SMS → Allow.")
                    Text("Xiaomi / Redmi / POCO: also allow \"Send SMS\" under Other permissions.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Then come back here: the status above turns green.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false; openAppInfo(context) }) { Text("Open App info") } },
            dismissButton = { TextButton(onClick = { showHelp = false }) { Text("Close") } },
        )
    }
}

/**
 * Whether Android lets the app run its checks on time. Phones (Motorola included) hold back
 * background work for battery-optimised apps, so checks can be hours apart overnight.
 */
@Composable
internal fun BatteryRow() {
    val context = LocalContext.current
    val tick = rememberTicker(2000)
    val free = remember(tick) {
        context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (free) Color(0xFFE8F5E9) else Color(0xFFFFF3E0), RoundedCornerShape(8.dp))
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (free) "Background checks: allowed ✓" else "Battery saving may delay checks (even by hours)",
            color = if (free) Color(0xFF2E7D32) else Color(0xFF8D4B00),
            fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        if (!free) TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text("Allow") }
    }
}
