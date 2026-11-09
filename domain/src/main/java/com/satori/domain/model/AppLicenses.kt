package com.satori.domain.model

/**
 * Static registry of all third-party licenses that Satori must acknowledge.
 *
 * Ordered by legal urgency:
 * 1. Gemma – custom Google terms, NOTICE text is contractually required.
 * 2. Parakeet – CC-BY 4.0, attribution is legally required.
 * 3. Parakeet v2 – CC-BY 4.0; a speech model for English, offered in Advanced settings.
 * 4. Cohere Transcribe – Apache 2.0; a speech model for English and German, offered there too.
 * 5. Qwen – Apache 2.0; the experimental AI model offered in Advanced settings.
 * 6. Gemma 4 – Apache 2.0; the AI model for finished recordings offered in Advanced settings.
 * 7. AI4Bharat IndicConformer – MIT; the Malayalam speech model offered in Advanced settings.
 * 8. AI4Bharat IndicTrans2 – MIT; the reader that a Malayalam recording's AI steps read.
 * 9. Silero VAD – MIT; the voice detection model bundled in the app.
 * 10. Sherpa-ONNX – Apache 2.0.
 * 11. llama.cpp – MIT.
 * 12. ONNX Runtime – MIT.
 * 13. SentencePiece – Apache 2.0, with Abseil's line and the BSD notices of the libraries it builds in.
 * 14. Doto – SIL Open Font License 1.1 (bundled font).
 * 15. Space Grotesk – SIL Open Font License 1.1 (bundled font).
 * 16. GeoNames – CC-BY 4.0; the bundled place names of "Save the place", attribution required.
 */
object AppLicenses {

