package com.arhomedecor.app.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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

/**
 * Debug panel (top), mode controls and instruction hint (bottom) over the camera view.
 * Only the controls take touches; taps anywhere else reach the AR view.
 */
@Composable
fun ArOverlay(
    status: ArStatus,
    cubeEdgeCentimetres: Int,
    onModeChange: (PlacementMode) -> Unit,
    onClearWalls: () -> Unit,
) {
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
            DebugLine("Walls", "${status.walls} detected, ${status.markedWalls} marked")
            DebugLine(
                "Height",
                status.cameraHeightMetres?.let { String.format(Locale.US, "%.2f m above floor", it) } ?: "-",
            )
            DebugLine("Depth", if (status.depthActive) "on" else "off")
            DebugLine("Floor fit", fitLabel(status.floorOffsetMetres, status.floorFitInliers))
            DebugLine("Wall fit", fitLabel(status.wallOffsetMetres, status.wallFitInliers))
            DebugLine(
                "Cube",
                status.cubeDistanceMetres?.let { String.format(Locale.US, "%.2f m away", it) } ?: "not placed",
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Place cube", status.mode == PlacementMode.CUBE) { onModeChange(PlacementMode.CUBE) }
                ModeChip("Mark wall", status.mode == PlacementMode.WALL) { onModeChange(PlacementMode.WALL) }
                if (status.markedWalls > 0 || status.wallStartPlaced) {
                    AssistChip(
                        onClick = onClearWalls,
                        label = { Text("Clear walls") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = PanelBackground,
                            labelColor = Color.White,
                        ),
                    )
                }
            }
            Text(
                text = hintFor(status, cubeEdgeCentimetres),
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(PanelBackground, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = PanelBackground,
            labelColor = Color.White,
            selectedContainerColor = Color.White,
            selectedLabelColor = Color.Black,
        ),
    )
}

/** e.g. "+1.2 cm (87% on surface)": the share shows how much of the view saw the surface itself. */
private fun fitLabel(offsetMetres: Float?, inliers: Float?): String {
    if (offsetMetres == null) return "-"
    val share = inliers?.let { String.format(Locale.US, " (%d%% on surface)", (it * 100).toInt()) } ?: ""
    return String.format(Locale.US, "%+.1f cm%s", offsetMetres * 100, share)
}

@Composable
private fun DebugLine(label: String, value: String) {
    Text(
        text = "${label.padEnd(10)}$value",
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}
