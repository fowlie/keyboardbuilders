package net.keyboardbuilders.keyboardbuilders.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * "Which cable reaches the computer?" -- backlog #97. The design's own board answers where
 * the receptacle is on it; a socketed design is a join against the controller catalogue,
 * the same shape `ConnectivityTest` protects, because the port is on the dev board.
 */
class UsbPortTest {

    private fun design(
        controller: Controller = Controller.Socket(ControllerFootprint.ProMicro),
        hostPort: Recorded<HostPort> = Recorded.Unknown,
        firmware: Recorded<Set<FirmwareRef>> = Recorded.Known(setOf(FirmwareRef.Qmk())),
    ) = Design(
        core = DesignCore(
            name = DesignName("Test Board"),
            slug = Slug("test-board"),
            sources = setOf(SourceRef("https://example.com/board")),
            firmware = firmware,
            layouts = setOf(Layout(KeyCount(42))),
            switches = setOf(SwitchFootprint.Mx),
            buildMethod = BuildMethod.Pcb(),
            controller = controller,
            body = Body.Unibody,
            licensing = Licensing(LicenseState.Unstated, LicenseState.Unstated, LicenseState.Unstated),
        ),
        extras = DesignExtras(hostPort = hostPort),
    )

    private data class Board(
        override val name: String,
        override val footprint: ControllerFootprint,
        override val usb: Recorded<Set<UsbPort>>,
        override val wireless: Boolean = false,
        override val mcuFamily: Recorded<McuFamily> = Recorded.Unknown,
        override val firmware: Set<String> = setOf("qmk"),
    ) : ControllerFacts

    private val niceNano = Board("nice!nano v2", ControllerFootprint.ProMicro, Recorded.Known(setOf(UsbPort.UsbC)),
                                 firmware = setOf("qmk", "zmk"))
    private val zmkOnly = Board("nRFMicro", ControllerFootprint.ProMicro, Recorded.Known(setOf(UsbPort.UsbC)),
                                firmware = setOf("zmk"))
    private val proMicro = Board("Pro Micro", ControllerFootprint.ProMicro,
                                 Recorded.Known(setOf(UsbPort.MicroB, UsbPort.UsbC)))
    private val imera = Board("Imera", ControllerFootprint.ProMicro, Recorded.Unknown)
    private val xiao = Board("Seeed XIAO RP2040", ControllerFootprint.SeeedXiao, Recorded.Known(setOf(UsbPort.UsbC)))

    private val soldered = Controller.Soldered(Recorded.Known(Mcu.Rp2040))

    @Test
    fun `a soldered board's own receptacle is the answer`() {
        val answer = design(soldered, Recorded.Known(HostPort.OnBoard(UsbPort.MiniB))).usbPortFor(emptyList())

        assertEquals(UsbPortAnswer.OnBoard(UsbPort.MiniB), answer)
    }

    @Test
    fun `a daughterboard is named as where the cable goes`() {
        assertEquals(UsbPortAnswer.Daughterboard,
                     design(soldered, Recorded.Known(HostPort.Daughterboard)).usbPortFor(emptyList()))
    }

    @Test
    fun `a soldered board with no readable receptacle is unrecorded, not guessed`() {
        assertEquals(UsbPortAnswer.Unrecorded, design(soldered).usbPortFor(listOf(niceNano)))
    }

    @Test
    fun `a socket lists every port its fitting boards offer, with which boards offer it`() {
        val answer = design().usbPortFor(listOf(niceNano, proMicro, imera, xiao))

        assertEquals(
            UsbPortAnswer.OnDevBoard(mapOf(
                UsbPort.UsbC to listOf("nice!nano v2", "Pro Micro"),
                UsbPort.MicroB to listOf("Pro Micro"),
            )),
            answer,
            "the XIAO does not fit a Pro Micro socket, and Imera's unknown port is left out",
        )
    }

    @Test
    fun `a socket nothing cataloged fits, or whose boards' ports nobody read, is unrecorded`() {
        assertEquals(UsbPortAnswer.Unrecorded, design().usbPortFor(listOf(xiao)))
        assertEquals(UsbPortAnswer.Unrecorded, design().usbPortFor(listOf(imera)))
    }

    @Test
    fun `a socketed board with a receptacle of its own answers with that`() {
        val answer = design(hostPort = Recorded.Known(HostPort.OnBoard(UsbPort.MiniB)))
            .usbPortFor(listOf(niceNano))

        assertEquals(UsbPortAnswer.OnBoard(UsbPort.MiniB), answer)
    }

    @Test
    fun `only boards that run the design's firmware are asked -- a ZMK board is not a classic Pro Micro`() {
        val zmk = design(firmware = Recorded.Known(setOf(FirmwareRef.Zmk())))

        assertEquals(
            UsbPortAnswer.OnDevBoard(mapOf(UsbPort.UsbC to listOf("nice!nano v2", "nRFMicro"))),
            zmk.usbPortFor(listOf(niceNano, zmkOnly, proMicro)),
            "the Pro Micro runs QMK only, so its micro-USB is not this design's to offer",
        )
    }

    @Test
    fun `where no fitting board runs the firmware, every fitting board still says what plugs in`() {
        val kmk = design(firmware = Recorded.Known(setOf(FirmwareRef.Kmk())))

        assertEquals(
            UsbPortAnswer.OnDevBoard(mapOf(
                UsbPort.UsbC to listOf("nice!nano v2", "Pro Micro"),
                UsbPort.MicroB to listOf("Pro Micro"),
            )),
            kmk.usbPortFor(listOf(niceNano, proMicro)),
        )
    }
}
