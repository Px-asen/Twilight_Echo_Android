package com.twilightecho.android.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Playback controls use Google Material Symbols Rounded (Apache-2.0), imported as native vectors.
 * Original SVGs and the icon mapping live in ui/icons/material-symbols-rounded.
 * Other icons retain the custom 24x24 grid and rounded strokes.
 */
object TwilightIcons {

    val Play: ImageVector by lazy {
        roundedSymbol("TwilightPlay",
            "M320-273v-414q0-17 12-28.5t28-11.5q5 0 10.5 1.5T381-721l326 207q9 6 13.5 15t4.5 19q0 10-4.5 19T707-446L381-239q-5 3-10.5 4.5T360-233q-16 0-28-11.5T320-273Z")
    }

    val Pause: ImageVector by lazy {
        roundedSymbol("TwilightPause",
            "M640-200q-33 0-56.5-23.5T560-280v-400q0-33 23.5-56.5T640-760q33 0 56.5 23.5T720-680v400q0 33-23.5 56.5T640-200Zm-320 0q-33 0-56.5-23.5T240-280v-400q0-33 23.5-56.5T320-760q33 0 56.5 23.5T400-680v400q0 33-23.5 56.5T320-200Z")
    }

    val SkipPrevious: ImageVector by lazy {
        roundedSymbol("TwilightSkipPrevious",
            "M220-280v-400q0-17 11.5-28.5T260-720q17 0 28.5 11.5T300-680v400q0 17-11.5 28.5T260-240q-17 0-28.5-11.5T220-280Zm458-1L430-447q-9-6-13.5-14.5T412-480q0-10 4.5-18.5T430-513l248-166q5-4 11-5t11-1q16 0 28 11t12 29v330q0 18-12 29t-28 11q-5 0-11-1t-11-5Z")
    }

    val SkipNext: ImageVector by lazy {
        roundedSymbol("TwilightSkipNext",
            "M660-280v-400q0-17 11.5-28.5T700-720q17 0 28.5 11.5T740-680v400q0 17-11.5 28.5T700-240q-17 0-28.5-11.5T660-280Zm-440-35v-330q0-18 12-29t28-11q5 0 11 1t11 5l248 166q9 6 13.5 14.5T548-480q0 10-4.5 18.5T530-447L282-281q-5 4-11 5t-11 1q-16 0-28-11t-12-29Z")
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
        roundedSymbol("TwilightQueue",
            "M640-160q-50 0-85-35t-35-85q0-50 35-85t85-35q11 0 21 1.5t19 6.5v-288q0-17 11.5-28.5T720-720h120q17 0 28.5 11.5T880-680q0 17-11.5 28.5T840-640h-80v360q0 50-35 85t-85 35ZM160-320q-17 0-28.5-11.5T120-360q0-17 11.5-28.5T160-400h240q17 0 28.5 11.5T440-360q0 17-11.5 28.5T400-320H160Zm0-160q-17 0-28.5-11.5T120-520q0-17 11.5-28.5T160-560h400q17 0 28.5 11.5T600-520q0 17-11.5 28.5T560-480H160Zm0-160q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h400q17 0 28.5 11.5T600-680q0 17-11.5 28.5T560-640H160Z")
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
        roundedSymbol("TwilightRepeatSequence",
            "M160-320q-17 0-28.5-11.5T120-360q0-17 11.5-28.5T160-400h240q17 0 28.5 11.5T440-360q0 17-11.5 28.5T400-320H160Zm0-160q-17 0-28.5-11.5T120-520q0-17 11.5-28.5T160-560h400q17 0 28.5 11.5T600-520q0 17-11.5 28.5T560-480H160Zm0-160q-17 0-28.5-11.5T120-680q0-17 11.5-28.5T160-720h400q17 0 28.5 11.5T600-680q0 17-11.5 28.5T560-640H160Zm511 499q-5 3-10 3t-10-2q-5-2-8-6.5t-3-10.5v-246q0-6 3-10.5t8-6.5q5-2 10-2t10 3l184 122q5 3 7 7.5t2 9.5q0 5-2 9.5t-7 7.5L671-141Z")
    }

    val RepeatAll: ImageVector by lazy {
        roundedSymbol("TwilightRepeatAll",
            "m274-200 34 34q12 12 11.5 28T308-110q-12 12-28.5 12.5T251-109L148-212q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l103-103q12-12 28.5-11.5T308-370q11 12 11.5 28T308-314l-34 34h406v-120q0-17 11.5-28.5T720-440q17 0 28.5 11.5T760-400v120q0 33-23.5 56.5T680-200H274Zm412-480H280v120q0 17-11.5 28.5T240-520q-17 0-28.5-11.5T200-560v-120q0-33 23.5-56.5T280-760h406l-34-34q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T709-851l103 103q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L709-589q-12 12-28.5 11.5T652-590q-11-12-11.5-28t11.5-28l34-34Z")
    }

