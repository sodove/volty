package ru.sodovaya.volty.domain.video

/**
 * The built-in overlays. C1 «Минимум», C2 «HUD» (the default) and C3 «Гонка» are the approved mock
 * (`docs/superpowers/mockups/2026-11-02-ride-video.html`); every coordinate below is that mock's, on its
 * 1080×1920 and 1920×1080 canvases, and [place] turns it into an anchor and an offset. The rest are telemetry-studio's
 * layouts rebuilt on this model: its auto-flowing rows, card style and whole-card rule swap, but none of its `?? 0`,
 * absolute pixels or dead font-weight control. Studio's speed rules (`value >= 40` turns the card yellow) were left
 * out on purpose: a fixed speed means nothing across a bicycle and a wheel, and a warning colour must mean a warning.
 *
 * Labels are blank ("follow the metric") so that nothing English can be burned in.
 */
object OverlayTemplates {
    private val TEXT = OverlayPalette.hex(OverlayPalette.TEXT)
    private val CYAN = OverlayPalette.hex(OverlayPalette.CYAN)
    private val GREEN = OverlayPalette.hex(OverlayPalette.GREEN)
    private val BATTERY = OverlayPalette.hex(OverlayPalette.BATTERY)
    private val ORANGE = OverlayPalette.hex(OverlayPalette.ORANGE)
    private val PINK = OverlayPalette.hex(OverlayPalette.PINK)
    private val CARD = OverlayPalette.hex(OverlayPalette.CARD_FILL)

    private const val PORTRAIT_W = 1080f
    private const val PORTRAIT_H = 1920f
    private const val LANDSCAPE_W = 1920f
    private const val LANDSCAPE_H = 1080f

    fun all(): List<OverlayPreset> = listOf(minimal(), hud(), racing(), topRow(), panel(), large())

    fun byId(id: String): OverlayPreset? = all().firstOrNull { it.id == id }

    fun minimal(): OverlayPreset = OverlayPreset(
        id = "minimal",
        name = "Minimal",
        layout = OverlayPresetLayout(
            portrait = OverlayFrameSpec(scrimBottom = 430f),
            landscape = OverlayFrameSpec(scrimBottom = 430f),
        ),
        widgets = listOf(
            OverlayWidget(
                id = "speed",
                metric = VideoMetric.SPEED,
                kind = OverlayWidgetKind.TEXT,
                style = style(CYAN),
                placement = OverlayPlacement(
                    portrait = portrait(OverlayAnchor.BOTTOM_START, 62f, 1634f, 530f, 190f, value = 240f, unit = 48f, label = 0f),
                    landscape = landscape(OverlayAnchor.BOTTOM_START, 62f, 794f, 560f, 190f, value = 240f, unit = 48f, label = 0f),
                ),
            ),
            OverlayWidget(
                id = "duty",
                metric = VideoMetric.DUTY,
                kind = OverlayWidgetKind.BAR,
                style = style(GREEN),
                rules = dutyRules(),
                arc = OverlayArc(min = 0f, max = 100f),
                bar = OverlayBar(height = 30f),
                placement = OverlayPlacement(
                    portrait = portrait(OverlayAnchor.BOTTOM_END, 610f, 1654f, 408f, 170f, value = 110f, unit = 46f, label = 28f),
                    landscape = landscape(OverlayAnchor.BOTTOM_START, 640f, 814f, 440f, 170f, value = 110f, unit = 46f, label = 28f),
                ),
            ),
        ),
    )