    val ALL: List<LicenseEntry> = listOf(

        // Gemma and Parakeet are open on arrival because their terms ask for visible attribution.
        // Their licence address and model card are the whole entry: no full text to repeat them.
        LicenseEntry(
            id = "gemma",
            componentName = "Google Gemma 3",
            licenseType = "Gemma Terms of Use",
            shortNotice = "",
            fullText = "",
            primaryUrl = "https://ai.google.dev/gemma/terms",
            secondaryUrl = "https://huggingface.co/google/gemma-3-1b-it",
            expandedByDefault = true
        ),

        LicenseEntry(
            id = "parakeet",
            componentName = "NVIDIA Parakeet TDT",
            licenseType = "Creative Commons Attribution 4.0 (CC BY 4.0)",
            shortNotice = "",
            fullText = "",
            primaryUrl = "https://creativecommons.org/licenses/by/4.0/",
            secondaryUrl = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3",
            expandedByDefault = true
        ),

        // The speech models Advanced settings offers for a better transcript after Stop, downloaded
        // on the phone as sherpa-onnx conversions (csukuangfj, csukuangfj2 on Hugging Face) under
        // the licences of the originals. Parakeet v2's terms ask for attribution as v3's do, so it
        // is open on arrival too. Cohere's copyright line names its publisher on Hugging Face; no
        // year is given, as none was read off it.
        LicenseEntry(
            id = "parakeet_v2",
            componentName = "NVIDIA Parakeet TDT 0.6B v2",
            licenseType = "Creative Commons Attribution 4.0 (CC BY 4.0)",
            shortNotice = "",
            fullText = "",
            primaryUrl = "https://creativecommons.org/licenses/by/4.0/",
            secondaryUrl = "https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2",
            expandedByDefault = true
        ),

        LicenseEntry(
            id = "cohere_transcribe",
            componentName = "Cohere Transcribe",
            licenseType = "Apache License 2.0",
            shortNotice = "© Cohere Labs. Licensed under the Apache License, Version 2.0.",
            fullText = apacheLicense("Cohere Labs"),
            primaryUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            secondaryUrl = "https://huggingface.co/CohereLabs/cohere-transcribe-03-2026"
        ),

        // The experimental AI model in Advanced settings. Licence and copyright line are those of
        // the model's repository (huggingface.co/Qwen/Qwen3.5-0.8B/blob/main/LICENSE).
        LicenseEntry(
            id = "qwen",
            componentName = "Alibaba Qwen 3.5",
            licenseType = "Apache License 2.0",
            shortNotice = "© 2026 Alibaba Cloud. Licensed under the Apache License, Version 2.0.",
            fullText = apacheLicense("2026 Alibaba Cloud"),
            primaryUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            secondaryUrl = "https://huggingface.co/Qwen/Qwen3.5-0.8B"
        ),

        // The model for finished recordings in Advanced settings, under Apache 2.0 unlike Gemma 3
        // above. The address is the repository the app downloads it from; no year is given, as
        // none was read off it.
        LicenseEntry(
            id = "gemma4",
            componentName = "Google Gemma 4",
            licenseType = "Apache License 2.0",
            shortNotice = "© Google LLC. Licensed under the Apache License, Version 2.0.",
            fullText = apacheLicense("Google LLC"),
            primaryUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            secondaryUrl = "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf"
        ),

        // The Malayalam speech model, downloaded on the phone as the community export
        // csndhanasekar/sherpa-onnx-indicconformer-ml-int8 at the commit ModelConfig pins, whose
        // LICENSE says "IndicConformer by AI4Bharat, MIT License." without a copyright line. The
        // line is that of AI4Bharat's own IndicConformer repository (its LICENSE, read 2026-10-16).
        LicenseEntry(
            id = "indicconformer",
            componentName = "AI4Bharat IndicConformer (Malayalam)",
            licenseType = "MIT License",
            shortNotice = "© 2024 AI4Bhārat. Licensed under the MIT License.",
            fullText = mitLicense("2024 AI4Bhārat"),
            primaryUrl = "https://github.com/AI4Bharat/IndicConformerASR/blob/master/LICENSE",
            secondaryUrl = "https://huggingface.co/ai4bharat/indicconformer_stt_ml_hybrid_ctc_rnnt_large",
            githubUrl = "https://github.com/AI4Bharat/IndicConformerASR"
        ),

        // The reader that Malayalam recordings' AI steps read, downloaded on the phone as the ONNX
        // export hari31416/indictrans2-indic-en-dist-200M-ONNX-int8 at the commit ModelConfig pins
        // (ReaderModels). The export's card gives the MIT License and no copyright line; the line is
        // that of AI4Bharat's IndicTrans2 repository (its LICENSE, read 2026-10-19).
        LicenseEntry(
            id = "indictrans2",
            componentName = "AI4Bharat IndicTrans2",
            licenseType = "MIT License",
            shortNotice = "© AI4Bharat. Licensed under the MIT License. " +
                    "Converted to ONNX by hari31416, under the MIT License.",
            fullText = mitLicense("AI4Bharat."),
            primaryUrl = "https://github.com/AI4Bharat/IndicTrans2/blob/main/LICENSE",
            secondaryUrl = "https://huggingface.co/ai4bharat/indictrans2-indic-en-dist-200M",
            githubUrl = "https://github.com/AI4Bharat/IndicTrans2"
        ),

        // The voice detection model in the app's assets (silero_vad.onnx), which splits speech into
        // lines. Licence and copyright line of its repository (its LICENSE, read 2026-10-16).
        LicenseEntry(
            id = "silero_vad",
            componentName = "Silero VAD",
            licenseType = "MIT License",
            shortNotice = "© 2020-present Silero Team. Licensed under the MIT License.",
            fullText = mitLicense("2020-present Silero Team"),
            primaryUrl = "https://github.com/snakers4/silero-vad/blob/master/LICENSE",
            githubUrl = "https://github.com/snakers4/silero-vad"
        ),

        LicenseEntry(
            id = "sherpa_onnx",
            componentName = "Sherpa-ONNX",
            licenseType = "Apache License 2.0",
            shortNotice = "© 2022–2024 k2-fsa / Next-gen Kaldi authors. " +
                    "Licensed under the Apache License, Version 2.0.",
            fullText = apacheLicense("2022-2024 k2-fsa / Next-gen Kaldi authors"),
            primaryUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            githubUrl = "https://github.com/k2-fsa/sherpa-onnx"
        ),

        LicenseEntry(
            id = "llama_cpp",
            componentName = "llama.cpp",
            licenseType = "MIT License",
            shortNotice = "© 2023–2024 The ggml authors. Licensed under the MIT License.",
            fullText = """
                MIT License

                Copyright (c) 2023-2024 The ggml authors

                Permission is hereby granted, free of charge, to any person obtaining a copy
                of this software and associated documentation files (the "Software"), to deal
                in the Software without restriction, including without limitation the rights
                to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
                copies of the Software, and to permit persons to whom the Software is
                furnished to do so, subject to the following conditions:

                The above copyright notice and this permission notice shall be included in all
                copies or substantial portions of the Software.

                THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
                IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
                FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
                AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
                LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
                OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
                SOFTWARE.
            """.trimIndent(),
            primaryUrl = "https://github.com/ggml-org/llama.cpp/blob/master/LICENSE",
            githubUrl = "https://github.com/ggml-org/llama.cpp"
        ),

        LicenseEntry(
            id = "onnx_runtime",
            componentName = "ONNX Runtime",
            licenseType = "MIT License",
            shortNotice = "© Microsoft Corporation. All rights reserved. " +
                    "Licensed under the MIT License.",
            fullText = """
                MIT License

                Copyright (c) Microsoft Corporation. All rights reserved.

                Permission is hereby granted, free of charge, to any person obtaining a copy
                of this software and associated documentation files (the "Software"), to deal
                in the Software without restriction, including without limitation the rights
                to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
                copies of the Software, and to permit persons to whom the Software is
                furnished to do so, subject to the following conditions:

                The above copyright notice and this permission notice shall be included in all
                copies or substantial portions of the Software.

                THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
                IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
                FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
                AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
                LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
                OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
                SOFTWARE.
            """.trimIndent(),
            primaryUrl = "https://github.com/microsoft/onnxruntime/blob/main/LICENSE",
            githubUrl = "https://github.com/onnx/onnx"
        ),

        // The tokenizer the reader's native library is built with (lib-reader-onnx), SentencePiece
        // 0.2.1 as third_party/sentencepiece pins it. Its LICENSE is the Apache text without a
        // copyright line and it has no NOTICE file; the line is its sources' headers'. Its library
        // compiles in its third_party/ (read 2026-10-19): a subset of Abseil under the same
        // licence, whose strings headers carry the line of The Abseil Authors (its flags and
        // containers carry SentencePiece's own), and protobuf-lite and Darts-clone, whose licences
        // ask that a binary carry their notices. Those lines and notices follow its own.
        LicenseEntry(
            id = "sentencepiece",
            componentName = "SentencePiece",
            licenseType = "Apache License 2.0",
            shortNotice = "© 2016 Google Inc. Licensed under the Apache License, Version 2.0. " +
                    "Includes parts of Abseil (© 2017 The Abseil Authors), under the same licence, " +
                    "and protobuf-lite and Darts-clone, under the BSD License.",
            fullText = apacheLicense("2016 Google Inc.") + "\n\n" + sentencePieceThirdParty(),
            primaryUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            githubUrl = "https://github.com/google/sentencepiece"
        ),

        LicenseEntry(
            id = "doto",
            componentName = "Doto",
            licenseType = "SIL Open Font License 1.1",
            shortNotice = DOTO_COPYRIGHT,
            fullText = silOpenFontLicense(
                copyright = DOTO_COPYRIGHT,
                faqUrl = "https://openfontlicense.org"
            ),
            primaryUrl = "https://openfontlicense.org",
            githubUrl = "https://github.com/oliverlalan/Doto"
        ),

        LicenseEntry(
            id = "space_grotesk",
            componentName = "Space Grotesk",
            licenseType = "SIL Open Font License 1.1",
            shortNotice = SPACE_GROTESK_COPYRIGHT,
            fullText = silOpenFontLicense(
                copyright = SPACE_GROTESK_COPYRIGHT,
                faqUrl = "http://scripts.sil.org/OFL"
            ),
            primaryUrl = "https://openfontlicense.org",
            githubUrl = "https://github.com/floriankarsten/space-grotesk"
        ),

        // The list of places that names the place of a recording (tools/gazetteer). The first
        // lines are the licence statement and disclaimer GeoNames puts on its data
        // (download.geonames.org/export/dump/readme.txt); CC BY 4.0 also asks to say what changed.
        LicenseEntry(
            id = "geonames",
            componentName = "GeoNames",
            licenseType = "Creative Commons Attribution 4.0 (CC BY 4.0)",
            shortNotice = "Place names from GeoNames, https://www.geonames.org",
            fullText = """
                This work is licensed under a Creative Commons Attribution 4.0 License,
                see https://creativecommons.org/licenses/by/4.0/
                The Data is provided "as is" without warranty or any representation of accuracy, timeliness or completeness.

                Satori uses the names, positions, countries and populations of the places in
                cities500 for Germany, Austria, Switzerland, the Netherlands, Belgium, Luxembourg,
                France, Denmark, Poland, the Czech Republic and India, and in cities15000 for the
                rest of the world, without historical, abandoned and destroyed places, as GeoNames
                published them on 12 October 2026.
            """.trimIndent(),
            primaryUrl = "https://creativecommons.org/licenses/by/4.0/"
        )
    )

