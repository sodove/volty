package ru.sodovaya.volty.domain.video

import ru.sodovaya.volty.util.UnitSystem
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Saved overlays must draw exactly as they did. **Why this exists:** 307706b0 redrew [OverlayWidgetKind.ICON_VALUE],
 * the model's default kind and the kind of every 0.7.63 preset, as a label-less chip, so every rider's saved overlay
 * changed on update (review I1). The golden below was captured by running this very test at eec044f6, the commit
 * before; the icon op's identity is left out because its type changed (enum to Font Awesome name) and is checked
 * separately, its geometry and colour are in the golden.
 */
class OverlayLegacyGoldenTest {
    private val snapshot = VideoSnapshot(
        1_000, "v",
        mapOf(
            VideoMetric.SPEED to VideoReading(42.0),
            VideoMetric.VOLTAGE to VideoReading(96.4),
            VideoMetric.SOC to VideoReading(78.0),
            VideoMetric.DUTY to VideoReading(61.0),
            VideoMetric.CONTROLLER_POWER to VideoReading(1762.0),
        ),
    )

    /** A rider's saved preset in 0.7.63's shape: flowed ICON_VALUE cards (one with no "kind" at all) and a placed one. */
    private val saved = OverlayCodec.decode(
        """{"version":1,"selected":{"id":"mine","name":"Mine","widgets":[
        {"id":"v","metric":"VOLTAGE","kind":"ICON_VALUE","icon":"BATTERY","label":"","unit":"V"},
        {"id":"s","metric":"SOC","icon":"BATTERY","label":"Мой заряд","unit":"%"},
        {"id":"p","metric":"CONTROLLER_POWER","kind":"ICON_VALUE","icon":"BOLT","label":"","unit":"W"},
        {"id":"d","metric":"DUTY","kind":"ICON_VALUE","icon":"WARNING","label":"","unit":"%",
         "placement":{"portrait":{"anchor":"BOTTOM_END","dx":54,"dy":120,"width":313,"height":166},
                      "landscape":{"anchor":"BOTTOM_END","dx":54,"dy":120,"width":313,"height":166}}}
        ]}}""",
    ).selected

    private fun f(v: Float) = (v * 100).roundToLong().toString()

    private fun dump(width: Int, height: Int): String {
        val scene = buildOverlayScene(OverlaySceneInput(saved, snapshot, VideoOverlayStrings(), UnitSystem.METRIC, width, height))
        return scene.ops.joinToString("\n") { op ->
            when (op) {
                is OverlayTextOp -> "T ${f(op.x)} ${f(op.baseline)} ${op.align} ${f(op.haloWidth)} ${f(op.trackingEm)} " +
                    op.runs.joinToString("|") { "${it.text}/${f(it.size)}/${it.color}/${it.bold}/${f(it.gapBefore)}" }
                is OverlayIconOp -> "I ${f(op.x)} ${f(op.y)} ${f(op.size)} ${op.color}"
                is OverlayRectOp -> "R ${f(op.left)} ${f(op.top)} ${f(op.right)} ${f(op.bottom)} ${f(op.radius)} ${op.fill} ${op.stroke} ${f(op.strokeWidth)}"
                else -> op.toString()
            }
        } + "\nW " + scene.widgets.joinToString(" ") { "${it.id}:${f(it.left)},${f(it.top)},${f(it.right)},${f(it.bottom)}" }
    }

    @Test
    fun aSavedIconValuePresetDrawsExactlyAsBefore() {
        val cases = listOf(Triple(1920, 1080, LANDSCAPE), Triple(1080, 1920, PORTRAIT))
        for ((w, h, golden) in cases) { val actual = dump(w, h); if (actual != golden) println("GOLDEN ${w}x$h\n$actual\nEND") }
        for ((w, h, golden) in cases) assertEquals(golden, dump(w, h), "${w}x$h")
    }

