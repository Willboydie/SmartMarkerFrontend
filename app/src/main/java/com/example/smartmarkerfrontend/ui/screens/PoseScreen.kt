package com.example.smartmarkerfrontend.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartmarkerfrontend.data.AppScreen
import com.example.smartmarkerfrontend.data.MoveStep
import com.example.smartmarkerfrontend.data.SmartMarkerState
import com.example.smartmarkerfrontend.ui.SmartMarkerViewModel
import com.example.smartmarkerfrontend.ui.components.SegButton
import com.example.smartmarkerfrontend.ui.components.SmButton
import com.example.smartmarkerfrontend.ui.components.SmButtonStyle
import com.example.smartmarkerfrontend.ui.components.SmIcons
import com.example.smartmarkerfrontend.ui.theme.Barlow
import com.example.smartmarkerfrontend.ui.theme.BarlowCondensed
import com.example.smartmarkerfrontend.ui.theme.SmColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PoseScreen(state: SmartMarkerState, vm: SmartMarkerViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when {
            state.posed -> IdentifiedPose(vm)
            state.locking -> TagFindingPad(state, vm)
            state.hadPose -> BeginTagFinding("Find Tags", onBegin = vm::beginTagSearch)
            else -> BeginTagFinding("Set Origin", onBegin = vm::setOrigin)
        }
    }
}

@Composable
private fun BeginTagFinding(label: String, onBegin: () -> Unit) {
    Box(
        Modifier
            .height(96.dp)
            .widthIn(min = 420.dp, max = 560.dp)
            .background(SmColors.accent)
            .border(1.dp, SmColors.accent)
            .clickable(onClick = onBegin)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 28.sp,
            color = SmColors.bg,
            textAlign = TextAlign.Center,
            letterSpacing = 0.4.sp,
            lineHeight = 32.sp
        )
    }
}

@Composable
private fun IdentifiedPose(vm: SmartMarkerViewModel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Pose Identified",
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 40.sp,
            color = SmColors.accent700,
            textAlign = TextAlign.Center,
            letterSpacing = (-0.4).sp,
            lineHeight = 42.sp
        )
        Text(
            "The marker knows where it stands. Vertices can be surveyed and projected.",
            fontFamily = Barlow,
            fontSize = 14.sp,
            color = SmColors.ink.copy(alpha = 0.62f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 440.dp)
        )
    }
    Spacer(Modifier.height(34.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmButton("Reposition", vm::resetPose, style = SmButtonStyle.Secondary, modifier = Modifier.height(52.dp).width(150.dp))
        SmButton("Clear origin", vm::clearOrigin, style = SmButtonStyle.Ghost, modifier = Modifier.height(52.dp).width(130.dp))
        SmButton("Continue to plan", { vm.go(AppScreen.Plan) }, modifier = Modifier.height(52.dp).width(210.dp))
    }
}

@Composable
private fun TagFindingPad(state: SmartMarkerState, vm: SmartMarkerViewModel) {
    val found = state.tags.count { it.aligned }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Tag Finding",
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 40.sp,
            color = SmColors.ink,
            textAlign = TextAlign.Center,
            letterSpacing = (-0.4).sp,
            lineHeight = 42.sp
        )
        Text(
            "Point the laser at a tag with the pad, then align. Two tags are needed. $found of 2 found.",
            fontFamily = Barlow,
            fontSize = 14.sp,
            color = SmColors.ink.copy(alpha = 0.62f),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 440.dp)
        )
    }
    Spacer(Modifier.height(34.dp))
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(34.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("TAG", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 1.2.sp, color = SmColors.ink.copy(alpha = 0.55f))
                TagSelect(state, vm)
                SmButton("+ New tag", vm::addTag, style = SmButtonStyle.Secondary, modifier = Modifier.height(42.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("MOVEMENT", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 1.2.sp, color = SmColors.ink.copy(alpha = 0.55f))
                Row {
                    SegButton("Fine", state.step == MoveStep.Fine, { vm.setStep(MoveStep.Fine) })
                    SegButton("Coarse", state.step == MoveStep.Coarse, { vm.setStep(MoveStep.Coarse) })
                }
            }
        }
        Dpad(state, vm)
    }
}

@Composable
private fun TagSelect(state: SmartMarkerState, vm: SmartMarkerViewModel) {
    var open by remember { mutableStateOf(false) }
    val cur = state.tags.firstOrNull { it.id == state.curTag }
    val label = "Tag ${state.curTag} · ${if (cur?.aligned == true) "aligned" else "not found"}"
    Box {
        Box(
            Modifier
                .height(42.dp)
                .widthIn(min = 132.dp)
                .background(SmColors.surface)
                .border(1.dp, SmColors.divider)
                .clickable { open = true }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(label, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.tags.forEach { tag ->
                DropdownMenuItem(
                    text = {
                        Text("Tag ${tag.id} · ${if (tag.aligned) "aligned" else "not found"}")
                    },
                    onClick = {
                        vm.pickTag(tag.id)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun Dpad(state: SmartMarkerState, vm: SmartMarkerViewModel) {
    val curLocked = state.tags.firstOrNull { it.id == state.curTag }?.aligned == true
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.size(96.dp))
            PadKey(onClick = { vm.nudge(0f, 1f) }) { Icon(SmIcons.ArrowUp, null, tint = SmColors.ink, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.size(96.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadKey(onClick = { vm.nudge(-1f, 0f) }) { Icon(SmIcons.ArrowLeft, null, tint = SmColors.ink, modifier = Modifier.size(34.dp)) }
            val bg = if (curLocked) SmColors.bg else SmColors.accent
            val fg = if (curLocked) SmColors.accent700 else SmColors.bg
            Box(
                Modifier
                    .size(96.dp)
                    .background(bg)
                    .border(1.dp, SmColors.accent)
                    .clickable(enabled = !curLocked, onClick = vm::confirmTag),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "TAG ${state.curTag}\nALIGNED",
                    color = fg,
                    fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    letterSpacing = 1.4.sp
                )
            }
            PadKey(onClick = { vm.nudge(1f, 0f) }) { Icon(SmIcons.ArrowRight, null, tint = SmColors.ink, modifier = Modifier.size(34.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.size(96.dp))
            PadKey(onClick = { vm.nudge(0f, -1f) }) { Icon(SmIcons.ArrowDown, null, tint = SmColors.ink, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.size(96.dp))
        }
    }
}

@Composable
private fun PadKey(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(96.dp)
            .background(SmColors.bg.copy(alpha = 0.9f))
            .border(1.dp, SmColors.divider)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}
