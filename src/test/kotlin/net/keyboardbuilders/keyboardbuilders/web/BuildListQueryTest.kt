package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.parts.NeedKind
import net.keyboardbuilders.keyboardbuilders.parts.Pick
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures
import net.keyboardbuilders.keyboardbuilders.parts.PartsFixtures.grid
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The build list's picks in the URL (#113): `?controller=nice-nano-v2&diodes=own`. */
class BuildListQueryTest {

    @Test
    fun `own and product slugs are read, one key per row`() {
        assertEquals(
            mapOf(NeedKind.Controller to Pick.Product("nice-nano-v2"), NeedKind.SwitchSockets to Pick.Own,
                  NeedKind.Diodes to Pick.Own, NeedKind.Pcb to Pick.Own, NeedKind.Switches to Pick.Product("gateron-cj")),
            readPicks(mapOf("controller" to "nice-nano-v2", "sockets" to "own", "diodes" to "own", "pcb" to "own",
                            "switches" to "gateron-cj")),
        )
    }

    @Test
    fun `blank values and unknown keys are ignored`() {
        assertEquals(emptyMap(), readPicks(mapOf("utm_source" to "x", "controller" to "", "diodes" to "  ", "Controller" to "own")))
    }

    @Test
    fun `typed text is kept raw, never checked here`() {
        assertEquals(mapOf(NeedKind.Controller to Pick.Product("<script>")), readPicks(mapOf("controller" to "<script>")))
    }

    private val rows = grid(offers = PartsFixtures.today + PartsFixtures.kitInStock,
        picks = mapOf(NeedKind.Diodes to Pick.Own, NeedKind.Controller to Pick.Product("nice-nano-v2"))).rows

    @Test
    fun `a link keeps the other picks, in row order, and lands on the row it changed`() {
        assertEquals("/designs/lily58-pro/build?controller=nice-nano-v2&sockets=kailh-choc-hotswap-socket&diodes=own#need-sockets",
            pickHref("lily58-pro", rows, NeedKind.SwitchSockets, Pick.Product("kailh-choc-hotswap-socket")))
    }

    @Test
    fun `picking the default removes the row's key`() {
        assertEquals("/designs/lily58-pro/build?diodes=own#need-controller",
            pickHref("lily58-pro", rows, NeedKind.Controller, null))
        assertEquals("/designs/lily58-pro/build#need-diodes",
            pickHref("lily58-pro", grid().rows, NeedKind.Diodes, null))
    }

    @Test
    fun `I have this toggles on and off`() {
        assertEquals("/designs/lily58-pro/build?controller=nice-nano-v2&diodes=own&pcb=own#need-pcb",
            pickHref("lily58-pro", rows, NeedKind.Pcb, Pick.Own))
        assertEquals("/designs/lily58-pro/build?controller=nice-nano-v2#need-diodes",
            pickHref("lily58-pro", rows, NeedKind.Diodes, null))
    }

    /** A bad value never reaches the next link: links are built only from the picks in effect. */
    @Test
    fun `a pick that was not used is dropped from every link`() {
        val bad = grid(picks = mapOf(NeedKind.Controller to Pick.Product("<script>"))).rows
        assertEquals("/designs/lily58-pro/build?diodes=own#need-diodes", pickHref("lily58-pro", bad, NeedKind.Diodes, Pick.Own))
    }

    /** #115: the picker page carries every pick in effect, and lands on no row. */
    @Test
    fun `the switches page link keeps every pick`() {
        assertEquals("/designs/lily58-pro/build/switches?controller=nice-nano-v2&diodes=own",
            pickerHref("lily58-pro", rows, NeedKind.Switches))
        assertEquals("/designs/lily58-pro/build/switches", pickerHref("lily58-pro", grid().rows, NeedKind.Switches))
    }

    /** #115: a shop for the picked product rides beside it, and alone it means nothing. */
    @Test
    fun `a shop is read beside its product, and kept in the links`() {
        assertEquals(mapOf(NeedKind.Controller to Pick.Product("nice-nano-v2", shop = "splitkb")),
            readPicks(mapOf("controller" to "nice-nano-v2", "controller-shop" to "splitkb")))
        assertEquals(emptyMap(), readPicks(mapOf("controller-shop" to "splitkb")))
        assertEquals(mapOf(NeedKind.Controller to Pick.Own), readPicks(mapOf("controller" to "own", "controller-shop" to "x")))
        assertEquals("/designs/lily58-pro/build?controller=nice-nano-v2&controller-shop=splitkb#need-controller",
            pickHref("lily58-pro", grid().rows, NeedKind.Controller, Pick.Product("nice-nano-v2", shop = "splitkb")))
    }
}
