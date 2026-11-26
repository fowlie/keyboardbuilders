package net.keyboardbuilders.keyboardbuilders.community

import net.keyboardbuilders.keyboardbuilders.domain.CorrectableFact
import java.util.Locale

/**
 * Small drawings for the correction form's answers: a few keycaps laid out the way each
 * answer means, so the difference shows before the words are read. Inline SVG, coloured
 * by app.css (`.fact-ico`) so it follows the reader's theme. Null where a picture would not
 * help (switch mounts read fine as words).
 */
object FactIcons {
    private const val W = 64
    private const val H = 46
    private const val KEY = 10.0
    private const val PITCH = 13.0

    fun of(fact: CorrectableFact, value: String): String? = when (fact) {
        CorrectableFact.Stagger -> when (value) {
            "ortho" -> grid(cols = 4, rows = 3) { _, _ -> 0.0 to 0.0 }
            "row" -> grid(cols = 4, rows = 3) { _, r -> r * 3.5 - 3.5 to 0.0 }
            else -> grid(cols = 4, rows = 3) { c, _ -> 0.0 to listOf(5.0, 1.0, -1.0, 2.5)[c] }
        }
        CorrectableFact.Splay -> when (value) {
            "yes" -> fan(listOf(-14.0, -4.0, 6.0))
            else -> fan(listOf(0.0, 0.0, 0.0))
        }
        CorrectableFact.SwitchMounts -> null
    }

    /** [cols] x [rows] keys, each moved by [shift] (dx, dy), centred in the box. */
    private fun grid(cols: Int, rows: Int, shift: (Int, Int) -> Pair<Double, Double>): String {
        val x0 = (W - (cols * PITCH - (PITCH - KEY))) / 2
        val y0 = (H - (rows * PITCH - (PITCH - KEY))) / 2
        val keys = (0 until cols).flatMap { c ->
            (0 until rows).map { r ->
                val (dx, dy) = shift(c, r)
                key(x0 + c * PITCH + dx, y0 + r * PITCH + dy)
            }
        }
        return svg(keys.joinToString(""))
    }

    /** Three column-staggered columns, each turned by its angle about a point below. */
    private fun fan(angles: List<Double>): String {
        val offsets = listOf(4.0, 0.0, 2.0)
        val x0 = (W - (3 * PITCH - (PITCH - KEY))) / 2
        val cols = angles.mapIndexed { c, a ->
            val keys = (0 until 3).joinToString("") { r -> key(x0 + c * PITCH, 3.0 + r * PITCH + offsets[c]) }
            """<g transform="rotate(${n(a)} ${n(x0 + c * PITCH + KEY / 2)} ${n(H + 14.0)})">$keys</g>"""
        }
        return svg(cols.joinToString(""))
    }

    private fun key(x: Double, y: Double) =
        """<rect x="${n(x)}" y="${n(y)}" width="${n(KEY)}" height="${n(KEY)}" rx="2.2"/>"""

    private fun svg(body: String) =
        """<svg class="fact-ico" viewBox="0 0 $W $H" aria-hidden="true">$body</svg>"""

    private fun n(v: Double) = String.format(Locale.ROOT, "%.1f", v)
}
