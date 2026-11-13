package net.keyboardbuilders.keyboardbuilders.parts

import net.keyboardbuilders.keyboardbuilders.catalog.ControllerBoard
import net.keyboardbuilders.keyboardbuilders.domain.BoardDrawn
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
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRef
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRules
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareSupport
import net.keyboardbuilders.keyboardbuilders.domain.KeyCount
import net.keyboardbuilders.keyboardbuilders.domain.KeyWiring
import net.keyboardbuilders.keyboardbuilders.domain.Layout
import net.keyboardbuilders.keyboardbuilders.domain.License
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.Licensing
import net.keyboardbuilders.keyboardbuilders.domain.McuFamily
import net.keyboardbuilders.keyboardbuilders.domain.PinCount
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import net.keyboardbuilders.keyboardbuilders.domain.SplitHalves
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import net.keyboardbuilders.keyboardbuilders.domain.SwitchMount
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * A Lily58 Pro-shaped design and a 42keebs-shaped shop, as the plan for #112 read them on
 * 2026-10-04. Fixtures, not the seed: `PartsCatalogTest` reads the real one.
 */
object PartsFixtures {

    val mit = Licensing(
        LicenseState.Stated(setOf(License.Mit)),
        LicenseState.Stated(setOf(License.Mit)),
        LicenseState.Stated(setOf(License.Mit)),
    )

    /** 58 keys, Pro Micro socket, a 5x6 matrix per half, ZMK only, MX and Choc sockets. */
    fun lily58(
        wiring: Recorded<KeyWiring> = Recorded.Known(KeyWiring.Matrix(PinCount(5), PinCount(6), DiodeDirection.ColToRow)),
        mounts: Recorded<Set<SwitchMount>> = Recorded.Known(setOf(SwitchMount.ChocSocket, SwitchMount.MxSocket)),
        controller: Controller = Controller.Socket(ControllerFootprint.ProMicro),
        body: Body = Body.Split(SplitHalves(2), Recorded.Unknown, Recorded.Unknown),
        boardSize: Recorded<BoardSize> = Recorded.Unknown,
        drawn: Recorded<BoardDrawn> = Recorded.Unknown,
    ) = Design(
        core = DesignCore(
            name = DesignName("Lily58 Pro"),
            slug = Slug("lily58-pro"),
            sources = setOf(SourceRef("https://github.com/kata0510/lily58")),
            firmware = Recorded.Known(setOf(FirmwareRef.Zmk("lily58"))),
            layouts = setOf(Layout(KeyCount(58))),
            switches = setOf(SwitchFootprint.Mx, SwitchFootprint.ChocV1),
            buildMethod = BuildMethod.Pcb(),
            controller = controller,
            body = body,
            licensing = mit,
        ),
        extras = DesignExtras(wiring = wiring, switchMounts = mounts, powerSwitch = Recorded.Known(false),
                              boardSize = boardSize, boardDrawn = drawn),
    )

    /** The two exclusions `seed/firmwares.toml` holds: no QMK on nRF52, no ZMK on AVR. */
    val rules = FirmwareRules.of(listOf(
        FirmwareSupport("qmk", setOf(McuFamily.Avr8, McuFamily.Rp2040), setOf(McuFamily.Nrf52), Recorded.Known(false)),
        FirmwareSupport("zmk", setOf(McuFamily.Nrf52, McuFamily.Rp2040), setOf(McuFamily.Avr8), Recorded.Known(true)),
    ))

    val niceNanoBoard = ControllerBoard("nice!nano v2", "nice-nano-v2", ControllerFootprint.ProMicro, true,
        Recorded.Known(McuFamily.Nrf52), setOf("zmk"), null)
    val proMicroBoard = ControllerBoard("Pro Micro", "pro-micro", ControllerFootprint.ProMicro, false,
        Recorded.Known(McuFamily.Avr8), setOf("qmk"), null)

    val mxSocket = Part(Slug("kailh-mx-hotswap-socket"), "Kailh MX hotswap socket",
        Fitting.SwitchSocket(setOf(SwitchMount.MxSocket)), mpn = "CPG151101S11")
    val chocSocket = Part(Slug("kailh-choc-hotswap-socket"), "Kailh Choc V1 hotswap socket",
        Fitting.SwitchSocket(setOf(SwitchMount.ChocSocket)), mpn = "CPG135001S30")
    val sod123f = Part(Slug("1n4148w-sod123f"), "1N4148W diode, SMD",
        Fitting.SwitchDiode(Recorded.Known(DiodePackage.Sod123F)))
    val sod123 = Part(Slug("1n4148w-sod123"), "1N4148W diode, SOD-123",
        Fitting.SwitchDiode(Recorded.Known(DiodePackage.Sod123)))
    /** Switches (#115): one MX sold singly, one Choc V1 in tens, and one the vendor does not type. */
    val gateronCj = Part(Slug("gateron-cj"), "Gateron CJ", Fitting.Switch(setOf(SwitchFootprint.Mx)),
        switch = SwitchSpec(Recorded.Known(SwitchKind.Linear)))
    val chocPink = Part(Slug("kailh-choc-pink"), "Kailh Choc Pink", Fitting.Switch(setOf(SwitchFootprint.ChocV1)),
        switch = SwitchSpec(Recorded.Known(SwitchKind.Linear)))
    val alpsSwitch = Part(Slug("alps-white"), "Alps White", Fitting.Switch(setOf(SwitchFootprint.Alps)),
        switch = SwitchSpec(Recorded.Unknown))
    val niceNano = Part.devBoard(Slug("nice-nano-v2"), niceNanoBoard)
    val proMicro = Part.devBoard(Slug("pro-micro"), proMicroBoard)

