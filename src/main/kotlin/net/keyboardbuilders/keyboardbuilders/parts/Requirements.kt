package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.domain.BoardDrawn
import net.keyboardbuilders.keyboardbuilders.domain.BoardSize
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.BuildMethod
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.KeyWiring
import net.keyboardbuilders.keyboardbuilders.domain.LayoutName
import net.keyboardbuilders.keyboardbuilders.domain.Mcu
import net.keyboardbuilders.keyboardbuilders.domain.PinCount
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import net.keyboardbuilders.keyboardbuilders.domain.isOneHalfOf
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import net.keyboardbuilders.keyboardbuilders.domain.largestKeyCount

/**
 * The requirement kinds, one line each on a build list. The PCB is measured and listed
 * but not priced: the owner postponed its fab cost (2026-10-04), so its line never counts
 * and is always named as left out.
 *
 * A [taste] row (#115, switches) has no default pick: which switch is the reader's
 * choice, not the cheapest in stock, so until it is made the line is "choose", and the
 * totals leave it out or name it as a gap (see `totalOf`).
 */
enum class NeedKind(val pricedHere: Boolean, val taste: Boolean = false) {
    Controller(true), SwitchSockets(true), Diodes(true), Pcb(false), Switches(true, taste = true),
}

/**
 * An interface and how many of it: "58 x Choc or MX hotswap socket". The quantity is
 * `Unknown` where the design's facts cannot give it (a split whose controller count the
 * wiring does not prove), and an Unknown quantity is never priced into a total.
 */
data class Requirement(val fitting: Fitting, val quantity: Recorded<Int>) {
    init {
        require((quantity.knownOrNull() ?: 1) >= 1) { "a requirement asks for at least one, was $quantity" }
    }
}

/** Where a line's interface or quantity comes from. Shown as a label on every line. */
enum class Basis {
    /** Read straight off the design's own record. */
    DesignFact,
    /** One per key of the largest layout. */
    OnePerKey,
    /** One per half, on a split whose scan is proved to be one half's. */
    OnePerHalf,
    /** The package was read off the board file's pads. */
    BoardFile,
    /** The vendor's own statement about its kit. */
    PerVendor,
}

/**
 * One line of a build list before any shop is asked. Four outcomes, and the last two are
 * the ones that keep it honest: [NotRecorded] is an Unknown upstream and is never folded
 * into [NothingToBuy], which is a fact.
 */
sealed interface Need {
    val kind: NeedKind

    /** [layoutName] is the layout the quantity was counted from, where it has a name. */
    data class Buy(
        val requirement: Requirement,
        val basis: Set<Basis>,
        val layoutName: Recorded<LayoutName> = Recorded.Unknown,
    ) : Need {
        override val kind: NeedKind get() = requirement.fitting.kind
    }

    data class NothingToBuy(override val kind: NeedKind, val why: NoneReason) : Need

    data class NotRecorded(override val kind: NeedKind) : Need

    /** Pads only a Mill-Max receptacle fits: a real need, priced in a later slice. */
    data class OutOfSlice(override val kind: NeedKind, val mounts: Set<SwitchMount>) : Need

    /**
     * The boards themselves: how many, why that many, and each one's size where the
     * board files give it. Priced in a later slice, so always left out of the total.
     */
    data class Boards(val count: Int, val made: BoardsMade, val size: Recorded<BoardSize>) : Need {
        init {
            require(count >= 1) { "a build takes at least one board, was $count" }
        }
        override val kind: NeedKind get() = NeedKind.Pcb
    }
}

/** Why a build takes the boards it does. */
enum class BoardsMade {
    /** A unibody, or a one-handed board. */
    One,
    /** A split: one board per half, from one file or one per hand. */
    OnePerHalf,
    /** A split whose record says one reversible board serves both hands. */
    Reversible,
    /** A split drawn whole in one file, the halves to be snapped or cut apart. */
    BothHalvesInOneFile,
}

sealed interface NoneReason {
    /** The chip is soldered to the board; `Unknown` where no source names it. */
    data class OnTheBoard(val mcu: Recorded<Mcu>) : NoneReason
    /** One pin per key, so no switch diodes. [perHalf] where [pins] is one half's, per `isOneHalfOf`. */
    data class DirectPin(val pins: PinCount, val perHalf: Boolean) : NoneReason
    data object SolderedSwitches : NoneReason
    data object Analog : NoneReason
    /** No PCB: the keys are wired by hand. */
    data object Handwired : NoneReason
}

