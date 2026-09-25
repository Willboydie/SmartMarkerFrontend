package com.example.smartmarkerfrontend.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartmarkerfrontend.data.NodeStatus
import com.example.smartmarkerfrontend.data.planBanner
import com.example.smartmarkerfrontend.data.SmartMarkerState
import com.example.smartmarkerfrontend.data.formatCoord
import com.example.smartmarkerfrontend.ui.SmartMarkerViewModel
import com.example.smartmarkerfrontend.ui.canvas.PlanCanvas
import com.example.smartmarkerfrontend.ui.components.BlueprintBox
import com.example.smartmarkerfrontend.ui.components.CanvasToggle
import com.example.smartmarkerfrontend.ui.components.SmButton
import com.example.smartmarkerfrontend.ui.components.SmButtonStyle
import com.example.smartmarkerfrontend.ui.components.SmIcons
import com.example.smartmarkerfrontend.ui.components.SmTag
import com.example.smartmarkerfrontend.ui.components.SmTagKind
import com.example.smartmarkerfrontend.ui.theme.Barlow
import com.example.smartmarkerfrontend.ui.theme.BarlowCondensed
import com.example.smartmarkerfrontend.ui.theme.SmColors
import kotlin.math.roundToInt

@Composable
fun PlanScreen(state: SmartMarkerState, vm: SmartMarkerViewModel) {
    val infinite = rememberInfiniteTransition(label = "plan")
    val pulse by infinite.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "pulse"
    )
    val shimmerR by infinite.animateFloat(
        10.5f, 16.5f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "shimmer"
    )
    val shimmerA by infinite.animateFloat(
        1f, 0.25f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "shA"
    )
    val view = LocalView.current
    LaunchedEffect(state.run, state.projecting, state.isSurveying) {
        view.keepScreenOn = state.run != null || state.projecting != null || state.isSurveying
    }
    DisposableEffect(view) {
        onDispose { view.keepScreenOn = false }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onSizeChanged { vm.onCanvasSize(it.width.toFloat(), it.height.toFloat()) }
    ) {
        val density = LocalDensity.current
        LaunchedEffect(constraints.maxWidth, constraints.maxHeight) {
            vm.onCanvasSize(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        }

        PlanCanvas(
            state = state,
            pulse = pulse,
            shimmerR = shimmerR,
            shimmerAlpha = shimmerA,
            onPan = vm::panBy,
            onPinch = vm::setView,
            onTap = vm::tapCanvas,
            onGestureStart = vm::markGestureStart,
            modifier = Modifier.fillMaxSize()
        )

        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CanvasToggle("Multi-select", state.multiSelect, vm::toggleMulti)
            CanvasToggle("Labels", state.labels, vm::toggleLabels)
            CanvasToggle("Marker FOV", state.fovOn, vm::toggleFov)
            CanvasToggle("Grid 1 m", state.grid, vm::toggleGrid)
        }

        Column(
            Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp)
                .width(44.dp)
                .border(1.dp, SmColors.divider)
                .background(SmColors.bg)
        ) {
            ZoomCell({ vm.zoomBy(1.25f) }) {
                Icon(SmIcons.Plus, null, tint = SmColors.ink, modifier = Modifier.size(18.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(SmColors.divider))
            ZoomCell({ vm.zoomBy(0.8f) }) {
                Icon(SmIcons.Minus, null, tint = SmColors.ink, modifier = Modifier.size(18.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(SmColors.divider))
            ZoomCell(vm::fitPlan) {
                Icon(SmIcons.Fit, null, tint = SmColors.ink, modifier = Modifier.size(18.dp))
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(14.dp)
                .background(SmColors.bg.copy(alpha = 0.88f))
                .border(1.dp, SmColors.divider)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val bar = state.scale.coerceIn(12f, 220f)
            Box(
                Modifier
                    .width(with(density) { bar.toDp() })
                    .height(6.dp)
                    .border(1.dp, SmColors.ink)
            )
            Text(
                text = "1 m  ·  1:${(3200f / state.scale).roundToInt()}",
                fontFamily = Barlow,
                fontSize = 11.sp,
                color = SmColors.ink
            )
        }

        if (state.showGestureHints) {
            Text(
                text = "drag — pan · pinch — zoom · tap node — select",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 62.dp),
                fontFamily = Barlow,
                fontSize = 10.5.sp,
                color = SmColors.ink.copy(alpha = 0.52f)
            )
        }

        ActionStack(state, vm, Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

@Composable
private fun ZoomCell(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun ActionStack(
    state: SmartMarkerState,
    vm: SmartMarkerViewModel,
    modifier: Modifier = Modifier
) {
    val sel = state.selectedNodes
    val hidden = sel.filter { !state.inView(it) }
    val anyUnsurveyed = sel.any { it.status == NodeStatus.Open || it.status == NodeStatus.NotVisible }
    val blockNote = when {
        state.projecting != null -> {
            val n = state.projecting.size
            "Beam live on $n ${if (n == 1) "point" else "points"} — project the beam, then halt."
        }
        planBanner(state.posed) != null -> planBanner(state.posed)!!
        hidden.isNotEmpty() -> "Out of the marker’s view: ${hidden.joinToString { it.id }} — turn the marker to bring them in."
        sel.isNotEmpty() && anyUnsurveyed ->
            "Survey before projecting: ${sel.filter { it.status == NodeStatus.Open || it.status == NodeStatus.NotVisible }.joinToString { it.id }}"
        else -> ""
    }
    val surveyOff = sel.isEmpty() || !state.posed || hidden.isNotEmpty() || state.run != null || state.projecting != null
    val projectOff = state.projecting == null &&
        (sel.isEmpty() || !state.posed || hidden.isNotEmpty() || anyUnsurveyed || state.run != null)

    Column(
        modifier.width(296.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (sel.size == 1) {
            val n = sel.first()
            val vis = state.inView(n)
            BlueprintBox(
                Modifier
                    .fillMaxWidth()
                    .shadow(16.dp, RectangleShape),
                background = SmColors.bg.copy(alpha = 0.94f)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(n.id, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                        SmTag(statusLabel(n.status), statusTag(n.status))
                        SmTag(if (vis) "In view" else "Out of view", if (vis) SmTagKind.Outline else SmTagKind.Neutral)
                        Spacer(Modifier.weight(1f))
                        SmButton("Close", vm::clearSelection, style = SmButtonStyle.Ghost, contentPadding = PaddingValuesCompact)
                    }
                    Row {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            MetaRow("E", formatCoord(n.x, state.units))
                            MetaRow("N", formatCoord(n.y, state.units))
                        }
                    }
                    Text(statusNote(n.status), fontFamily = Barlow, fontSize = 11.5.sp, color = SmColors.ink.copy(alpha = 0.62f), lineHeight = 16.sp)
                }
            }
        } else if (sel.size > 1) {
            BlueprintBox(
                Modifier
                    .fillMaxWidth()
                    .shadow(16.dp, RectangleShape),
                background = SmColors.bg.copy(alpha = 0.94f)
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${sel.size} vertices selected", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Spacer(Modifier.weight(1f))
                    SmButton("Clear", vm::clearSelection, style = SmButtonStyle.Ghost, contentPadding = PaddingValuesCompact)
                }
            }
        }

        if (blockNote.isNotEmpty()) {
            val tone = if (state.projecting != null) SmColors.accent else SmColors.bad
            Text(
                text = blockNote,
                modifier = Modifier
                    .widthIn(max = 296.dp)
                    .background(tone.copy(alpha = 0.12f))
                    .border(1.dp, tone)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                fontFamily = Barlow,
                fontSize = 11.5.sp,
                color = tone,
                lineHeight = 16.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val projecting = state.projecting != null
            PlanActionButton(
                label = "Survey",
                onClick = vm::startSurvey,
                enabled = !surveyOff,
                fill = SmColors.bg,
                border = SmColors.divider,
                text = SmColors.ink
            )
            PlanActionButton(
                label = if (projecting) "Halt Projection" else "Project",
                onClick = vm::toggleProject,
                enabled = !projectOff,
                fill = if (projecting) SmColors.bad else SmColors.accent,
                border = if (projecting) SmColors.bad else SmColors.accent,
                text = SmColors.bg
            )
        }
    }
}

private val PaddingValuesCompact = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)

@Composable
private fun PlanActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    fill: Color,
    border: Color,
    text: Color
) {
    Box(
        Modifier
            .width(132.dp)
            .height(52.dp)
            .background(if (enabled) fill else fill.copy(alpha = 0.45f).compositeOver(SmColors.bg))
            .border(1.dp, if (enabled) border else border.copy(alpha = 0.45f).compositeOver(SmColors.bg))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = text.copy(alpha = if (enabled) 1f else 0.45f),
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun MetaRow(k: String, v: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(k, color = SmColors.ink.copy(alpha = 0.55f), fontSize = 12.5.sp, fontFamily = Barlow, modifier = Modifier.width(14.dp))
        Text(v, fontSize = 12.5.sp, fontFamily = Barlow)
    }
}

private fun statusLabel(st: NodeStatus) = when (st) {
    NodeStatus.Open -> "Unsurveyed"
    NodeStatus.Surveyed -> "Surveyed"
    NodeStatus.Projected -> "Projected"
    NodeStatus.NotVisible -> "Not visible"
}

private fun statusTag(st: NodeStatus) = when (st) {
    NodeStatus.Open -> SmTagKind.Neutral
    NodeStatus.Surveyed, NodeStatus.Projected -> SmTagKind.Accent
    NodeStatus.NotVisible -> SmTagKind.Outline
}

private fun statusNote(st: NodeStatus) = when (st) {
    NodeStatus.Open -> "Not surveyed yet — projection stays locked until a return is captured."
    NodeStatus.Surveyed -> "Return captured. Ready to project."
    NodeStatus.Projected -> "Beam projected on the floor. Re-project to refresh the spot."
    NodeStatus.NotVisible -> "No return here. Clear the line of sight and survey again."
}