    fun hud(): OverlayPreset = OverlayPreset(
        id = "hud",
        name = "HUD",
        layout = OverlayPresetLayout(
            portrait = OverlayFrameSpec(scrimTop = 620f, scrimBottom = 620f, chipY = 586f),
            landscape = OverlayFrameSpec(scrimBottom = 600f),
        ),
        widgets = listOf(
            quarterDial(
                "speed", VideoMetric.SPEED, OverlayWidgetKind.ARC_LEFT, CYAN, emptyList(),
                portrait(OverlayAnchor.TOP_START, 60f, 84f, 452f, 452f, value = 150f, unit = 32f, label = 26f),
                landscape(OverlayAnchor.BOTTOM_START, 66f, 574f, 452f, 452f, value = 150f, unit = 32f, label = 26f),
            ),
            quarterDial(
                "duty", VideoMetric.DUTY, OverlayWidgetKind.ARC_RIGHT, GREEN, dutyRules(),
                portrait(OverlayAnchor.TOP_END, 568f, 84f, 452f, 452f, value = 150f, unit = 32f, label = 26f),
                landscape(OverlayAnchor.BOTTOM_END, 1402f, 574f, 452f, 452f, value = 150f, unit = 32f, label = 26f),
            ),
            card(
                "battery-current", VideoMetric.BATTERY_CURRENT, BATTERY, emptyList(),
                portrait(OverlayAnchor.BOTTOM_START, 54f, 1484f, 313f, 176f),
                landscape(OverlayAnchor.BOTTOM_CENTER, 574f, 694f, 250f, 166f),
            ),
            card(
                "esc-temperature", VideoMetric.ESC_TEMPERATURE, ORANGE, temperatureRules(),
                portrait(OverlayAnchor.BOTTOM_CENTER, 383f, 1484f, 313f, 176f),
                landscape(OverlayAnchor.BOTTOM_CENTER, 838f, 694f, 250f, 166f),
            ),
            card(
                "motor-temperature", VideoMetric.MOTOR_TEMPERATURE, PINK, temperatureRules(),
                portrait(OverlayAnchor.BOTTOM_END, 712f, 1484f, 314f, 176f),
                landscape(OverlayAnchor.BOTTOM_CENTER, 1102f, 694f, 250f, 166f),
            ),
            batteryBar(
                portrait(OverlayAnchor.BOTTOM_CENTER, 62f, 1696f, 956f, 92f, value = 60f, unit = 32f, label = 0f),
                landscape(OverlayAnchor.BOTTOM_CENTER, 574f, 884f, 778f, 92f, value = 60f, unit = 32f, label = 0f),
            ),
        ),
    )

    fun racing(): OverlayPreset = OverlayPreset(
        id = "racing",
        name = "Racing",
        layout = OverlayPresetLayout(
            portrait = OverlayFrameSpec(scrimTop = 360f, scrimBottom = 1000f),
            landscape = OverlayFrameSpec(scrimTop = 340f, scrimBottom = 560f, chipY = 200f),
        ),
        widgets = listOf(
            tripText(
                "time", VideoMetric.ELAPSED,
                portrait(OverlayAnchor.TOP_START, 62f, 68f, 300f, 106f, value = 64f, unit = 30f, label = 24f),
                landscape(OverlayAnchor.TOP_START, 62f, 62f, 240f, 106f, value = 64f, unit = 30f, label = 24f),
            ),
            tripText(
                "distance", VideoMetric.DISTANCE,
                portrait(OverlayAnchor.TOP_START, 62f, 218f, 300f, 106f, value = 64f, unit = 30f, label = 24f),
                landscape(OverlayAnchor.TOP_START, 320f, 62f, 240f, 106f, value = 64f, unit = 30f, label = 24f),
            ),
            OverlayWidget(
                id = "speed",
                metric = VideoMetric.SPEED,
                kind = OverlayWidgetKind.ARC,
                style = style(CYAN),
                arc = OverlayArc(thickness = 30f, ticks = true, autoScale = true),
                placement = OverlayPlacement(
                    portrait = portrait(OverlayAnchor.BOTTOM_START, 56f, 902f, 592f, 592f, value = 184f, unit = 32f, label = 26f),
                    landscape = landscape(OverlayAnchor.BOTTOM_START, 60f, 396f, 620f, 620f, value = 184f, unit = 32f, label = 26f),
                ),
            ),
            OverlayWidget(
                id = "duty",
                metric = VideoMetric.DUTY,
                kind = OverlayWidgetKind.ARC,
                style = style(GREEN),
                rules = dutyRules(),
                arc = OverlayArc(min = 0f, max = 100f, thickness = 24f),
                placement = OverlayPlacement(
                    // The mock's r=198 dial touched the speed dial; 186 at 838 keeps a clear gap.
                    portrait = portrait(OverlayAnchor.BOTTOM_END, 652f, 1054f, 372f, 372f, value = 100f, unit = 32f, label = 26f),
                    landscape = landscape(OverlayAnchor.BOTTOM_END, 1416f, 576f, 440f, 440f, value = 110f, unit = 32f, label = 26f),
                ),
            ),
            card(
                "battery-current", VideoMetric.BATTERY_CURRENT, BATTERY, emptyList(),
                portrait(OverlayAnchor.BOTTOM_START, 54f, 1496f, 313f, 166f),
                landscape(OverlayAnchor.BOTTOM_CENTER, 730f, 694f, 226f, 166f),
            ),
            card(
                "esc-temperature", VideoMetric.ESC_TEMPERATURE, ORANGE, temperatureRules(),
                portrait(OverlayAnchor.BOTTOM_CENTER, 383f, 1496f, 313f, 166f),
                landscape(OverlayAnchor.BOTTOM_CENTER, 970f, 694f, 226f, 166f),
            ),
            card(
                "motor-temperature", VideoMetric.MOTOR_TEMPERATURE, PINK, temperatureRules(),
                portrait(OverlayAnchor.BOTTOM_END, 712f, 1496f, 314f, 166f),
                null,
            ),
            batteryBar(
                portrait(OverlayAnchor.BOTTOM_CENTER, 62f, 1696f, 956f, 92f, value = 60f, unit = 32f, label = 0f),
                landscape(OverlayAnchor.BOTTOM_CENTER, 730f, 884f, 466f, 92f, value = 60f, unit = 32f, label = 0f),
            ),
        ),
    )

