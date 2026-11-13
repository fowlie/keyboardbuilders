package net.keyboardbuilders.keyboardbuilders.catalog

import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRules
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareSupport
import net.keyboardbuilders.keyboardbuilders.domain.McuFamily
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * `seed/firmwares.toml` -> [FirmwareRules]. Four rows: what each firmware family runs on,
 * what it demonstrably cannot, and whether it does wireless.
 *
 * IN `catalog` AND NOT `web`, unlike `ControllerCatalog` was until 2026-09-10: this is
 * read by `SeedImporter` in `persistence` as well as by the web layer, and
 * `persistence -> web` would invert the one-directional layering this package keeps.
 * `ControllerCatalog` moved here in the same session for the same reason.
 *
 * SIBLING OF `ControllerCatalog`, deliberately built the same way -- one `Resource`, one
 * `readValue`, no logic. The rules themselves live in `domain`; this only loads them.
 */
@Component
class FirmwareCatalog(
    mapper: ObjectMapper,
    @Value("classpath:seed/firmwares.json") resource: Resource,
) {
    private val entries: List<FirmwareSupport> =
        resource.inputStream.use { mapper.readValue<List<SeedFirmwareRow>>(it) }.map { it.toDomain() }

    val rules: FirmwareRules = FirmwareRules.of(entries)

    /** For the part pages, which name a firmware rather than reason about it. */
    fun all(): List<FirmwareSupport> = entries
}

/**
 * `...Row` rather than `SeedFirmware`, because `SeedDesignCatalog` already has a
 * `SeedFirmware` in this package and it is a genuinely different thing: a DESIGN'S
 * REFERENCE to a firmware (family plus a shield or a keyboard path) against this file's
 * FIRMWARE'S OWN CAPABILITIES. The compiler caught the clash; the names should keep the
 * distinction visible without it.
 */
private data class SeedFirmwareRow(
    val family: String,
    val name: String,
    /** Snake case to match the JSON keys verbatim, as `SeedControllerBoard` does. */
    val mcu_families_supported: List<String>,
    val mcu_families_excluded: List<String>,
    /** `"yes"` / `"no"` / `"unknown"`, never a bool -- see [toWireless]. */
    val wireless: String,
    val source: String? = null,
) {
    fun toDomain() = FirmwareSupport(
        family = family,
        supported = mcu_families_supported.map { it.toMcuFamily() }.toSet(),
        excluded = mcu_families_excluded.map { it.toMcuFamily() }.toSet(),
        wireless = wireless.toWireless(),
    )
}

/**
 * Non-null [SeedFirmwareRow.wireless] plus an `else` that throws, rather than a nullable
 * field defaulting to `false`.
 *
 * A GUESSED `false` HERE DOES NOT BLANK A PAGE, which is why it must be impossible: it
 * quietly moves designs out of `wireless` and the site still reads correctly. `unknown` is
 * a stated third state (`bluemicro` is in it) and reaches a reader as "we do not know".
 * `build_controllers.py` refuses anything outside these three on the way in; this is the
 * second gate, for a hand-edited JSON.
 */
private fun String.toWireless(): Recorded<Boolean> = when (this) {
    "yes" -> Recorded.Known(true)
    "no" -> Recorded.Known(false)
    "unknown" -> Recorded.Unknown
    else -> error("unknown wireless state '$this' in seed/firmwares.json")
}

/** Shared spelling with `seed/controllers.toml`'s `mcu_family`; same `else`, same reason:
 *  a typo that became `Unknown` would silently return ruled-out designs to the tier above
 *  it, which reads as a working page. */
private fun String.toMcuFamily(): McuFamily = when (this) {
    "avr8" -> McuFamily.Avr8
    "rp2040" -> McuFamily.Rp2040
    "nrf52" -> McuFamily.Nrf52
    "stm32" -> McuFamily.Stm32
    "samd21" -> McuFamily.Samd21
    else -> error("unknown mcu family '$this' in seed/firmwares.json")
}
