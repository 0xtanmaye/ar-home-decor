package com.arhomedecor.app

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arhomedecor.app.ar.ArActivity
import com.arhomedecor.app.ar.EXTRA_PLAYBACK
import com.arhomedecor.app.device.AppMode
import com.arhomedecor.app.device.DepthSupport
import com.arhomedecor.app.device.DeviceCapabilities
import com.arhomedecor.app.device.DeviceCapabilityChecker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Development: `adb shell am start ... --es playback <file>` replays a recorded session
        // (see tools/play-recording.sh). Only debuggable builds accept it.
        val playback = intent.getStringExtra(EXTRA_PLAYBACK)
        if (playback != null && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Passes the other development extras (e.g. the depth debug view) along too.
            startActivity(Intent(this, ArActivity::class.java).putExtras(intent))
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    DeviceCheckScreen(Modifier.padding(padding).padding(24.dp))
                }
            }
        }
    }
}

@Composable
private fun DeviceCheckScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var capabilities by remember { mutableStateOf<DeviceCapabilities?>(null) }
    // The check runs once the camera permission question has been answered either way.
    var permissionResolved by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionResolved = true }

    LaunchedEffect(Unit) {
        if (DeviceCapabilityChecker.hasCameraPermission(context)) {
            permissionResolved = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(permissionResolved) {
        if (permissionResolved) {
            capabilities = DeviceCapabilityChecker.check(context.applicationContext)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AR Home Decor", style = MaterialTheme.typography.headlineMedium)
        val caps = capabilities
        if (caps == null) {
            CircularProgressIndicator()
            Text("Checking device capabilities…")
        } else {
            Text("Device: ${caps.model}")
            Text("Android ${caps.androidVersion} (API ${caps.apiLevel})")
            Text("OpenGL ES ${caps.glEsVersion}")
            Text("ARCore: ${caps.arCoreAvailability}")
            Text("Depth API: ${describe(caps.depth)}")
            Text("Mode: ${describe(caps.mode)}", style = MaterialTheme.typography.titleMedium)
            when (caps.mode) {
                AppMode.LIVE_AR, AppMode.NEEDS_ARCORE_INSTALL -> Button(
                    onClick = { context.startActivity(Intent(context, ArActivity::class.java)) },
                ) {
                    // ArActivity asks Play Store to install/update ARCore when needed.
                    Text(if (caps.mode == AppMode.LIVE_AR) "Start AR" else "Install ARCore and start")
                }
                AppMode.PHOTO_MODE -> Text("Photo mode will be available in a later version.")
                AppMode.CHECKING -> Unit
            }
        }
    }
}

private fun describe(mode: AppMode): String = when (mode) {
    AppMode.CHECKING -> "checking"
    AppMode.LIVE_AR -> "live AR available"
    AppMode.NEEDS_ARCORE_INSTALL -> "install or update Google Play Services for AR"
    AppMode.PHOTO_MODE -> "photo mode (live AR not supported on this device)"
}

private fun describe(depth: DepthSupport): String = when (depth) {
    DepthSupport.SUPPORTED -> "supported"
    DepthSupport.NOT_SUPPORTED -> "not supported"
    DepthSupport.NEEDS_CAMERA_PERMISSION -> "unknown (camera permission needed)"
    DepthSupport.NOT_APPLICABLE -> "not applicable"
    DepthSupport.CHECK_FAILED -> "check failed"
}
