package com.example.smartmarkerfrontend.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartmarkerfrontend.ui.theme.Barlow
import com.example.smartmarkerfrontend.ui.theme.BarlowCondensed
import com.example.smartmarkerfrontend.ui.theme.SmColors

@Composable
fun LoadingOverlay(
    title: String,
    message: String,
    progress: Float? = null,
    readout: String? = null,
    onCancel: (() -> Unit)? = null
) {
    val spin = rememberInfiniteTransition(label = "load")
    val rot by spin.animateFloat(
        0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "r"
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(SmColors.neutral900.copy(alpha = 0.42f)),
        contentAlignment = Alignment.Center
    ) {
        BlueprintBox(
            Modifier.width(330.dp),
            markTopLeft = true,
            markTopRight = true,
            markBottomLeft = true,
            markBottomRight = true
        ) {
            Column(
                Modifier.padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    title,
                    fontFamily = BarlowCondensed,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    color = SmColors.accent700
                )
                if (progress == null) {
                    Icon(
                        SmIcons.Spinner,
                        contentDescription = null,
                        tint = SmColors.accent,
                        modifier = Modifier.size(42.dp).rotate(rot)
                    )
                } else {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .border(1.dp, SmColors.ink.copy(alpha = 0.18f))
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                                    .height(10.dp)
                                    .background(SmColors.accent)
                            )
                        }
                        if (readout != null) {
                            Text(
                                readout,
                                fontFamily = BarlowCondensed,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 22.sp
                            )
                        }
                    }
                }
                Text(
                    message,
                    fontFamily = Barlow,
                    fontSize = 12.5.sp,
                    color = SmColors.ink.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                if (onCancel != null) {
                    SmButton(
                        "Cancel",
                        onCancel,
                        style = SmButtonStyle.Secondary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