    /** Studio's `top_row`: six cards flowing from the left edge. */
    private fun topRow(): OverlayPreset = OverlayPreset(
        id = "top-row",
        name = "Top Row",
        centerCards = false,
        widgets = listOf(
            studioCard("speed", VideoMetric.SPEED),
            studioCard("voltage", VideoMetric.VOLTAGE),
            studioCard("soc", VideoMetric.SOC, studioBatteryRules()),
            studioCard("battery-temperature", VideoMetric.BATTERY_TEMPERATURE, studioTemperatureRules()),
            studioCard("controller-power", VideoMetric.CONTROLLER_POWER),
            studioCard("controller-current", VideoMetric.CONTROLLER_CURRENT),
        ),
    )

    /** Studio's `hud` and `racing` (which it defines identically): the same cards and a free speed arc near the bottom. */
    private fun panel(): OverlayPreset = OverlayPreset(
        id = "studio-panel",
        name = "Panel",
        widgets = topRow().widgets.filter { it.metric != VideoMetric.SPEED } + OverlayWidget(
            id = "speed-arc",
            metric = VideoMetric.SPEED,
            kind = OverlayWidgetKind.ARC,
            style = OverlayStyle(background = "#00000000", foreground = TEXT, fontSize = 48f, cornerRadius = 0f, padding = 0f, accent = CYAN),
            arc = OverlayArc(x = 50f, y = 78f, radius = 130f, thickness = 10f, autoScale = true),
        ),
    )

    /** Studio's `minimal`: two large cards at 72 px, here 1.5 times a regular card. */
    private fun large(): OverlayPreset = OverlayPreset(
        id = "studio-large",
        name = "Large",
        centerCards = false,
        widgets = listOf(
            studioCard("speed", VideoMetric.SPEED, fontSize = 42f),
            studioCard("soc", VideoMetric.SOC, studioBatteryRules(), fontSize = 42f),
        ),
    )

    /** The rules a metric comes with: the thresholds of the built-in presets, or none. */
    fun defaultRules(metric: VideoMetric): List<OverlayRule> = when (metric) {
        VideoMetric.DUTY -> dutyRules()
        VideoMetric.SOC -> socRules()
        VideoMetric.ESC_TEMPERATURE, VideoMetric.MOTOR_TEMPERATURE, VideoMetric.BATTERY_TEMPERATURE -> temperatureRules()
        else -> emptyList()
    }

    fun cardStyle(accent: String): OverlayStyle = style(accent)

    private fun style(accent: String) = OverlayStyle(
        background = CARD,
        foreground = TEXT,
        cornerRadius = 20f,
        padding = 24f,
        accent = accent,
    )

    private fun quarterDial(
        id: String,
        metric: VideoMetric,
        kind: OverlayWidgetKind,
        accent: String,
        rules: List<OverlayRule>,
        portrait: OverlayBox,
        landscape: OverlayBox,
    ) = OverlayWidget(
        id = id,
        metric = metric,
        kind = kind,
        style = style(accent),
        rules = rules,
        arc = OverlayArc(min = 0f, max = 100f, thickness = 20f, autoScale = metric == VideoMetric.SPEED),
        placement = OverlayPlacement(portrait, landscape),
    )

    private fun card(
        id: String,
        metric: VideoMetric,
        accent: String,
        rules: List<OverlayRule>,
        portrait: OverlayBox,
        landscape: OverlayBox?,
    ) = OverlayWidget(
        id = id,
        metric = metric,
        kind = OverlayWidgetKind.VALUE_CARD,
        style = style(accent),
        rules = rules,
        placement = OverlayPlacement(portrait, landscape),
    )

