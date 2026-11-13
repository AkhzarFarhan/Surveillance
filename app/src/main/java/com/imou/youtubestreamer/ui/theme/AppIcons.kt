package com.imou.youtubestreamer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight vector icons replacing the 40 MB `androidx.compose.material:material-icons-extended`
 * dependency so the APK stays ultra-compact without losing any UI icons.
 */
object AppIcons {

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()
    }

    val Dashboard: ImageVector by lazy {
        icon("Dashboard") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 13f); horizontalLineToRelative(8f); verticalLineTo(3f); horizontalLineTo(3f); verticalLineToRelative(10f); close()
                moveTo(3f, 21f); horizontalLineToRelative(8f); verticalLineToRelative(-6f); horizontalLineTo(3f); verticalLineToRelative(6f); close()
                moveTo(13f, 21f); horizontalLineToRelative(8f); verticalLineTo(11f); horizontalLineToRelative(-8f); verticalLineToRelative(10f); close()
                moveTo(13f, 3f); verticalLineToRelative(6f); horizontalLineToRelative(8f); verticalLineTo(3f); horizontalLineToRelative(-8f); close()
            }
        }
    }

    val ListAlt: ImageVector by lazy {
        icon("ListAlt") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 5f); verticalLineToRelative(14f); horizontalLineTo(5f); verticalLineTo(5f); horizontalLineToRelative(14f); moveTo(20.1f, 3f); horizontalLineTo(3.9f)
                curveTo(3.4f, 3f, 3f, 3.4f, 3f, 3.9f); verticalLineToRelative(16.2f); curveTo(3f, 20.5f, 3.4f, 21f, 3.9f, 21f); horizontalLineToRelative(16.2f)
                curveToRelative(0.4f, 0f, 0.9f, -0.5f, 0.9f, -0.9f); verticalLineTo(3.9f); curveToRelative(0f, -0.5f, -0.5f, -0.9f, -0.9f, -0.9f); close()
                moveTo(11f, 7f); horizontalLineToRelative(6f); verticalLineToRelative(2f); horizontalLineToRelative(-6f); verticalLineTo(7f); close()
                moveTo(11f, 11f); horizontalLineToRelative(6f); verticalLineToRelative(2f); horizontalLineToRelative(-6f); verticalLineToRelative(-2f); close()
                moveTo(11f, 15f); horizontalLineToRelative(6f); verticalLineToRelative(2f); horizontalLineToRelative(-6f); verticalLineToRelative(-2f); close()
                moveTo(7f, 7f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineTo(7f); verticalLineTo(7f); close()
                moveTo(7f, 11f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineTo(7f); verticalLineToRelative(-2f); close()
                moveTo(7f, 15f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineTo(7f); verticalLineToRelative(-2f); close()
            }
        }
    }

    val SystemUpdate: ImageVector by lazy {
        icon("SystemUpdate") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 1.01f); lineTo(7f, 1f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(18f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(10f); curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(3f); curveToRelative(0f, -1.1f, -0.9f, -1.99f, -2f, -1.99f); close()
                moveTo(17f, 19f); horizontalLineTo(7f); verticalLineTo(5f); horizontalLineToRelative(10f); verticalLineToRelative(14f); close()
                moveTo(16f, 13f); horizontalLineToRelative(-3f); verticalLineTo(8f); horizontalLineToRelative(-2f); verticalLineToRelative(5f)
                horizontalLineTo(8f); lineToRelative(4f, 4f); lineToRelative(4f, -4f); close()
            }
        }
    }

    val OpenInBrowser: ImageVector by lazy {
        icon("OpenInBrowser") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 4f); horizontalLineTo(5f); curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(12f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f); horizontalLineToRelative(4f); verticalLineToRelative(-2f); horizontalLineTo(5f)
                verticalLineTo(8f); horizontalLineToRelative(14f); verticalLineToRelative(10f); horizontalLineToRelative(-4f); verticalLineToRelative(2f)
                horizontalLineToRelative(4f); curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineTo(6f); curveToRelative(0f, -1.1f, -0.89f, -2f, -2f, -2f); close()
                moveTo(12f, 10f); lineToRelative(-4f, 4f); horizontalLineToRelative(3f); verticalLineToRelative(6f); horizontalLineToRelative(2f)
                verticalLineToRelative(-6f); horizontalLineToRelative(3f); lineToRelative(-4f, -4f); close()
            }
        }
    }

    val OpenInNew: ImageVector by lazy {
        icon("OpenInNew") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 19f); horizontalLineTo(5f); verticalLineTo(5f); horizontalLineToRelative(7f); verticalLineTo(3f); horizontalLineTo(5f)
                curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(14f); curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                horizontalLineToRelative(14f); curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineToRelative(-7f); horizontalLineToRelative(-2f); verticalLineToRelative(7f); close()
                moveTo(14f, 3f); verticalLineToRelative(2f); horizontalLineToRelative(3.59f); lineToRelative(-9.83f, 9.83f); lineToRelative(1.41f, 1.41f)
                lineTo(19f, 6.41f); verticalLineTo(10f); horizontalLineToRelative(2f); verticalLineTo(3f); horizontalLineToRelative(-7f); close()
            }
        }
    }

    val ErrorOutline: ImageVector by lazy {
        icon("ErrorOutline") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(11f, 15f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); close()
                moveTo(11f, 7f); horizontalLineToRelative(2f); verticalLineToRelative(6f); horizontalLineToRelative(-2f); close()
                moveTo(11.99f, 2f); curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f); reflectiveCurveToRelative(4.47f, 10f, 9.99f, 10f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f); reflectiveCurveTo(17.52f, 2f, 11.99f, 2f); close()
                moveTo(12f, 20f); curveToRelative(-4.42f, 0f, -8f, -3.58f, -8f, -8f); reflectiveCurveToRelative(3.58f, -8f, 8f, -8f)
                reflectiveCurveToRelative(8f, 3.58f, 8f, 8f); reflectiveCurveToRelative(-3.58f, 8f, -8f, 8f); close()
            }
        }
    }

    val BatteryAlert: ImageVector by lazy {
        icon("BatteryAlert") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(15.67f, 4f); horizontalLineTo(14f); verticalLineTo(2f); horizontalLineToRelative(-4f); verticalLineToRelative(2f); horizontalLineTo(8.33f)
                curveTo(7.6f, 4f, 7f, 4.6f, 7f, 5.33f); verticalLineToRelative(15.33f); curveTo(7f, 21.4f, 7.6f, 22f, 8.33f, 22f)
                horizontalLineToRelative(7.33f); curveToRelative(0.74f, 0f, 1.34f, -0.6f, 1.34f, -1.33f); verticalLineTo(5.33f)
                curveTo(17f, 4.6f, 16.4f, 4f, 15.67f, 4f); close()
                moveTo(13f, 18f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(13f, 14f); horizontalLineToRelative(-2f); verticalLineTo(9f); horizontalLineToRelative(2f); verticalLineToRelative(5f); close()
            }
        }
    }

    val VpnKey: ImageVector by lazy {
        icon("VpnKey") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12.65f, 10f); curveTo(11.83f, 7.67f, 9.61f, 6f, 7f, 6f); curveToRelative(-3.31f, 0f, -6f, 2.69f, -6f, 6f)
                reflectiveCurveToRelative(2.69f, 6f, 6f, 6f); curveToRelative(2.61f, 0f, 4.83f, -1.67f, 5.65f, -4f); horizontalLineTo(17f)
                verticalLineToRelative(4f); horizontalLineToRelative(4f); verticalLineToRelative(-4f); horizontalLineToRelative(2f); verticalLineToRelative(-4f); horizontalLineTo(12.65f); close()
                moveTo(7f, 14f); curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f); reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
                reflectiveCurveToRelative(2f, 0.9f, 2f, 2f); reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f); close()
            }
        }
    }

    val ContentPaste: ImageVector by lazy {
        icon("ContentPaste") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 2f); horizontalLineToRelative(-4.18f); curveTo(14.4f, 0.84f, 13.3f, 0f, 12f, 0f); curveToRelative(-1.3f, 0f, -2.4f, 0.84f, -2.82f, 2f)
                horizontalLineTo(5f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(16f); curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(14f); curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineTo(4f); curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); close()
                moveTo(12f, 2f); curveToRelative(0.55f, 0f, 1f, 0.45f, 1f, 1f); reflectiveCurveToRelative(-0.45f, 1f, -1f, 1f)
                reflectiveCurveToRelative(-1f, -0.45f, -1f, -1f); reflectiveCurveToRelative(0.45f, -1f, 1f, -1f); close()
                moveTo(19f, 20f); horizontalLineTo(5f); verticalLineTo(4f); horizontalLineToRelative(2f); verticalLineToRelative(3f); horizontalLineToRelative(10f)
                verticalLineTo(4f); horizontalLineToRelative(2f); verticalLineToRelative(16f); close()
            }
        }
    }

    val ContentCopy: ImageVector by lazy {
        icon("ContentCopy") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 1f); horizontalLineTo(4f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(14f)
                horizontalLineToRelative(2f); verticalLineTo(3f); horizontalLineToRelative(12f); verticalLineTo(1f); close()
                moveTo(19f, 5f); horizontalLineTo(8f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(14f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(11f); curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(7f); curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); close()
                moveTo(19f, 21f); horizontalLineTo(8f); verticalLineTo(7f); horizontalLineToRelative(11f); verticalLineToRelative(14f); close()
            }
        }
    }

    val Stop: ImageVector by lazy {
        icon("Stop") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 6f); horizontalLineToRelative(12f); verticalLineToRelative(12f); horizontalLineTo(6f); close()
            }
        }
    }

    val Pause: ImageVector by lazy {
        icon("Pause") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 19f); horizontalLineToRelative(4f); verticalLineTo(5f); horizontalLineTo(6f); verticalLineToRelative(14f); close()
                moveTo(14f, 5f); verticalLineToRelative(14f); horizontalLineToRelative(4f); verticalLineTo(5f); horizontalLineToRelative(-4f); close()
            }
        }
    }

    val CloudSync: ImageVector by lazy {
        icon("CloudSync") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19.35f, 10.04f); curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f); curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f); curveToRelative(0f, 3.31f, 2.69f, 6f, 6f, 6f); horizontalLineToRelative(13f)
                curveToRelative(2.76f, 0f, 5f, -2.24f, 5f, -5f); curveToRelative(0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f); close()
                moveTo(14f, 13f); verticalLineToRelative(4f); horizontalLineToRelative(-4f); verticalLineToRelative(-4f); horizontalLineTo(7f)
                lineToRelative(5f, -5f); lineToRelative(5f, 5f); horizontalLineToRelative(-3f); close()
            }
        }
    }

    val Wifi: ImageVector by lazy {
        icon("Wifi") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(1f, 9f); lineToRelative(2f, 2f); curveToRelative(4.97f, -4.97f, 13.03f, -4.97f, 18f, 0f); lineToRelative(2f, -2f)
                curveTo(16.93f, 2.93f, 7.08f, 2.93f, 1f, 9f); close()
                moveTo(9f, 17f); lineToRelative(3f, 3f); lineToRelative(3f, -3f); curveToRelative(-1.65f, -1.66f, -4.34f, -1.66f, -6f, 0f); close()
                moveTo(5f, 13f); lineToRelative(2f, 2f); curveToRelative(2.76f, -2.76f, 7.24f, -2.76f, 10f, 0f); lineToRelative(2f, -2f)
                curveTo(15.14f, 9.14f, 8.87f, 9.14f, 5f, 13f); close()
            }
        }
    }

    val WifiOff: ImageVector by lazy {
        icon("WifiOff") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(22.99f, 9f); curveTo(19.15f, 5.16f, 13.8f, 3.76f, 8.84f, 4.78f); lineToRelative(2.52f, 2.52f)
                curveToRelative(3.47f, -0.17f, 6.99f, 1.05f, 9.63f, 3.7f); lineToRelative(2f, -2f); close()
                moveTo(19f, 13f); curveToRelative(-1.29f, -1.29f, -2.84f, -2.13f, -4.49f, -2.56f); lineToRelative(3.53f, 3.53f); lineToRelative(0.96f, -0.97f); close()
                moveTo(3.27f, 1.44f); lineTo(2f, 2.72f); lineToRelative(2.05f, 2.06f); curveTo(2.78f, 5.6f, 1.57f, 6.62f, 0.49f, 7.7f)
                lineToRelative(2f, 2f); curveToRelative(1.06f, -1.06f, 2.26f, -1.91f, 3.53f, -2.56f); lineToRelative(2.2f, 2.2f)
                curveTo(6.99f, 10.02f, 5.86f, 10.89f, 4.9f, 11.86f); lineToRelative(2f, 2f); curveToRelative(0.9f, -0.9f, 1.98f, -1.56f, 3.14f, -1.96f)
                lineToRelative(7.69f, 7.69f); lineToRelative(1.27f, -1.27f); lineTo(3.27f, 1.44f); close()
                moveTo(9f, 17f); lineToRelative(3f, 3f); lineToRelative(3f, -3f); curveToRelative(-1.65f, -1.66f, -4.34f, -1.66f, -6f, 0f); close()
            }
        }
    }

    val Videocam: ImageVector by lazy {
        icon("Videocam") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 10.5f); verticalLineTo(7f); curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f); horizontalLineTo(4f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f); verticalLineToRelative(10f); curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(12f); curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f); verticalLineToRelative(-3.5f); lineToRelative(4f, 4f)
                verticalLineToRelative(-11f); lineToRelative(-4f, 4f); close()
            }
        }
    }

    val Router: ImageVector by lazy {
        icon("Router") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20.2f, 5.9f); lineToRelative(0.8f, -0.8f); curveTo(19.6f, 3.7f, 17.8f, 3f, 16f, 3f); reflectiveCurveToRelative(-3.6f, 0.7f, -5f, 2.1f)
                lineToRelative(0.8f, 0.8f); curveTo(13f, 4.8f, 14.5f, 4.2f, 16f, 4.2f); reflectiveCurveToRelative(3f, 0.6f, 4.2f, 1.7f); close()
                moveTo(19.3f, 6.7f); curveToRelative(-0.9f, -0.9f, -2.1f, -1.4f, -3.3f, -1.4f); reflectiveCurveToRelative(-2.4f, 0.5f, -3.3f, 1.4f)
                lineToRelative(0.8f, 0.8f); curveToRelative(0.7f, -0.7f, 1.6f, -1f, 2.5f, -1f); reflectiveCurveToRelative(1.8f, 0.3f, 2.5f, 1f); lineToRelative(0.8f, -0.8f); close()
                moveTo(19f, 13f); horizontalLineToRelative(-2f); verticalLineTo(9f); horizontalLineToRelative(-2f); verticalLineToRelative(4f); horizontalLineTo(5f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(4f); curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineToRelative(-4f); curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); close()
                moveTo(8f, 18f); horizontalLineTo(6f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(11.5f, 18f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(15f, 18f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
            }
        }
    }

    val Visibility: ImageVector by lazy {
        icon("Visibility") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 4.5f); curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f); curveToRelative(1.73f, 4.39f, 6f, 7.5f, 11f, 7.5f)
                reflectiveCurveToRelative(9.27f, -3.11f, 11f, -7.5f); curveToRelative(-1.73f, -4.39f, -6f, -7.5f, -11f, -7.5f); close()
                moveTo(12f, 17f); curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f); reflectiveCurveToRelative(2.24f, -5f, 5f, -5f)
                reflectiveCurveToRelative(5f, 2.24f, 5f, 5f); reflectiveCurveToRelative(-2.24f, 5f, -5f, 5f); close()
                moveTo(12f, 9f); curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f); reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
                reflectiveCurveToRelative(3f, -1.34f, 3f, -3f); reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f); close()
            }
        }
    }

    val VisibilityOff: ImageVector by lazy {
        icon("VisibilityOff") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 7f); curveToRelative(2.76f, 0f, 5f, 2.24f, 5f, 5f); curveToRelative(0f, 0.65f, -0.13f, 1.26f, -0.36f, 1.83f)
                lineToRelative(2.92f, 2.92f); curveToRelative(1.51f, -1.26f, 2.7f, -2.89f, 3.43f, -4.75f); curveToRelative(-1.73f, -4.39f, -6f, -7.5f, -11f, -7.5f)
                curveToRelative(-1.4f, 0f, -2.74f, 0.25f, -3.98f, 0.7f); lineToRelative(2.16f, 2.16f); curveTo(10.74f, 7.13f, 11.35f, 7f, 12f, 7f); close()
                moveTo(2f, 4.27f); lineToRelative(2.28f, 2.28f); lineToRelative(0.46f, 0.46f); curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1f, 12f)
                curveToRelative(1.73f, 4.39f, 6f, 7.5f, 11f, 7.5f); curveToRelative(1.55f, 0f, 3.03f, -0.3f, 4.38f, -0.84f); lineToRelative(0.42f, 0.42f)
                lineTo(19.73f, 22f); lineTo(21f, 20.73f); lineTo(3.27f, 3f); lineTo(2f, 4.27f); close()
                moveTo(7.53f, 9.8f); lineToRelative(1.55f, 1.55f); curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
                curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f); curveToRelative(0.22f, 0f, 0.44f, -0.03f, 0.65f, -0.08f); lineToRelative(1.55f, 1.55f)
                curveToRelative(-0.67f, 0.33f, -1.41f, 0.53f, -2.2f, 0.53f); curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
                curveToRelative(0f, -0.79f, 0.2f, -1.53f, 0.53f, -2.2f); close()
            }
        }
    }

    val NetworkCheck: ImageVector by lazy {
        icon("NetworkCheck") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(15.9f, 5f); curveToRelative(-0.17f, 0f, -0.32f, 0.09f, -0.41f, 0.23f); lineToRelative(-0.07f, 0.15f); lineToRelative(-5.18f, 11.65f)
                curveToRelative(-0.16f, 0.29f, -0.26f, 0.61f, -0.26f, 0.96f); curveToRelative(0f, 1.11f, 0.9f, 2.01f, 2.01f, 2.01f)
                curveToRelative(0.96f, 0f, 1.77f, -0.68f, 1.96f, -1.59f); lineToRelative(0.01f, -0.03f); lineTo(16.4f, 5.5f)
                curveToRelative(0f, -0.28f, -0.22f, -0.5f, -0.5f, -0.5f); close()
                moveTo(1f, 9f); lineToRelative(2f, 2f); curveToRelative(2.88f, -2.88f, 6.79f, -4.08f, 10.53f, -3.62f); lineToRelative(1.19f, -2.68f)
                curveTo(9.89f, 3.84f, 4.74f, 5.27f, 1f, 9f); close()
                moveTo(21f, 11f); lineToRelative(2f, -2f); curveToRelative(-1.64f, -1.64f, -3.55f, -2.82f, -5.59f, -3.57f); lineToRelative(-0.53f, 2.82f)
                curveToRelative(1.5f, 0.62f, 2.9f, 1.53f, 4.12f, 2.75f); close()
                moveTo(17f, 15f); lineToRelative(2f, -2f); curveToRelative(-0.8f, -0.8f, -1.7f, -1.42f, -2.66f, -1.89f); lineToRelative(-0.55f, 2.92f)
                curveToRelative(0.42f, 0.27f, 0.83f, 0.59f, 1.21f, 0.97f); close()
                moveTo(5f, 13f); lineToRelative(2f, 2f); curveToRelative(1.13f, -1.13f, 2.56f, -1.79f, 4.03f, -2f); lineToRelative(1.28f, -2.88f)
                curveToRelative(-2.63f, -0.08f, -5.3f, 0.87f, -7.31f, 2.88f); close()
            }
        }
    }

    val VideoSettings: ImageVector by lazy {
        icon("VideoSettings") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 6f); horizontalLineToRelative(18f); verticalLineToRelative(5f); horizontalLineToRelative(2f); verticalLineTo(6f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); horizontalLineTo(3f); curveTo(1.9f, 4f, 1f, 4.9f, 1f, 6f)
                verticalLineToRelative(12f); curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(9f); verticalLineToRelative(-2f)
                horizontalLineTo(3f); verticalLineTo(6f); close()
                moveTo(15f, 12f); lineTo(9f, 8f); verticalLineToRelative(8f); lineToRelative(6f, -4f); close()
                moveTo(19f, 14f); horizontalLineToRelative(-2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); verticalLineToRelative(2f)
                horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f)
                verticalLineToRelative(-2f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); close()
            }
        }
    }

    val Save: ImageVector by lazy {
        icon("Save") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 3f); horizontalLineTo(5f); curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(14f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f); horizontalLineToRelative(14f); curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(7f); lineToRelative(-4f, -4f); close()
                moveTo(12f, 19f); curveToRelative(-1.66f, 0f, -3f, -1.34f, -3f, -3f); reflectiveCurveToRelative(1.34f, -3f, 3f, -3f)
                reflectiveCurveToRelative(3f, 1.34f, 3f, 3f); reflectiveCurveToRelative(-1.34f, 3f, -3f, 3f); close()
                moveTo(15f, 9f); horizontalLineTo(5f); verticalLineTo(5f); horizontalLineToRelative(10f); verticalLineToRelative(4f); close()
            }
        }
    }
}
