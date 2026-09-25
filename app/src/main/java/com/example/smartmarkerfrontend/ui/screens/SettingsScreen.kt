package com.example.smartmarkerfrontend.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartmarkerfrontend.R
import com.example.smartmarkerfrontend.data.SmartMarkerState
import com.example.smartmarkerfrontend.data.Units
import com.example.smartmarkerfrontend.ui.SmartMarkerViewModel
import com.example.smartmarkerfrontend.ui.components.BlueprintBox
import com.example.smartmarkerfrontend.ui.components.SegButton
import com.example.smartmarkerfrontend.ui.components.SmButton
import com.example.smartmarkerfrontend.ui.components.SmButtonStyle
import com.example.smartmarkerfrontend.ui.theme.Barlow
import com.example.smartmarkerfrontend.ui.theme.BarlowCondensed
import com.example.smartmarkerfrontend.ui.theme.SmColors

@Composable
fun SettingsScreen(state: SmartMarkerState, vm: SmartMarkerViewModel) {
    Row(
        Modifier
            .fillMaxSize()
            .padding(22.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            SettingsCard("Units & plan") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Plan display", fontFamily = Barlow, fontSize = 12.sp, color = SmColors.ink.copy(alpha = 0.7f))
                    Row {
                        SegButton("mm", state.units == Units.Millimetres, { vm.setUnits(Units.Millimetres) })
                        SegButton("m", state.units == Units.Metres, { vm.setUnits(Units.Metres) })
                        SegButton("ft", state.units == Units.Feet, { vm.setUnits(Units.Feet) })
                    }
                    Text(
                        "Display only. The plan is stored in metres.",
                        fontFamily = Barlow,
                        fontSize = 11.sp,
                        color = SmColors.ink.copy(alpha = 0.55f)
                    )
                }
            }
            SettingsCard("Connection") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(9.dp).background(if (state.link) SmColors.accent else SmColors.bad))
                    Text(
                        if (state.link) "Marker connected" else "Marker disconnected",
                        fontFamily = Barlow,
                        fontSize = 13.5.sp
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Reconnect automatically", fontFamily = Barlow, fontSize = 13.5.sp)
                    PillToggle(state.autoReconnect, vm::toggleAutoReconnect)
                }
                SmButton(
                    if (state.link) "Disconnect" else "Reconnect",
                    vm::toggleLink,
                    style = SmButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            SettingsCard("Laser") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Beam brightness — ${state.beam}%", fontFamily = Barlow, fontSize = 12.sp, color = SmColors.ink.copy(alpha = 0.7f))
                    Slider(
                        value = state.beam.toFloat(),
                        onValueChange = { vm.setBeam(it.toInt()) },
                        valueRange = 10f..100f,
                        steps = 17,
                        colors = SliderDefaults.colors(
                            thumbColor = SmColors.accent,
                            activeTrackColor = SmColors.accent,
                            inactiveTrackColor = SmColors.neutral300
                        )
                    )
                }
            }
            SettingsCard("About") {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    AboutRow("App", stringResource(R.string.app_version))
                    AboutRow("Firmware", state.firmwareVersion ?: stringResource(R.string.firmware_default))
                    AboutRow("Tablet", stringResource(R.string.tablet_about))
                    AboutRow("Signed in", state.user.email)
                }
                SmButton("Check for firmware update", {}, style = SmButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    BlueprintBox(
        Modifier.fillMaxWidth(),
        markTopLeft = true,
        markTopRight = true,
        markBottomLeft = true,
        markBottomRight = true
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            content()
        }
    }
}

@Composable
private fun AboutRow(k: String, v: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(k, color = SmColors.ink.copy(alpha = 0.55f), fontSize = 13.sp, fontFamily = Barlow, modifier = Modifier.weight(0.34f))
        Text(v, fontSize = 13.sp, fontFamily = Barlow, modifier = Modifier.weight(0.66f))
    }
}

@Composable
fun PillToggle(on: Boolean, onClick: () -> Unit) {
    val bg = if (on) SmColors.accent else androidx.compose.ui.graphics.Color.Transparent
    val fg = if (on) SmColors.bg else SmColors.ink
    val border = if (on) SmColors.accent else SmColors.divider
    Box(
        Modifier
            .border(1.dp, border)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            if (on) "ON" else "OFF",
            color = fg,
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 1.sp
        )
    }
}
