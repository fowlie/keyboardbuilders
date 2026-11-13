package net.keyboardbuilders.keyboardbuilders.web

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

/**
 * Production never sets `keyboardbuilders.parts.enabled`, so a push that carried the build
 * list by accident would serve it nowhere. This is that context: the property off, no route.
 * The test config turns it on for every other class, so this one has a context of its own.
 */
@SpringBootTest(properties = ["keyboardbuilders.parts.enabled=false"])
@AutoConfigureMockMvc
class BuildListDisabledTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `without the property the build list is a 404`() {
        assertEquals(404, mockMvc.get("/designs/lily58-pro/build").andReturn().response.status)
        assertEquals(200, mockMvc.get("/designs/lily58-pro").andReturn().response.status)
    }
}
