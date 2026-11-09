package com.satori.regex

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.satori.data.provider.AndroidResourceProvider
import com.satori.domain.language.WordClasses
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.SessionWithDetails
import com.satori.domain.model.TranscriptionSession
import com.satori.domain.usecase.llm.PromptRules
import com.satori.ui.export.MarkdownExport
import dalvik.system.DexFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Array as Arrays
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.util.IdentityHashMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Every regular expression of the app's code compiled on Android's regex engine, ICU, and what
 * needs Android to run (0.7.4). ICU rejects some patterns that the JVM, on which every unit test
 * runs, takes: on 2026-10-19 PromptRules' `\{[a-z_]+}`, with a bare "}", failed the class's start on
 * the phone, and with it every AI call that checks its answer, while all 3,640 unit tests passed.
 *
 * The scan starts every class of the app's own code (com.satori, com.arm.aichat, and com.k2fsa of
 * its lib-sherpa-onnx, which holds none today) that keeps a pattern in a static field: a Regex or
 * a Pattern, a list, map or array of them, a lazy one, or an object of the app's that holds one,
 * such as ExplicitTodoSpotter's cues. Starting the class compiles its patterns; a class that can't
 * start, or a lazy one that throws, fails the test with what it threw, all of them listed. Each
 * pattern found goes to logcat (tag IcuRegexTest) with its class and field. Only static fields:
 * [RegexRunsTest], which runs here too, runs each class once besides, which compiles the patterns
 * built as it works, so a pattern built in a function or kept in an instance field needs a run
 * there.
 *
 * Part of the release check (docs/DEV_SETUP.md): the debug APK and this test APK on an emulator.
 */
class IcuRegexTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun the_engine_is_icu() {
        // The JVM reads a bare "}" as a letter; ICU rejects it. Without ICU this check checks nothing.
        assertThrows(PatternSyntaxException::class.java) { Pattern.compile("a}") }
    }

    @Test
    fun every_regex_of_the_apps_code_compiles_on_icu() {
        @Suppress("DEPRECATION")
        val names = DexFile(context.packageCodePath).entries().toList().filter { name -> PACKAGES.any(name::startsWith) }
        val found = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (name in names) {
            val type = try {
                Class.forName(name, false, context.classLoader)
            } catch (e: LinkageError) {
                // A class the APK can't load at all runs nowhere; none of the app's is one.
                failed += "$name could not be loaded: ${causes(e)}"
                continue
            }
            val fields = staticFields(type) ?: continue
            if (fields.none { mayHoldRegex(it.genericType, mutableSetOf()) }) continue
            try {
                Class.forName(name, true, context.classLoader)
            } catch (e: Throwable) {
                failed += "$name could not start: ${causes(e)}"
                continue
            }
            for (field in fields) {
                field.isAccessible = true
                collect(field.get(null), "${type.name}.${field.name}", 0, IdentityHashMap(), found, failed)
            }
        }
        found.forEach { Log.i(TAG, it) }
        Log.i(TAG, "${found.size} patterns of ${names.size} classes compiled on ICU")
        assertEquals("Classes that failed on ICU:\n" + failed.joinToString("\n"), emptyList<String>(), failed)
        // The scan reached what it must: a companion's pattern, a lazy one, and one in an object of a list.
        assertTrue(found.any { it.startsWith("com.satori.domain.usecase.llm.PromptRules.PLACEHOLDER: ") })
        assertTrue(found.any { it.startsWith("com.satori.domain.usecase.todo.TodoGrounding.ZU_INFINITIVE\$delegate") })
        assertTrue(found.any { it.startsWith("com.satori.domain.usecase.todo.ExplicitTodoSpotter.CUES[") })
        assertTrue("Only ${found.size} patterns found", found.size >= MIN_PATTERNS)
    }

    /**
     * The prompts the app ships, as aapt2 compiled them, held to some of the cases of the domain's
     * PromptRulesTest, which reads the same strings on the JVM: the same answers on ICU.
     */
    @Test
    fun the_shipped_prompts_rules_read_as_on_the_jvm() {
        val prompts = AndroidResourceProvider(context)
        for (mode in RecordingMode.entries.filter { it != RecordingMode.REAL_TIME_TRANSLATION }) {
            val english = PromptRules.of(prompts.getPromptForMode(mode, "en"))
            for (rule in listOf("Keep it concise.", "No filler words.", "One idea per point.", "Plain statements.")) {
                assertTrue("$mode: $rule", english.echoes(rule))
            }
            assertFalse(mode.name, english.echoes("Keep the receipts for the tax return."))
            val german = PromptRules.of(prompts.getPromptForMode(mode, "de"))
            assertTrue(mode.name, german.echoes("Keine Füllwörter."))
            assertFalse(mode.name, german.echoes("Die Belege für die Steuer aufheben."))
        }
        // The to-dos' rules hold the header's, whose placeholders ("{noted}") are left out: 0.7.3's bare "}".
        val todos = PromptRules.of(prompts.getTodoExtractionPrompt("en"), prompts.getTodoHeaderTemplate("en"))
        assertTrue(todos.echoes("Never make up a time."))
        assertFalse(todos.echoes("Call the bank about the loan."))
        val name = PromptRules.of(prompts.getSessionTitlePrompt("en"))
        assertTrue(name.echoes("Subject Line of an Email"))
        assertFalse(name.echoes("Garden Shed Repair"))
        val topics = PromptRules.of(prompts.getMindTopicsPrompt("en"))
        assertTrue(topics.echoes("Use only what the text says."))
        assertFalse(topics.echoes("Book the car service before the holidays."))
        assertTrue(PromptRules.of(prompts.getMindSummaryPrompt("en")).echoes("Never report on the recordings."))
    }

    /**
     * [WordClasses.NOT_A_WORD_CHAR] is ICU's \W spelled out, which the JVM reads alike: here it
     * takes the very characters ICU's \W takes, every code point, and so with IGNORE_CASE, as
     * TodoGate's key of the to-do answer compiles it.
     */
    @Test
    fun the_spelled_out_non_word_class_is_icus_own() {
        for (options in listOf(emptySet(), setOf(RegexOption.IGNORE_CASE))) {
            val icu = Regex("""\W""", options).toPattern().matcher("")
            val spelled = Regex(WordClasses.NOT_A_WORD_CHAR, options).toPattern().matcher("")
            val differ = mutableListOf<String>()
            var checked = 0
            var taken = 0
            for (point in 0..Character.MAX_CODE_POINT) {
                if (point in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code) continue
                val char = String(Character.toChars(point))
                val nonWord = icu.reset(char).matches()
                if (nonWord != spelled.reset(char).matches()) differ += "U+%04X".format(point)
                checked++
                if (nonWord) taken++
            }
            assertEquals(options.toString(), emptyList<String>(), differ.take(20))
            // Not a check of nothing: \W takes some code points, and not all of them.
            assertTrue("$options: \\W took $taken of $checked", taken in 1 until checked)
        }
    }

    @Test
    fun a_file_name_is_made_of_a_recordings_name() {
        val session = TranscriptionSession(id = "s1", name = "Budget: Q4 / review?", createdAt = 0L, lastModifiedAt = 0L)
        val stem = MarkdownExport.fileStem(context, SessionWithDetails(session, emptyList(), emptyList()))
        assertTrue(stem, stem.endsWith(" Budget Q4 review"))
    }

    /**
     * Whether a field of [type] can hold a pattern: one, a collection, map, pair, array or lazy of
     * them (one whose contents its type doesn't say too), or a class of the app's that holds one.
     */
    private fun mayHoldRegex(type: Type, seen: MutableSet<Class<*>>): Boolean = when (type) {
        is Class<*> -> when {
            type == Regex::class.java || type == Pattern::class.java -> true
            type.isArray -> mayHoldRegex(type.componentType!!, seen)
            isContainer(type) -> true
            PACKAGES.any(type.name::startsWith) && seen.add(type) -> instanceFields(type).any { mayHoldRegex(it.genericType, seen) }
            else -> false
        }
        is ParameterizedType -> {
            val raw = type.rawType as Class<*>
            val held = type.actualTypeArguments.any { mayHoldRegex(it, seen) }
            if (isContainer(raw)) held else held || mayHoldRegex(raw, seen)
        }
        is GenericArrayType -> mayHoldRegex(type.genericComponentType, seen)
        is WildcardType -> type.upperBounds.any { mayHoldRegex(it, seen) }
        else -> false
    }

    private fun isContainer(type: Class<*>): Boolean = CONTAINERS.any { it.isAssignableFrom(type) }

    /**
     * The patterns in [value], reached from a static field as [path]: inside lazies, collections,
     * maps, arrays and the app's objects. A lazy one that throws as it is read goes to [failed],
     * and the scan goes on, so that every class that fails is listed.
     */
    private fun collect(value: Any?, path: String, depth: Int, seen: IdentityHashMap<Any, Unit>, out: MutableList<String>, failed: MutableList<String>) {
        if (value == null || depth > MAX_DEPTH || seen.put(value, Unit) != null) return
        when {
            value is Regex -> out += "$path: ${value.pattern}"
            value is Pattern -> out += "$path: ${value.pattern()}"
            value is Lazy<*> -> {
                val held = try {
                    value.value
                } catch (e: Throwable) {
                    failed += "$path could not be read: ${causes(e)}"
                    return
                }
                collect(held, path, depth + 1, seen, out, failed)
            }
            value is Map<*, *> -> value.entries.forEachIndexed { i, (k, v) ->
                collect(k, "$path{$i}", depth + 1, seen, out, failed)
                collect(v, "$path[$k]", depth + 1, seen, out, failed)
            }
            value is Iterable<*> -> value.forEachIndexed { i, item -> collect(item, "$path[$i]", depth + 1, seen, out, failed) }
            value.javaClass.isArray && !value.javaClass.componentType!!.isPrimitive ->
                for (i in 0 until Arrays.getLength(value)) collect(Arrays.get(value, i), "$path[$i]", depth + 1, seen, out, failed)
            value is Pair<*, *> -> {
                collect(value.first, "$path.first", depth + 1, seen, out, failed)
                collect(value.second, "$path.second", depth + 1, seen, out, failed)
            }
            PACKAGES.any(value.javaClass.name::startsWith) -> for (field in instanceFields(value.javaClass)) {
                field.isAccessible = true
                collect(field.get(value), "$path.${field.name}", depth + 1, seen, out, failed)
            }
        }
    }

    private fun staticFields(type: Class<*>): List<Field>? = try {
        type.declaredFields.filter { Modifier.isStatic(it.modifiers) && !it.type.isPrimitive && it.type != String::class.java }
    } catch (e: LinkageError) {
        // A field of a type the APK leaves out holds no pattern of the app's.
        null
    }

    private fun instanceFields(type: Class<*>): List<Field> = try {
        type.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive && it.type != String::class.java }
    } catch (e: LinkageError) {
        emptyList()
    }

    private fun causes(e: Throwable): String = generateSequence(e) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }

    private companion object {
        const val TAG = "IcuRegexTest"
        val PACKAGES = listOf("com.satori.", "com.arm.aichat.", "com.k2fsa.")
        val CONTAINERS = listOf(Iterable::class.java, Map::class.java, Lazy::class.java, Pair::class.java)

        /** The app has 170 patterns in static fields (0.7.4); far fewer means the scan missed them. */
        const val MIN_PATTERNS = 150
        const val MAX_DEPTH = 6
    }
}
