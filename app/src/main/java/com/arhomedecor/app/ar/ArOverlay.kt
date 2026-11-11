package com.arhomedecor.app.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val PanelBackground = Color(0x99000000)

/** Debug panel (top) and instruction hint (bottom) drawn over the camera view. Not touchable. */
@Composable
fun ArOverlay(status: ArStatus, cubeEdgeCentimetres: Int) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
    ) {
        Column(
            Modifier
                .align(Alignment.TopStart)
                .background(PanelBackground, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            DebugLine("FPS", status.fps.toString())
            DebugLine("Tracking", trackingLabel(status))
            DebugLine("Floors", status.floors.toString())
            DebugLine("Surfaces", status.raisedSurfaces.toString())
            DebugLine("Walls", status.walls.toString())
            DebugLine(
                "Height",
                status.cameraHeightMetres?.let { String.format(Locale.US, "%.2f m above floor", it) } ?: "-",
            )
            DebugLine(
                "Cube",
                status.cubeDistanceMetres?.let { String.format(Locale.US, "%.2f m away", it) } ?: "not placed",
            )
        }
        Text(
            text = hintFor(status, cubeEdgeCentimetres),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(PanelBackground, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun DebugLine(label: String, value: String) {
    Text(
        text = "${label.padEnd(9)}$value",
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}
