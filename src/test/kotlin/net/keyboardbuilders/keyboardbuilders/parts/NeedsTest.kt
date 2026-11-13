package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.domain.BoardDrawn
import net.keyboardbuilders.keyboardbuilders.domain.BoardOutline
import net.keyboardbuilders.keyboardbuilders.domain.BoardSize
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.BuildMethod
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.DesignCore
import net.keyboardbuilders.keyboardbuilders.domain.DesignExtras
import net.keyboardbuilders.keyboardbuilders.domain.DesignName
import net.keyboardbuilders.keyboardbuilders.domain.DiodeDirection
import net.keyboardbuilders.keyboardbuilders.domain.Hand
import net.keyboardbuilders.keyboardbuilders.domain.KeyCount
import net.keyboardbuilders.keyboardbuilders.domain.KeyWiring
import net.keyboardbuilders.keyboardbuilders.domain.Layout
import net.keyboardbuilders.keyboardbuilders.domain.LayoutName
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.Licensing
import net.keyboardbuilders.keyboardbuilders.domain.Mcu
import net.keyboardbuilders.keyboardbuilders.domain.PinCount
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import net.keyboardbuilders.keyboardbuilders.domain.SplitHalves
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * How a design's published facts become what a builder buys (#112). Pure fixtures shaped
 * like the two demo designs, then one test per other outcome. The rule every test here
 * keeps: an Unknown fact upstream is "not recorded", never "nothing to buy".
 */
class NeedsTest {

    private val split = Body.Split(SplitHalves(2), Recorded.Unknown, Recorded.Unknown)

    private fun design(
        layouts: Set<Layout> = setOf(Layout(KeyCount(58))),
        controller: Controller = Controller.Socket(ControllerFootprint.ProMicro),
        body: Body = split,
        wiring: Recorded<KeyWiring> = Recorded.Known(matrix(5, 6)),
        mounts: Recorded<Set<SwitchMount>> = Recorded.Known(setOf(SwitchMount.ChocSocket, SwitchMount.MxSocket)),
        boardSize: Recorded<BoardSize> = Recorded.Unknown,
        drawn: Recorded<BoardDrawn> = Recorded.Unknown,
        buildMethod: BuildMethod = BuildMethod.Pcb(),
    ) = Design(
        core = DesignCore(
            name = DesignName("Test Board"),
            slug = Slug("test-board"),
            sources = setOf(SourceRef("https://example.com/board")),
            firmware = Recorded.Unknown,
            layouts = layouts,
            switches = setOf(SwitchFootprint.Mx),
            buildMethod = buildMethod,
            controller = controller,
            body = body,
            licensing = Licensing(LicenseState.Unstated, LicenseState.Unstated, LicenseState.Unstated),
        ),
        extras = DesignExtras(wiring = wiring, switchMounts = mounts, boardSize = boardSize, boardDrawn = drawn),
    )

    private fun matrix(rows: Int, cols: Int) = KeyWiring.Matrix(PinCount(rows), PinCount(cols), DiodeDirection.ColToRow)

    private val sod123 = Recorded.Known(DiodePackage.Sod123)

    private fun Design.need(kind: NeedKind, pkg: Recorded<DiodePackage> = Recorded.Unknown): Need =
        needs(pkg).single { it.kind == kind }

    private fun buy(fitting: Fitting, quantity: Recorded<Int>, vararg basis: Basis, layout: Recorded<LayoutName> = Recorded.Unknown) =
        Need.Buy(Requirement(fitting, quantity), basis.toSet(), layout)

    @Test
    fun `there is always one need per kind, in kind order`() {
        assertEquals(NeedKind.entries, design().needs(Recorded.Unknown).map { it.kind })
        assertEquals(NeedKind.entries, design(wiring = Recorded.Unknown, mounts = Recorded.Unknown)
            .needs(Recorded.Unknown).map { it.kind })
    }

    // -- Lily58 Pro: 58 keys, Pro Micro socket, 5x6 matrix per half, Choc and MX sockets ---

    @Test
    fun `a Lily58-shaped split takes one Pro Micro per half`() {
        assertEquals(
            buy(Fitting.ControllerSocket(setOf(ControllerFootprint.ProMicro)), Recorded.Known(2), Basis.OnePerHalf),
            design().need(NeedKind.Controller),
        )
    }

    @Test
    fun `a Lily58-shaped board takes one MX or Choc socket per key`() {
        assertEquals(
            buy(Fitting.SwitchSocket(setOf(SwitchMount.MxSocket, SwitchMount.ChocSocket)), Recorded.Known(58), Basis.OnePerKey),
            design().need(NeedKind.SwitchSockets),
        )
    }

