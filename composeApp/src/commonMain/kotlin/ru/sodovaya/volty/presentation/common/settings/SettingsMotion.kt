package ru.sodovaya.volty.presentation.common.settings

/** Which way a settings page slides: forward enters from the end, back leaves towards it. */
enum class PageDirection { FORWARD, BACK }

/** Going to the hub is always "back"; opening any page (even from another page) is "forward". */
fun pageDirection(targetIsHub: Boolean): PageDirection =
    if (targetIsHub) PageDirection.BACK else PageDirection.FORWARD

/** At most one inline section is open: tapping the open one closes it, any other replaces it. */
fun <T> toggleExpanded(current: T?, tapped: T): T? = if (current == tapped) null else tapped
