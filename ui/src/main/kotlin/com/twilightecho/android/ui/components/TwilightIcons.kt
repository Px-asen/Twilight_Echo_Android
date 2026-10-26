package com.twilightecho.android.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Precision custom vector iconography aligned with Desktop `PlayerControlIcon.vue` (24x24 grid,
 * crisp 1.8dp rounded strokes and geometric fills). Eliminates text buttons and raw emojis.
 */
object TwilightIcons {

    val Play: ImageVector by lazy {
        ImageVector.Builder(
            name = "TwilightPlay",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(7.5f, 5.2f)
                curveTo(6.7f, 4.7f, 5.7f, 5.3f, 5.7f, 6.2f)
                verticalLineTo(17.8f)
                curveTo(5.7f, 18.7f, 6.7f, 19.3f, 7.5f, 18.8f)
                lineTo(18.2f, 13.0f)
                curveTo(19.0f, 12.5f, 19.0f, 11.5f, 18.2f, 11.0f)
                close()
            }
        }.build()
    }

    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "TwilightPause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6.5f, 5f)
                horizontalLineTo(9.8f)
                verticalLineTo(19f)
                horizontalLineTo(6.5f)
                close()
                moveTo(14.2f, 5f)
                horizontalLineTo(17.5f)
                verticalLineTo(19f)
                horizontalLineTo(14.2f)
                close()
            }
        }.build()
    }

    val SkipPrevious: ImageVector by lazy {
        ImageVector.Builder(
            name = "TwilightSkipPrevious",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 6f)
                verticalLineTo(18f)
            }
            path(
                fill = SolidColor(Color.Black)
            ) {
                moveTo(18f, 6.2f)
                verticalLineTo(17.8f)
                lineTo(9.2f, 12f)
                close()
            }
        }.build()
    }

    val SkipNext: ImageVector by lazy {
        ImageVector.Builder(
            name = "TwilightSkipNext",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(18f, 6f)
                verticalLineTo(18f)
            }
            path(
                fill = SolidColor(Color.Black)
            ) {
                moveTo(6f, 6.2f)
                verticalLineTo(17.8f)
                lineTo(14.8f, 12f)
                close()
            }
        }.build()
    }

    val HeartOutline: ImageVector by lazy {
        strokeIcon("TwilightHeartOutline") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(20.8f, 4.6f)
                curveTo(18.7f, 2.5f, 15.3f, 2.5f, 13.2f, 4.6f)
                lineTo(12f, 5.8f)
                lineTo(10.8f, 4.6f)
                curveTo(8.7f, 2.5f, 5.3f, 2.5f, 3.2f, 4.6f)
                curveTo(1.1f, 6.7f, 1.1f, 10.1f, 3.2f, 12.2f)
                lineTo(12f, 21f)
                lineTo(20.8f, 12.2f)
                curveTo(22.9f, 10.1f, 22.9f, 6.7f, 20.8f, 4.6f)
                close()
            }
        }
    }

    val HeartFilled: ImageVector by lazy {
        ImageVector.Builder(
            name = "TwilightHeartFilled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20.8f, 4.6f)
                curveTo(18.7f, 2.5f, 15.3f, 2.5f, 13.2f, 4.6f)
                lineTo(12f, 5.8f)
                lineTo(10.8f, 4.6f)
                curveTo(8.7f, 2.5f, 5.3f, 2.5f, 3.2f, 4.6f)
                curveTo(1.1f, 6.7f, 1.1f, 10.1f, 3.2f, 12.2f)
                lineTo(12f, 21f)
                lineTo(20.8f, 12.2f)
                curveTo(22.9f, 10.1f, 22.9f, 6.7f, 20.8f, 4.6f)
                close()
            }
        }.build()
    }

    val Bookmark: ImageVector by lazy {
        strokeIcon("TwilightBookmark") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 4f)
                horizontalLineTo(18f)
                verticalLineTo(20f)
                lineTo(12f, 15.5f)
                lineTo(6f, 20f)
                close()
            }
        }
    }

    val Queue: ImageVector by lazy {
        strokeIcon("TwilightQueue") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(8f, 6f)
                horizontalLineTo(20f)
                moveTo(8f, 12f)
                horizontalLineTo(20f)
                moveTo(8f, 18f)
                horizontalLineTo(20f)
                moveTo(4f, 6f)
                horizontalLineTo(4.2f)
                moveTo(4f, 12f)
                horizontalLineTo(4.2f)
                moveTo(4f, 18f)
                horizontalLineTo(4.2f)
            }
        }
    }

    val Equalizer: ImageVector by lazy {
        strokeIcon("TwilightEqualizer") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(5f, 4f)
                verticalLineTo(8f)
                moveTo(5f, 12f)
                verticalLineTo(20f)
                moveTo(12f, 4f)
                verticalLineTo(13f)
                moveTo(12f, 17f)
                verticalLineTo(20f)
                moveTo(19f, 4f)
                verticalLineTo(7f)
                moveTo(19f, 11f)
                verticalLineTo(20f)

                moveTo(2.8f, 8f)
                horizontalLineTo(7.2f)
                verticalLineTo(11.5f)
                horizontalLineTo(2.8f)
                close()

                moveTo(9.8f, 13f)
                horizontalLineTo(14.2f)
                verticalLineTo(16.5f)
                horizontalLineTo(9.8f)
                close()

                moveTo(16.8f, 7f)
                horizontalLineTo(21.2f)
                verticalLineTo(10.5f)
                horizontalLineTo(16.8f)
                close()
            }
        }
    }

    val Library: ImageVector by lazy {
        strokeIcon("TwilightLibrary") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 6f)
                verticalLineTo(19f)
                moveTo(8.5f, 5f)
                verticalLineTo(19f)
                moveTo(13f, 6f)
                lineTo(19.5f, 18.5f)
                moveTo(14.5f, 5f)
                lineTo(20.5f, 16.5f)
            }
        }
    }

    val RepeatSequence: ImageVector by lazy {
        strokeIcon("TwilightRepeatSequence") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 8f)
                horizontalLineTo(18f)
                moveTo(15f, 5f)
                lineTo(18.5f, 8f)
                lineTo(15f, 11f)
                moveTo(4f, 16f)
                horizontalLineTo(18f)
                moveTo(15f, 13f)
                lineTo(18.5f, 16f)
                lineTo(15f, 19f)
            }
        }
    }

    val RepeatAll: ImageVector by lazy {
        strokeIcon("TwilightRepeatAll") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(17f, 2f)
                lineTo(21f, 6f)
                lineTo(17f, 10f)
                moveTo(3f, 11f)
                verticalLineTo(9f)
                curveTo(3f, 7.3f, 4.3f, 6f, 6f, 6f)
                horizontalLineTo(21f)
                moveTo(7f, 22f)
                lineTo(3f, 18f)
                lineTo(7f, 14f)
                moveTo(21f, 13f)
                verticalLineTo(15f)
                curveTo(21f, 16.7f, 19.7f, 18f, 18f, 18f)
                horizontalLineTo(3f)
            }
        }
    }

    val RepeatOne: ImageVector by lazy {
        strokeIcon("TwilightRepeatOne") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(17f, 2f)
                lineTo(21f, 6f)
                lineTo(17f, 10f)
                moveTo(3f, 11f)
                verticalLineTo(9f)
                curveTo(3f, 7.3f, 4.3f, 6f, 6f, 6f)
                horizontalLineTo(21f)
                moveTo(7f, 22f)
                lineTo(3f, 18f)
                lineTo(7f, 14f)
                moveTo(21f, 13f)
                verticalLineTo(15f)
                curveTo(21f, 16.7f, 19.7f, 18f, 18f, 18f)
                horizontalLineTo(3f)
                // Center '1'
                moveTo(11f, 10.5f)
                lineTo(12.5f, 9.5f)
                verticalLineTo(14.5f)
            }
        }
    }

    val Shuffle: ImageVector by lazy {
        strokeIcon("TwilightShuffle") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(16f, 3f)
                horizontalLineTo(21f)
                verticalLineTo(8f)
                moveTo(4f, 20f)
                lineTo(21f, 3f)
                moveTo(21f, 16f)
                verticalLineTo(21f)
                horizontalLineTo(16f)
                moveTo(15f, 15f)
                lineTo(21f, 21f)
                moveTo(4f, 4f)
                lineTo(9f, 9f)
            }
        }
    }

    val Search: ImageVector by lazy {
        strokeIcon("TwilightSearch") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(17f, 11f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5f, 11f)
                arcTo(6f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17f, 11f)
                moveTo(15.5f, 15.5f)
                lineTo(20f, 20f)
            }
        }
    }

    val MusicNote: ImageVector by lazy {
        strokeIcon("TwilightMusicNote") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9f, 18f)
                verticalLineTo(5f)
                lineTo(20f, 3f)
                verticalLineTo(16f)
                moveTo(9f, 18f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 3f, 18f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9f, 18f)
                moveTo(20f, 16f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14f, 16f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20f, 16f)
            }
        }
    }

    val AlbumDisc: ImageVector by lazy {
        strokeIcon("TwilightAlbumDisc") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(21f, 12f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 3f, 12f)
                arcTo(9f, 9f, 0f, isMoreThanHalf = true, isPositiveArc = true, 21f, 12f)
                moveTo(14.5f, 12f)
                arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.5f, 12f)
                arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14.5f, 12f)
            }
        }
    }

    val ArtistMic: ImageVector by lazy {
        strokeIcon("TwilightArtistMic") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 2f)
                curveTo(10.3f, 2f, 9f, 3.3f, 9f, 5f)
                verticalLineTo(11f)
                curveTo(9f, 12.7f, 10.3f, 14f, 12f, 14f)
                curveTo(13.7f, 14f, 15f, 12.7f, 15f, 11f)
                verticalLineTo(5f)
                curveTo(15f, 3.3f, 13.7f, 2f, 12f, 2f)
                close()
                moveTo(19f, 10f)
                verticalLineTo(11f)
                curveTo(19f, 14.9f, 15.9f, 18f, 12f, 18f)
                curveTo(8.1f, 18f, 5f, 14.9f, 5f, 11f)
                verticalLineTo(10f)
                moveTo(12f, 18f)
                verticalLineTo(22f)
                moveTo(8f, 22f)
                horizontalLineTo(16f)
            }
        }
    }

    val RadarScan: ImageVector by lazy {
        strokeIcon("TwilightRadarScan") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 3f)
                verticalLineTo(21f)
                moveTo(3f, 12f)
                horizontalLineTo(21f)
                moveTo(18.5f, 12f)
                arcTo(6.5f, 6.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5.5f, 12f)
                arcTo(6.5f, 6.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 18.5f, 12f)
            }
        }
    }

    val SignalWave: ImageVector by lazy {
        strokeIcon("TwilightSignalWave") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(2f, 12f)
                horizontalLineTo(5.5f)
                lineTo(8f, 5f)
                lineTo(12f, 19f)
                lineTo(15.5f, 8f)
                lineTo(18f, 12f)
                horizontalLineTo(22f)
            }
        }
    }

    val Close: ImageVector by lazy {
        strokeIcon("TwilightClose") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(18f, 6f)
                lineTo(6f, 18f)
                moveTo(6f, 6f)
                lineTo(18f, 18f)
            }
        }
    }

    val Plus: ImageVector by lazy {
        strokeIcon("TwilightPlus") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 5f)
                verticalLineTo(19f)
                moveTo(5f, 12f)
                horizontalLineTo(19f)
            }
        }
    }

    val Trash: ImageVector by lazy {
        strokeIcon("TwilightTrash") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 7f)
                horizontalLineTo(20f)
                moveTo(9f, 7f)
                verticalLineTo(4f)
                horizontalLineTo(15f)
                verticalLineTo(7f)
                moveTo(6f, 7f)
                lineTo(7f, 20f)
                horizontalLineTo(17f)
                lineTo(18f, 7f)
            }
        }
    }

    val Home: ImageVector by lazy {
        strokeIcon("TwilightHome") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 10.5f)
                lineTo(12f, 3f)
                lineTo(21f, 10.5f)
                moveTo(5.5f, 9.2f)
                verticalLineTo(19.5f)
                curveTo(5.5f, 20.1f, 5.9f, 20.5f, 6.5f, 20.5f)
                horizontalLineTo(17.5f)
                curveTo(18.1f, 20.5f, 18.5f, 20.1f, 18.5f, 19.5f)
                verticalLineTo(9.2f)
                moveTo(9.8f, 20.5f)
                verticalLineTo(14.5f)
                horizontalLineTo(14.2f)
                verticalLineTo(20.5f)
            }
        }
    }

    val Settings: ImageVector by lazy {
        strokeIcon("TwilightSettings") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(15f, 12f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9f, 12f)
                arcTo(3f, 3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 15f, 12f)

                moveTo(12f, 2.5f)
                verticalLineTo(5f)
                moveTo(12f, 19f)
                verticalLineTo(21.5f)
                moveTo(2.5f, 12f)
                horizontalLineTo(5f)
                moveTo(19f, 12f)
                horizontalLineTo(21.5f)
                moveTo(5.3f, 5.3f)
                lineTo(7.1f, 7.1f)
                moveTo(16.9f, 16.9f)
                lineTo(18.7f, 18.7f)
                moveTo(18.7f, 5.3f)
                lineTo(16.9f, 7.1f)
                moveTo(7.1f, 16.9f)
                lineTo(5.3f, 18.7f)

                moveTo(18.5f, 12f)
                arcTo(6.5f, 6.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5.5f, 12f)
                arcTo(6.5f, 6.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 18.5f, 12f)
            }
        }
    }

    val ChevronDown: ImageVector by lazy {
        strokeIcon("TwilightChevronDown") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 9f)
                lineTo(12f, 15f)
                lineTo(18f, 9f)
            }
        }
    }

    val Sparkles: ImageVector by lazy {
        strokeIcon("TwilightSparkles") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 3f)
                lineTo(14.2f, 8.8f)
                lineTo(20f, 11f)
                lineTo(14.2f, 13.2f)
                lineTo(12f, 19f)
                lineTo(9.8f, 13.2f)
                lineTo(4f, 11f)
                lineTo(9.8f, 8.8f)
                close()
                moveTo(19f, 3f)
                verticalLineTo(6f)
                moveTo(17.5f, 4.5f)
                horizontalLineTo(20.5f)
            }
        }
    }

    private inline fun strokeIcon(
        name: String,
        block: ImageVector.Builder.() -> Unit
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply(block).build()
}