    @Test
    fun `a matrix takes one diode per key, of the board file's package when it was read`() {
        assertEquals(
            buy(Fitting.SwitchDiode(sod123), Recorded.Known(58), Basis.OnePerKey, Basis.BoardFile),
            design().need(NeedKind.Diodes, sod123),
        )
        assertEquals(
            buy(Fitting.SwitchDiode(Recorded.Unknown), Recorded.Known(58), Basis.OnePerKey),
            design().need(NeedKind.Diodes),
        )
    }

    // -- Corne v4: RP2040 on the board, every key on its own pin, Choc sockets -----------

    private val corneV4 = design(
        layouts = setOf(Layout(KeyCount(42), name = Recorded.Known(LayoutName("Standard"))),
                        Layout(KeyCount(36), name = Recorded.Known(LayoutName("Mini")))),
        controller = Controller.Soldered(Recorded.Known(Mcu.Rp2040)),
        wiring = Recorded.Known(KeyWiring.DirectPin(PinCount(23))),
        mounts = Recorded.Known(setOf(SwitchMount.ChocSocket)),
    )

    @Test
    fun `a soldered chip is nothing to buy, and says which chip`() {
        assertEquals(
            Need.NothingToBuy(NeedKind.Controller, NoneReason.OnTheBoard(Recorded.Known(Mcu.Rp2040))),
            corneV4.need(NeedKind.Controller),
        )
    }

    @Test
    fun `a two-layout board takes sockets for the larger layout, and names it`() {
        assertEquals(
            buy(Fitting.SwitchSocket(setOf(SwitchMount.ChocSocket)), Recorded.Known(42), Basis.OnePerKey,
                layout = Recorded.Known(LayoutName("Standard"))),
            corneV4.need(NeedKind.SwitchSockets),
        )
    }

    /** 23 pins on a 42-key split is one half's scan, as `isOneHalfOf` says on the design page. */
    @Test
    fun `direct-pin wiring takes no switch diodes, and says when the pins are one half's`() {
        assertEquals(
            Need.NothingToBuy(NeedKind.Diodes, NoneReason.DirectPin(PinCount(23), perHalf = true)),
            corneV4.need(NeedKind.Diodes),
        )
        assertEquals(
            Need.NothingToBuy(NeedKind.Diodes, NoneReason.DirectPin(PinCount(23), perHalf = false)),
            design(layouts = setOf(Layout(KeyCount(23))), body = Body.Unibody,
                wiring = Recorded.Known(KeyWiring.DirectPin(PinCount(23)))).need(NeedKind.Diodes),
        )
    }

    @Test
    fun `analog wiring takes no switch diodes`() {
        assertEquals(
            Need.NothingToBuy(NeedKind.Diodes, NoneReason.Analog),
            design(wiring = Recorded.Known(KeyWiring.Analog)).need(NeedKind.Diodes),
        )
    }

    // -- Unknown upstream is never "nothing" -----------------------------------------------

    @Test
    fun `unrecorded wiring leaves the diodes not recorded, never none`() {
        assertEquals(Need.NotRecorded(NeedKind.Diodes), design(wiring = Recorded.Unknown).need(NeedKind.Diodes, sod123))
    }

    @Test
    fun `unrecorded mounts leave the sockets not recorded`() {
        assertEquals(Need.NotRecorded(NeedKind.SwitchSockets), design(mounts = Recorded.Unknown).need(NeedKind.SwitchSockets))
    }

    @Test
    fun `a split whose wiring is unrecorded takes its footprint in an unknown number`() {
        assertEquals(
            buy(Fitting.ControllerSocket(setOf(ControllerFootprint.ProMicro)), Recorded.Unknown, Basis.DesignFact),
            design(wiring = Recorded.Unknown).need(NeedKind.Controller),
        )
    }

    /** Cheapino: 36 keys on one 8x12 scan, both halves run from one chip. */
    @Test
    fun `a split whose scan could be the whole board takes an unknown number of controllers`() {
        val cheapino = design(layouts = setOf(Layout(KeyCount(36))), wiring = Recorded.Known(matrix(8, 12)))
        assertEquals(
            buy(Fitting.ControllerSocket(setOf(ControllerFootprint.ProMicro)), Recorded.Unknown, Basis.DesignFact),
            cheapino.need(NeedKind.Controller),
        )
    }

    @Test
    fun `a unibody or one-handed board takes one controller`() {
        val one = buy(Fitting.ControllerSocket(setOf(ControllerFootprint.ProMicro)), Recorded.Known(1), Basis.DesignFact)
        assertEquals(one, design(body = Body.Unibody).need(NeedKind.Controller))
        assertEquals(one, design(body = Body.OneHanded(Hand.Left)).need(NeedKind.Controller))
    }