    /**
     * The Apache License 2.0 notice as its authors attach it to a project: the copyright line
     * (`Copyright` and then [copyright]) and the terms.
     */
    private fun apacheLicense(copyright: String): String = """
        Apache License
        Version 2.0, January 2004
        http://www.apache.org/licenses/

        Copyright $copyright

        Licensed under the Apache License, Version 2.0 (the "License");
        you may not use this file except in compliance with the License.
        You may obtain a copy of the License at

            http://www.apache.org/licenses/LICENSE-2.0

        Unless required by applicable law or agreed to in writing, software
        distributed under the License is distributed on an "AS IS" BASIS,
        WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
        See the License for the specific language governing permissions and
        limitations under the License.
    """.trimIndent()

    /**
     * The MIT License as its authors attach it to a project: the copyright line (`Copyright (c)`
     * and then [copyright]) and the terms.
     */
    private fun mitLicense(copyright: String): String = """
        MIT License

        Copyright (c) $copyright

        Permission is hereby granted, free of charge, to any person obtaining a copy
        of this software and associated documentation files (the "Software"), to deal
        in the Software without restriction, including without limitation the rights
        to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
        copies of the Software, and to permit persons to whom the Software is
        furnished to do so, subject to the following conditions:

        The above copyright notice and this permission notice shall be included in all
        copies or substantial portions of the Software.

        THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
        IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
        FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
        AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
        LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
        OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
        SOFTWARE.
    """.trimIndent()

