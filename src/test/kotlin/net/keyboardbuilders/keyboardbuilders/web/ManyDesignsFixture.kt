package net.keyboardbuilders.keyboardbuilders.web

import net.keyboardbuilders.keyboardbuilders.catalog.ControllerCatalog
import net.keyboardbuilders.keyboardbuilders.catalog.DesignCatalog
import net.keyboardbuilders.keyboardbuilders.domain.Body
import net.keyboardbuilders.keyboardbuilders.domain.BuildMethod
import net.keyboardbuilders.keyboardbuilders.domain.Controller
import net.keyboardbuilders.keyboardbuilders.domain.ControllerFootprint
import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.DesignCore
import net.keyboardbuilders.keyboardbuilders.domain.DesignName
import net.keyboardbuilders.keyboardbuilders.domain.FirmwareRef
import net.keyboardbuilders.keyboardbuilders.domain.KeyCount
import net.keyboardbuilders.keyboardbuilders.domain.Layout
import net.keyboardbuilders.keyboardbuilders.domain.License
import net.keyboardbuilders.keyboardbuilders.domain.LicenseState
import net.keyboardbuilders.keyboardbuilders.domain.Licensing
import net.keyboardbuilders.keyboardbuilders.domain.Recorded
import net.keyboardbuilders.keyboardbuilders.domain.Slug
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import net.keyboardbuilders.keyboardbuilders.domain.SplitHalves
import net.keyboardbuilders.keyboardbuilders.domain.SwitchFootprint
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.io.ByteArrayResource
import tools.jackson.databind.ObjectMapper

/**
 * 236 synthetic designs, `board-1` to `board-236`, and two fixed dev boards. Shared by
 * [SeoTest] and [PaginationTest] through `@Import`, so the two run in one Spring context
 * instead of two (#114). Each used to carry its own copy; they differed only in the
 * body, which neither class asserts on.
 */
@TestConfiguration
class ManyDesignsFixture {
    @Bean
    @Primary
    fun catalog(): DesignCatalog = object : DesignCatalog {
        private val designs = (1..236).map { design(it) }
        override fun all() = designs
        override fun bySlug(slug: String) = designs.firstOrNull { it.core.slug.value == slug }
    }

    /**
     * FIXED, like the designs above, and for the same reason: `/parts/dev-board/:slug`
     * is in the sitemap now, so the real `seed/controllers.json` would make
     * `SeoTest`'s sitemap count a statement about how much controller research has
     * been done. Two boards, because one could not catch a sitemap that listed the
     * first and stopped.
     */
    @Bean
    @Primary
    fun fixedControllers(mapper: ObjectMapper) = ControllerCatalog(
        mapper,
        ByteArrayResource(
            """[{"name":"nice!nano v2","slug":"nice-nano-v2","footprint":"pro_micro","wireless":true,"mcu_family":"nrf52","firmware":["zmk"]},
                {"name":"Elite-Pi","slug":"elite-pi","footprint":"pro_micro","wireless":false,"mcu_family":"rp2040","firmware":["qmk"]}]"""
                .toByteArray()
        ),
    )

    private fun design(n: Int) = Design(
        core = DesignCore(
            name = DesignName("Board $n"),
            slug = Slug("board-$n"),
            sources = setOf(SourceRef("https://github.com/example/board$n")),
            firmware = Recorded.Known(setOf(FirmwareRef.Qmk("example/board$n"))),
            layouts = setOf(Layout(KeyCount(42))),
            switches = setOf(SwitchFootprint.Mx),
            buildMethod = BuildMethod.Pcb(),
            controller = Controller.Socket(ControllerFootprint.ProMicro),
            body = Body.Split(halves = SplitHalves(2), reversible = Recorded.Unknown, links = Recorded.Unknown),
            licensing = Licensing(
                hardware = LicenseState.Stated(setOf(License.Mit)),
                firmware = LicenseState.Stated(setOf(License.Mit)),
                documentation = LicenseState.Unstated,
            ),
        ),
    )
}
