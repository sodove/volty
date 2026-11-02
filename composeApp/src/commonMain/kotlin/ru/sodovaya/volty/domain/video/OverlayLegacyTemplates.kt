package ru.sodovaya.volty.domain.video

/**
 * The four templates as 0.7.63 stored them, kept only to recognise an untouched copy in a saved document: such a copy
 * is the old look by default, not by choice, so [upgraded] swaps it for the new preset of the same id. A preset the
 * rider edited differs from these and stays exactly as it is, drawn by the older auto-flow rules.
 */
internal object OverlayLegacyTemplates {
    private const val AMBER = "#FFFFC857"
    private const val RED = "#FFFF7070"

    private val legacy by lazy { listOf(topRow(), hud(), minimal(), racing()).associateBy { it.id } }

    fun upgraded(preset: OverlayPreset): OverlayPreset {
        val old = legacy[preset.id] ?: return preset
        // 0.7.63 wrote English labels into the presets; a later revision stores them blank.
        val normalized = preset.copy(widgets = preset.widgets.map { if (isAutoOverlayLabel(it)) it.copy(label = "") else it })
        return if (normalized == old) OverlayTemplates.byId(preset.id) ?: preset else preset
    }

    private fun hud(): OverlayPreset = OverlayPreset(
        id = "hud",
        name = "HUD",
        widgets = listOf(
            sourceCard("voltage", VideoMetric.VOLTAGE, OverlayIcon.BATTERY),
            sourceCard("soc", VideoMetric.SOC, OverlayIcon.BATTERY, lowSocRules()),
            sourceCard(
                "battery-temperature",
                VideoMetric.BATTERY_TEMPERATURE,
                OverlayIcon.TEMPERATURE,
                temperatureRules(),
            ),
            sourceCard("controller-power", VideoMetric.CONTROLLER_POWER, OverlayIcon.BOLT),
            sourceCard("controller-current", VideoMetric.CONTROLLER_CURRENT, OverlayIcon.CURRENT),
            sourceCard("duty", VideoMetric.DUTY, OverlayIcon.WARNING, dutyRules()),
            speedArc(id = "speed-arc", y = 84f, radius = 130f, max = 100f),
        ),
    )

    private fun topRow(): OverlayPreset = OverlayPreset(
        id = "top-row",
        name = "Top Row",
        widgets = listOf(
            sourceCard("speed", VideoMetric.SPEED, OverlayIcon.GAUGE),
            sourceCard("voltage", VideoMetric.VOLTAGE, OverlayIcon.BATTERY),
            sourceCard("soc", VideoMetric.SOC, OverlayIcon.BATTERY, lowSocRules()),
            sourceCard(
                "battery-temperature",
                VideoMetric.BATTERY_TEMPERATURE,
                OverlayIcon.TEMPERATURE,
                temperatureRules(),
            ),
            sourceCard("controller-power", VideoMetric.CONTROLLER_POWER, OverlayIcon.BOLT),
            sourceCard("controller-current", VideoMetric.CONTROLLER_CURRENT, OverlayIcon.CURRENT),
        ),
    )

    private fun minimal(): OverlayPreset = OverlayPreset(
        id = "minimal",
        name = "Minimal",
        widgets = listOf(
            sourceCard(
                "speed",
                VideoMetric.SPEED,
                OverlayIcon.GAUGE,
                style = OverlayStyle(fontSize = 48f),
            ),
            sourceCard("soc", VideoMetric.SOC, OverlayIcon.BATTERY, lowSocRules()),
        ),
    )

    private fun racing(): OverlayPreset = OverlayPreset(
        id = "racing",
        name = "Racing",
        centerCards = false,
        widgets = listOf(
            sourceCard("duty", VideoMetric.DUTY, OverlayIcon.WARNING, dutyRules(), OverlayWidgetKind.VALUE_CARD),
            sourceCard("motor-current", VideoMetric.MOTOR_CURRENT, OverlayIcon.CURRENT, kind = OverlayWidgetKind.VALUE_CARD),
            sourceCard(
                "esc-temperature",
                VideoMetric.ESC_TEMPERATURE,
                OverlayIcon.TEMPERATURE,
                kind = OverlayWidgetKind.VALUE_CARD,
                rules = temperatureRules(),
            ),
            sourceCard(
                "motor-temperature",
                VideoMetric.MOTOR_TEMPERATURE,
                OverlayIcon.TEMPERATURE,
                kind = OverlayWidgetKind.VALUE_CARD,
                rules = temperatureRules(),
            ),
            sourceCard("controller-power", VideoMetric.CONTROLLER_POWER, OverlayIcon.BOLT, kind = OverlayWidgetKind.VALUE_CARD),
            sourceCard("soc", VideoMetric.SOC, OverlayIcon.BATTERY, lowSocRules(), OverlayWidgetKind.VALUE_CARD),
            speedArc(id = "speed-arc", y = 80f, radius = 130f, max = 100f),
        ),
    )

    private fun sourceCard(
        id: String,
        metric: VideoMetric,
        icon: OverlayIcon,
        rules: List<OverlayRule> = emptyList(),
        kind: OverlayWidgetKind = OverlayWidgetKind.ICON_VALUE,
        style: OverlayStyle = OverlayStyle(),
    ) = OverlayWidget(
        id = id,
        metric = metric,
        kind = kind,
        icon = icon,
        label = "",
        style = style,
        rules = rules,
    )

    private fun speedArc(id: String, y: Float, radius: Float, max: Float) = OverlayWidget(
        id = id,
        metric = VideoMetric.SPEED,
        kind = OverlayWidgetKind.ARC,
        icon = OverlayIcon.GAUGE,
        label = "",
        style = OverlayStyle(
            background = "#00000000",
            foreground = AMBER,
            fontSize = 48f,
            cornerRadius = 0f,
            padding = 0f,
            shadow = false,
        ),
        arc = OverlayArc(x = 50f, y = y, radius = radius, min = 0f, max = max, thickness = 10f),
    )

    private fun lowSocRules() = listOf(
        OverlayRule(OverlayComparison.LT, threshold = 30.0, foreground = AMBER),
        OverlayRule(OverlayComparison.LT, threshold = 15.0, foreground = RED),
    )

    private fun temperatureRules() = listOf(
        OverlayRule(OverlayComparison.GTE, threshold = 60.0, foreground = AMBER),
        OverlayRule(OverlayComparison.GTE, threshold = 80.0, foreground = RED),
    )

    private fun dutyRules() = listOf(
        OverlayRule(OverlayComparison.GTE, threshold = 70.0, foreground = AMBER),
        OverlayRule(OverlayComparison.GTE, threshold = 85.0, foreground = RED),
    )
}
