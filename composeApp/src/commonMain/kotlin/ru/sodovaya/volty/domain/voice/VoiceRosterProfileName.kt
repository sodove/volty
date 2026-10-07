package ru.sodovaya.volty.domain.voice

/** Optional profile metadata cannot earn a roster row or transport evidence. */
fun voiceRosterProfileName(value: String?): String? = value?.takeIf {
    it.length in 2..80 && it.isNotBlank() && it.none(Char::isISOControl)
}
