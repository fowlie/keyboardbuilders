package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * The interfaces a part plugs into (#112). Each one refuses a value no part could fit, so
 * a requirement built from it cannot ask for nothing.
 */
class FittingTest {

    @Test
    fun `a switch socket takes at least one mount`() {
        assertThrows<IllegalArgumentException> { Fitting.SwitchSocket(emptySet()) }
    }

    /** Solder pads and Mill-Max holes take no socket you can buy as one part. */
    @Test
    fun `a switch socket is an MX or a Choc socket, never solder or Mill-Max`() {
        assertThrows<IllegalArgumentException> { Fitting.SwitchSocket(setOf(SwitchMount.Solder)) }
        assertThrows<IllegalArgumentException> {
            Fitting.SwitchSocket(setOf(SwitchMount.MxSocket, SwitchMount.MillMax))
        }
        assertEquals(
            setOf(SwitchMount.MxSocket, SwitchMount.ChocSocket),
            Fitting.SwitchSocket(setOf(SwitchMount.MxSocket, SwitchMount.ChocSocket)).mounts,
        )
    }

    @Test
    fun `a controller socket takes at least one footprint`() {
        assertThrows<IllegalArgumentException> { Fitting.ControllerSocket(emptySet()) }
        Fitting.ControllerSocket(setOf(ControllerFootprint.ProMicro))
    }

    @Test
    fun `a requirement never asks for zero of a part`() {
        val diode = Fitting.SwitchDiode(Recorded.Known(DiodePackage.Sod123))
        assertThrows<IllegalArgumentException> { Requirement(diode, Recorded.Known(0)) }
        assertThrows<IllegalArgumentException> { Requirement(diode, Recorded.Known(-1)) }
        Requirement(diode, Recorded.Known(1))
        Requirement(diode, Recorded.Unknown)
    }

    @Test
    fun `each fitting names the kind of need it answers`() {
        assertEquals(NeedKind.Controller, Fitting.ControllerSocket(setOf(ControllerFootprint.ProMicro)).kind)
        assertEquals(NeedKind.SwitchSockets, Fitting.SwitchSocket(setOf(SwitchMount.ChocSocket)).kind)
        assertEquals(NeedKind.Diodes, Fitting.SwitchDiode(Recorded.Unknown).kind)
    }
}