    /**
     * What SentencePiece compiles into its own library, as its 0.2.1 ships it in its third_party/:
     * Abseil's copyright line (its terms are the Apache text above), protobuf-lite's LICENSE and
     * Darts-clone's (its lines wrapped).
     */
    private fun sentencePieceThirdParty(): String = """
        SentencePiece's library includes parts of Abseil, under the Apache License, Version 2.0:

        Copyright 2017 The Abseil Authors.

        It also includes protobuf-lite:

        Copyright 2008 Google Inc.  All rights reserved.

        Redistribution and use in source and binary forms, with or without
        modification, are permitted provided that the following conditions are
        met:

            * Redistributions of source code must retain the above copyright
        notice, this list of conditions and the following disclaimer.
            * Redistributions in binary form must reproduce the above
        copyright notice, this list of conditions and the following disclaimer
        in the documentation and/or other materials provided with the
        distribution.
            * Neither the name of Google Inc. nor the names of its
        contributors may be used to endorse or promote products derived from
        this software without specific prior written permission.

        THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
        "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
        LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
        A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
        OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
        SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
        LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
        DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
        THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
        (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
        OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

        Code generated by the Protocol Buffer compiler is owned by the owner
        of the input file used when generating it.  This code is not
        standalone and requires a support library to be linked with it.  This
        support library is itself covered by the above license.

        and Darts-clone:

        Copyright (c) 2008-2011, Susumu Yata
        All rights reserved.

        Redistribution and use in source and binary forms, with or without modification,
        are permitted provided that the following conditions are met:

        - Redistributions of source code must retain the above copyright notice, this
        list of conditions and the following disclaimer.
        - Redistributions in binary form must reproduce the above copyright notice, this
        list of conditions and the following disclaimer in the documentation and/or
        other materials provided with the distribution.
        - Neither the name of the <ORGANIZATION> nor the names of its contributors may
        be used to endorse or promote products derived from this software without
        specific prior written permission.

        THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
        ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
        WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
        DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
        ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
        (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
        LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
        ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
        (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
        SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
    """.trimIndent()

