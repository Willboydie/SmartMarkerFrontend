package com.example.smartmarkerfrontend.ui.chrome

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartmarkerfrontend.R
import com.example.smartmarkerfrontend.data.AppScreen
import com.example.smartmarkerfrontend.data.SmartMarkerState
import com.example.smartmarkerfrontend.ui.SmartMarkerViewModel
import com.example.smartmarkerfrontend.ui.components.BlueprintBox
import com.example.smartmarkerfrontend.ui.components.SmButton
import com.example.smartmarkerfrontend.ui.components.SmButtonStyle
import com.example.smartmarkerfrontend.ui.components.SmIcons
import com.example.smartmarkerfrontend.ui.components.StatusChip
import com.example.smartmarkerfrontend.ui.theme.Barlow
import com.example.smartmarkerfrontend.ui.theme.BarlowCondensed
import com.example.smartmarkerfrontend.ui.theme.SmColors

@Composable
fun SideNav(state: SmartMarkerState, vm: SmartMarkerViewModel) {
    Column(
        Modifier
            .width(74.dp)
            .fillMaxHeight()
            .background(SmColors.surface)
            .border(width = 0.dp, color = SmColors.divider)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_smart_marker),
            contentDescription = "Smart Marker",
            modifier = Modifier
                .size(30.dp)
                .padding(bottom = 10.dp)
        )
        NavTab("PLAN", SmIcons.Plan, state.screen == AppScreen.Plan) { vm.go(AppScreen.Plan) }
        NavTab("PLANS", SmIcons.Plans, state.screen == AppScreen.Plans) { vm.go(AppScreen.Plans) }
        NavTab("POSE", SmIcons.Pose, state.screen == AppScreen.Pose) { vm.go(AppScreen.Pose) }
        Spacer(Modifier.weight(1f))
        NavTab("SETTINGS", SmIcons.Settings, state.screen == AppScreen.Settings) { vm.go(AppScreen.Settings) }
    }
}

@Composable
private fun NavTab(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) SmColors.accent else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (selected) SmColors.bg else SmColors.ink
    val border = if (selected) SmColors.accent else androidx.compose.ui.graphics.Color.Transparent
    Column(
        Modifier
            .width(58.dp)
            .background(bg)
            .border(1.dp, border)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(22.dp))
        Text(
            label,
            color = fg,
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 9.sp,
            letterSpacing = 0.72.sp
        )
    }
}

