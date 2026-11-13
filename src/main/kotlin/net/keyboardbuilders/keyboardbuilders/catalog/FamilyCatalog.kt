package net.keyboardbuilders.keyboardbuilders.catalog

import net.keyboardbuilders.keyboardbuilders.domain.FamilyName
import net.keyboardbuilders.keyboardbuilders.domain.SourceRef
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue

/** One confirmed family: its name, and the source a person cited when ruling on it. */
data class FamilyRecord(val name: FamilyName, val source: SourceRef) {
    val slug: String get() = name.slug.value
}

/**
 * The confirmed families, for `/designs/families/{slug}` -- backlog #107. Built by
 * `scripts/build_seed.py` from `seed/families.toml` into `seed/families.json`.
 *
 * NAME AND SOURCE ONLY, NEVER THE MEMBERS. Each design already carries its own `family`
 * in `designs.json`, and that is what the design page's family line reads. A member list
 * here would be a second answer to "which boards are the Corne", free to disagree with
 * the first after a half-finished rebuild; reading membership off the designs makes the
 * two pages agree by construction. `FamilyCatalogTest` holds the two files to each other.
 *
 * ITS OWN COMPONENT, NOT A FIELD OF `DesignCatalog`, so a test that swaps in a fixture
 * design catalog still boots: those families simply have no members in the fixture, and
 * a family with fewer than two members has no page.
 *
 * Loading is eager and throws on two families at one slug -- "Bad Wings" and "Bad-Wings"
 * would share `/designs/families/bad-wings`, and which one a reader got would depend on file
 * order. Refusing to start is the build refusing, since every test that boots the app
 * fails with it.
 */
@Component
class FamilyCatalog(
    mapper: ObjectMapper,
    @Value("classpath:seed/families.json") resource: Resource,
) {
    private val families: List<FamilyRecord> =
        resource.inputStream.use { mapper.readValue<List<SeedFamily>>(it) }
            .map { FamilyRecord(FamilyName(it.name), SourceRef(it.source)) }
            .sortedBy { it.name.value.lowercase() }

    private val bySlug: Map<String, FamilyRecord> = families.groupBy { it.slug }.mapValues { (slug, same) ->
        check(same.size == 1) {
            "families ${same.map { it.name.value }} share the slug '$slug' -- rename one in seed/families.toml"
        }
        same.single()
    }.onEach { (slug, _) ->
        // `/designs/{slug}/compatible` outranks `/designs/families/{slug}` in Spring (the
        // longer literal wins), so this family's page could never be reached.
        check(slug != "compatible") {
            "a family slugged '$slug' is shadowed by /designs/{slug}/$slug -- rename it in seed/families.toml"
        }
    }

    /** Alphabetical by name, the order the `/designs/families` index lists them in. */
    fun all(): List<FamilyRecord> = families

    fun bySlug(slug: String): FamilyRecord? = bySlug[slug]
}

/** The wire format, `{"name": "Corne", "source": "https://github.com/foostan"}`. */
internal data class SeedFamily(val name: String, val source: String)
