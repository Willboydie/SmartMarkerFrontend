package com.example.smartmarkerfrontend.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartmarkerfrontend.ui.theme.BarlowCondensed
import com.example.smartmarkerfrontend.ui.theme.SmColors

enum class SmButtonStyle { Primary, Secondary, Ghost }

@Composable
fun SmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SmButtonStyle = SmButtonStyle.Primary,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg = when (style) {
        SmButtonStyle.Primary -> if (pressed) SmColors.accent700 else SmColors.accent
        SmButtonStyle.Secondary -> if (pressed) SmColors.ink.copy(alpha = 0.14f) else Color.Transparent
        SmButtonStyle.Ghost -> if (pressed) SmColors.accent.copy(alpha = 0.18f) else Color.Transparent
    }
    val fg = when (style) {
        SmButtonStyle.Primary -> SmColors.bg
        SmButtonStyle.Secondary -> SmColors.ink
        SmButtonStyle.Ghost -> SmColors.accent
    }
    val border = when (style) {
        SmButtonStyle.Primary -> SmColors.accent
        SmButtonStyle.Secondary -> SmColors.divider
        SmButtonStyle.Ghost -> Color.Transparent
    }
    Box(
        modifier
            .defaultMinSize(minHeight = 36.dp)
            .background(bg, RectangleShape)
            .border(1.dp, border, RectangleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg.copy(alpha = if (enabled) 1f else 0.45f),
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            letterSpacing = 0.2.sp
        )
    }
}

enum class SmTagKind { Accent, Neutral, Outline }

@Composable
fun SmTag(
    text: String,
    kind: SmTagKind,
    modifier: Modifier = Modifier
) {
    val bg = when (kind) {
        SmTagKind.Accent -> SmColors.accent100
        SmTagKind.Neutral -> SmColors.neutral100
        SmTagKind.Outline -> Color.Transparent
    }
    val fg = when (kind) {
        SmTagKind.Accent -> SmColors.accent800
        SmTagKind.Neutral -> SmColors.neutral800
        SmTagKind.Outline -> SmColors.accent
    }
    val border = when (kind) {
        SmTagKind.Outline -> SmColors.accent
        else -> Color.Transparent
    }
    Text(
        text = text,
        modifier = modifier
            .background(bg)
            .border(1.dp, border)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = fg,
        fontFamily = BarlowCondensed,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 0.2.sp
    )
}

@Composable
fun BlueprintBox(
    modifier: Modifier = Modifier,
    background: Color = SmColors.bg,
    markTopLeft: Boolean = true,
    markTopRight: Boolean = false,
    markBottomLeft: Boolean = false,
    markBottomRight: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val markColor = SmColors.ink.copy(alpha = 0.55f)
    Box(
        modifier
            .background(background)
            .border(1.dp, SmColors.divider)
            .drawWithContent {
                drawContent()
                val arm = 11.dp.toPx()
                val inset = 0.5f
                fun mark(x: Float, y: Float) {
                    drawLine(markColor, Offset(x, y - arm / 2), Offset(x, y + arm / 2), 1.dp.toPx())
                    drawLine(markColor, Offset(x - arm / 2, y), Offset(x + arm / 2, y), 1.dp.toPx())
                }
                if (markTopLeft) mark(inset, inset)
                if (markTopRight) mark(size.width - inset, inset)
                if (markBottomLeft) mark(inset, size.height - inset)
                if (markBottomRight) mark(size.width - inset, size.height - inset)
            },
        content = content
    )
}

@Composable
fun CanvasToggle(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (on) SmColors.accent else SmColors.bg.copy(alpha = 0.88f)
    val fg = if (on) SmColors.bg else SmColors.ink
    val border = if (on) SmColors.accent else SmColors.divider
    Box(
        modifier
            .background(bg)
            .border(1.dp, border)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = fg,
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.5.sp,
            letterSpacing = 0.7.sp
        )
    }
}

@Composable
fun StatusChip(
    text: String,
    onClick: (() -> Unit)? = null,
    foreground: Color,
    border: Color,
    background: Color,
    icon: ImageVector? = null
) {
    val inner = @Composable {
        Row(
            Modifier
                .background(background)
                .border(1.dp, border)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(15.dp))
            }
            Text(
                text = text.uppercase(),
                color = foreground,
                fontFamily = BarlowCondensed,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                letterSpacing = 0.5.sp
            )
        }
    }
    inner()
}

@Composable
fun SegButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) SmColors.accent else Color.Transparent
    val fg = if (selected) SmColors.bg else SmColors.ink
    val border = if (selected) SmColors.accent else SmColors.divider
    Box(
        modifier
            .background(bg)
            .border(1.dp, border)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            fontFamily = BarlowCondensed,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 0.6.sp
        )
    }
}