    @Test
    fun aSavedIconValueCardKeepsItsWordAndTheMetricsIcon() {
        val scene = buildOverlayScene(OverlaySceneInput(saved, snapshot, VideoOverlayStrings(), UnitSystem.METRIC, 1920, 1080))
        val words = scene.ops.filterIsInstance<OverlayTextOp>().flatMap { it.runs }.map { it.text }
        assertEquals(true, "Мой заряд" in words, "a typed label is still burned in")
        assertEquals(4, scene.ops.filterIsInstance<OverlayIconOp>().size)
        assertEquals(listOf("battery-full", "battery-full", "bolt", "gauge-simple"), scene.ops.filterIsInstance<OverlayIconOp>().map { it.icon })
    }

    private companion object {
        val LANDSCAPE = listOf(
            "OverlayScrimOp(top=0.0, bottom=322.0, darkAtTop=true)",
            "R 46826 5400 78498 20200 1000 -1728053248 619377912 200",
            "T 49226 9792 START 0 8 VOLTAGE/2400/-1/true/0",
            "I 49226 12592 5120 -1",
            "T 55146 17200 START 0 0 96.4/6400/-1/true/0|V/3008/-1/false/800",
            "R 80098 5400 106035 20200 1000 -1728053248 619377912 200",
            "T 82498 9792 START 0 8 Мой заряд/2400/-1/true/0",
            "I 82498 12592 5120 -1",
            "T 88418 17200 START 0 0 78/6400/-1/true/0|%/3008/-1/false/800",
            "R 107635 5400 145174 20200 1000 -1728053248 619377912 200",
            "T 110035 9792 START 0 8 ESC POWER/2400/-1/true/0",
            "I 110035 12592 5120 -1",
            "T 115955 17200 START 0 0 1762/6400/-1/true/0|W/3008/-1/false/800",
            "R 155300 79400 186600 96000 1000 -1728053248 619377912 200",
            "T 157700 83792 START 0 8 DUTY/2400/-1/true/0",
            "I 157700 88392 5120 -1",
            "T 163620 93000 START 0 0 61/6400/-1/true/0|%/3000/-1/false/800",
            "W v:46826,5400,78498,20200 s:80098,5400,106035,20200 p:107635,5400,145174,20200 d:155300,79400,186600,96000",
        ).joinToString("\n")
        val PORTRAIT = listOf(
            "OverlayScrimOp(top=0.0, bottom=486.0, darkAtTop=true)",
            "R 24395 5400 56068 20200 1000 -1728053248 619377912 200",
            "T 26795 9792 START 0 8 VOLTAGE/2400/-1/true/0",
            "I 26795 12592 5120 -1",
            "T 32715 17200 START 0 0 96.4/6400/-1/true/0|V/3008/-1/false/800",
            "R 57668 5400 83605 20200 1000 -1728053248 619377912 200",
            "T 60068 9792 START 0 8 Мой заряд/2400/-1/true/0",
            "I 60068 12592 5120 -1",
            "T 65988 17200 START 0 0 78/6400/-1/true/0|%/3008/-1/false/800",
            "R 35230 21800 72770 36600 1000 -1728053248 619377912 200",
            "T 37630 26192 START 0 8 ESC POWER/2400/-1/true/0",
            "I 37630 28992 5120 -1",
            "T 43550 33600 START 0 0 1762/6400/-1/true/0|W/3008/-1/false/800",
            "R 71300 163400 102600 180000 1000 -1728053248 619377912 200",
            "T 73700 167792 START 0 8 DUTY/2400/-1/true/0",
            "I 73700 172392 5120 -1",
            "T 79620 177000 START 0 0 61/6400/-1/true/0|%/3000/-1/false/800",
            "W v:24395,5400,56068,20200 s:57668,5400,83605,20200 p:35230,21800,72770,36600 d:71300,163400,102600,180000",
        ).joinToString("\n")
    }
}