    val kit = Kit(
        slug = Slug("42keebs-lily58-pro"), name = "Lily58 Pro Hotswap Kit", shop = "42keebs",
        url = "https://42keebs.eu/shop/kits/pro-micro-based/lily58-pro-hotswap-split-ergo-50-kit/",
        basedOn = Slug("lily58-pro"), readOn = LocalDate.of(2026, 10, 4),
        contents = listOf(
            KitLine(Recorded.Known(2), "main PCBs", NeedKind.Pcb),
            KitLine(Recorded.Known(58), "MX or Choc hotswap sockets, chosen at checkout", NeedKind.SwitchSockets),
            KitLine(Recorded.Known(60), "SMD diodes, package not stated", NeedKind.Diodes),
            KitLine(Recorded.Known(2), "SMD power switch", null),
        ),
        needs = listOf(
            Requirement(Fitting.ControllerSocket(setOf(ControllerFootprint.ProMicro)), Recorded.Known(2)),
            Requirement(Fitting.Switch(setOf(SwitchFootprint.Mx, SwitchFootprint.ChocV1)), Recorded.Known(58)),
        ),
        notPriced = listOf("TRRS cable (for a wired build)"),
    )

    val products: List<Product> = listOf(mxSocket, chocSocket, sod123f, niceNano, proMicro, gateronCj, chocPink, alpsSwitch, kit)

    val shop = Shop("42keebs", "42. Keebs", "EUR", pricesIncludeVat = false, vat = mapOf("NO" to 25))
    /** A second shop, prices with VAT in, for a reader's shop choice (#115). */
    val otherShop = Shop("splitkb", "splitkb.com", "EUR", pricesIncludeVat = true, vat = emptyMap())
    val norway = Region("NO")
    val now: Instant = Instant.parse("2026-10-04T12:00:00Z")

    fun listing(ref: String, product: Part, pack: Int = 1, shop: String = "42keebs") =
        Listing(shop, ref, product.slug, null, pack, if (shop == "42keebs") "https://42keebs.eu/$ref" else "https://$shop.example/$ref")

    fun offer(listing: Listing, netMinor: Long, stock: Stock, age: Duration = Duration.ofHours(1)) =
        Offer(listing, Observation(listing.shop, listing.ref, now - age, netMinor, "EUR", stock))

    val kitListing = Listing("42keebs", "6241", kit.slug, "MX, black", 1, kit.url)

    /** Today's prices, as the plan read them: the MX socket is the only thing in stock. */
    val today: List<Offer> = listOf(
        offer(listing("855", mxSocket, pack = 10), 150, Stock.InStock),
        offer(listing("6198", chocSocket, pack = 10), 150, Stock.OutOfStock),
        offer(listing("7336", sod123f, pack = 10), 25, Stock.InStock),
        offer(listing("4808", niceNano), 2070, Stock.Backorder),
        offer(listing("382", proMicro), 580, Stock.OutOfStock),
        offer(listing("26354", gateronCj), 50, Stock.InStock),
        offer(listing("9012", chocPink, pack = 10), 490, Stock.InStock),
        offer(listing("9999", alpsSwitch), 40, Stock.InStock),
    )

    /** The kit's own price today, MX black: 35.00 net. Not in [today], so a kit starts unpriced. */
    val kitInStock: Offer = offer(kitListing, 3500, Stock.InStock)

    fun grid(
        design: Design = lily58(),
        diodePackage: Recorded<DiodePackage> = Recorded.Known(DiodePackage.Sod123),
        products: List<Product> = this.products,
        offers: List<Offer> = today,
        listings: List<Listing> = offers.map { it.listing },
        picks: Map<NeedKind, Pick> = emptyMap(),
    ) = buildList(design, diodePackage, products, listOf(shop, otherShop), listings, offers, norway, now, rules, picks)

    fun BuildList.row(kind: NeedKind): Row = rows.single { it.kind == kind }

    /** The self-sourced line of a row whose design need is a buy: its offers, in order, and its default. */
    fun BuildList.line(kind: NeedKind): Line = row(kind).let { Line(it.need, it.offers, it.unseen) }

    /** Column 0 is "Buy it yourself"; 1 is the first kit. */
    fun BuildList.cell(kind: NeedKind, column: Int = 0): Cell = row(kind).cells[column]
}
