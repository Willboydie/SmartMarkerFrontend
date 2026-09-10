package com.example.smartmarkerfrontend.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun strokeIcon(
    name: String,
    viewport: Float = 24f,
    stroke: Float = 1.5f,
    fill: Boolean = false,
    block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = viewport,
    viewportHeight = viewport
).apply {
    path(
        fill = if (fill) SolidColor(Color.Black) else SolidColor(Color.Transparent),
        stroke = if (fill) null else SolidColor(Color.Black),
        strokeLineWidth = stroke,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block
    )
}.build()

object SmIcons {
    val Plan: ImageVector by lazy {
        strokeIcon("plan") {
            moveTo(3f, 5.5f)
            lineTo(9f, 3f)
            lineTo(15f, 5.5f)
            lineTo(21f, 3f)
            verticalLineTo(18.5f)
            lineTo(15f, 21f)
            lineTo(9f, 18.5f)
            lineTo(3f, 21f)
            close()
            moveTo(9f, 3f)
            verticalLineTo(18.5f)
            moveTo(15f, 5.5f)
            verticalLineTo(21f)
        }
    }

    val Plans: ImageVector by lazy {
        strokeIcon("plans") {
            moveTo(14f, 3f)
            horizontalLineTo(7f)
            curveTo(5.895f, 3f, 5f, 3.895f, 5f, 5f)
            verticalLineTo(19f)
            curveTo(5f, 20.105f, 5.895f, 21f, 7f, 21f)
            horizontalLineTo(17f)
            curveTo(18.105f, 21f, 19f, 20.105f, 19f, 19f)
            verticalLineTo(8f)
            close()
            moveTo(14f, 3f)
            verticalLineTo(8f)
            horizontalLineTo(19f)
            moveTo(12f, 18f)
            verticalLineTo(12f)
            moveTo(9f, 15f)
            lineTo(12f, 18f)
            lineTo(15f, 15f)
        }
    }

