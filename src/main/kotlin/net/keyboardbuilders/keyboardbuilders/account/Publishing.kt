package net.keyboardbuilders.keyboardbuilders.account

import net.keyboardbuilders.keyboardbuilders.persistence.SeedImporter
import net.keyboardbuilders.keyboardbuilders.persistence.Unpublished
import org.springframework.stereotype.Component

/**
 * Takes a design off the site, or puts it back (#116). Who may do it is the controller's
 * question; this records it and re-runs the import, which is what removes the design from
 * the facet counts and search. The import takes about two seconds and runs on a click a
 * few designers a month will make, so rebuilding beats a second, partial write path.
 *
 * SYNCHRONIZED because two imports at once would both open with `DELETE FROM design`.
 */
@Component
class Publishing(private val unpublished: Unpublished, private val importer: SeedImporter) {

    @Synchronized
    fun unpublish(slug: String, by: String) {
        unpublished.add(slug, by)
        importer.importAll()
    }

    @Synchronized
    fun publish(slug: String) {
        unpublished.remove(slug)
        importer.importAll()
    }
}