    @Test
    fun `an unknown soldered chip is still nothing to buy`() {
        assertEquals(
            Need.NothingToBuy(NeedKind.Controller, NoneReason.OnTheBoard(Recorded.Unknown)),
            design(controller = Controller.Soldered()).need(NeedKind.Controller),
        )
    }

    // -- switch mounts ---------------------------------------------------------------------

    @Test
    fun `solder-only pads take no socket, with or without Mill-Max holes`() {
        for (mounts in listOf(setOf(SwitchMount.Solder), setOf(SwitchMount.MillMax, SwitchMount.Solder))) {
            assertEquals(
                Need.NothingToBuy(NeedKind.SwitchSockets, NoneReason.SolderedSwitches),
                design(mounts = Recorded.Known(mounts)).need(NeedKind.SwitchSockets),
                "$mounts",
            )
        }
    }

    /**
     * 30 designs take a socket AND solder (Cheapino, Chocofly). The pads accept the socket,
     * so a hotswap build buys them; "nothing to buy" would hide that.
     */
    @Test
    fun `pads that take a socket or solder still take the socket`() {
        assertEquals(
            buy(Fitting.SwitchSocket(setOf(SwitchMount.MxSocket)), Recorded.Known(58), Basis.OnePerKey),
            design(mounts = Recorded.Known(setOf(SwitchMount.MillMax, SwitchMount.MxSocket, SwitchMount.Solder)))
                .need(NeedKind.SwitchSockets),
        )
    }

    @Test
    fun `Mill-Max-only pads are out of this slice`() {
        assertEquals(
            Need.OutOfSlice(NeedKind.SwitchSockets, setOf(SwitchMount.MillMax)),
            design(mounts = Recorded.Known(setOf(SwitchMount.MillMax))).need(NeedKind.SwitchSockets),
        )
    }

    /** Two layouts of one size name neither: picking one name would be a guess. */
    @Test
    fun `a tie for the largest layout names no layout`() {
        val tied = design(layouts = setOf(
            Layout(KeyCount(58), name = Recorded.Known(LayoutName("A"))),
            Layout(KeyCount(58), name = Recorded.Known(LayoutName("B"))),
        ))
        val sockets = assertIs<Need.Buy>(tied.need(NeedKind.SwitchSockets))
        assertEquals(Recorded.Known(58), sockets.requirement.quantity)
        assertEquals(Recorded.Unknown, sockets.layoutName)
    }

    // -- the PCB: a line of its own, read off the board files, not priced in this slice ---

    private val lilySize = Recorded.Known(BoardSize.Each(BoardOutline(142.52, 106.33)))

    @Test
    fun `a split needs a board per half, at the size its file measures`() {
        assertEquals(
            Need.Boards(2, BoardsMade.OnePerHalf, lilySize),
            design(boardSize = lilySize, drawn = Recorded.Known(BoardDrawn.Half)).need(NeedKind.Pcb),
        )
    }

    @Test
    fun `a unibody needs one board`() {
        val size = Recorded.Known(BoardSize.Each(BoardOutline(285.0, 94.6)))
        assertEquals(
            Need.Boards(1, BoardsMade.One, size),
            design(body = Body.Unibody, boardSize = size, drawn = Recorded.Known(BoardDrawn.Board)).need(NeedKind.Pcb),
        )
    }

    @Test
    fun `a reversible split's two boards are one design`() {
        val reversible = Body.Split(SplitHalves(2), Recorded.Known(true), Recorded.Unknown)
        assertEquals(
            Need.Boards(2, BoardsMade.Reversible, lilySize),
            design(body = reversible, boardSize = lilySize, drawn = Recorded.Known(BoardDrawn.Half)).need(NeedKind.Pcb),
        )
    }

    /** Ferris, Sweep, Corne v4: one file, both halves joined by tabs, so no half's size. */
    @Test
    fun `both halves in one file say so, and an unread size stays unread`() {
        assertEquals(
            Need.Boards(2, BoardsMade.BothHalvesInOneFile, Recorded.Unknown),
            design(drawn = Recorded.Known(BoardDrawn.BothHalves)).need(NeedKind.Pcb),
        )
    }

    /** Nothing says what the file draws: a split still has a board per half, and nothing more is claimed. */
    @Test
    fun `an unread board is a board per half of unrecorded size`() {
        assertEquals(Need.Boards(2, BoardsMade.OnePerHalf, Recorded.Unknown), design().need(NeedKind.Pcb))
    }

    @Test
    fun `a hand-wired design has no board to buy`() {
        assertEquals(
            Need.NothingToBuy(NeedKind.Pcb, NoneReason.Handwired),
            design(buildMethod = BuildMethod.Handwire).need(NeedKind.Pcb),
        )
    }
}