    val Pose: ImageVector by lazy {
        ImageVector.Builder("pose", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 20f)
                arcToRelative(8f, 8f, 0f, true, true, 0f, -16f)
                arcToRelative(8f, 8f, 0f, true, true, 0f, 16f)
                close()
                moveTo(12f, 1f)
                verticalLineTo(7f)
                moveTo(12f, 17f)
                verticalLineTo(23f)
                moveTo(1f, 12f)
                horizontalLineTo(7f)
                moveTo(17f, 12f)
                horizontalLineTo(23f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 13.6f)
                arcToRelative(1.6f, 1.6f, 0f, true, true, 0f, -3.2f)
                arcToRelative(1.6f, 1.6f, 0f, true, true, 0f, 3.2f)
                close()
            }
        }.build()
    }

    val Settings: ImageVector by lazy {
        strokeIcon("settings", stroke = 1.4f) {
            moveTo(15.2f, 12f)
            arcToRelative(3.2f, 3.2f, 0f, true, true, -6.4f, 0f)
            arcToRelative(3.2f, 3.2f, 0f, true, true, 6.4f, 0f)
            close()
            moveTo(10.7f, 2.4f)
            horizontalLineToRelative(2.6f)
            lineToRelative(0.4f, 2.1f)
            lineToRelative(1.9f, 0.8f)
            lineToRelative(1.8f, -1.2f)
            lineToRelative(1.8f, 1.8f)
            lineToRelative(-1.2f, 1.8f)
            lineToRelative(0.8f, 1.9f)
            lineToRelative(2.1f, 0.4f)
            verticalLineToRelative(2.6f)
            lineToRelative(-2.1f, 0.4f)
            lineToRelative(-0.8f, 1.9f)
            lineToRelative(1.2f, 1.8f)
            lineToRelative(-1.8f, 1.8f)
            lineToRelative(-1.8f, -1.2f)
            lineToRelative(-1.9f, 0.8f)
            lineToRelative(-0.4f, 2.1f)
            horizontalLineToRelative(-2.6f)
            lineToRelative(-0.4f, -2.1f)
            lineToRelative(-1.9f, -0.8f)
            lineToRelative(-1.8f, 1.2f)
            lineToRelative(-1.8f, -1.8f)
            lineToRelative(1.2f, -1.8f)
            lineToRelative(-0.8f, -1.9f)
            lineToRelative(-2.1f, -0.4f)
            verticalLineToRelative(-2.6f)
            lineToRelative(2.1f, -0.4f)
            lineToRelative(0.8f, -1.9f)
            lineToRelative(-1.2f, -1.8f)
            lineToRelative(1.8f, -1.8f)
            lineToRelative(1.8f, 1.2f)
            lineToRelative(1.9f, -0.8f)
            close()
        }
    }

    val Laser: ImageVector by lazy {
        strokeIcon("laser", stroke = 1.8f) {
            moveTo(12f, 15f)
            arcToRelative(3f, 3f, 0f, true, true, 0f, -6f)
            arcToRelative(3f, 3f, 0f, true, true, 0f, 6f)
            close()
            moveTo(12f, 2f)
            verticalLineTo(6f)
            moveTo(12f, 18f)
            verticalLineTo(22f)
            moveTo(2f, 12f)
            horizontalLineTo(6f)
            moveTo(18f, 12f)
            horizontalLineTo(22f)
            moveTo(5f, 5f)
            lineTo(7.5f, 7.5f)
            moveTo(16.5f, 16.5f)
            lineTo(19f, 19f)
            moveTo(19f, 5f)
            lineTo(16.5f, 7.5f)
            moveTo(7.5f, 16.5f)
            lineTo(5f, 19f)
        }
    }

    val PoseOk: ImageVector by lazy {
        strokeIcon("poseOk", stroke = 1.8f) {
            moveTo(12f, 20f)
            arcToRelative(8f, 8f, 0f, true, true, 0f, -16f)
            arcToRelative(8f, 8f, 0f, true, true, 0f, 16f)
            close()
            moveTo(12f, 4f)
            verticalLineTo(20f)
            moveTo(4f, 12f)
            horizontalLineTo(20f)
        }
    }

    val PoseWarn: ImageVector by lazy {
        ImageVector.Builder("poseWarn", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 3f)
                lineTo(2.5f, 20f)
                horizontalLineToRelative(19f)
                close()
                moveTo(12f, 9f)
                verticalLineTo(14f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 18.2f)
                arcToRelative(1f, 1f, 0f, true, true, 0f, -2f)
                arcToRelative(1f, 1f, 0f, true, true, 0f, 2f)
                close()
            }
        }.build()
    }

    val Signal: ImageVector by lazy {
        ImageVector.Builder("signal", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(2.5f, 8.5f)
                arcToRelative(15f, 15f, 0f, false, true, 19f, 0f)
                moveTo(5.5f, 12f)
                arcToRelative(10.5f, 10.5f, 0f, false, true, 13f, 0f)
                moveTo(8.5f, 15.4f)
                arcToRelative(6f, 6f, 0f, false, true, 7f, 0f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 20.3f)
                arcToRelative(1.3f, 1.3f, 0f, true, true, 0f, -2.6f)
                arcToRelative(1.3f, 1.3f, 0f, true, true, 0f, 2.6f)
                close()
            }
        }.build()
    }

    val ChevronDown: ImageVector by lazy {
        strokeIcon("chevron", stroke = 2f) {
            moveTo(6f, 9f)
            lineTo(12f, 15f)
            lineTo(18f, 9f)
        }
    }

    val Plus: ImageVector by lazy {
        strokeIcon("plus", stroke = 1.5f) {
            moveTo(12f, 5f)
            verticalLineTo(19f)
            moveTo(5f, 12f)
            horizontalLineTo(19f)
        }
    }

    val Minus: ImageVector by lazy {
        strokeIcon("minus", stroke = 1.5f) {
            moveTo(5f, 12f)
            horizontalLineTo(19f)
        }
    }

    val Fit: ImageVector by lazy {
        strokeIcon("fit", stroke = 1.5f) {
            moveTo(4f, 9f)
            verticalLineTo(4f)
            horizontalLineToRelative(5f)
            moveTo(20f, 9f)
            verticalLineTo(4f)
            horizontalLineToRelative(-5f)
            moveTo(4f, 15f)
            verticalLineToRelative(5f)
            horizontalLineToRelative(5f)
            moveTo(20f, 15f)
            verticalLineToRelative(5f)
            horizontalLineToRelative(-5f)
        }
    }

    val File: ImageVector by lazy {
        strokeIcon("file") {
            moveTo(14f, 3f)
            horizontalLineTo(7f)
            curveTo(5.895f, 3f, 5f, 3.895f, 5f, 5f)
            verticalLineTo(19f)
            curveTo(5f, 20.105f, 5.895f, 21f, 7f, 21f)
            horizontalLineTo(17f)
            curveTo(18.105f, 21f, 19f, 20.105f, 19f, 19f)
            verticalLineTo(8f)
            close()
            moveTo(14f, 3f)
            verticalLineTo(8f)
            horizontalLineTo(19f)
        }
    }

    val ArrowUp: ImageVector by lazy {
        strokeIcon("up") {
            moveTo(12f, 19f)
            verticalLineTo(5f)
            moveTo(6f, 11f)
            lineTo(12f, 5f)
            lineTo(18f, 11f)
        }
    }

    val ArrowDown: ImageVector by lazy {
        strokeIcon("down") {
            moveTo(12f, 5f)
            verticalLineTo(19f)
            moveTo(6f, 13f)
            lineTo(12f, 19f)
            lineTo(18f, 13f)
        }
    }

    val ArrowLeft: ImageVector by lazy {
        strokeIcon("left") {
            moveTo(19f, 12f)
            horizontalLineTo(5f)
            moveTo(11f, 6f)
            lineTo(5f, 12f)
            lineTo(11f, 18f)
        }
    }

    val ArrowRight: ImageVector by lazy {
        strokeIcon("right") {
            moveTo(5f, 12f)
            horizontalLineTo(19f)
            moveTo(13f, 6f)
            lineTo(19f, 12f)
            lineTo(13f, 18f)
        }
    }

    val Spinner: ImageVector by lazy {
        strokeIcon("spinner", stroke = 2.2f) {
            moveTo(12f, 3f)
            arcToRelative(9f, 9f, 0f, true, false, 9f, 9f)
        }
    }
}
