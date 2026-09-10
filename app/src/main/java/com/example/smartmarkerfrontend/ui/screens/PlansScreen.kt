package com.example.smartmarkerfrontend.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartmarkerfrontend.data.SmartMarkerState
import com.example.smartmarkerfrontend.ui.SmartMarkerViewModel
import com.example.smartmarkerfrontend.ui.components.BlueprintBox
import com.example.smartmarkerfrontend.ui.components.SmButton
import com.example.smartmarkerfrontend.ui.components.SmIcons
import com.example.smartmarkerfrontend.ui.components.SmTag
import com.example.smartmarkerfrontend.ui.components.SmTagKind
import com.example.smartmarkerfrontend.ui.theme.Barlow
import com.example.smartmarkerfrontend.ui.theme.BarlowCondensed
import com.example.smartmarkerfrontend.ui.theme.SmColors

@Composable
fun PlansScreen(state: SmartMarkerState, vm: SmartMarkerViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importDxf)
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 30.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Column {
                Text("Plans", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                Text(
                    "Tap a plan to open it on the canvas.",
                    fontFamily = Barlow,
                    fontSize = 13.sp,
                    color = SmColors.ink.copy(alpha = 0.6f)
                )
            }

            BlueprintBox(
                Modifier.fillMaxWidth(),
                markTopLeft = true,
                markTopRight = true,
                markBottomLeft = true,
                markBottomRight = true
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    state.files.forEachIndexed { i, file ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.openPlan(file.name) }
                                .padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Icon(
                                SmIcons.File,
                                contentDescription = null,
                                tint = if (file.open) SmColors.accent else SmColors.ink,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(file.name, fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 15.5.sp)
                                Text(
                                    "Uploaded ${file.uploaded}",
                                    fontFamily = Barlow,
                                    fontSize = 11.5.sp,
                                    color = SmColors.ink.copy(alpha = 0.55f)
                                )
                            }
                            SmTag(if (file.open) "Open" else "Uploaded", if (file.open) SmTagKind.Accent else SmTagKind.Neutral)
                        }
                        if (i != state.files.lastIndex) {
                            HorizontalDivider(color = SmColors.ink.copy(alpha = 0.10f), thickness = 1.dp)
                        }
                    }
                }
            }

            BlueprintBox(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Add a plan", fontFamily = BarlowCondensed, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Text(
                            "Pick a .dxf file from this tablet.",
                            fontFamily = Barlow,
                            fontSize = 12.sp,
                            color = SmColors.ink.copy(alpha = 0.58f)
                        )
                    }
                    SmButton(
                        "Choose .dxf file…",
                        onClick = { picker.launch(arrayOf("*/*")) },
                        modifier = Modifier.height(48.dp)
                    )
                }
            }

            if (state.importError != null) {
                Text(
                    state.importError,
                    color = SmColors.bad,
                    fontFamily = Barlow,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SmColors.bad.copy(alpha = 0.12f))
                        .clickable(onClick = vm::dismissImportError)
                        .padding(10.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
