package com.satori.domain.usecase.mindmap

import com.satori.domain.model.MindNode
import com.satori.domain.model.MindNodeKind
import com.satori.domain.usecase.mindmap.ThemeMatcher.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeMatcherTest {

    private var clock = 0L

    private fun theme(id: String, label: String, key: String = ThemeMatcher.keyOf(label)) =
        MindNode(id, MindNodeKind.THEME, label, key, createdAt = clock++, updatedAt = 0L)

    private fun topic(id: String, theme: MindNode, label: String) =
        MindNode(id, MindNodeKind.TOPIC, label, ThemeMatcher.keyOf(label), parentId = theme.id, createdAt = clock++, updatedAt = 0L)

    private fun ThemeMatcher.placeOfLabel(label: String): Place = placeOf(ThemeMatcher.keyOf(label))

    @Test
    fun `a key is the label's content stems, each once and in order, whatever their form`() {
        assertEquals("gard shed", ThemeMatcher.keyOf("The garden shed"))
        assertEquals("gard shed", ThemeMatcher.keyOf("Sheds in the garden"))
        assertEquals(ThemeMatcher.keyOf("Gartenhaus"), ThemeMatcher.keyOf("Gartenhäuser"))
        assertEquals(ThemeMatcher.keyOf("Küche"), ThemeMatcher.keyOf("KÜCHEN"))
        assertEquals("", ThemeMatcher.keyOf("Of the"))
    }

    @Test
    fun `a topic said again goes onto the topic with its key`() {
        val heating = theme("t1", "Heating")
        val repair = topic("p1", heating, "Heating repair")
        val matcher = ThemeMatcher(listOf(heating, repair))

        assertEquals(Place.OnTopic(repair, heating), matcher.placeOfLabel("The heating repairs"))
    }

    @Test
    fun `a theme whose first topic is gone takes its key back as a new topic`() {
        val shed = theme("t1", "Garden shed")
        val roof = topic("p1", shed, "Shed roof")
        val matcher = ThemeMatcher(listOf(shed, roof))

        assertEquals(Place.UnderTheme(shed), matcher.placeOfLabel("Garden shed"))
    }

    @Test
    fun `half the content words in common is one theme`() {
        val shed = theme("t1", "Garden shed")
        val heizung = theme("t2", "Heizung")
        val kitchen = theme("t3", "Küche")
        val matcher = ThemeMatcher(listOf(shed, heizung, kitchen))

        assertEquals(Place.UnderTheme(shed), matcher.placeOfLabel("Garden"))
        assertEquals(Place.UnderTheme(heizung), matcher.placeOfLabel("Reparatur der Heizung"))
        assertEquals(Place.UnderTheme(kitchen), matcher.placeOfLabel("Neue Küchen"))
    }

    @Test
    fun `a shared word no other theme has joins the theme`() {
        val repair = theme("t1", "Heating repair")
        val matcher = ThemeMatcher(listOf(repair, theme("t2", "Rent")))

        assertEquals(Place.UnderTheme(repair), matcher.placeOfLabel("Heating costs"))
    }

    @Test
    fun `a shared word that only names an occasion or a kind of note joins nothing`() {
        val matcher = ThemeMatcher(listOf(theme("t1", "Team meeting"), theme("t2", "Termin beim Zahnarzt")))

        assertEquals(Place.NewTheme, matcher.placeOfLabel("Meeting with the bank"))
        assertEquals(Place.NewTheme, matcher.placeOfLabel("Termin mit der Bank"))
    }

    @Test
    fun `a shared word that only says what was done, paid or held joins nothing`() {
        val matcher = ThemeMatcher(
            listOf(theme("t1", "Heating repair"), theme("t2", "Heating costs"), theme("t3", "Birthday party"), theme("t4", "Auto Rechnung"))
        )

        assertEquals(Place.NewTheme, matcher.placeOfLabel("Car repair"))
        assertEquals(Place.NewTheme, matcher.placeOfLabel("Rent costs"))
        assertEquals(Place.NewTheme, matcher.placeOfLabel("Office party"))
        assertEquals(Place.NewTheme, matcher.placeOfLabel("Zahnarzt Rechnung"))
    }

    @Test
    fun `a generic word still counts in the Jaccard index`() {
        val meeting = theme("t1", "Team meeting")
        val matcher = ThemeMatcher(listOf(meeting))

        assertEquals(Place.UnderTheme(meeting), matcher.placeOfLabel("Meeting"))
    }

    @Test
    fun `a word two themes share is not rare`() {
        val matcher = ThemeMatcher(listOf(theme("t1", "Heating repair"), theme("t2", "Heating oil")))

        assertEquals(Place.NewTheme, matcher.placeOfLabel("Heating bills"))
    }

    @Test
    fun `the theme with the higher Jaccard index wins`() {
        val tiles = theme("t1", "Roof tiles")
        val shed = theme("t2", "Garden shed")
        val matcher = ThemeMatcher(listOf(tiles, shed))

        assertEquals(Place.UnderTheme(shed), matcher.placeOfLabel("Garden shed roof"))
    }

    @Test
    fun `a tie goes to the theme listed first, the older one`() {
        val anna = theme("t1", "Anna")
        val berlin = theme("t2", "Berlin")

        assertEquals(Place.UnderTheme(anna), ThemeMatcher(listOf(anna, berlin)).placeOfLabel("Anna in Berlin"))
        assertEquals(Place.UnderTheme(berlin), ThemeMatcher(listOf(berlin, anna)).placeOfLabel("Anna in Berlin"))
    }

    @Test
    fun `nodes added while a recording is mapped are found by its later topics`() {
        val matcher = ThemeMatcher(emptyList())
        assertEquals(Place.NewTheme, matcher.placeOfLabel("Garden shed"))

        val shed = theme("t1", "Garden shed")
        val first = topic("p1", shed, "Garden shed")
        matcher.add(shed)
        matcher.add(first)

        assertEquals(Place.OnTopic(first, shed), matcher.placeOfLabel("garden sheds"))
        assertEquals(Place.UnderTheme(shed), matcher.placeOfLabel("Shed paint"))
    }

    @Test
    fun `a key stored in another form still matches`() {
        val shed = theme("t1", "Garden shed", key = "garden sheds")

        assertEquals(Place.UnderTheme(shed), ThemeMatcher(listOf(shed)).placeOfLabel("Garden shed"))
    }

    @Test
    fun `nothing in common, or a key without words, is a new theme`() {
        val matcher = ThemeMatcher(listOf(theme("t1", "Garden shed")))

        assertEquals(Place.NewTheme, matcher.placeOfLabel("Rent"))
        assertEquals(Place.NewTheme, matcher.placeOf(""))
    }
}