    private const val DOTO_COPYRIGHT =
        "Copyright 2024 The Doto Project Authors (https://github.com/oliverlalan/Doto)"

    private const val SPACE_GROTESK_COPYRIGHT =
        "Copyright 2020 The Space Grotesk Project Authors (https://github.com/floriankarsten/space-grotesk)"

    /**
     * The SIL Open Font License 1.1 as shipped with each bundled font: the font's copyright
     * line, where to find the licence and its FAQ, then the licence text itself.
     */
    private fun silOpenFontLicense(copyright: String, faqUrl: String): String = """
        $copyright

        This Font Software is licensed under the SIL Open Font License, Version 1.1.
        This license is copied below, and is also available with a FAQ at:
        $faqUrl


        -----------------------------------------------------------
        SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007
        -----------------------------------------------------------

        PREAMBLE
        The goals of the Open Font License (OFL) are to stimulate worldwide
        development of collaborative font projects, to support the font creation
        efforts of academic and linguistic communities, and to provide a free and
        open framework in which fonts may be shared and improved in partnership
        with others.

        The OFL allows the licensed fonts to be used, studied, modified and
        redistributed freely as long as they are not sold by themselves. The
        fonts, including any derivative works, can be bundled, embedded, 
        redistributed and/or sold with any software provided that any reserved
        names are not used by derivative works. The fonts and derivatives,
        however, cannot be released under any other type of license. The
        requirement for fonts to remain under this license does not apply
        to any document created using the fonts or their derivatives.

        DEFINITIONS
        "Font Software" refers to the set of files released by the Copyright
        Holder(s) under this license and clearly marked as such. This may
        include source files, build scripts and documentation.

        "Reserved Font Name" refers to any names specified as such after the
        copyright statement(s).

        "Original Version" refers to the collection of Font Software components as
        distributed by the Copyright Holder(s).

        "Modified Version" refers to any derivative made by adding to, deleting,
        or substituting -- in part or in whole -- any of the components of the
        Original Version, by changing formats or by porting the Font Software to a
        new environment.

        "Author" refers to any designer, engineer, programmer, technical
        writer or other person who contributed to the Font Software.

        PERMISSION & CONDITIONS
        Permission is hereby granted, free of charge, to any person obtaining
        a copy of the Font Software, to use, study, copy, merge, embed, modify,
        redistribute, and sell modified and unmodified copies of the Font
        Software, subject to the following conditions:

        1) Neither the Font Software nor any of its individual components,
        in Original or Modified Versions, may be sold by itself.

        2) Original or Modified Versions of the Font Software may be bundled,
        redistributed and/or sold with any software, provided that each copy
        contains the above copyright notice and this license. These can be
        included either as stand-alone text files, human-readable headers or
        in the appropriate machine-readable metadata fields within text or
        binary files as long as those fields can be easily viewed by the user.

        3) No Modified Version of the Font Software may use the Reserved Font
        Name(s) unless explicit written permission is granted by the corresponding
        Copyright Holder. This restriction only applies to the primary font name as
        presented to the users.

        4) The name(s) of the Copyright Holder(s) or the Author(s) of the Font
        Software shall not be used to promote, endorse or advertise any
        Modified Version, except to acknowledge the contribution(s) of the
        Copyright Holder(s) and the Author(s) or with their explicit written
        permission.

        5) The Font Software, modified or unmodified, in part or in whole,
        must be distributed entirely under this license, and must not be
        distributed under any other license. The requirement for fonts to
        remain under this license does not apply to any document created
        using the Font Software.

        TERMINATION
        This license becomes null and void if any of the above conditions are
        not met.

        DISCLAIMER
        THE FONT SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
        EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF
        MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
        OF COPYRIGHT, PATENT, TRADEMARK, OR OTHER RIGHT. IN NO EVENT SHALL THE
        COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
        INCLUDING ANY GENERAL, SPECIAL, INDIRECT, INCIDENTAL, OR CONSEQUENTIAL
        DAMAGES, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
        FROM, OUT OF THE USE OR INABILITY TO USE THE FONT SOFTWARE OR FROM
        OTHER DEALINGS IN THE FONT SOFTWARE.
    """.trimIndent()
}
