package ru.sodovaya.volty.presentation.common

/** Maps a Vehicle.iconKey (preset string) to a single-glyph emoji for avatars. */
fun iconKeyToEmoji(key: String?): String = when (key) {
    "skateboard" -> "🛹"
    "ebike" -> "🚲"
    "scooter" -> "🛵"
    "unicycle", "wheel" -> "🛞"
    "moto" -> "🏍"
    "solar" -> "☀"
    "ev" -> "🚗"
    "boat" -> "⛵"
    "rv" -> "🚐"
    else -> "⚡"
}
