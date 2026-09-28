package com.vivekkaushik.revv.ui.hmi

/** SVG path data from the design, on a 24×24 viewport unless noted. */
object HmiIcons {
    const val AUTO = "M2 7a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v9H2zM15 9h5a2 2 0 0 1 2 2v10h-7zM6 20h6"
    const val VEHICLE = "M3.5 17a9 9 0 1 1 17 0M12 15l4.5-5M13.6 16a1.6 1.6 0 1 1-3.2 0 1.6 1.6 0 0 1 3.2 0"
    const val MAPS = "M3 11 21 3l-8 18-2-8z"
    const val CAMERA =
        "M3 8a2 2 0 0 1 2-2h2l2-2h6l2 2h2a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM16 13a4 4 0 1 1-8 0 4 4 0 0 1 8 0"
    const val SETTINGS = "M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0M19 12a7 7 0 1 1-14 0 7 7 0 0 1 14 0M12 2v3M12 19v3M2 12h3M19 12h3"
    const val PHONE = "M5 3h3.5l2 5-2.5 1.6a11 11 0 0 0 6.4 6.4L16 13.5l5 2V19a2 2 0 0 1-2 2A17 17 0 0 1 3 5a2 2 0 0 1 2-2z"
    const val APPS = "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z"
    const val HOME = "M3 11l9-8 9 8M5 10v10h14V10M10 20v-6h4v6"
    const val RADIO =
        "M3 9a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM7 7l10-4M11 15a3 3 0 1 1-6 0 3 3 0 0 1 6 0M14 12h4M14 16h4"
    const val POWER = "M12 3v8M6.3 6.3a8 8 0 1 0 11.4 0"
    const val BACK = "M15 6l-6 6 6 6"
    const val BLUETOOTH = "M7 7l10 10-5 5V2l5 5L7 17"
    const val WIFI = "M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01"
    const val BACKSPACE = "M21 5H9l-6 7 6 7h12zM12 9l6 6M18 9l-6 6"
    const val DISPLAY = "M2 5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2zM8 21h8M12 18v3"
    const val SOUND = "M11 5 6 9H2v6h4l5 4zM15.5 8.5a5 5 0 0 1 0 7M19 5a9 9 0 0 1 0 14"

    // Filled media glyphs.
    const val PLAY = "M8 5v14l11-7z"
    const val PAUSE = "M6 5h4v14H6zM14 5h4v14h-4z"
    const val PREVIOUS = "M19 5v14l-10-7zM5 5h2.4v14H5z"
    const val NEXT = "M5 5v14l10-7zM16.6 5h2.4v14h-2.4z"

    const val SEARCH = "M10.5 17a6.5 6.5 0 1 1 0-13 6.5 6.5 0 0 1 0 13zM15.5 15.5 21 21"

    // Guidance arrows on a 64×64 viewport, stroked at 5. Left-hand versions are drawn mirrored.
    const val TURN_RIGHT = "M20 58V22h30M40 11l11 11-11 11"
    const val STRAIGHT = "M32 58V9M21 20l11-11 11 11"
    const val SLIGHT_RIGHT = "M24 58V34L46 12M31 12h15v15"
    const val SHARP_RIGHT = "M20 58V14L46 40M31 40h15V25"
    const val UTURN_RIGHT = "M22 58V26a12 12 0 0 1 24 0v22M36 38l10 10 10-10"
    const val KEEP_RIGHT = "M32 58V40L18 26V10M32 40l14-14V9M35 20l11-11 11 11"
    const val MERGE = "M32 58V9M21 20l11-11 11 11M12 58c0-16 20-16 20-32"
    const val ROUNDABOUT = "M32 58V44M42 34a10 10 0 1 1-20 0 10 10 0 0 1 20 0M39 27 50 16M40 16h10v10"
    const val ARRIVE = "M20 58V8M20 10h26l-7 9 7 9H20"
}
