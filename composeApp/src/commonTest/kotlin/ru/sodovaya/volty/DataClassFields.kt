package ru.sodovaya.volty

/**
 * Splits a data class' `toString()` into its top-level `property=value` pairs.
 *
 * **Stands in for reflection, which common code does not have.** `toString()` is
 * generated from the primary constructor, so it enumerates exactly the
 * properties a `copy()` can carry — *including ones added tomorrow*. That last
 * part is the whole point: it turns "did the author remember this new field?"
 * from something a reviewer has to notice into something the build fails on.
 *
 * Depth tracking is what makes it usable: nested `Pack(...)` renderings and list
 * brackets are full of commas and `=` signs that are not field separators.
 *
 * ## Where this is used, and why it lives here
 *
 * Two guards, on two different classes, both of the form *"this fixture leaves
 * no field at its default"*:
 *
 *  - `VehicleEditComponentTest.the identity fixture leaves no Vehicle field at
 *    its default` — Task 1's original, protecting `saving with nothing edited is
 *    an identity on the whole vehicle`;
 *  - `KableBmsRepositoryBegodeFunnelTest.the fold fixture leaves no
 *    ControllerData field at its default` — G2 Task 6's, protecting `the
 *    single-controller fold carries every field`.
 *
 * Both host tests compare whole objects, and whole-object equality is **blind to
 * a field left at its default on both sides**: the field is simply absent from
 * the comparison, and nothing fails. `batteryLevelFraction` sat in exactly that
 * blind spot for two parts, with a comment calling it a KNOWN HOLE, until a
 * human happened to edit the fixture. This is the mechanical version of that
 * human.
 *
 * Shared rather than copied because a second copy is a second thing to fix, and
 * because the two callers found different vacuity traps in it — see each one's
 * splitter self-checks, which every new caller must repeat.
 */

/** The inside of a data class' `toString()`, without the class name or parentheses. */
internal fun renderedBody(rendered: String): String =
    rendered.substring(rendered.indexOf('(') + 1, rendered.length - 1)

internal fun renderedFields(rendered: String): Map<String, String> {
    val body = renderedBody(rendered)
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    body.forEachIndexed { i, ch ->
        when (ch) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            ',' -> if (depth == 0) { parts += body.substring(start, i); start = i + 1 }
        }
    }
    parts += body.substring(start)
    // The value is NOT trimmed: it starts immediately after '=', and trimming a
    // trailing space would break the callers' round-trip check on a String field
    // that legitimately ends in one.
    return parts.associate { part ->
        val eq = part.indexOf('=')
        part.substring(0, eq).trim() to part.substring(eq + 1)
    }
}
