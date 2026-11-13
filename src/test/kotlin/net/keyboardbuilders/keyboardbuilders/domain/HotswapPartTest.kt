package net.keyboardbuilders.keyboardbuilders.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The parts side of #107 step 9: each hotswap part states the mount it fits, and a board
 * takes the parts whose mount it accepts. The owner's rail group (2026-10-02): Any, Kailh
 * socket, Gateron socket, Mill-Max.
 */
class HotswapPartTest {

    @Test
    fun `MX socket pads take a Kailh or a Gateron socket`() {
        assertEquals(listOf(HotswapPart.KailhSocket, HotswapPart.GateronSocket),
                     setOf(SwitchMount.MxSocket).hotswapParts())
    }

    /** Gateron makes no Choc socket that we know of, so Choc pads take Kailh's alone. */
    @Test
    fun `Choc socket pads take a Kailh socket only`() {
        assertEquals(listOf(HotswapPart.KailhSocket), setOf(SwitchMount.ChocSocket).hotswapParts())
    }

    @Test
    fun `wide plated pin holes take Mill-Max`() {
        assertEquals(listOf(HotswapPart.MillMax),
                     setOf(SwitchMount.Solder, SwitchMount.MillMax).hotswapParts())
    }

    @Test
    fun `a solder-only board takes no hotswap part`() {
        assertEquals(emptyList(), setOf(SwitchMount.Solder).hotswapParts())
    }
}
