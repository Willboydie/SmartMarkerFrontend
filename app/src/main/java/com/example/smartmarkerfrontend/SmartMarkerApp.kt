package com.example.smartmarkerfrontend

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.smartmarkerfrontend.data.AppScreen
import com.example.smartmarkerfrontend.ui.SmartMarkerViewModel
import com.example.smartmarkerfrontend.ui.chrome.ConnectionDialog
import com.example.smartmarkerfrontend.ui.chrome.SideNav
import com.example.smartmarkerfrontend.ui.chrome.TopBar
import com.example.smartmarkerfrontend.ui.chrome.UserMenu
import com.example.smartmarkerfrontend.ui.components.LoadingOverlay
import com.example.smartmarkerfrontend.ui.screens.PlanScreen
import com.example.smartmarkerfrontend.ui.screens.PlansScreen
import com.example.smartmarkerfrontend.ui.screens.PoseScreen
import com.example.smartmarkerfrontend.ui.screens.SettingsScreen
import com.example.smartmarkerfrontend.ui.theme.SmColors

@Composable
fun SmartMarkerApp(vm: SmartMarkerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val view = LocalView.current
    LaunchedEffect(state.importing, state.showSurveyLoading, state.projecting) {
        view.keepScreenOn = state.importing || state.showSurveyLoading || state.projecting != null
    }
    DisposableEffect(view) {
        onDispose { view.keepScreenOn = false }
    }
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxSize()
                .background(SmColors.bg)
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            Box {
                SideNav(state, vm)
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .align(androidx.compose.ui.Alignment.CenterEnd)
                        .background(SmColors.divider)
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box {
                    TopBar(state, vm)
                }
                Box(Modifier.weight(1f).fillMaxSize()) {
                    when (state.screen) {
                        AppScreen.Plan -> PlanScreen(state, vm)
                        AppScreen.Plans -> PlansScreen(state, vm)
                        AppScreen.Pose -> PoseScreen(state, vm)
                        AppScreen.Settings -> SettingsScreen(state, vm)
                    }
                    if (state.userOpen) {
                        UserMenu(state, vm::closeMenus)
                    }
                    if (state.connOpen) {
                        ConnectionDialog(state, vm)
                    }
                }
            }
        }
        if (state.importing) {
            LoadingOverlay(
                title = "PARSING PLAN",
                message = "Reading DXF geometry…"
            )
        } else if (state.showSurveyLoading) {
            val run = state.run
            val done = run?.done ?: 0
            val total = run?.ids?.size ?: 0
            val current = run?.currentId
            LoadingOverlay(
                title = "SURVEYING",
                message = when {
                    current != null -> "Hold still — reading return from $current"
                    done >= total && total > 0 -> "Finishing survey…"
                    else -> "Waiting for the marker…"
                },
                progress = if (total > 0) done / total.toFloat() else 0f,
                readout = "$done / $total points",
                onCancel = vm::cancelRun
            )
        }
    }
}