/**
 * What this design needs for each kind of the first slice, always one [Need] per
 * [NeedKind] in that order. Reads only facts the site already publishes, plus the diode
 * package, which no design record carries yet and the caller supplies (from the board
 * scan, or `Unknown`).
 */
fun Design.needs(diodePackage: Recorded<DiodePackage>): List<Need> =
    listOf(controllerNeed(), socketNeed(), diodeNeed(diodePackage), boardNeed(), switchNeed())

/**
 * One switch per key of the largest layout, any of the design's footprints. The footprint
 * is a gate field, never Unknown on a published design (`DesignCore` refuses an empty
 * set), so this line is always a buy.
 */
private fun Design.switchNeed(): Need = perKey(Fitting.Switch(core.switches), setOf(Basis.OnePerKey))

/**
 * The count is the body's: one board, or one per half. The file says only whether both
 * halves come in one drawing, and the record whether one board serves both hands.
 */
private fun Design.boardNeed(): Need {
    if (core.buildMethod == BuildMethod.Handwire) return Need.NothingToBuy(NeedKind.Pcb, NoneReason.Handwired)
    val size = extras.boardSize
    return when (val body = core.body) {
        Body.Unibody, is Body.OneHanded -> Need.Boards(1, BoardsMade.One, size)
        is Body.Split -> Need.Boards(
            body.halves.value,
            when {
                extras.boardDrawn == Recorded.Known(BoardDrawn.BothHalves) -> BoardsMade.BothHalvesInOneFile
                body.reversible == Recorded.Known(true) -> BoardsMade.Reversible
                else -> BoardsMade.OnePerHalf
            },
            size,
        )
    }
}

private fun Design.controllerNeed(): Need {
    val socket = when (val c = core.controller) {
        is Controller.Soldered -> return Need.NothingToBuy(NeedKind.Controller, NoneReason.OnTheBoard(c.mcu))
        is Controller.Socket -> Fitting.ControllerSocket(c.footprints)
    }
    return when (val body = core.body) {
        Body.Unibody, is Body.OneHanded -> Need.Buy(Requirement(socket, Recorded.Known(1)), setOf(Basis.DesignFact))
        // One per half only where the scan proves each half has its own chip; Cheapino and
        // Pando run both halves from one, and nothing else on the record says which.
        is Body.Split -> when (extras.wiring.knownOrNull()?.let { isOneHalfOf(it) } == true) {
            true -> Need.Buy(Requirement(socket, Recorded.Known(body.halves.value)), setOf(Basis.OnePerHalf))
            false -> Need.Buy(Requirement(socket, Recorded.Unknown), setOf(Basis.DesignFact))
        }
    }
}

private fun Design.socketNeed(): Need {
    val mounts = extras.switchMounts.knownOrNull() ?: return Need.NotRecorded(NeedKind.SwitchSockets)
    val sockets = mounts intersect SOCKET_MOUNTS
    return when {
        // Pads that take a socket or solder still take the socket: a hotswap build buys them.
        sockets.isNotEmpty() -> perKey(Fitting.SwitchSocket(sockets), setOf(Basis.OnePerKey))
        SwitchMount.Solder in mounts -> Need.NothingToBuy(NeedKind.SwitchSockets, NoneReason.SolderedSwitches)
        else -> Need.OutOfSlice(NeedKind.SwitchSockets, mounts)
    }
}

private fun Design.diodeNeed(pkg: Recorded<DiodePackage>): Need =
    when (val wiring = extras.wiring.knownOrNull()) {
        null -> Need.NotRecorded(NeedKind.Diodes)
        is KeyWiring.Matrix -> perKey(
            Fitting.SwitchDiode(pkg),
            if (pkg is Recorded.Known) setOf(Basis.OnePerKey, Basis.BoardFile) else setOf(Basis.OnePerKey),
        )
        is KeyWiring.DirectPin -> Need.NothingToBuy(NeedKind.Diodes, NoneReason.DirectPin(wiring.pins, isOneHalfOf(wiring)))
        KeyWiring.Analog -> Need.NothingToBuy(NeedKind.Diodes, NoneReason.Analog)
    }

/** One per key of the largest layout, named when exactly one layout is that large. */
private fun Design.perKey(fitting: Fitting, basis: Set<Basis>): Need.Buy {
    val keys = largestKeyCount()
    val largest = core.layouts.filter { it.keys.value == keys }
    val name = largest.singleOrNull()?.name ?: Recorded.Unknown
    return Need.Buy(Requirement(fitting, Recorded.Known(keys)), basis, name)
}
