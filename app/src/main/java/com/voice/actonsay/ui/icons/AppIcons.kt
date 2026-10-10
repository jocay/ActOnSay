package com.voice.actonsay.ui.icons

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 项目只依赖 material-icons-core，扩展图标以 Material Symbols 路径数据内联定义，
 * 避免引入体积很大的 material-icons-extended。
 */
object AppIcons {
    val Task: ImageVector by lazy {
        materialIcon(name = "Task") { materialPath {
            moveTo(5f, 2f); horizontalLineTo(19f); verticalLineTo(22f); horizontalLineTo(5f); close()
            moveTo(7f, 4f); verticalLineTo(20f); horizontalLineTo(17f); verticalLineTo(4f); close()
            moveTo(9f, 8f); horizontalLineTo(15f); verticalLineTo(10f); horizontalLineTo(9f); close()
            moveTo(9f, 13f); horizontalLineTo(15f); verticalLineTo(15f); horizontalLineTo(9f); close()
        } }
    }

    val Schedule: ImageVector by lazy {
        materialIcon(name = "Schedule") { materialPath {
            moveTo(12f, 2f); curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f); reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
            reflectiveCurveToRelative(10f, -4.48f, 10f, -10f); reflectiveCurveTo(17.52f, 2f, 12f, 2f); close()
            moveTo(12f, 4f); curveTo(16.42f, 4f, 20f, 7.58f, 20f, 12f); reflectiveCurveToRelative(-3.58f, 8f, -8f, 8f)
            reflectiveCurveToRelative(-8f, -3.58f, -8f, -8f); reflectiveCurveToRelative(3.58f, -8f, 8f, -8f); close()
            moveTo(11f, 6f); horizontalLineTo(13f); verticalLineTo(11.5f); lineTo(17f, 14f); lineTo(16f, 15.5f); lineTo(11f, 12.5f); close()
        } }
    }

    val Terminal: ImageVector by lazy {
        materialIcon(name = "Terminal") { materialPath {
            moveTo(2f, 4f); horizontalLineTo(22f); verticalLineTo(20f); horizontalLineTo(2f); close()
            moveTo(4f, 6f); verticalLineTo(18f); horizontalLineTo(20f); verticalLineTo(6f); close()
            moveTo(6f, 8f); lineTo(10f, 12f); lineTo(6f, 16f); lineTo(5f, 15f); lineTo(8f, 12f); lineTo(5f, 9f); close()
            moveTo(12f, 14f); horizontalLineTo(18f); verticalLineTo(16f); horizontalLineTo(12f); close()
        } }
    }

    val Extension: ImageVector by lazy {
        materialIcon(name = "Extension") { materialPath {
            moveTo(4f, 4f); horizontalLineTo(9f); verticalLineTo(2f); horizontalLineTo(15f); verticalLineTo(4f)
            horizontalLineTo(20f); verticalLineTo(9f); horizontalLineTo(22f); verticalLineTo(15f); horizontalLineTo(20f)
            verticalLineTo(20f); horizontalLineTo(4f); close()
            moveTo(6f, 6f); verticalLineTo(18f); horizontalLineTo(18f); verticalLineTo(6f); close()
        } }
    }

    val Skill: ImageVector by lazy {
        materialIcon(name = "Skill") { materialPath {
            moveTo(3f, 3f); horizontalLineTo(11f); lineTo(12f, 4f); lineTo(13f, 3f); horizontalLineTo(21f)
            verticalLineTo(19f); horizontalLineTo(13f); lineTo(12f, 21f); lineTo(11f, 19f); horizontalLineTo(3f); close()
            moveTo(5f, 5f); verticalLineTo(17f); horizontalLineTo(10f); verticalLineTo(5f); close()
            moveTo(14f, 5f); verticalLineTo(17f); horizontalLineTo(19f); verticalLineTo(5f); close()
        } }
    }

    val Mic: ImageVector by lazy {
        materialIcon(name = "Mic") {
            materialPath {
                moveTo(12f, 14f); curveToRelative(1.66f, 0f, 2.99f, -1.34f, 2.99f, -3f); lineTo(15f, 5f)
                curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f); reflectiveCurveTo(9f, 3.34f, 9f, 5f); verticalLineToRelative(6f)
                curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f); close()
                moveTo(17.3f, 11f); curveToRelative(0f, 3f, -2.54f, 5.1f, -5.3f, 5.1f); reflectiveCurveTo(6.7f, 14f, 6.7f, 11f)
                horizontalLineTo(5f); curveToRelative(0f, 3.41f, 2.72f, 6.23f, 6f, 6.72f); verticalLineTo(21f); horizontalLineToRelative(2f)
                verticalLineToRelative(-3.28f); curveToRelative(3.28f, -0.48f, 6f, -3.3f, 6f, -6.72f); horizontalLineToRelative(-1.7f); close()
            }
        }
    }

    val MicOff: ImageVector by lazy {
        materialIcon(name = "MicOff") {
            materialPath {
                moveTo(19f, 11f); horizontalLineToRelative(-1.7f); curveToRelative(0f, 0.74f, -0.16f, 1.43f, -0.43f, 2.05f)
                lineToRelative(1.23f, 1.23f); curveToRelative(0.56f, -0.98f, 0.9f, -2.09f, 0.9f, -3.28f); close()
                moveTo(14.98f, 11.17f); curveToRelative(0f, -0.06f, 0.02f, -0.11f, 0.02f, -0.17f); verticalLineTo(5f)
                curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f); reflectiveCurveTo(9f, 3.34f, 9f, 5f); verticalLineToRelative(0.18f)
                lineToRelative(5.98f, 5.99f); close()
                moveTo(4.27f, 3f); lineTo(3f, 4.27f); lineToRelative(6.01f, 6.01f); verticalLineTo(11f)
                curveToRelative(0f, 1.66f, 1.33f, 3f, 2.99f, 3f); curveToRelative(0.22f, 0f, 0.44f, -0.03f, 0.65f, -0.08f)
                lineToRelative(1.66f, 1.66f); curveToRelative(-0.71f, 0.33f, -1.5f, 0.52f, -2.31f, 0.52f)
                curveToRelative(-2.76f, 0f, -5.3f, -2.1f, -5.3f, -5.1f); horizontalLineTo(5f)
                curveToRelative(0f, 3.41f, 2.72f, 6.23f, 6f, 6.72f); verticalLineTo(21f); horizontalLineToRelative(2f)
                verticalLineToRelative(-3.28f); curveToRelative(0.91f, -0.13f, 1.77f, -0.45f, 2.54f, -0.9f)
                lineTo(19.73f, 21f); lineTo(21f, 19.73f); lineTo(4.27f, 3f); close()
            }
        }
    }

    val VolumeUp: ImageVector by lazy {
        materialIcon(name = "VolumeUp") {
            materialPath {
                moveTo(3f, 9f); verticalLineToRelative(6f); horizontalLineToRelative(4f); lineToRelative(5f, 5f); verticalLineTo(4f)
                lineTo(7f, 9f); horizontalLineTo(3f); close()
                moveTo(16.5f, 12f); curveToRelative(0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f); verticalLineToRelative(8.05f)
                curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f); close()
                moveTo(14f, 3.23f); verticalLineToRelative(2.06f); curveToRelative(2.89f, 0.86f, 5f, 3.54f, 5f, 6.71f)
                reflectiveCurveToRelative(-2.11f, 5.85f, -5f, 6.71f); verticalLineToRelative(2.06f)
                curveToRelative(4.01f, -0.91f, 7f, -4.49f, 7f, -8.77f); reflectiveCurveToRelative(-2.99f, -7.86f, -7f, -8.77f); close()
            }
        }
    }

    val Stop: ImageVector by lazy {
        materialIcon(name = "Stop") {
            materialPath { moveTo(6f, 6f); horizontalLineToRelative(12f); verticalLineToRelative(12f); horizontalLineTo(6f); close() }
        }
    }

    val Power: ImageVector by lazy {
        materialIcon(name = "PowerSettingsNew") {
            materialPath {
                moveTo(13f, 3f); horizontalLineToRelative(-2f); verticalLineToRelative(10f); horizontalLineToRelative(2f); verticalLineTo(3f); close()
                moveTo(17.83f, 5.17f); lineToRelative(-1.42f, 1.42f); curveTo(17.99f, 7.86f, 19f, 9.81f, 19f, 12f)
                curveToRelative(0f, 3.87f, -3.13f, 7f, -7f, 7f); reflectiveCurveTo(5f, 15.87f, 5f, 12f)
                curveToRelative(0f, -2.19f, 1.01f, -4.14f, 2.58f, -5.42f); lineTo(6.17f, 5.17f); curveTo(4.23f, 6.82f, 3f, 9.26f, 3f, 12f)
                curveToRelative(0f, 4.97f, 4.03f, 9f, 9f, 9f); reflectiveCurveToRelative(9f, -4.03f, 9f, -9f)
                curveToRelative(0f, -2.74f, -1.23f, -5.18f, -3.17f, -6.83f); close()
            }
        }
    }

    val Chat: ImageVector by lazy {
        materialIcon(name = "ChatBubble") {
            materialPath {
                moveTo(20f, 2f); horizontalLineTo(4f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(18f)
                lineToRelative(4f, -4f); horizontalLineToRelative(14f); curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineTo(4f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); close()
            }
        }
    }

    val Bolt: ImageVector by lazy {
        materialIcon(name = "Bolt") {
            materialPath {
                moveTo(7f, 2f); verticalLineToRelative(11f); horizontalLineToRelative(3f); verticalLineToRelative(9f)
                lineToRelative(7f, -12f); horizontalLineToRelative(-4f); lineToRelative(4f, -8f); close()
            }
        }
    }

    val Palette: ImageVector by lazy {
        materialIcon(name = "Palette") {
            materialPath {
                moveTo(12f, 3f); curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f); reflectiveCurveToRelative(4.03f, 9f, 9f, 9f)
                curveToRelative(0.83f, 0f, 1.5f, -0.67f, 1.5f, -1.5f); curveToRelative(0f, -0.39f, -0.15f, -0.74f, -0.39f, -1.01f)
                curveToRelative(-0.23f, -0.26f, -0.38f, -0.61f, -0.38f, -0.99f); curveToRelative(0f, -0.83f, 0.67f, -1.5f, 1.5f, -1.5f)
                horizontalLineTo(16f); curveToRelative(2.76f, 0f, 5f, -2.24f, 5f, -5f); curveToRelative(0f, -4.42f, -4.03f, -8f, -9f, -8f); close()
                moveTo(6.5f, 12f); curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f); reflectiveCurveTo(5.67f, 9f, 6.5f, 9f)
                reflectiveCurveTo(8f, 9.67f, 8f, 10.5f); reflectiveCurveTo(7.33f, 12f, 6.5f, 12f); close()
                moveTo(9.5f, 8f); curveTo(8.67f, 8f, 8f, 7.33f, 8f, 6.5f); reflectiveCurveTo(8.67f, 5f, 9.5f, 5f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f); reflectiveCurveTo(10.33f, 8f, 9.5f, 8f); close()
                moveTo(14.5f, 8f); curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f); reflectiveCurveTo(13.67f, 5f, 14.5f, 5f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f); reflectiveCurveTo(15.33f, 8f, 14.5f, 8f); close()
                moveTo(17.5f, 12f); curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f); reflectiveCurveTo(16.67f, 9f, 17.5f, 9f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f); reflectiveCurveToRelative(-0.67f, 1.5f, -1.5f, 1.5f); close()
            }
        }
    }

    val Robot: ImageVector by lazy {
        materialIcon(name = "SmartToy") {
            materialPath {
                moveTo(20f, 9f); verticalLineTo(7f); curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); horizontalLineToRelative(-3f)
                curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f); reflectiveCurveTo(9f, 3.34f, 9f, 5f); horizontalLineTo(6f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(2f); curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
                reflectiveCurveToRelative(1.34f, 3f, 3f, 3f); verticalLineToRelative(4f); curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(12f); curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f); verticalLineToRelative(-4f)
                curveToRelative(1.66f, 0f, 3f, -1.34f, 3f, -3f); reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f); close()
                moveTo(7.5f, 11.5f); curveToRelative(0f, -0.83f, 0.67f, -1.5f, 1.5f, -1.5f); reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveTo(9.83f, 13f, 9f, 13f); reflectiveCurveToRelative(-1.5f, -0.67f, -1.5f, -1.5f); close()
                moveTo(16f, 17f); horizontalLineTo(8f); verticalLineToRelative(-2f); horizontalLineToRelative(8f); verticalLineToRelative(2f); close()
                moveTo(15f, 13f); curveToRelative(-0.83f, 0f, -1.5f, -0.67f, -1.5f, -1.5f); reflectiveCurveTo(14.17f, 10f, 15f, 10f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f); reflectiveCurveTo(15.83f, 13f, 15f, 13f); close()
            }
        }
    }

    val GraphicEq: ImageVector by lazy {
        materialIcon(name = "GraphicEq") {
            materialPath {
                moveTo(7f, 18f); horizontalLineToRelative(2f); verticalLineTo(6f); horizontalLineTo(7f); verticalLineToRelative(12f); close()
                moveTo(11f, 22f); horizontalLineToRelative(2f); verticalLineTo(2f); horizontalLineToRelative(-2f); verticalLineToRelative(20f); close()
                moveTo(3f, 14f); horizontalLineToRelative(2f); verticalLineToRelative(-4f); horizontalLineTo(3f); verticalLineToRelative(4f); close()
                moveTo(15f, 18f); horizontalLineToRelative(2f); verticalLineTo(6f); horizontalLineToRelative(-2f); verticalLineToRelative(12f); close()
                moveTo(19f, 10f); verticalLineToRelative(4f); horizontalLineToRelative(2f); verticalLineToRelative(-4f); horizontalLineToRelative(-2f); close()
            }
        }
    }

    val RecordVoice: ImageVector by lazy {
        materialIcon(name = "RecordVoiceOver") {
            materialPath {
                moveTo(9f, 13f); curveToRelative(2.21f, 0f, 4f, -1.79f, 4f, -4f); reflectiveCurveToRelative(-1.79f, -4f, -4f, -4f)
                reflectiveCurveToRelative(-4f, 1.79f, -4f, 4f); reflectiveCurveToRelative(1.79f, 4f, 4f, 4f); close()
                moveTo(9f, 7f); curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f); reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f)
                reflectiveCurveToRelative(-2f, -0.9f, -2f, -2f); reflectiveCurveToRelative(0.9f, -2f, 2f, -2f); close()
                moveTo(9f, 15f); curveToRelative(-2.67f, 0f, -8f, 1.34f, -8f, 4f); verticalLineToRelative(2f); horizontalLineToRelative(16f)
                verticalLineToRelative(-2f); curveToRelative(0f, -2.66f, -5.33f, -4f, -8f, -4f); close()
                moveTo(3f, 19f); curveToRelative(0.22f, -0.72f, 3.31f, -2f, 6f, -2f); curveToRelative(2.7f, 0f, 5.8f, 1.29f, 6f, 2f)
                horizontalLineTo(3f); close()
                moveTo(15.08f, 7.05f); curveToRelative(0.84f, 1.18f, 0.84f, 2.71f, 0f, 3.89f); lineToRelative(1.68f, 1.69f)
                curveToRelative(2.02f, -2.02f, 2.02f, -5.07f, 0f, -7.27f); lineToRelative(-1.68f, 1.69f); close()
                moveTo(20.07f, 2f); lineToRelative(-1.63f, 1.63f); curveToRelative(2.77f, 3.02f, 2.77f, 7.56f, 0f, 10.74f)
                lineTo(20.07f, 16f); curveToRelative(3.9f, -3.89f, 3.91f, -9.95f, 0f, -14f); close()
            }
        }
    }

    val ContentCopy: ImageVector by lazy {
        materialIcon(name = "ContentCopy") {
            materialPath {
                moveTo(16f, 1f); horizontalLineTo(4f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(14f)
                horizontalLineToRelative(2f); verticalLineTo(3f); horizontalLineToRelative(12f); verticalLineTo(1f); close()
                moveTo(19f, 5f); horizontalLineTo(8f); curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f); verticalLineToRelative(14f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(11f); curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(7f); curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); close()
                moveTo(19f, 21f); horizontalLineTo(8f); verticalLineTo(7f); horizontalLineToRelative(11f); verticalLineToRelative(14f); close()
            }
        }
    }

    val Key: ImageVector by lazy {
        materialIcon(name = "VpnKey") {
            materialPath {
                moveTo(12.65f, 10f); curveTo(11.83f, 7.67f, 9.61f, 6f, 7f, 6f); curveToRelative(-3.31f, 0f, -6f, 2.69f, -6f, 6f)
                reflectiveCurveToRelative(2.69f, 6f, 6f, 6f); curveToRelative(2.61f, 0f, 4.83f, -1.67f, 5.65f, -4f); horizontalLineTo(17f)
                verticalLineToRelative(4f); horizontalLineToRelative(4f); verticalLineToRelative(-4f); horizontalLineToRelative(2f)
                verticalLineToRelative(-4f); horizontalLineTo(12.65f); close()
                moveTo(7f, 14f); curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f); reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
                reflectiveCurveToRelative(2f, 0.9f, 2f, 2f); reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f); close()
            }
        }
    }

    val Cloud: ImageVector by lazy {
        materialIcon(name = "Cloud") {
            materialPath {
                moveTo(19.35f, 10.04f); curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f); curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f); curveToRelative(0f, 3.31f, 2.69f, 6f, 6f, 6f); horizontalLineToRelative(13f)
                curveToRelative(2.76f, 0f, 5f, -2.24f, 5f, -5f); curveToRelative(0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f); close()
            }
        }
    }

    val Visibility: ImageVector by lazy {
        materialIcon(name = "Visibility") {
            materialPath {
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
        materialIcon(name = "VisibilityOff") {
            materialPath {
                moveTo(12f, 7f); curveToRelative(2.76f, 0f, 5f, 2.24f, 5f, 5f); curveToRelative(0f, 0.65f, -0.13f, 1.26f, -0.36f, 1.83f)
                lineToRelative(2.92f, 2.92f); curveToRelative(1.51f, -1.26f, 2.7f, -2.89f, 3.43f, -4.75f)
                curveToRelative(-1.73f, -4.39f, -6f, -7.5f, -11f, -7.5f); curveToRelative(-1.4f, 0f, -2.74f, 0.25f, -3.98f, 0.7f)
                lineToRelative(2.16f, 2.16f); curveTo(10.74f, 7.13f, 11.35f, 7f, 12f, 7f); close()
                moveTo(2f, 4.27f); lineToRelative(2.28f, 2.28f); lineToRelative(0.46f, 0.46f); curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1f, 12f)
                curveToRelative(1.73f, 4.39f, 6f, 7.5f, 11f, 7.5f); curveToRelative(1.55f, 0f, 3.03f, -0.3f, 4.38f, -0.84f)
                lineToRelative(0.42f, 0.42f); lineTo(19.73f, 22f); lineTo(21f, 20.73f); lineTo(3.27f, 3f); lineTo(2f, 4.27f); close()
                moveTo(7.53f, 9.8f); lineToRelative(1.55f, 1.55f); curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
                curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f); curveToRelative(0.22f, 0f, 0.44f, -0.03f, 0.65f, -0.08f)
                lineToRelative(1.55f, 1.55f); curveToRelative(-0.67f, 0.33f, -1.41f, 0.53f, -2.2f, 0.53f)
                curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f); curveToRelative(0f, -0.79f, 0.2f, -1.53f, 0.53f, -2.2f); close()
                moveTo(11.84f, 9.02f); lineToRelative(3.15f, 3.15f); lineToRelative(0.02f, -0.16f); curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
                lineToRelative(-0.17f, 0.01f); close()
            }
        }
    }

    val DarkMode: ImageVector by lazy {
        materialIcon(name = "DarkMode") {
            materialPath {
                moveTo(12f, 3f); curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f); reflectiveCurveToRelative(4.03f, 9f, 9f, 9f)
                reflectiveCurveToRelative(9f, -4.03f, 9f, -9f); curveToRelative(0f, -0.46f, -0.04f, -0.92f, -0.1f, -1.36f)
                curveToRelative(-0.98f, 1.37f, -2.58f, 2.26f, -4.4f, 2.26f); curveToRelative(-2.98f, 0f, -5.4f, -2.42f, -5.4f, -5.4f)
                curveToRelative(0f, -1.81f, 0.89f, -3.42f, 2.26f, -4.4f); curveTo(12.92f, 3.04f, 12.46f, 3f, 12f, 3f); close()
            }
        }
    }

    val History: ImageVector by lazy {
        materialIcon(name = "History") {
            materialPath {
                moveTo(13f, 3f); curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f); horizontalLineTo(1f); lineToRelative(3.89f, 3.89f)
                lineToRelative(0.07f, 0.14f); lineTo(9f, 12f); horizontalLineTo(6f); curveToRelative(0f, -3.87f, 3.13f, -7f, 7f, -7f)
                reflectiveCurveToRelative(7f, 3.13f, 7f, 7f); reflectiveCurveToRelative(-3.13f, 7f, -7f, 7f)
                curveToRelative(-1.93f, 0f, -3.68f, -0.79f, -4.94f, -2.06f); lineToRelative(-1.42f, 1.42f)
                curveTo(8.27f, 19.99f, 10.51f, 21f, 13f, 21f); curveToRelative(4.97f, 0f, 9f, -4.03f, 9f, -9f)
                reflectiveCurveToRelative(-4.03f, -9f, -9f, -9f); close()
                moveTo(12f, 8f); verticalLineToRelative(5f); lineToRelative(4.28f, 2.54f); lineToRelative(0.72f, -1.21f)
                lineToRelative(-3.5f, -2.08f); verticalLineTo(8f); horizontalLineTo(12f); close()
            }
        }
    }

    val Tune: ImageVector by lazy {
        materialIcon(name = "Tune") {
            materialPath {
                moveTo(3f, 17f); verticalLineToRelative(2f); horizontalLineToRelative(6f); verticalLineToRelative(-2f); horizontalLineTo(3f); close()
                moveTo(3f, 5f); verticalLineToRelative(2f); horizontalLineToRelative(10f); verticalLineTo(5f); horizontalLineTo(3f); close()
                moveTo(13f, 21f); verticalLineToRelative(-2f); horizontalLineToRelative(8f); verticalLineToRelative(-2f); horizontalLineToRelative(-8f)
                verticalLineToRelative(-2f); horizontalLineToRelative(-2f); verticalLineToRelative(6f); horizontalLineToRelative(2f); close()
                moveTo(7f, 9f); verticalLineToRelative(2f); horizontalLineTo(3f); verticalLineToRelative(2f); horizontalLineToRelative(4f)
                verticalLineToRelative(2f); horizontalLineToRelative(2f); verticalLineTo(9f); horizontalLineTo(7f); close()
                moveTo(21f, 13f); verticalLineToRelative(-2f); horizontalLineTo(11f); verticalLineToRelative(2f); horizontalLineToRelative(10f); close()
                moveTo(15f, 9f); horizontalLineToRelative(2f); verticalLineTo(7f); horizontalLineToRelative(4f); verticalLineTo(5f)
                horizontalLineToRelative(-4f); verticalLineTo(3f); horizontalLineToRelative(-2f); verticalLineToRelative(6f); close()
            }
        }
    }

    val Keyboard: ImageVector by lazy {
        materialIcon(name = "Keyboard") {
            materialPath {
                moveTo(20f, 5f); horizontalLineTo(4f); curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f); lineTo(2f, 17f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f); horizontalLineToRelative(16f); curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(7f); curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f); close()
                moveTo(11f, 8f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); verticalLineTo(8f); close()
                moveTo(11f, 11f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); close()
                moveTo(8f, 8f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineTo(8f); verticalLineTo(8f); close()
                moveTo(8f, 11f); horizontalLineToRelative(2f); verticalLineToRelative(2f); horizontalLineTo(8f); verticalLineToRelative(-2f); close()
                moveTo(7f, 13f); horizontalLineTo(5f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(7f, 10f); horizontalLineTo(5f); verticalLineTo(8f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(16f, 17f); horizontalLineTo(8f); verticalLineToRelative(-2f); horizontalLineToRelative(8f); verticalLineToRelative(2f); close()
                moveTo(16f, 13f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(16f, 10f); horizontalLineToRelative(-2f); verticalLineTo(8f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(19f, 13f); horizontalLineToRelative(-2f); verticalLineToRelative(-2f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
                moveTo(19f, 10f); horizontalLineToRelative(-2f); verticalLineTo(8f); horizontalLineToRelative(2f); verticalLineToRelative(2f); close()
            }
        }
    }

    val Sparkle: ImageVector by lazy {
        materialIcon(name = "AutoAwesome") {
            materialPath {
                moveTo(19f, 9f); lineToRelative(1.25f, -2.75f); lineTo(23f, 5f); lineToRelative(-2.75f, -1.25f); lineTo(19f, 1f)
                lineToRelative(-1.25f, 2.75f); lineTo(15f, 5f); lineToRelative(2.75f, 1.25f); lineTo(19f, 9f); close()
                moveTo(11.5f, 9.5f); lineTo(9f, 4f); lineTo(6.5f, 9.5f); lineTo(1f, 12f); lineToRelative(5.5f, 2.5f); lineTo(9f, 20f)
                lineToRelative(2.5f, -5.5f); lineTo(17f, 12f); lineToRelative(-5.5f, -2.5f); close()
                moveTo(19f, 15f); lineToRelative(-1.25f, 2.75f); lineTo(15f, 19f); lineToRelative(2.75f, 1.25f); lineTo(19f, 23f)
                lineToRelative(1.25f, -2.75f); lineTo(23f, 19f); lineToRelative(-2.75f, -1.25f); lineTo(19f, 15f); close()
            }
        }
    }

    val Hearing: ImageVector by lazy {
        materialIcon(name = "Hearing") {
            materialPath {
                moveTo(17f, 20f); curveToRelative(-0.29f, 0f, -0.56f, -0.06f, -0.76f, -0.15f); curveToRelative(-0.71f, -0.37f, -1.21f, -0.88f, -1.71f, -2.38f)
                curveToRelative(-0.51f, -1.56f, -1.47f, -2.29f, -2.39f, -3f); curveToRelative(-0.79f, -0.61f, -1.61f, -1.24f, -2.32f, -2.53f)
                curveTo(9.29f, 10.98f, 9f, 9.93f, 9f, 9f); curveToRelative(0f, -2.8f, 2.2f, -5f, 5f, -5f); reflectiveCurveToRelative(5f, 2.2f, 5f, 5f)
                horizontalLineToRelative(2f); curveToRelative(0f, -3.93f, -3.07f, -7f, -7f, -7f); reflectiveCurveTo(7f, 5.07f, 7f, 9f)
                curveToRelative(0f, 1.26f, 0.38f, 2.65f, 1.07f, 3.9f); curveToRelative(0.91f, 1.65f, 1.98f, 2.48f, 2.85f, 3.15f)
                curveToRelative(0.81f, 0.62f, 1.39f, 1.07f, 1.71f, 2.05f); curveToRelative(0.6f, 1.82f, 1.37f, 2.84f, 2.73f, 3.55f)
                curveToRelative(0.51f, 0.23f, 1.07f, 0.35f, 1.64f, 0.35f); curveToRelative(2.21f, 0f, 4f, -1.79f, 4f, -4f)
                horizontalLineToRelative(-2f); curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f); close()
                moveTo(7.64f, 2.64f); lineTo(6.22f, 1.22f); curveTo(4.23f, 3.21f, 3f, 5.96f, 3f, 9f); reflectiveCurveToRelative(1.23f, 5.79f, 3.22f, 7.78f)
                lineToRelative(1.41f, -1.41f); curveTo(6.01f, 13.74f, 5f, 11.49f, 5f, 9f); reflectiveCurveToRelative(1.01f, -4.74f, 2.64f, -6.36f); close()
                moveTo(11.5f, 9f); curveToRelative(0f, 1.38f, 1.12f, 2.5f, 2.5f, 2.5f); reflectiveCurveToRelative(2.5f, -1.12f, 2.5f, -2.5f)
                reflectiveCurveToRelative(-1.12f, -2.5f, -2.5f, -2.5f); reflectiveCurveTo(11.5f, 7.62f, 11.5f, 9f); close()
            }
        }
    }

    val Language: ImageVector by lazy {
        materialIcon(name = "Language") {
            materialPath {
                moveTo(11.99f, 2f); curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f); reflectiveCurveToRelative(4.47f, 10f, 9.99f, 10f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f); reflectiveCurveTo(17.52f, 2f, 11.99f, 2f); close()
                moveTo(18.92f, 8f); horizontalLineToRelative(-2.95f); curveToRelative(-0.32f, -1.25f, -0.78f, -2.45f, -1.38f, -3.56f)
                curveToRelative(1.84f, 0.63f, 3.37f, 1.91f, 4.33f, 3.56f); close()
                moveTo(12f, 4.04f); curveToRelative(0.83f, 1.2f, 1.48f, 2.53f, 1.91f, 3.96f); horizontalLineToRelative(-3.82f)
                curveToRelative(0.43f, -1.43f, 1.08f, -2.76f, 1.91f, -3.96f); close()
                moveTo(4.26f, 14f); curveTo(4.1f, 13.36f, 4f, 12.69f, 4f, 12f); reflectiveCurveToRelative(0.1f, -1.36f, 0.26f, -2f)
                horizontalLineToRelative(3.38f); curveToRelative(-0.08f, 0.66f, -0.14f, 1.32f, -0.14f, 2f); curveToRelative(0f, 0.68f, 0.06f, 1.34f, 0.14f, 2f)
                horizontalLineTo(4.26f); close()
                moveTo(5.08f, 16f); horizontalLineToRelative(2.95f); curveToRelative(0.32f, 1.25f, 0.78f, 2.45f, 1.38f, 3.56f)
                curveToRelative(-1.84f, -0.63f, -3.37f, -1.9f, -4.33f, -3.56f); close()
                moveTo(8.03f, 8f); horizontalLineTo(5.08f); curveToRelative(0.96f, -1.66f, 2.49f, -2.93f, 4.33f, -3.56f)
                curveTo(8.81f, 5.55f, 8.35f, 6.75f, 8.03f, 8f); close()
                moveTo(12f, 19.96f); curveToRelative(-0.83f, -1.2f, -1.48f, -2.53f, -1.91f, -3.96f); horizontalLineToRelative(3.82f)
                curveToRelative(-0.43f, 1.43f, -1.08f, 2.76f, -1.91f, 3.96f); close()
                moveTo(14.34f, 14f); horizontalLineTo(9.66f); curveToRelative(-0.09f, -0.66f, -0.16f, -1.32f, -0.16f, -2f)
                curveToRelative(0f, -0.68f, 0.07f, -1.35f, 0.16f, -2f); horizontalLineToRelative(4.68f); curveToRelative(0.09f, 0.65f, 0.16f, 1.32f, 0.16f, 2f)
                curveToRelative(0f, 0.68f, -0.07f, 1.34f, -0.16f, 2f); close()
                moveTo(14.59f, 19.56f); curveToRelative(0.6f, -1.11f, 1.06f, -2.31f, 1.38f, -3.56f); horizontalLineToRelative(2.95f)
                curveToRelative(-0.96f, 1.65f, -2.49f, 2.93f, -4.33f, 3.56f); close()
                moveTo(16.36f, 14f); curveToRelative(0.08f, -0.66f, 0.14f, -1.32f, 0.14f, -2f); curveToRelative(0f, -0.68f, -0.06f, -1.34f, -0.14f, -2f)
                horizontalLineToRelative(3.38f); curveToRelative(0.16f, 0.64f, 0.26f, 1.31f, 0.26f, 2f); reflectiveCurveToRelative(-0.1f, 1.36f, -0.26f, 2f)
                horizontalLineToRelative(-3.38f); close()
            }
        }
    }

    val Replay: ImageVector by lazy {
        materialIcon(name = "Replay") {
            materialPath {
                moveTo(12f, 5f); verticalLineTo(1f); lineTo(7f, 6f); lineToRelative(5f, 5f); verticalLineTo(7f)
                curveToRelative(3.31f, 0f, 6f, 2.69f, 6f, 6f); reflectiveCurveToRelative(-2.69f, 6f, -6f, 6f); reflectiveCurveToRelative(-6f, -2.69f, -6f, -6f)
                horizontalLineTo(4f); curveToRelative(0f, 4.42f, 3.58f, 8f, 8f, 8f); reflectiveCurveToRelative(8f, -3.58f, 8f, -8f)
                reflectiveCurveToRelative(-3.58f, -8f, -8f, -8f); close()
            }
        }
    }

    val Face: ImageVector by lazy {
        materialIcon(name = "SentimentSatisfied") {
            materialPath {
                moveTo(11.99f, 2f); curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f); reflectiveCurveToRelative(4.47f, 10f, 9.99f, 10f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f); reflectiveCurveTo(17.52f, 2f, 11.99f, 2f); close()
                moveTo(12f, 20f); curveToRelative(-4.42f, 0f, -8f, -3.58f, -8f, -8f); reflectiveCurveToRelative(3.58f, -8f, 8f, -8f)
                reflectiveCurveToRelative(8f, 3.58f, 8f, 8f); reflectiveCurveToRelative(-3.58f, 8f, -8f, 8f); close()
                moveTo(15.5f, 11f); curveToRelative(0.83f, 0f, 1.5f, -0.67f, 1.5f, -1.5f); reflectiveCurveTo(16.33f, 8f, 15.5f, 8f)
                reflectiveCurveTo(14f, 8.67f, 14f, 9.5f); reflectiveCurveToRelative(0.67f, 1.5f, 1.5f, 1.5f); close()
                moveTo(8.5f, 11f); curveToRelative(0.83f, 0f, 1.5f, -0.67f, 1.5f, -1.5f); reflectiveCurveTo(9.33f, 8f, 8.5f, 8f)
                reflectiveCurveTo(7f, 8.67f, 7f, 9.5f); reflectiveCurveTo(7.67f, 11f, 8.5f, 11f); close()
                moveTo(12f, 17.5f); curveToRelative(2.33f, 0f, 4.31f, -1.46f, 5.11f, -3.5f); horizontalLineTo(6.89f)
                curveToRelative(0.8f, 2.04f, 2.78f, 3.5f, 5.11f, 3.5f); close()
            }
        }
    }
}
