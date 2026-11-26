package net.keyboardbuilders.keyboardbuilders.catalog

import net.keyboardbuilders.keyboardbuilders.domain.Design
import net.keyboardbuilders.keyboardbuilders.domain.corrected
import net.keyboardbuilders.keyboardbuilders.persistence.Corrections
import net.keyboardbuilders.keyboardbuilders.persistence.Unpublished
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import tools.jackson.databind.ObjectMapper

/**
 * The designs the site shows: the seed, less the ones their author unpublished (#116), with
 * the applied corrections over it (2026-11-26).
 *
 * ASKS THE TABLES ON EVERY CALL rather than caching, so an author's click takes effect on
 * the next page view with nothing to invalidate. They hold a handful of rows.
 */
class PublishedDesigns(
    private val seed: DesignCatalog,
    private val unpublished: Unpublished,
    private val corrections: Corrections,
) : DesignCatalog {

    override fun all(): List<Design> {
        val hidden = unpublished.all()
        val fixes = corrections.applied()
        val shown = if (hidden.isEmpty()) seed.all() else seed.all().filter { it.core.slug.value !in hidden }
        if (fixes.isEmpty()) return shown
        val touched = fixes.map { it.slug }.toSet()
        return shown.map { if (it.core.slug.value in touched) it.corrected(fixes) else it }
    }

    override fun bySlug(slug: String): Design? =
        seed.bySlug(slug)?.takeUnless { unpublished.contains(slug) }?.corrected(corrections.applied())
}

/**
 * TWO CATALOGUES, AND ONLY ONE IS THE DEFAULT. Pages get [PublishedDesigns]. The whole seed
 * is `@Qualifier(SEED)` and never injected by type alone (`defaultCandidate = false`), for
 * the parts catalogue: it checks its kits against designs once, at boot, and a kit based on
 * a design its author later unpublished must not stop the app from starting.
 *
 * Test fixtures that declare their own `@Primary` `DesignCatalog` still win over both.
 */
@Configuration
class DesignCatalogs {

    @Bean(defaultCandidate = false)
    @Qualifier(SEED)
    fun seedDesigns(
        mapper: ObjectMapper,
        @Value("classpath:seed/designs.json") resource: Resource,
    ): SeedDesignCatalog = SeedDesignCatalog(mapper, resource)

    @Bean
    fun designCatalog(@Qualifier(SEED) seed: SeedDesignCatalog, unpublished: Unpublished, corrections: Corrections): DesignCatalog =
        PublishedDesigns(seed, unpublished, corrections)

    companion object {
        const val SEED = "seed"
    }
}
