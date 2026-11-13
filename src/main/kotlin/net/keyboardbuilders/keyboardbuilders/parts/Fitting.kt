package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.knownOrNull
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount

/*
 * The parts module (#112), first slice: what a design needs, the parts that answer it,
 * and what a shop charges for them. Three layers that never mix -- a requirement is read
 * from the design's own facts, a product is curated seed data, an offer is a dated
 * observation of one shop -- and this file is the seam between the first two.
 */

/**
 * The interface a part plugs into, and the one a design's requirement names. A part
 * fits a requirement when the two meet, the same shape `Controller.Socket` and
 * `SwitchMount` already have on the design side (#107 step 9). It reuses those domain
 * enums rather than copying them, so the two sides cannot drift.
 */
sealed interface Fitting {
    /** Any of these footprints; a dev board drops into one. */
    data class ControllerSocket(val footprints: Set<ControllerFootprint>) : Fitting {
        init { require(footprints.isNotEmpty()) { "a controller socket takes at least one footprint" } }
    }

    /**
     * Any of these hotswap sockets. Only MX and Choc: solder pads take no part, and a
     * Mill-Max receptacle is a different purchase (two per switch) this slice leaves out.
     */
    data class SwitchSocket(val mounts: Set<SwitchMount>) : Fitting {
        init {
            require(mounts.isNotEmpty()) { "a switch socket takes at least one mount" }
            require(mounts.all { it in SOCKET_MOUNTS }) { "a switch socket is MX or Choc, was $mounts" }
        }
    }

    /** One switch diode. `Unknown` where nobody has read the board's diode pads. */
    data class SwitchDiode(val pkg: Recorded<DiodePackage>) : Fitting

    /**
     * A key switch, by the footprint it sits in: the design's own `core.switches` on the
     * requirement side, the one shape a switch has on the part side (#115). A `Bespoke`
     * footprint is one design's own, so no part on a shelf names it.
     */
    data class Switch(val footprints: Set<SwitchFootprint>) : Fitting {
        init { require(footprints.isNotEmpty()) { "a switch fits at least one footprint" } }
    }
}

/**
 * What the vendor calls a switch's feel, and only that: a colour is not a kind, so a
 * switch the vendor does not type is `Unknown`, never guessed linear (#115).
 */
enum class SwitchKind { Linear, Tactile, SilentLinear, SilentTactile, Clicky }

/**
 * What a switch part says beyond its footprint. [stated] is what a vendor's listing states
 * (#115), null where we read none.
 */
data class SwitchSpec(val kind: Recorded<SwitchKind>, val stated: StatedSpec? = null)

/**
 * A switch's facts as one shop's listing [per] states them, read on [readOn]. Forces in gf,
 * travel in mm. A field the listing leaves out is `Unknown`, never "no". [vendorSays] is the
 * vendor's own words, kept where a fact is given without saying which (Kailh Choc's "20 gf").
 */
data class StatedSpec(
    val per: String,
    val readOn: java.time.LocalDate,
    val actuationForceGf: Recorded<Double> = Recorded.Unknown,
    val bottomOutForceGf: Recorded<Double> = Recorded.Unknown,
    val actuationMm: Recorded<Double> = Recorded.Unknown,
    val travelMm: Recorded<Double> = Recorded.Unknown,
    val factoryLubed: Recorded<Boolean> = Recorded.Unknown,
    val pins: Recorded<Int> = Recorded.Unknown,
    val housing: Recorded<String> = Recorded.Unknown,
    val stem: Recorded<String> = Recorded.Unknown,
    val vendorSays: Recorded<String> = Recorded.Unknown,
) {
    init {
        for ((name, v) in listOf("actuation_force_gf" to actuationForceGf, "bottom_out_force_gf" to bottomOutForceGf))
            v.knownOrNull()?.let { require(it in 1.0..200.0) { "$name is 1 to 200 gf, was $it" } }
        for ((name, v) in listOf("actuation_mm" to actuationMm, "travel_mm" to travelMm))
            v.knownOrNull()?.let { require(it in 0.1..10.0) { "$name is 0.1 to 10 mm, was $it" } }
        pins.knownOrNull()?.let { require(it == 3 || it == 5) { "pins is 3 or 5, was $it" } }
        for ((name, v) in listOf("housing" to housing, "stem" to stem, "vendor_says" to vendorSays))
            v.knownOrNull()?.let { require(it.isNotBlank()) { "$name is the vendor's words, or left out" } }
    }
}

/** The mounts a bought hotswap socket goes into. */
val SOCKET_MOUNTS: Set<SwitchMount> = setOf(SwitchMount.MxSocket, SwitchMount.ChocSocket)

/**
 * A diode's package, as the board's pads or the vendor's listing name it. Strict tokens:
 * SOD-123F is its own member and is NOT SOD-123 until the owner rules that one fits the
 * other's pads. A near match is a ruling, never a default.
 */
enum class DiodePackage { Do35, Sod123, Sod123F, Sod323, Sod523 }

val Fitting.kind: NeedKind
    get() = when (this) {
        is Fitting.ControllerSocket -> NeedKind.Controller
        is Fitting.SwitchSocket -> NeedKind.SwitchSockets
        is Fitting.SwitchDiode -> NeedKind.Diodes
        is Fitting.Switch -> NeedKind.Switches
    }
