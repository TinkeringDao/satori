package com.satori.ui.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The debug screen's place (0.8.0-dev2): a task of its own, kept out of Recents, so an adb start of
 * a tool never lands in Satori's task, which the launcher and the quick key bring up; and no such
 * screen in the manifest a release build has.
 */
class DebugManifestTest {

    private fun activities(sourceSet: String): List<Element> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val nodes = factory.newDocumentBuilder().parse(File("src/$sourceSet/AndroidManifest.xml")).getElementsByTagName("activity")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.android(name: String): String = getAttributeNS(ANDROID, name)

    @Test
    fun `the debug screen has a task of its own and stays out of Recents`() {
        val screen = activities("debug").single { it.android("name") == "com.satori.ui.debug.DebugToolsActivity" }

        // Not the package's own affinity, Satori's task, and none another screen of the app has.
        assertEquals("com.satori.debugtools", screen.android("taskAffinity"))
        assertEquals("true", screen.android("excludeFromRecents"))
        assertEquals("true", screen.android("exported"))
        assertTrue(activities("main").none { it.android("taskAffinity") == "com.satori.debugtools" })
    }

    @Test
    fun `a release build's manifest has no debug screen`() {
        val main = activities("main").map { it.android("name") }

        assertTrue(main.toString(), main.isNotEmpty() && main.none { "debug" in it.lowercase() })
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
