package com.satori.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLicensesTest {

    private fun entry(id: String) = AppLicenses.ALL.first { it.id == id }

    @Test
    fun `every component is listed once`() {
        val ids = AppLicenses.ALL.map { it.id }

        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `the page lists what Satori is built with, and no entry for the app itself`() {
        assertEquals(
            listOf(
                "gemma", "parakeet", "parakeet_v2", "cohere_transcribe", "qwen", "gemma4", "indicconformer",
                "indictrans2", "silero_vad", "sherpa_onnx", "llama_cpp", "onnx_runtime", "sentencepiece", "doto",
                "space_grotesk", "geonames"
            ),
            AppLicenses.ALL.map { it.id }
        )
    }

    @Test
    fun `the Malayalam model and the voice detection model have their MIT licences`() {
        val malayalam = entry("indicconformer")
        val silero = entry("silero_vad")

        for (model in listOf(malayalam, silero)) {
            assertEquals("MIT License", model.licenseType)
            assertTrue(model.componentName, model.fullText.startsWith("MIT License"))
            assertTrue(model.componentName, "Permission is hereby granted, free of charge" in model.fullText)
        }
        // The copyright lines as their repositories' LICENSE files give them.
        assertTrue("Copyright (c) 2024 AI4Bhārat" in malayalam.fullText)
        assertTrue("Copyright (c) 2020-present Silero Team" in silero.fullText)
        assertEquals("https://huggingface.co/ai4bharat/indicconformer_stt_ml_hybrid_ctc_rnnt_large", malayalam.secondaryUrl)
        assertEquals("https://github.com/snakers4/silero-vad", silero.githubUrl)
        // The MIT text is the same as llama.cpp's, but for its copyright line.
        assertEquals(
            entry("llama_cpp").fullText.substringAfter("Permission"),
            silero.fullText.substringAfter("Permission")
        )
    }

    @Test
    fun `the reader has IndicTrans2's MIT licence and names the export it is downloaded as`() {
        val reader = entry("indictrans2")

        assertEquals("MIT License", reader.licenseType)
        assertTrue(reader.fullText.startsWith("MIT License\n\nCopyright (c) AI4Bharat.\n"))
        assertEquals(
            entry("llama_cpp").fullText.substringAfter("Permission"),
            reader.fullText.substringAfter("Permission")
        )
        assertTrue("hari31416" in reader.shortNotice && "MIT License" in reader.shortNotice)
        assertEquals("https://huggingface.co/ai4bharat/indictrans2-indic-en-dist-200M", reader.secondaryUrl)
        // The app never says that a Malayalam recording's notes are English: the entry names no language.
        assertTrue("English" !in reader.componentName && "Malayalam" !in reader.componentName)
    }

    @Test
    fun `SentencePiece has the Apache notice, then the BSD notices of what it builds in`() {
        val sentencePiece = entry("sentencepiece")

        assertEquals("Apache License 2.0", sentencePiece.licenseType)
        assertTrue(sentencePiece.fullText.startsWith("Apache License\nVersion 2.0, January 2004"))
        assertTrue("Copyright 2016 Google Inc." in sentencePiece.fullText)
        // Its absl subset's strings headers are Abseil's, under the same Apache terms.
        assertTrue("\nCopyright 2017 The Abseil Authors.\n" in sentencePiece.fullText)
        assertTrue("© 2017 The Abseil Authors" in sentencePiece.shortNotice)
        assertTrue("Copyright 2008 Google Inc.  All rights reserved." in sentencePiece.fullText)
        assertTrue("Copyright (c) 2008-2011, Susumu Yata" in sentencePiece.fullText)
        assertEquals(2, Regex("Redistributions in binary form must reproduce").findAll(sentencePiece.fullText).count())
        assertEquals(
            entry("sherpa_onnx").fullText.substringAfter("Licensed under the Apache License").trim(),
            sentencePiece.fullText.substringAfter("Licensed under the Apache License")
                .substringBefore("SentencePiece's library includes").trim()
        )
    }

    @Test
    fun `the experimental Qwen model has its licence`() {
        val qwen = entry("qwen")

        assertEquals("Apache License 2.0", qwen.licenseType)
        assertTrue("Alibaba Cloud" in qwen.fullText)
        assertTrue("Alibaba Cloud" in qwen.shortNotice)
        assertTrue(qwen.secondaryUrl.orEmpty().startsWith("https://huggingface.co/Qwen/"))
    }

    @Test
    fun `Gemma 4, the model for finished recordings, has its Apache licence and its repository`() {
        val gemma4 = entry("gemma4")

        assertEquals("Apache License 2.0", gemma4.licenseType)
        assertTrue("Google LLC" in gemma4.fullText)
        assertEquals("https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf", gemma4.secondaryUrl)
    }

    @Test
    fun `the speech models after Stop have their licences and model cards`() {
        val parakeetV2 = entry("parakeet_v2")
        val cohere = entry("cohere_transcribe")

        assertEquals("Creative Commons Attribution 4.0 (CC BY 4.0)", parakeetV2.licenseType)
        assertEquals("https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2", parakeetV2.secondaryUrl)
        assertEquals("Apache License 2.0", cohere.licenseType)
        assertTrue("Cohere Labs" in cohere.fullText)
        assertEquals("https://huggingface.co/CohereLabs/cohere-transcribe-03-2026", cohere.secondaryUrl)
    }

    @Test
    fun `only Gemma and the Parakeet models open on arrival, each showing its addresses once`() {
        val expanded = AppLicenses.ALL.filter { it.expandedByDefault }

        assertEquals(listOf("gemma", "parakeet", "parakeet_v2"), expanded.map { it.id })
        // Their full text used to repeat the licence address that is shown above it.
        expanded.forEach { assertEquals("", it.fullText) }
    }

    @Test
    fun `the other entries still carry their full licence text`() {
        AppLicenses.ALL.filterNot { it.id in setOf("gemma", "parakeet", "parakeet_v2") }
            .forEach { assertTrue(it.componentName, it.fullText.isNotBlank()) }
    }

    @Test
    fun `the Apache notice is the same for every Apache component`() {
        val tails = listOf("sherpa_onnx", "qwen", "gemma4", "cohere_transcribe").map { id ->
            entry(id).fullText.substringAfter("Licensed under the Apache License")
        }

        assertEquals(1, tails.map { it.trim() }.distinct().size)
        assertFalse(tails.first().isBlank())
    }

    @Test
    fun `the Apache notice keeps the licence address indented under its sentence`() {
        listOf("sherpa_onnx", "qwen", "gemma4", "cohere_transcribe").forEach { id ->
            assertTrue(
                id,
                "You may obtain a copy of the License at\n\n" +
                    "    http://www.apache.org/licenses/LICENSE-2.0\n\n" +
                    "Unless required by applicable law" in entry(id).fullText
            )
        }
    }

    @Test
    fun `GeoNames is credited with the licence statement and disclaimer of its data`() {
        val geonames = entry("geonames")

        assertEquals("Creative Commons Attribution 4.0 (CC BY 4.0)", geonames.licenseType)
        assertEquals("https://creativecommons.org/licenses/by/4.0/", geonames.primaryUrl)
        assertTrue("https://www.geonames.org" in geonames.shortNotice)
        assertTrue(
            geonames.fullText.startsWith(
                "This work is licensed under a Creative Commons Attribution 4.0 License,\n" +
                    "see https://creativecommons.org/licenses/by/4.0/\n" +
                    "The Data is provided \"as is\" without warranty or any representation of accuracy, " +
                    "timeliness or completeness."
            )
        )
        assertTrue("cities500" in geonames.fullText && "cities15000" in geonames.fullText)
    }

    @Test
    fun `every entry has a licence address`() {
        AppLicenses.ALL.forEach { assertTrue(it.componentName, it.primaryUrl.startsWith("https://")) }
    }
}
