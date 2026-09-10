package com.example.smartmarkerfrontend.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.rotate
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
    val found = state.tags.count { it.locked }
    val poseTitle = when {
        !state.hadPose -> "Origin Not Set"
        state.computing -> "Computing pose…"
        state.posed && found >= 2 -> "Site Pose Identified"
        else -> "Find a Tag"
    }
    val poseSub = when {
        !state.hadPose -> "No origin on file. Stand the marker where the site origin should be, level it, then set the pose."
        state.computing -> "Solving origin and rotation from both tags."
        state.posed && found >= 2 -> "The marker knows where it stands. Vertices can be surveyed and projected."
        state.locking -> "Searching for Tag ${state.curTag}…"
        else -> "Pick a tag, point the laser dot at it with the pad, then align. Two tags are needed. $found of 2 found."
    }
    val titleColor = if (state.posed && found >= 2 && !state.computing) SmColors.accent700 else SmColors.ink
    val spin = rememberInfiniteTransition(label = "spin")
    val rot by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "r")

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.computing) {
                    Icon(
                        SmIcons.Spinner,
                        contentDescription = null,
                        tint = SmColors.accent,
                        modifier = Modifier.size(22.dp).rotate(rot)
                    )
                }
                Text(
                    poseTitle,
                    fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 40.sp,
                    color = titleColor,
                    textAlign = TextAlign.Center,
                    letterSpacing = (-0.4).sp,
                    lineHeight = 42.sp
                )
            }
            Text(
                poseSub,
                fontFamily = Barlow,
                fontSize = 14.sp,
                color = SmColors.ink.copy(alpha = 0.62f),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 440.dp)
            )
        }

        Spacer(Modifier.height(34.dp))

        if (!state.hadPose) {
            Box(
                Modifier
                    .height(96.dp)
                    .widthIn(min = 420.dp)
                    .background(SmColors.accent)
                    .border(1.dp, SmColors.accent)
                    .clickable(onClick = vm::setOrigin)
                    .padding(horizontal = 42.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Set Current Pose as Origin",
                    fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    color = SmColors.bg,
                    letterSpacing = 0.4.sp
                )
            }
        } else {
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

                Dpad(state, vm, rot)
            }
        }

        if (state.posed) {
            Spacer(Modifier.height(34.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SmButton("Reposition", vm::resetPose, style = SmButtonStyle.Secondary, modifier = Modifier.height(52.dp).width(150.dp))
                SmButton("Clear origin", vm::clearOrigin, style = SmButtonStyle.Ghost, modifier = Modifier.height(52.dp).width(130.dp))
                SmButton("Continue to plan", { vm.go(AppScreen.Plan) }, modifier = Modifier.height(52.dp).width(210.dp))
            }
        }
    }
}

@Composable
private fun TagSelect(state: SmartMarkerState, vm: SmartMarkerViewModel) {
    var open by remember { mutableStateOf(false) }
    val cur = state.tags.firstOrNull { it.n == state.curTag }
    val label = "Tag ${state.curTag} · ${if (cur?.locked == true) "aligned" else "not found"}"
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
                        Text("Tag ${tag.n} · ${if (tag.locked) "aligned" else "not found"}")
                    },
                    onClick = {
                        vm.pickTag(tag.n)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun Dpad(state: SmartMarkerState, vm: SmartMarkerViewModel, rot: Float) {
    val curLocked = state.tags.firstOrNull { it.n == state.curTag }?.locked == true
    val lockOff = (curLocked && !state.computing) || state.locking || state.computing
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.size(96.dp))
            PadKey(onClick = { vm.nudge(0f, 1f) }) { Icon(SmIcons.ArrowUp, null, tint = SmColors.ink, modifier = Modifier.size(34.dp)) }
            Spacer(Modifier.size(96.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadKey(onClick = { vm.nudge(-1f, 0f) }) { Icon(SmIcons.ArrowLeft, null, tint = SmColors.ink, modifier = Modifier.size(34.dp)) }
            val bg = when {
                curLocked && !state.computing -> SmColors.bg
                state.locking || state.computing -> SmColors.accent700
                else -> SmColors.accent
            }
            val fg = if (curLocked && !state.computing) SmColors.accent700 else SmColors.bg
            Box(
                Modifier
                    .size(96.dp)
                    .background(bg)
                    .border(1.dp, SmColors.accent)
                    .clickable(enabled = !lockOff, onClick = vm::autoLock),
                contentAlignment = Alignment.Center
            ) {
                if (state.locking) {
                    Icon(SmIcons.Spinner, null, tint = fg, modifier = Modifier.size(30.dp).rotate(rot))
                } else {
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
