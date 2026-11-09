package com.satori.domain.usecase.llm

import com.satori.domain.fakes.FakeResourceProvider
import com.satori.domain.provider.ResourceProvider
import com.satori.domain.repository.OutputGrammar
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * [FakeResourceProvider] with the JSON keys the app ships for each language, German ones for
 * "de", so a test can follow the language from the prompt into the grammar and the parser.
 */
class LocalizedResourceProvider : ResourceProvider by FakeResourceProvider() {
    override fun getJsonFieldTitle(localeCode: String) = if (german(localeCode)) "titel" else "title"
    override fun getJsonFieldPoints(localeCode: String) = if (german(localeCode)) "punkte" else "points"
    override fun getJsonFieldSummary(localeCode: String) = if (german(localeCode)) "zusammenfassung" else "summary"
    override fun getJsonFieldActionItems(localeCode: String) = if (german(localeCode)) "aktionen" else "action_items"

    private fun german(localeCode: String) = localeCode.startsWith("de")
}

/** The JSON schema a notes call was held to; fails for a call without one. */
fun OutputGrammar?.schema(): JsonObject =
    Json.parseToJsonElement((this as OutputGrammar.JsonSchema).schema).jsonObject

/** The keys of the answer a notes call was held to, in the order the model writes them. */
fun OutputGrammar?.answerKeys(): List<String> = schema().getValue("properties").jsonObject.keys.toList()
