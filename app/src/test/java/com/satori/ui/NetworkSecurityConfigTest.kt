package com.satori.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The network policy: HTTPS only in a release build; a debug build lets plain HTTP through to the
 * emulator's test server at 10.0.2.2 alone, so the first run's download tests can use it (design
 * 5.0), and to nothing else.
 */
class NetworkSecurityConfigTest {

    private fun config(sourceSet: String): Element = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/$sourceSet/res/xml/network_security_config.xml")).documentElement

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    @Test
    fun `a release build permits no cleartext at all`() {
        val main = config("main")

        assertEquals("false", main.children("base-config").single().getAttribute("cleartextTrafficPermitted"))
        assertTrue(main.children("domain-config").isEmpty())
    }

    @Test
    fun `a debug build permits it to the emulator's test server alone`() {
        val debug = config("debug")

        assertEquals("false", debug.children("base-config").single().getAttribute("cleartextTrafficPermitted"))
        val cleartext = debug.children("domain-config").single()
        assertEquals("true", cleartext.getAttribute("cleartextTrafficPermitted"))
        val domain = cleartext.children("domain").single()
        assertEquals("10.0.2.2", domain.textContent.trim())
        assertEquals("false", domain.getAttribute("includeSubdomains"))
    }
}
