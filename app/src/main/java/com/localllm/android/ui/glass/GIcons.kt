package com.localllm.android.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * First-party glyph set (24px grid). Geometric strokes replace every Material
 * icon so no material-icons artifact ships in the app.
 */
object GIcons {

    private fun stroke(vararg d: String): ImageVector {
        // Single addPath: multiple subpaths in ONE path string. (Separate addPath
        // calls proved unreliable on-device — only the first call painted.)
        val b = ImageVector.Builder("g", 24.dp, 24.dp, 24f, 24f)
        b.addPath(
            PathParser().parsePathString(d.joinToString("")).toNodes(),
            pathFillType = PathFillType.NonZero,
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
        return b.build()
    }

    private fun fill(vararg d: String): ImageVector {
        val b = ImageVector.Builder("g", 24.dp, 24.dp, 24f, 24f)
        b.addPath(
            PathParser().parsePathString(d.joinToString("")).toNodes(),
            fill = SolidColor(Color.Black)
        )
        return b.build()
    }

    private fun evenOdd(vararg d: String): ImageVector {
        val b = ImageVector.Builder("g", 24.dp, 24.dp, 24f, 24f)
        b.addPath(
            PathParser().parsePathString(d.joinToString("")).toNodes(),
            pathFillType = PathFillType.EvenOdd,
            fill = SolidColor(Color.Black)
        )
        return b.build()
    }

    val Menu: ImageVector by lazy { stroke("M4 7h16M4 12h16M4 17h16") }
    val Add: ImageVector by lazy { stroke("M12 5v14M5 12h14") }
    val Close: ImageVector by lazy { stroke("M6 6l12 12M18 6L6 18") }
    val Check: ImageVector by lazy { stroke("M5 12.5l4.5 4.5L19 7.5") }
    val Done: ImageVector get() = Check
    val CheckCircle: ImageVector by lazy {
        stroke("M12 3c5 0 9 4 9 9s-4 9-9 9-9-4-9-9 4-9 9-9z", "M8.3 12.2l2.5 2.5 5-5.4")
    }
    val ArrowBack: ImageVector by lazy { stroke("M19 12H5M11 6l-6 6 6 6") }
    val ArrowForward: ImageVector by lazy { stroke("M5 12h14M13 6l6 6-6 6") }
    val ArrowDown: ImageVector by lazy { stroke("M6 9l6 6 6-6") }
    val ArrowUp: ImageVector by lazy { stroke("M6 15l6-6 6 6") }
    val Send: ImageVector by lazy { fill("M3 11.5L21 3l-7 18-2.5-7.5z") }
    val Stop: ImageVector by lazy { fill("M7 7h10v10H7z") }
    val PlayArrow: ImageVector by lazy { fill("M8 5l11 7-11 7z") }
    val Mic: ImageVector by lazy {
        stroke("M12 2c2.2 0 4 1.8 4 4v5c0 2.2-1.8 4-4 4s-4-1.8-4-4V6c0-2.2 1.8-4 4-4z",
            "M5 11c0 3.9 3.1 7 7 7s7-3.1 7-7M12 18v3")
    }
    val MicOff: ImageVector by lazy {
        stroke("M12 2c2.2 0 4 1.8 4 4v5c0 2.2-1.8 4-4 4s-4-1.8-4-4V6c0-2.2 1.8-4 4-4z",
            "M5 11c0 3.9 3.1 7 7 7s7-3.1 7-7M12 18v3M4 4l16 16")
    }
    val Headphones: ImageVector by lazy {
        stroke("M4 15v-3c0-4.4 3.6-8 8-8s8 3.6 8 8v3",
            "M4 15h3v6H5a1 1 0 0 1-1-1zM20 15h-3v6h2a1 1 0 0 0 1-1z")
    }
    val Settings: ImageVector by lazy {
        stroke("M12 8.5c1.9 0 3.5 1.6 3.5 3.5s-1.6 3.5-3.5 3.5-3.5-1.6-3.5-3.5S10.1 8.5 12 8.5z",
            "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9L7 7M17 17l2.1 2.1M19.1 4.9L17 7M7 17l-2.1 2.1")
    }
    val MoreVert: ImageVector by lazy { stroke("M10.8 5h2.4M10.8 12h2.4M10.8 19h2.4") }
    val Tune: ImageVector by lazy {
        stroke("M4 7h16M4 17h16M13.8 7h2.4M7.8 17h2.4")
    }
    val Search: ImageVector by lazy {
        stroke("M11 4c3.9 0 7 3.1 7 7s-3.1 7-7 7-7-3.1-7-7 3.1-7 7-7zM16.2 16.2L21 21")
    }
    val Edit: ImageVector by lazy { stroke("M4 20l1-4L16.5 4.5a2.1 2.1 0 0 1 3 3L8 19zM14.5 6.5l3 3") }
    val Delete: ImageVector by lazy { stroke("M5 7h14M10 5h4M8 7l1 13h6l1-13M10 11v6M14 11v6") }
    val DeleteForever: ImageVector get() = Delete
    val Description: ImageVector by lazy { stroke("M7 3h7l5 5v13H7zM14 3v5h5M10 13h6M10 17h6") }
    val Image: ImageVector by lazy {
        stroke("M4 5h16v14H4z", "M4 17l5-4 4 3 3-2 4 3", "M8.3 10h2.4")
    }
    val Dns: ImageVector by lazy {
        stroke("M4 5h16v6H4zM4 13h16v6H4z", "M6.3 8h2.4M6.3 16h2.4")
    }
    val Storage: ImageVector by lazy {
        stroke("M4 6c0-1 3.6-2 8-2s8 1 8 2v12c0 1-3.6 2-8 2s-8-1-8-2zM4 12c0 1 3.6 2 8 2s8-1 8-2")
    }
    val SdCard: ImageVector by lazy { stroke("M7 3h8l5 5v13H7zM8.8 17h2.4M12.8 17h2.4M10 13.5h4") }
    val Lock: ImageVector by lazy {
        stroke("M7 11h10v9H7zM9 11V8a3 3 0 0 1 6 0v3", "M10.8 15h2.4")
    }
    val Key: ImageVector by lazy {
        stroke("M8 4c2.2 0 4 1.8 4 4s-1.8 4-4 4-4-1.8-4-4 1.8-4 4-4zM11 11l9 9M16 16l2 2")
    }
    val Language: ImageVector by lazy {
        stroke("M12 3c5 0 9 4 9 9s-4 9-9 9-9-4-9-9 4-9 9-9z",
            "M12 3c2.8 0 5 4 5 9s-2.2 9-5 9-5-4-5-9 2.2-9 5-9zM3 12h18")
    }
    val Memory: ImageVector by lazy {
        stroke("M9 9h6v6H9zM5 5h14v14H5zM9 1v3M15 1v3M9 20v3M15 20v3M1 9h3M1 15h3M20 9h3M20 15h3")
    }
    val Bolt: ImageVector by lazy { fill("M13 2L4 14h6l-1 8 9-12h-6z") }
    val Speed: ImageVector by lazy {
        stroke("M4.5 19c1-5 4-8.5 7.5-8.5S18.5 14 19.5 19M12 14.5l4-4.5", "M10.8 15.5h2.4")
    }
    val Layers: ImageVector by lazy { stroke("M12 3l9 5-9 5-9-5zM3.5 12.5L12 17l8.5-4.5M3.5 16.5L12 21l8.5-4.5") }
    val AutoAwesome: ImageVector by lazy {
        fill("M12 2l2.2 7.8L22 12l-7.8 2.2L12 22l-2.2-7.8L2 12l7.8-2.2z",
            "M19 2l.9 2.6 2.6.9-2.6.9L19 9l-.9-2.6-2.6-.9 2.6-.9z")
    }
    val Psychology: ImageVector get() = AutoAwesome
    val Info: ImageVector by lazy {
        stroke("M12 3c5 0 9 4 9 9s-4 9-9 9-9-4-9-9 4-9 9-9z", "M12 11v5", "M10.8 7.5h2.4")
    }
    val ErrorOutline: ImageVector by lazy {
        stroke("M12 3.5L22 20H2z", "M12 10v4", "M10.8 17h2.4")
    }
    val CloudDownload: ImageVector by lazy {
        stroke("M7 18a4 4 0 1 1 .6-7.96A5.5 5.5 0 0 1 18.3 12H18a3 3 0 0 1 0 6zM12 12v7M9 16.5l3 3 3-3")
    }
    val Download: ImageVector by lazy { stroke("M12 4v11M7 10.5l5 5 5-5M5 20h14") }
    val ContentCopy: ImageVector by lazy { stroke("M9 9h11v11H9zM5 15V4h11") }
    val ContentPaste: ImageVector by lazy { stroke("M6 6h12v15H6zM9 3h6v4H9zM9 13h6M9 17h6") }
    val Hub: ImageVector by lazy {
        stroke("M12 7v4M12 12h7M12 12H5M12 12v7",
            "M10.8 4.5h2.4M18.8 12h2.4M10.8 19.5h2.4M2.8 12h2.4M10.8 12h2.4")
    }
    val DarkMode: ImageVector by lazy {
        evenOdd("M12 2c5.5 0 10 4.5 10 10s-4.5 10-10 10S2 17.5 2 12 6.5 2 12 2z",
            "M15.5 2c3.9 0 7 3.1 7 7s-3.1 7-7 7-7-3.1-7-7 3.1-7 7-7z")
    }
    val LightMode: ImageVector by lazy {
        stroke("M12 8c2.2 0 4 1.8 4 4s-1.8 4-4 4-4-1.8-4-4 1.8-4 4-4z",
            "M12 1v3M12 20v3M1 12h3M20 12h3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M19.8 4.2l-2.1 2.1M6.3 17.7l-2.1 2.1")
    }
    val Visibility: ImageVector by lazy {
        stroke("M2 12s3.5-6.5 10-6.5S22 12 22 12s-3.5 6.5-10 6.5S2 12 2 12z", "M10.8 12h2.4")
    }
    val VisibilityOff: ImageVector by lazy {
        stroke("M2 12s3.5-6.5 10-6.5c2 0 3.7.6 5.2 1.4M22 12s-3.5 6.5-10 6.5c-2 0-3.7-.6-5.2-1.4M4 4l16 16")
    }
    val Refresh: ImageVector by lazy {
        stroke("M20 12c0 4.4-3.6 8-8 8-3.4 0-6.3-2.1-7.5-5M4 12c0-4.4 3.6-8 8-8 3.4 0 6.3 2.1 7.5 5",
            "M20 3v4.5h-4.5M4 21v-4.5h4.5")
    }
    val PowerSettingsNew: ImageVector by lazy {
        stroke("M12 2v8M12 5.5c4.1 0 7.5 3.4 7.5 7.5s-3.4 7.5-7.5 7.5-7.5-3.4-7.5-7.5c0-2.9 1.7-5.4 4-6.6")
    }
    val RecordVoiceOver: ImageVector by lazy {
        stroke("M5 9c-1.5 2-1.5 4 0 6M19 9c1.5 2 1.5 4 0 6M8 10c-1 1.3-1 2.7 0 4M16 10c1 1.3 1 2.7 0 4",
            "M10.8 12h2.4")
    }
    val Chat: ImageVector by lazy { stroke("M4 5h16v11H9l-5 4zM8 9.5h8M8 12.5h5") }
    val VolumeUp: ImageVector by lazy {
        stroke("M4 9v6h4l5 4V5L8 9z",
            "M16 9c1.2 1.6 1.2 4.4 0 6M18.5 7c2 2.6 2 7.4 0 10")
    }
    val Lan: ImageVector by lazy {
        stroke("M12 12v7M12 10V4M4 4h16",
            "M10.8 12h2.4M10.8 4h2.4M2.8 4h2.4M18.8 4h2.4")
    }
    val Palette: ImageVector by lazy {
        stroke("M12 3c5 0 9 4 9 9 0 2.5-2 4-4.5 4h-2c-1.4 0-2.5 1.1-2.5 2.5V19c-3.9 0-9-2.4-9-7 0-5 4-9 9-9z",
            "M6.8 11h2.4M10.8 8h2.4M14.8 11h2.4")
    }
}
