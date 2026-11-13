package net.keyboardbuilders.keyboardbuilders.persistence

import net.keyboardbuilders.keyboardbuilders.web.SlugRenames
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Runs the import when the application starts. Forgejo #15.
 *
 * WHY EVERY START AND NOT ONLY WHEN EMPTY. The deployed database is a freshly built file
 * every time (facet backfill (ticket 13)
 * decision 8 makes the aliveness cron a rebuild), so a database that matches the seed is
 * the only state worth being in. Importing only into an empty database would leave a
 * developer whose seed changed staring at yesterday's corpus, which is the drift this
 * whole design avoids by rebuilding.
 *
 * That is only safe because the import is idempotent, and that property has its own test
 * rather than being assumed here.
 *
 * OFF UNLESS ASKED, and on in the `dev` profile. Two reasons, and the second was found
 * by a test rather than reasoned out. Production receives a database built on the dev's
 * machine and shipped whole, so importing at startup there would rebuild what was
 * already built. And left on by default this fired inside every `@SpringBootTest`,
 * writing the seed into whatever database that test happened to use — a side effect of
 * running the suite.
 *
 * Set `keyboardbuilders.import-on-startup=false` to inspect a database without it being
 * rewritten under you, which is the one case where "always rebuild" is the wrong answer.
 */
@Component
@ConditionalOnProperty(
    name = ["keyboardbuilders.import-on-startup"],
    havingValue = "true",
    matchIfMissing = false,
)
class ImportOnStartup(
    private val importer: SeedImporter,
    private val hearts: Hearts,
    private val renames: SlugRenames,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val started = System.nanoTime()
        val imported = importer.importAll()
        val millis = (System.nanoTime() - started) / 1_000_000
        // One line, at INFO, because it is a fact about the data the site is serving and
        // the dev log is deliberately short enough for it to be read.
        log.info(
            "Imported {} designs and {} facets in {} ms",
            imported.designs, imported.facets, millis,
        )
        // HEARTS ARE KEYED BY SLUG, and the corpus renames slugs. After every import, each
        // former slug's hearts move to the slug it is now, so a renamed design keeps them.
        val carried = hearts.carry(renames.all())
        if (carried > 0) log.info("Carried hearts across {} renamed slugs", carried)
    }
}
