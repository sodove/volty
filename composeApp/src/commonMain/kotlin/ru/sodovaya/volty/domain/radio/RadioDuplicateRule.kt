package ru.sodovaya.volty.domain.radio

/**
 * Two phones may raise the same community's radio at once (each saw nothing when it probed). The
 * rule that makes exactly one of them give way must be deterministic on both sides, and a scan
 * result shows an access point's BSSID but not its member count, so the BSSID alone decides.
 */
object RadioDuplicateRule {
    /**
     * True when this hub should dissolve its group and join [otherBssid]'s: the other BSSID is
     * lexicographically smaller (case-insensitive). Equal BSSIDs never yield, so two sides can
     * never both give way and the rule is antisymmetric.
     *
     * [ownMembers] and [otherMembersUnknown] are accepted so a later revision can prefer the
     * fuller group once member counts are visible; today they do not influence the result.
     */
    fun shouldYield(
        ownMembers: Int,
        ownBssid: String,
        otherBssid: String,
        otherMembersUnknown: Boolean = true,
    ): Boolean = otherBssid.lowercase() < ownBssid.lowercase()
}