@Composable
fun TopBar(state: SmartMarkerState, vm: SmartMarkerViewModel) {
    val blink = rememberInfiniteTransition(label = "blink")
    val blinkA by blink.animateFloat(
        1f, 0.25f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "ba"
    )
    val title = when (state.screen) {
        AppScreen.Plan -> "Plan view"
        AppScreen.Plans -> "Plans"
        AppScreen.Pose -> "Positioning"
        AppScreen.Settings -> "Settings"
    }
    val poseText = when {
        state.posed -> "Pose Known"
        state.locking -> "Finding tags…"
        state.hadPose -> "Device Moved"
        else -> "Origin Not Set"
    }
    val poseFg = when {
        state.posed -> SmColors.accent700
        state.locking -> SmColors.ink
        else -> SmColors.badText
    }
    val poseBorder = when {
        state.posed -> SmColors.accent
        state.locking -> SmColors.divider
        else -> SmColors.bad
    }
    val poseBg = when {
        state.posed -> SmColors.accent.copy(alpha = 0.12f)
        state.locking -> androidx.compose.ui.graphics.Color.Transparent
        else -> SmColors.bad.copy(alpha = 0.14f)
    }
    val laserOn = state.laserOn

    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(SmColors.bg)
            .border(width = 0.dp, color = SmColors.divider)
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column {
                Text("Smart Marker", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = (-0.17).sp)
                Text(
                    state.jobName.uppercase(),
                    fontFamily = Barlow,
                    fontSize = 10.5.sp,
                    letterSpacing = 0.95.sp,
                    color = SmColors.ink.copy(alpha = 0.52f)
                )
            }
            Box(Modifier.width(1.dp).height(28.dp).background(SmColors.divider))
            Text(
                title.uppercase(),
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                letterSpacing = 1.3.sp,
                color = SmColors.accent700
            )
            Spacer(Modifier.weight(1f))

            StatusChip(
                text = if (laserOn) "Laser On" else "Laser Off",
                foreground = if (laserOn) SmColors.badText else SmColors.ink.copy(alpha = 0.55f),
                border = if (laserOn) SmColors.bad else SmColors.divider,
                background = if (laserOn) SmColors.bad.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent,
                icon = SmIcons.Laser
            )

            StatusChip(
                text = poseText,
                onClick = { vm.go(AppScreen.Pose) },
                foreground = poseFg,
                border = poseBorder,
                background = poseBg,
                icon = if (state.posed) SmIcons.PoseOk else if (!state.locking) SmIcons.PoseWarn else SmIcons.Pose
            )

            Row(
                Modifier
                    .height(36.dp)
                    .border(1.dp, SmColors.divider)
                    .clickable(onClick = vm::toggleConn)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .alpha(blinkA)
                        .background(if (state.link) SmColors.accent else SmColors.bad)
                )
                Icon(SmIcons.Signal, contentDescription = null, tint = SmColors.ink, modifier = Modifier.size(15.dp))
                Text(
                    if (state.link) "CONNECTED" else "NO LINK",
                    fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 0.66.sp
                )
            }

            Box(Modifier.width(1.dp).height(28.dp).background(SmColors.divider))

            Row(
                Modifier
                    .height(38.dp)
                    .clickable(onClick = vm::toggleUser)
                    .padding(start = 4.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .background(SmColors.accent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        state.user.initials,
                        color = SmColors.bg,
                        fontFamily = BarlowCondensed,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
                Column {
                    Text(state.user.name, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                    Text(
                        state.user.role.uppercase(),
                        fontFamily = Barlow,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp,
                        color = SmColors.ink.copy(alpha = 0.52f)
                    )
                }
                Icon(SmIcons.ChevronDown, null, tint = SmColors.ink, modifier = Modifier.size(12.dp))
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(1.dp)
                .background(SmColors.divider)
        )
    }
}

@Composable
fun UserMenu(state: SmartMarkerState, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss)) {
        BlueprintBox(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 12.dp)
                .width(220.dp)
                .clickable(enabled = false, onClick = {})
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Signed in as ${state.user.email}",
                    fontFamily = Barlow,
                    fontSize = 11.5.sp,
                    color = SmColors.ink.copy(alpha = 0.6f)
                )
                SmButton("Switch site", {}, style = SmButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
                SmButton("Sign out", {}, style = SmButtonStyle.Ghost)
            }
        }
    }
}

@Composable
fun ConnectionDialog(state: SmartMarkerState, vm: SmartMarkerViewModel) {
    Box(
        Modifier
            .fillMaxSize()
            .background(SmColors.neutral900.copy(alpha = 0.45f))
            .clickable(onClick = vm::closeConn),
        contentAlignment = Alignment.Center
    ) {
        BlueprintBox(
            Modifier
                .width(400.dp)
                .clickable(enabled = false, onClick = {}),
            markTopLeft = true,
            markTopRight = true,
            markBottomLeft = true,
            markBottomRight = true
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val listed = state.deviceName
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(10.dp).background(if (listed != null) SmColors.accent else SmColors.bad))
                    Text(
                        listed ?: if (state.link) "Waiting for device" else "Marker disconnected",
                        fontFamily = BarlowCondensed,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp
                    )
                }
                Text(
                    when {
                        listed == null && state.link ->
                            "Connected to the marker. Waiting for the advertisement."
                        listed == null ->
                            "Join the phone hotspot, or press reconnect."
                        state.seeingTelemetry ->
                            if (state.sessionActive) "Session active. Telemetry is on."
                            else "Telemetry is on."
                        state.sessionActive -> "Session active. No telemetry."
                        else -> "Session idle. No telemetry."
                    },
                    fontFamily = Barlow,
                    fontSize = 13.sp,
                    color = SmColors.ink.copy(alpha = 0.68f),
                    lineHeight = 20.sp
                )
                SmButton(
                    "Start session",
                    vm::startSession,
                    enabled = listed != null && !state.sessionActive,
                    modifier = Modifier.fillMaxWidth()
                )
                SmButton(
                    "Join session",
                    vm::joinSession,
                    style = SmButtonStyle.Secondary,
                    enabled = listed != null && state.sessionActive,
                    modifier = Modifier.fillMaxWidth()
                )
                SmButton(
                    "End session",
                    vm::endSession,
                    style = SmButtonStyle.Ghost,
                    enabled = listed != null && state.sessionActive,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    SmButton("Close", vm::closeConn, style = SmButtonStyle.Secondary)
                    SmButton(if (state.link) "Disconnect" else "Reconnect", vm::toggleLink)
                }
            }
        }
    }
}
