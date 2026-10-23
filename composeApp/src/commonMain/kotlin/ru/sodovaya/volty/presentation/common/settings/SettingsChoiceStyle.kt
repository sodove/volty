package ru.sodovaya.volty.presentation.common.settings

/** How a [SettingsChoiceRow] shows its options. */
enum class ChoiceStyle { SEGMENTED, DROPDOWN }

/**
 * Segmented only while every label fits on one line; otherwise a dropdown, which never truncates.
 *
 * The budget comes from the width a segment actually has. On a 360 dp phone a row inside a
 * settings card is about 296 dp wide, a segment loses roughly 24 dp to padding and the selected
 * one another 18 dp to its check mark, and Russian text at 14 sp runs about 7.5 dp a character:
 * with three options that leaves 10 characters, with two 14. More than three options never fit,
 * whatever their length. An earlier version rendered every set of up to four options as segments
 * with `maxLines = 1`, and the rider saw «Приглушать музыку» cut off.
 */
fun choiceStyleFor(labels: List<String>): ChoiceStyle {
    val limit = when (labels.size) {
        1, 2 -> MAX_LABEL_WITH_TWO
        3 -> MAX_LABEL_WITH_THREE
        else -> return ChoiceStyle.DROPDOWN
    }
    return if (labels.all { it.length <= limit }) ChoiceStyle.SEGMENTED else ChoiceStyle.DROPDOWN
}

private const val MAX_LABEL_WITH_TWO = 14
private const val MAX_LABEL_WITH_THREE = 10
