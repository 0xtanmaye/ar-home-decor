package com.arhomedecor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.arhomedecor.app.device.AppMode
import com.arhomedecor.app.device.DeviceCapabilities
import com.arhomedecor.app.device.DeviceCapabilityChecker

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    LaunchedEffect(Unit) {
        capabilities = DeviceCapabilityChecker.check(context.applicationContext)
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
            Text("Depth API: ${caps.depthSupported?.let { if (it) "supported" else "not supported" } ?: "unknown"}")
            Text("Mode: ${describe(caps.mode)}", style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun describe(mode: AppMode): String = when (mode) {
    AppMode.CHECKING -> "checking"
    AppMode.LIVE_AR -> "live AR available"
    AppMode.NEEDS_ARCORE_INSTALL -> "install or update Google Play Services for AR"
    AppMode.PHOTO_MODE -> "photo mode (live AR not supported on this device)"
}
