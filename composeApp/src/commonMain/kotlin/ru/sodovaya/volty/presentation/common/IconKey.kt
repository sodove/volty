package ru.sodovaya.volty.presentation.common

/** Maps a Vehicle.iconKey (preset string) to a single-glyph emoji for avatars. */
fun iconKeyToEmoji(key: String?): String = when (key) {
    "skateboard" -> "🛹"
    "ebike" -> "🚲"
    "scooter" -> "🛵"
    "moto" -> "🏍"
    "solar" -> "☀"
    "ev" -> "🚗"
    "boat" -> "⛵"
    "rv" -> "🚐"
    else -> "⚡"
}
