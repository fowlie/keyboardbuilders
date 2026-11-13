package net.keyboardbuilders.keyboardbuilders.domain

/**
 * Which cable reaches the computer — backlog #97, the design page's "USB port" row.
 *
 * A DERIVATION, like [connectivityFor], and for the same reason: on a socketed design the
 * port is on the dev board, and which dev board is the builder's choice. Only a board's
 * own receptacle is a fact about the design ([DesignExtras.hostPort]).
 */
sealed interface UsbPortAnswer {
    /** The design's own board carries it. */
    data class OnBoard(val port: UsbPort) : UsbPortAnswer

    /** The design's board wires to a separate USB daughterboard, which carries it. */
    data object Daughterboard : UsbPortAnswer

    /**
     * The dev board carries it: each port a fitting catalogue board is sold with, and
     * which boards those are. One key means every catalogued board that fits agrees.
     */
    data class OnDevBoard(val choices: Map<UsbPort, List<String>>) : UsbPortAnswer

    /** Nothing read: no receptacle on the board, or no fitting board whose port is known. */
    data object Unrecorded : UsbPortAnswer
}

/**
 * The design's own receptacle wins where there is one, a socketed one-piece board's
 * pass-through included. Otherwise a socket asks the catalogue, and a board whose port no
 * source states is left out rather than guessed at.
 */
fun Design.usbPortFor(boards: List<ControllerFacts>): UsbPortAnswer {
    when (val own = extras.hostPort.knownOrNull()) {
        is HostPort.OnBoard -> return UsbPortAnswer.OnBoard(own.port)
        HostPort.Daughterboard -> return UsbPortAnswer.Daughterboard
        null -> {}
    }
    val socket = core.controller as? Controller.Socket ?: return UsbPortAnswer.Unrecorded
    val onFootprint = boards.filter { it.footprint in socket.footprints }
    // ONLY BOARDS THAT RUN THIS DESIGN'S FIRMWARE, the same narrowing Connectivity makes:
    // a ZMK design's socket takes a classic Pro Micro physically, but its micro-USB is not
    // this design's to offer, since the Pro Micro runs QMK only. Where no fitting board runs
    // the firmware, every fitting board still answers -- what plugs in is a physical fact,
    // and a blank row would hide it.
    // An unrecorded firmware runs on no board we can name, so every fitting board answers.
    val mine = core.firmware.knownOrNull().orEmpty().map { it.familyKey() }.toSet()
    val runs = onFootprint.filter { (it.firmware intersect mine).isNotEmpty() }.ifEmpty { onFootprint }
    val choices = UsbPort.entries.associateWith { port ->
        runs.filter { port in (it.usb.knownOrNull() ?: emptySet()) }
            .map { it.name }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }.filterValues { it.isNotEmpty() }
    return if (choices.isEmpty()) UsbPortAnswer.Unrecorded else UsbPortAnswer.OnDevBoard(choices)
}
