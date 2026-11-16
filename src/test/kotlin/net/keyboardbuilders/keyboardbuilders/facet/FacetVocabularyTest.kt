package net.keyboardbuilders.keyboardbuilders.facet

import net.keyboardbuilders.keyboardbuilders.domain.FacetKey
import net.keyboardbuilders.keyboardbuilders.persistence.SeedImporter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals

/**
 * Every token the importer writes has words in its facet. Before each facet owned its own
 * vocabulary, the rail's label `when`s ended in `else -> value`, so a token added on the
 * projection side and forgotten on the rail rendered raw (`choc_v1`) with nothing failing.
 * Run against the real seed, because that is where a new token first appears.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FacetVocabularyTest {

    @Autowired lateinit var importer: SeedImporter
    @Autowired lateinit var db: JdbcClient

    @Test
    fun `every token the seed projects has a label in its facet`() {
        importer.importAll()
        val unlabelled = db.sql("SELECT DISTINCT key, value FROM design_facet")
            .query { rs, _ -> rs.getString("key") to rs.getString("value") }
            .list()
            .filter { (key, value) -> FacetKey.of(key)!!.facet.label(value) == null }
        assertEquals(emptyList(), unlabelled)
    }

    @Test
    fun `each key names its own facet`() {
        FacetKey.entries.forEach { assertEquals(it, it.facet.key) }
    }
}