    val RepeatOne: ImageVector by lazy {
        roundedSymbol("TwilightRepeatOne",
            "M460-540h-30q-13 0-21.5-8.5T400-570q0-13 8.5-21.5T430-600h50q17 0 28.5 11.5T520-560v170q0 13-8.5 21.5T490-360q-13 0-21.5-8.5T460-390v-150ZM274-200l34 34q12 12 11.5 28T308-110q-12 12-28.5 12.5T251-109L148-212q-6-6-8.5-13t-2.5-15q0-8 2.5-15t8.5-13l103-103q12-12 28.5-11.5T308-370q11 12 11.5 28T308-314l-34 34h406v-120q0-17 11.5-28.5T720-440q17 0 28.5 11.5T760-400v120q0 33-23.5 56.5T680-200H274Zm412-480H280v120q0 17-11.5 28.5T240-520q-17 0-28.5-11.5T200-560v-120q0-33 23.5-56.5T280-760h406l-34-34q-12-12-11.5-28t11.5-28q12-12 28.5-12.5T709-851l103 103q6 6 8.5 13t2.5 15q0 8-2.5 15t-8.5 13L709-589q-12 12-28.5 11.5T652-590q-11-12-11.5-28t11.5-28l34-34Z")
    }

    val Shuffle: ImageVector by lazy {
        roundedSymbol("TwilightShuffle",
            "M600-160q-17 0-28.5-11.5T560-200q0-17 11.5-28.5T600-240h64l-99-99q-12-12-11.5-28.5T566-396q12-12 28.5-12t28.5 12l97 98v-62q0-17 11.5-28.5T760-400q17 0 28.5 11.5T800-360v160q0 17-11.5 28.5T760-160H600Zm-428-12q-11-11-11-28t11-28l492-492h-64q-17 0-28.5-11.5T560-760q0-17 11.5-28.5T600-800h160q17 0 28.5 11.5T800-760v160q0 17-11.5 28.5T760-560q-17 0-28.5-11.5T720-600v-64L228-172q-11 11-28 11t-28-11Zm-1-560q-11-11-11-28t11-28q11-11 27.5-11t28.5 11l168 167q11 11 11.5 27.5T395-565q-11 11-28 11t-28-11L171-732Z")
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

    val ArrowBack: ImageVector by lazy {
        strokeIcon("ArrowBack") { path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
        } }
    }
    val ArrowRight: ImageVector by lazy {
        strokeIcon("ArrowRight") { path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5f, 12f); lineTo(19f, 12f); moveTo(13f, 6f); lineTo(19f, 12f); lineTo(13f, 18f)
        } }
    }
    val More: ImageVector by lazy {
        strokeIcon("More") { path(fill = SolidColor(Color.Black)) {
            for (x in listOf(5f, 12f, 19f)) {
                moveTo(x + 1.5f, 12f)
                arcTo(1.5f, 1.5f, 0f, true, true, x - 1.5f, 12f)
                arcTo(1.5f, 1.5f, 0f, true, true, x + 1.5f, 12f)
            }
        } }
    }
    val Computer: ImageVector by lazy {
        strokeIcon("Computer") { path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 16f); lineTo(3f, 16f); close()
            moveTo(12f, 16f); lineTo(12f, 21f); moveTo(8f, 21f); lineTo(16f, 21f)
        } }
    }

    val Phone: ImageVector by lazy {
        strokeIcon("Phone") { path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(8f, 2.5f); lineTo(16f, 2.5f)
            arcTo(2f, 2f, 0f, false, true, 18f, 4.5f); lineTo(18f, 19.5f)
            arcTo(2f, 2f, 0f, false, true, 16f, 21.5f); lineTo(8f, 21.5f)
            arcTo(2f, 2f, 0f, false, true, 6f, 19.5f); lineTo(6f, 4.5f)
            arcTo(2f, 2f, 0f, false, true, 8f, 2.5f); close()
            moveTo(10.5f, 18.5f); lineTo(13.5f, 18.5f)
        } }
    }
    val Speaker: ImageVector by lazy {
        strokeIcon("Speaker") { path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 4.5f); lineTo(13f, 19.5f); lineTo(8f, 15f); lineTo(4f, 15f); close()
            moveTo(16.5f, 9f); arcTo(4f, 4f, 0f, false, true, 16.5f, 15f)
            moveTo(19f, 6.5f); arcTo(7.5f, 7.5f, 0f, false, true, 19f, 17.5f)
        } }
    }

    /** Material Symbols Rounded SVGs use a 0 -960 960 960 viewBox. */
    private fun roundedSymbol(name: String, data: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f
    ).apply {
        group(translationY = 960f) {
            addPath(
                pathData = addPathNodes(data),
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero
            )
        }
    }.build()

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
