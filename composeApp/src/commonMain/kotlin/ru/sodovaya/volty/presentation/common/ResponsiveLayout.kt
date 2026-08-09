package ru.sodovaya.volty.presentation.common

/**
 * Layout family selected from the content bounds available to a dashboard.
 *
 * A square viewport keeps the portrait composition: only a genuinely wider
 * content area gets the two-pane arrangement. Insets are applied by the
 * caller before measuring these bounds, so this classifier has no knowledge of
 * system bars or device dimensions.
 */
enum class ResponsiveLayoutMode {
    PORTRAIT,
    WIDE
}

fun responsiveLayoutMode(widthPx: Int, heightPx: Int): ResponsiveLayoutMode =
    if (widthPx > heightPx) ResponsiveLayoutMode.WIDE else ResponsiveLayoutMode.PORTRAIT
