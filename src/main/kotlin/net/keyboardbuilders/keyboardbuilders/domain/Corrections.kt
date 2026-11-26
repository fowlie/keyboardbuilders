package net.keyboardbuilders.keyboardbuilders.domain

/**
 * The measured facts a person may correct on the site (owner, 2026-11-26), each with the
 * values it may take. The tokens are the seed's own, so a correction copies into the
 * override files unchanged.
 *
 * Exposed components is not here: it is a ruling on looks, not a measurement, and a
 * reviewer could not check a stranger's answer against anything.
 */
enum class CorrectableFact(val token: String, val label: String, val choices: List<String>, val many: Boolean = false) {
    Splay("splay", "Splay", listOf("yes", "no")),
    Stagger("stagger", "Stagger", listOf("ortho", "row", "column")),
    SwitchMounts("switch_mounts", "Hotswap", listOf("solder", "mx_socket", "choc_socket", "millmax"), many = true);

    /** The chosen values as the stored text, or null if they are not a valid answer. */
    fun encode(values: List<String>): String? {
        val picked = values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (picked.isEmpty() || picked.any { it !in choices }) return null
        if (!many && picked.size != 1) return null
        return choices.filter { it in picked }.joinToString(",")
    }

    /** What the design says now, as tokens; empty when it is not recorded. */
    fun current(design: Design): Set<String> = when (this) {
        Splay -> setOfNotNull(design.extras.splay.knownOrNull()?.let { if (it) "yes" else "no" })
        Stagger -> setOfNotNull(design.extras.stagger.knownOrNull()?.name?.lowercase())
        SwitchMounts -> design.extras.switchMounts.knownOrNull().orEmpty().map { it.token() }.toSet()
    }

    /** [design] with this fact set to [value], a string [encode] produced. */
    fun apply(design: Design, value: String): Design {
        val tokens = value.split(",")
        val extras = when (this) {
            Splay -> design.extras.copy(splay = Recorded.Known(tokens.single() == "yes"))
            Stagger -> design.extras.copy(stagger = Recorded.Known(net.keyboardbuilders.keyboardbuilders.domain.Stagger.valueOf(tokens.single().replaceFirstChar { it.uppercase() })))
            SwitchMounts -> design.extras.copy(switchMounts = Recorded.Known(tokens.map { it.toMount() }.toSet()))
        }
        return design.copy(extras = extras)
    }

    companion object {
        fun of(token: String): CorrectableFact? = entries.firstOrNull { it.token == token }
    }
}

/** One correction that applies: the latest for its slug and fact. */
data class AppliedCorrection(val slug: String, val fact: CorrectableFact, val value: String)

/** [this] with every correction for it applied, oldest first so the latest wins. */
fun Design.corrected(corrections: List<AppliedCorrection>): Design =
    corrections.filter { it.slug == core.slug.value }.fold(this) { d, c -> c.fact.apply(d, c.value) }

fun SwitchMount.token(): String = when (this) {
    SwitchMount.Solder -> "solder"
    SwitchMount.MxSocket -> "mx_socket"
    SwitchMount.ChocSocket -> "choc_socket"
    SwitchMount.MillMax -> "millmax"
}

private fun String.toMount(): SwitchMount =
    SwitchMount.entries.first { it.token() == this }