    private fun batteryBar(portrait: OverlayBox, landscape: OverlayBox) = OverlayWidget(
        id = "soc",
        metric = VideoMetric.SOC,
        kind = OverlayWidgetKind.BAR,
        style = style(BATTERY),
        rules = socRules(),
        arc = OverlayArc(min = 0f, max = 100f),
        bar = OverlayBar(height = 20f, segments = 10, valueBelow = true, secondary = VideoMetric.VOLTAGE),
        placement = OverlayPlacement(portrait, landscape),
    )

    private fun tripText(id: String, metric: VideoMetric, portrait: OverlayBox, landscape: OverlayBox) = OverlayWidget(
        id = id,
        metric = metric,
        kind = OverlayWidgetKind.TEXT,
        style = style(TEXT),
        placement = OverlayPlacement(portrait, landscape),
    )

    private fun studioCard(
        id: String,
        metric: VideoMetric,
        rules: List<OverlayRule> = emptyList(),
        fontSize: Float = 28f,
    ) = OverlayWidget(
        id = id,
        metric = metric,
        kind = OverlayWidgetKind.VALUE_CARD,
        style = OverlayStyle(background = "#B3000000", foreground = "#FFFFFFFF", fontSize = fontSize, cornerRadius = 16f, padding = 20f),
        rules = rules,
    )

    private fun portrait(
        anchor: OverlayAnchor, x: Float, y: Float, w: Float, h: Float,
        value: Float = 64f, unit: Float = 30f, label: Float = 24f,
    ) = place(PORTRAIT_W, PORTRAIT_H, anchor, x, y, w, h, value, unit, label)

    private fun landscape(
        anchor: OverlayAnchor, x: Float, y: Float, w: Float, h: Float,
        value: Float = 64f, unit: Float = 30f, label: Float = 24f,
    ) = place(LANDSCAPE_W, LANDSCAPE_H, anchor, x, y, w, h, value, unit, label)

    /** Turns a rectangle on a design canvas into an anchor and the inward offset [OverlayBox] stores. */
    private fun place(
        canvasW: Float, canvasH: Float, anchor: OverlayAnchor,
        x: Float, y: Float, w: Float, h: Float,
        value: Float, unit: Float, label: Float,
    ) = OverlayBox(
        anchor = anchor,
        dx = offset(anchor.horizontal, canvasW, x, w),
        dy = offset(anchor.vertical, canvasH, y, h),
        width = w,
        height = h,
        valueSize = value,
        unitSize = unit,
        labelSize = label,
    )

    private fun offset(side: Int, canvas: Float, position: Float, length: Float): Float = when {
        side < 0 -> position
        side > 0 -> canvas - position - length
        else -> position + length / 2 - canvas / 2
    }

    private fun socRules() = listOf(
        OverlayRule(OverlayComparison.LT, threshold = 30.0, foreground = ORANGE),
        OverlayRule(OverlayComparison.LT, threshold = 15.0, foreground = PINK),
    )

    private fun temperatureRules() = listOf(
        OverlayRule(OverlayComparison.GTE, threshold = 60.0, foreground = ORANGE),
        OverlayRule(OverlayComparison.GTE, threshold = 80.0, foreground = PINK),
    )

    private fun dutyRules() = listOf(
        OverlayRule(OverlayComparison.GTE, threshold = 70.0, foreground = ORANGE),
        OverlayRule(OverlayComparison.GTE, threshold = 85.0, foreground = PINK),
    )

    private fun studioBatteryRules() = listOf(
        OverlayRule(OverlayComparison.LT, threshold = 30.0, foreground = STUDIO_ON_ALARM, background = "#CCFFA500"),
        OverlayRule(OverlayComparison.LT, threshold = 15.0, foreground = STUDIO_ON_ALARM, background = "#CCFF0000"),
    )

    private fun studioTemperatureRules() = listOf(
        OverlayRule(OverlayComparison.GTE, threshold = 60.0, foreground = STUDIO_ON_ALARM, background = "#CCFFA500"),
        OverlayRule(OverlayComparison.GTE, threshold = 80.0, foreground = STUDIO_ON_ALARM, background = "#CCFF0000"),
    )

    private const val STUDIO_ON_ALARM = "#FF000000"
}
