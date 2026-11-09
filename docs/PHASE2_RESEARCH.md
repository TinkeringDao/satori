# Phase 2 research: memory bank, mental map, to-dos and reminders

**Scope.** What Satori's Phase 2 should build, and from which open-source parts:
- a memory bank modelled on [Hindsight][hs], with core and recent memory;
- a searchable "mental map of thoughts" in which every memory links back to the transcript segments it came from;
- to-dos from summarized voice notes, and reminders for them.

This is the product Phase 2 from the project notes. It is unrelated to the "Phase 0-3" of the NPU plan in `docs/NPU_RESEARCH.md`.

**Method.**
- Research date 2026-10-10. Five research angles ran in parallel: memory layers and knowledge graphs; voice-note and meeting apps; PKM and mind-map tools; on-device Android building blocks; EN+DE to-do and reminder extraction with Android alarms.
- A separate verifier checked each angle against primary sources: repositories, licence files, docs and papers. Where researcher and verifier disagreed, this report uses the verifier's correction.
- Read-only. Nothing was installed, built or run. "Last activity" is the newest commit or release seen on 2026-10-10.
- docs.letta.com and docs.cognee.ai were not reachable from the development machine, so the Letta and Cognee details come from their GitHub sources.
- A separate model study ran on the same day (in the project notes). Where it bears on Phase 2 (which LLM and embedder, grammar style, real memory use on the current llama.cpp), this report uses its findings and marks them "model study".

**Conventions.**
- Paths are relative to the repo root. Data-layer files are under `data/src/main/java/com/satori/data/`.
- "Est." marks an estimate. "UNVERIFIED" marks a claim that no primary source confirmed or that nobody has tested on the razr.
- "Lead check" marks a fact that the lead researcher checked in this repo or in a primary document while writing this report.
- Sections 3 to 8 are recommendations. The facts they rest on are cited; the rest is reasoning.

**Already decided** (project notes, 2026-10-10):
- Voice notes, not only meetings, detect to-dos. To-dos appear on the home screen and are pushed as reminders. Omi is the design reference.
- A power-key quick key and recording categories are also in scope.
- Today only the Meeting modes extract to-dos, and they show only inside the recording.

## 1. Short answer

- **One database, no new engine.** Build the memory bank inside Satori's Room database. Move Room 2.6.1 to 2.8.x on the androidx `BundledSQLiteDriver`, which bundles a SQLite with FTS5 ([androidx][androidx-sqlite]). Vectors go in BLOB columns searched by brute force, with [sqlite-vec][sqlite-vec] as an option. The map is an edge table, and timestamps are indexed. A personal bank holds 10^3 to 10^5 items, where a flat scan costs a few ms (Est.). Skip graph databases (Kuzu is archived) and HNSW indexes.
- **Port Hindsight's design, sized for a phone.** Hindsight's parts map onto Satori like this: recording = document; transcript segment = chunk; extracted facts = memory units; consolidated observations carry proof counts and quotes; mental models = core-memory pages, read straight from the database. Hindsight is MIT, so its prompts and recall algorithm can be ported with the notice. Its reflect loop (an agent with up to 10 tool calls) stays off the phone.
- **Provenance is the spine of the map.** Every memory, observation and to-do cites segment IDs and a quote that must occur in the cited segment ([Omi][omi-obs]), and every edge keeps its evidence. Tapping anything opens its source line. Once audio and segment offsets are saved, it also plays the audio at that spot.
- **Two LLM calls per recording; the rest runs overnight.** After Stop, run the existing summary plus one ADD-only extraction call in the style of [Mem0 v3][mem0-v3], held to a grammar. To-dos come first. Facts follow once a stronger model than Gemma 3 1B is in (Gemma 4 E2B is the planned upgrade). Merging, observations, mental models, topics and map layout run in WorkManager while the phone charges and is idle.
- **Search uses Hindsight's four arms, with no LLM at query time:**
  - FTS5 bm25, with one normalized index for German stems and compound parts and one trigram index for substrings;
  - EmbeddingGemma vectors;
  - a walk over the edge table;
  - a time window from the same EN+DE date parser.

  Fuse them with RRF (k = 60) and Hindsight's small recency and proof boosts ([Hindsight recall][hs-recall]). EmbeddingGemma 1 runs on today's llama.cpp. EmbeddingGemma 2 (270M, Apache-2.0, 310 MB at Q8_0, same text quality) follows the llama.cpp upgrade.
- **To-dos: parse first, then let the model choose.** A deterministic Kotlin EN+DE time resolver proposes candidate times. It is anchored on each segment's wall clock and the recording's time zone. A grammar built for each call has enums of those candidates and of the segment IDs, so the model can only pick and cite. The 1B model never does date maths. Extracted to-dos are suggestions; the user accepts each one on a chip that shows weekday and date. Only an explicit "Satori, erinnere mich ..." or "Satori, remind me ..." skips the review.
- **Reminders fire from Satori's own rows.** Declare `USE_EXACT_ALARM` and keep one "next due" `setExactAndAllowWhileIdle` alarm, re-armed on boot, app update, time change and time-zone change. Reminders go to a high-importance channel with `CATEGORY_REMINDER` and Done and Snooze actions. WorkManager never delivers a reminder. Optional hand-offs: the Tasks.org v0 provider, `.ics` files for jtx Board or calendar apps, and a calendar insert for appointments. Never insert into a Google-synced calendar.
- **The map is a lens, not the home screen.** No mature Compose graph library exists, so draw a small deterministic ego map in-house (ExcaliBrain-style zones, relevance rings) on a Canvas with telephoto pan and zoom. Give it a list form for the 480 x 410 dp cover screen and for TalkBack. Scope it to today or this week and enter it from search. Cap the nodes by degree of interest: 8 to 12 on the cover screen, 30 to 50 on the inner screen.
- **Smallest useful slice first.**
  1. To-dos and reminders from voice notes. These work with today's Gemma 3 1B and llama.cpp b9574.
  2. FTS5 search.
  3. Memories with embeddings and a Related list. These need a second model handle in the native layer, but no llama.cpp upgrade.
  4. Overnight consolidation and core pages.
  5. The map.
- **Measure on the owner's own notes.** Published memory benchmarks are run by the vendors and are not comparable. A third-party run put naive RAG level with A-MEM and ahead of MemoryOS and open-source Mem0. Keep a private EN/DE test set on the phone, and use the MIT and BSD date corpora (Recognizers-Text, Duckling) as JVM tests.

## 2. Landscape

Licence rule: MIT, Apache-2.0, BSD and MPL-2.0 code can be bundled or ported with its notice. GPL, AGPL and source-available projects are studied for ideas only. Model weights carry their own terms.

### 2.1 Memory layers and knowledge graphs

| Project | Licence | Last activity | What it does | Offline / Android fit | What Satori takes |
|---|---|---|---|---|---|
| [Hindsight][hs] | MIT | commit 2026-10-09, v0.10.3 2026-10-08 | Agent memory server. Retain: an LLM extracts facts, entities, dates and causal links. Recall: semantic + BM25 + graph + temporal, RRF, cross-encoder. Reflect: an agent loop. Background observations; mental models | Python + Postgres; no Android. Its fully local mode runs llama.cpp with gemma-4-E2B-it Q4_K_M (~3.5 GB), JSON grammar on. Default embedder and reranker are English-only | The reference design: data model, recall algorithm, deterministic entity resolver, deletion with re-consolidation |
| [Graphiti][graphiti] (Zep) | Apache-2.0 | commit 2026-10-09, v0.30.2 2026-09-08 | Temporal graph of episodes, entities and fact edges. Contradicted facts are invalidated, not deleted | Python + graph database; several LLM calls per episode; the README warns that very small models break the JSON schema | valid/invalid and created/expired times; an episodes list on each fact; recording start as reference time; dedupe by exact name, then MinHash, then LLM |
| [Letta Code][letta-code] (MemGPT) | Apache-2.0 | letta-code commit 2026-10-10, v0.34.10 2026-10-09; the Letta V1 server is retired | Core blocks always in context, recall and archival storage; Letta Code keeps memory as Markdown files and reflects in the background | Node CLI, cloud by default; needs strong tool calling ([MemGPT][memgpt]: GPT-3.5 degraded) | Core / recent / archive tiers; its reflection checklist as a nightly job |
| [Mem0][mem0] | Apache-2.0 | commit 2026-10-07, Python SDK v2.2.1 2026-09-25 | Fact memory. Its v3 algorithm is single-pass, ADD-only extraction, with BM25 as a boost and entity overlap | Python, cloud defaults; the BM25/entity path hard-codes the English spaCy model | ADD-only extraction per recording now; merging later, in the background |
| [A-MEM][amem] | MIT | commit 2025-11-06 | Zettelkasten notes; the LLM links each new note to its nearest neighbours and rewrites them | Python; ran with Llama 3.2 1B at ~1,200 tokens and 1.1 s per operation on a desktop GPU ([paper][amem-paper]); English embedder | Link step: candidates from code, the model picks from an enum of their IDs; rewrites only overnight |
| [Cognee][cognee] / [Cognee-RS][cognee-rs] | Apache-2.0; RS: MIT OR Apache-2.0 | commits 2026-10-08 / 2026-10-09 | Document -> chunk -> entity/event graph with content-hash chunk IDs; a GLiNER2 path without an LLM | Cognee-RS builds for Android from source, but without LanceDB, with a vector store that does not persist, an English embedder and an external LLM | The [Event schema][cognee-events] (time_from, time_to, location, people) for appointments; watch Cognee-RS |
| [MiniRAG][minirag] | MIT | commit 2025-10-16 | Graph RAG built for small models: chunk and entity nodes, path scoring | Python; 1.5-4B models did indexing and answering; English data | Keep segments as nodes in the graph; LiHua-World as an English test set |
| [LightRAG][lightrag], [GraphRAG][graphrag] | MIT | v1.5.7 2026-09-02; v3.3.0 2026-10-08 (maintenance mode) | LLM-built entity graphs and community reports | Not for phones: LightRAG names Qwen3-30B-A3B as its local minimum; with 1.5-4B models MiniRAG measured both below naive RAG. [FastGraphRAG][graphrag-methods] builds the graph without an LLM (English noun phrases) | Source-ID lists on every derived item; topic reports refreshed in the background |
| [HippoRAG 2][hipporag] | MIT | commit 2026-10-06 | OpenIE graph plus Personalized PageRank | Indexing used a 70B model; PageRank itself is cheap | PageRank (damping 0.5) seeded by query entities as the graph arm |
| [txtai][txtai-graph] | Apache-2.0 | v9.14.0 2026-10-06 | Embeddings database that builds a semantic graph (15 neighbours, minscore 0.1) and topic communities | Python; the graph needs no LLM; German labels need German stopwords | kNN edges and Louvain / label-propagation topics labelled by top terms |
| [MemoryOS][memoryos] | Apache-2.0 | commit 2026-07-07 | Short-, mid- and long-term memory with "heat" promotion | Python + LLM; heat is arithmetic | Heat to propose core-memory candidates |
| [LightMem][lightmem], [Memobase][memobase] | MIT; Apache-2.0 | commit 2026-09-05; v0.0.42 2026-01-11 | Offline "sleep-time" updates; a buffer flushed with a fixed 3 LLM calls | Python servers; both patterns fit a phone | One flush per recording with a fixed call count; consolidation while charging |
| [MemOS][memos] local plugin, [agent-memory][agentmem] | Apache-2.0; MIT | v2.0.34 2026-09-23; commit 2026-10-09 | SQLite + FTS5 + vectors, all local; reads without a model, with progressive disclosure | Node and Python; designs to port | Confirms the one-SQLite design; one-line abstracts first for a small context |
| [GLiNER2][gliner2] | Apache-2.0 (code and multilingual model cards) | commit 2026-10-09 | Encoder that extracts entities, classes and relations in one pass | Multilingual models of 205-307M (gliner2-multi-v1 lists German); a GLiNER2 ONNX export is not documented (UNVERIFIED on Android) | Later option: entities and action spans without the LLM |

Also checked:
- [LongMemory][longmem] (Apache-2.0): recorded vs valid time, deterministic decay with explicit reinforcement.
- [Basic Memory][basicmem] (AGPL-3.0, ideas only): Markdown observations and typed links.
- [Supermemory][supermem]: the repo is MIT, but the local engine binary is not open source.
- [mem-agent][memagent]: a Qwen3-4B fine-tune; its base model scored 0 on memory updates.
- TencentDB Agent Memory (MIT): now a server hub.
- nano-graphrag, LangMem, Memary and fast-graphrag (MIT).
- MIRIX, Nemori, EverOS, memU and Memori (MIT or Apache-2.0): all need servers or, by default, cloud LLMs.
- Kuzu, the embedded graph database, was archived on 2025-10-10 ([repo][kuzu]).

**Benchmarks.** The headline numbers come from the vendors and use large models.
- Hindsight's paper gives LongMemEval 83.6% with OSS-20B and 89.0% with OSS-120B. Its 91.4% LongMemEval and 89.61% LoCoMo rows use Gemini-3 Pro as the answer model ([paper][hs-paper]).
- Mem0's README scores come from its managed platform.
- A third-party LoCoMo run in the [LightMem][lightmem] repo (gpt-4o-mini) scored full context 73.83, naive RAG 63.64, A-MEM 64.16, MemoryOS 58.25 and open-source Mem0 36.49.

Plain hybrid search over segments is a strong baseline.

### 2.2 Voice-note and meeting apps

| Project | Licence | Last activity | What it does | Offline / Android fit | What Satori takes |
|---|---|---|---|---|---|
| [Omi][omi] | MIT | commit and Desktop v0.12.464, 2026-10-10 | Wearable plus app: per-conversation notes, action items, events, memories, a knowledge graph, due-date reminders | Not offline: all extraction runs on a FastAPI backend with cloud LLMs; only the reminder fires on the phone | The main blueprint: separate stores, cited segment IDs, due-date rules, a suggestion inbox, 48 h short-term memory, RRF. Its BM25 tokenizer drops umlauts |
| [Calendar Add][caladd] | WTFPL v2 | commit 2026-08-30 | Kotlin/Compose app: text, images and short voice captures become calendar events, using Gemma 4 E2B on LiteRT-LM | Offline after the model download; voice goes to Gemma as WAV (no ASR); OCR needs Play services; English UI | The reference-datetime prompt block; staged "observations -> temporal normalization -> JSON"; idempotent calendar sync |
| [Vox Transcribe][vox] | MIT | 0.1.0 2026-07-16 | Android voice notes: on-device ASR, then Gemma 4 E4B cleanup and summary | Fully offline; English and German; dropped E2B because cleanup quality was not enough; 12 GB RAM recommended | German detection with a forced output language; one model job at a time |
| [Meetily][meetily] | MIT | v0.4.1 2026-09-12 | Desktop meeting notes: Whisper or Parakeet TDT v3, then llama.cpp | Offline on desktop; no Android; default Qwen 3.5 2B, Gemma 3 1B kept as a "legacy tier" | Section templates; "tight_structured" sampling (temp 0.1, top_k 20, top_p 0.88). Avoid its English-then-translate pass |
| [Anarlog][anarlog] (formerly Hyprnote) | MIT outside `enterprise/`; GPL-3.0 before 2026-04-26 | commit 2026-10-10, desktop v1.4.29 2026-10-08 | Local-first meeting notepad (Tauri; Expo mobile beta) | Offline on desktop; the mobile beta has no on-device STT | People and organisation tables with a polymorphic entity_mentions edge table; a search index fed by SQLite triggers through a dirty queue |
| [AI Edge Gallery][gallery] | Apache-2.0 | 1.0.20 2026-10-06 | Google's on-device AI showcase app | Inference on-device; some optional features use the network | Kotlin reference: EmbeddingGemma 2 with MediaPipe SqliteVectorStore and metadata ([code][gallery-retrieval]) |
| [Vaulto Note][vaulto] | GPL-3.0 | commit 2026-10-06 | Voice notes with Whisper and Qwen 3.5, task extraction, reminders | Local mode offline; picks Qwen 3.5 4B on phones with 7 GB RAM or more | Ideas only: a short task prompt with "Today is weekday, date", regex validation, 09:00 for date-only reminders |
| [Ramble][ramble] | MIT | v1.3.0 2026-06-08 | Flutter voice notes with a local Gemma 3 1B; "Miko" link comments | Local LLM; OS speech recognizer | LLM-free [link rules][ramble-miko]: 2+ shared tags, repeated task, contradiction, topic back after 14+ days. Its weekday reminder offsets are wrong |
| [OpenWhispr][openwhispr] | MIT | app v1.10.2 2026-09-15 | Desktop dictation and notes with local STT, llama.cpp and Qdrant | Offline on desktop; Android build unreleased | FTS5 kept in sync by triggers; a Unicode-safe query builder ([code][openwhispr-search]) |
| [Scriberr][scriberr] | MIT | v1.2.0 2025-12-17 | Self-hosted transcription server | Server | Note anchor = word index + time + exact quote ([model][scriberr-note]) |
| [Speakr][speakr] | AGPL-3.0 | v0.10.13-alpha 2026-10-04 | Self-hosted transcription with events and library-wide search | Server | Ideas only: reminder_minutes per event; store the embedding model ID with the vectors |
| [Screenpipe][screenpipe] | Commercial licence since 2026-06-10 (earlier MIT versions stay MIT) | commit 2026-10-10 | Desktop capture with a memories table and scheduled "pipes" | Desktop only | Ideas only: an importance score; a [missed to-dos][screenpipe-todos] review of the last 3 days |
| [Chirp][chirp] | Apache-2.0 (the APK bundles GPL/LGPL MP3 code) | v5.0.0 2026-08-12 | Android voice notes and dictation keyboard | On-device STT with English-only models; AI features are cloud BYOK | The safe Room FTS [query builder][chirp-fts]: letters and digits only, `*` suffix, at most 16 tokens |

Also checked, low relevance:
- Owl (MIT, inactive since 2024): VAD plus silence-timeout splitting into conversations.
- Vibe and Buzz (MIT, desktop).
- Amurex and Whishper (AGPL).
- NotelyVoice (GPL-3.0).
- Transcribro (ISC).
- meeting_helper (Apache-2.0, cloud summaries).
- Off Grid and ToolNeuron (MIT).
- LLM Hub (PolyForm Noncommercial).
- ADeus (CC BY-NC-ND).

No surveyed project is a fully offline Android app that combines a memory bank, to-dos and reminders.

### 2.3 PKM and mind-map tools

| Project | Licence | Last activity | What it does | Offline / Android fit | What Satori takes |
|---|---|---|---|---|---|
| [Smart Connections][sc] (Obsidian) | Source-available: MIT-style grant plus a non-compete | 4.7.2 2026-08-06, commit 2026-10-03 | Local embeddings; a Connections list ranked when a note opens; drag a result in to link it, or pin or hide it | Runs on Obsidian mobile; the first run fetches runtime and weights from CDNs; German options are multilingual-e5-small and granite-97m | Ideas only: a match is a lead to confirm or hide; the score is "a ranking signal, not a grade" |
| [Smart Connections Visualizer][scv] | MIT | 1.0.27 2026-01-06 | Ego graph where distance from the centre follows relevance | Desktop-only plugin | Relevance rings, a minimum-relevance slider, truncated labels |
| [ExcaliBrain][excalibrain] | MIT | 0.2.19 2026-09-29 | Structured map of the active note: parents above, children below, friends left and right, siblings | Plugin; also loads on mobile | Deterministic zone layout for the main map ([layout code][excalibrain-layout]) |
| [Breadcrumbs][breadcrumbs] | MIT | stable 4.21.14 2026-09-27; new maintainer since May 2026 | Typed links with implied inverses; a Matrix view that groups neighbours by relation type | Local, no AI | The Matrix is the list form of the map; store one direction, derive the inverse |
| [Graph Analysis][graph-analysis] | GPL-3.0 | commit 2026-01-12, release 0.15.4 2022-01-31 | Co-citation with the citing sentence, Jaccard, Adamic-Adar, label propagation | Local, no AI | Ideas only: co-mention edges with evidence; implement the textbook algorithms |
| [Logseq][logseq] (DB version) | AGPL-3.0 | commit 2026-10-09, Desktop 2.0.2 beta 2026-10-07 | Outliner; linked and unlinked references on every node; tasks with status history; a Pixi graph view | The mobile app has no graph view ([routes][logseq-routes]); semantic search only on macOS Apple silicon | Ideas only: [declutter numbers][logseq-pixi], the task model, "the graph is optional on mobile" |
| [Trilium][trilium-sim] | AGPL-3.0 | v0.106.0 2026-09-26 | Similar Notes without embeddings; note maps and relation maps | Desktop or server; English stemming | Ideas only: explainable similarity from time proximity and shared words |
| [Anytype][anytype-spec] | Any Source Available License 1.0 | 0.50.4 2026-10-01 | Typed objects; Android quick capture suggests a type with Gemini Nano | The Android app is local; no graph on Android | Ideas only: AI suggestions never block capture or apply themselves, and fail silently with the reason logged |
| [Khoj][khoj-filters] | AGPL-3.0 | 2.0.0-beta.28 2026-03-26 | Self-hosted "second brain" with date filters | Server; English models | Ideas only: filter syntax (`dt>=`, `dt<=`, `+"word"`, `-"word"`) and dates stored per chunk |
| [AFFiNE][affine-layout] | MIT outside the Enterprise Edition backend | canary 2026-10-10 | The AI answers in Markdown, which becomes a mind-map tree; "expand from this node" | Editor local; AI in the cloud | Outline -> tree pipeline; per-node expansion; spacing 45 / 110 / 200 |
| [compose-mindmap][compose-mindmap] | Apache-2.0 | 0.1.1 2026-05-31 (JitPack); main 2026-10-02 | Compose tree mind map: collapse, focusNode, fitContent | Native; 0 stars; many features only on the unreleased main branch | Vendor the source for the per-recording mind map |
| [KotlinFlow][kotlinflow] | MIT | v1.0.1 2026-09-29 (Maven Central) | Compose node-graph editor with tree, grid and force layouts and a minimap | Native; 4 stars; built for editing | Reference for viewport maths and layouts |
| [telephoto][telephoto] | Apache-2.0 | 0.19.0 2026-04-02 | Pan and zoom for any Compose content | Native | Pan and zoom for the map; it consumes taps, so handle them through its onClick |
| [GraphView][graphview] | Apache-2.0 | v0.8.1 2021-04-10 | Buchheim-Walker, Fruchterman-Reingold and Sugiyama layouts for Views | Stale; View-based | Port the tidy-tree layout |
| [Automatic Linker][autolinker] | Apache-2.0 | 4.10.7 2026-10-03 | Turns plain-text note names into links, with aliases and a closest-match rule | Local, no AI | Licence-safe reference for an alias-based entity linker |
| [markmap][markmap] | MIT (bundles d3-flextree, WTFPL) | v0.18.12 2025-06-12 | Markdown outline -> SVG mind map | WebView; the JS must be bundled locally | Quick prototype only |

Also checked:
- [Reor][reor] (AGPL, archived 2026-03-07): a "Related notes" sidebar of the 20 nearest chunks.
- Obsidian core (proprietary): local graph with depth, unlinked mentions, timelapse ([graph help][obsidian-graph]).
- SiYuan (AGPL): block IDs that encode the creation time, a minRefs slider.
- Joplin with Jarvis (AGPL): to-do alarms, tags copied from similar notes.
- [Obsidian Tasks][obsidian-tasks] (MIT): due, scheduled, start and done dates, rrule recurrence.
- Mind Elixir (MIT), jsMind (BSD-3), simple-mind-map (library MIT, client closed).
- force-graph, sigma.js, graphology and Cytoscape.js (MIT), [d3-force][d3-force] (ISC).
- [JGraphT][jgrapht] (EPL-2.0 or LGPL-2.1): check before bundling.
- SteleKit (Elastic-2.0) and InfraNodus (non-commercial).
- A licence trap: the npm package `@cosmograph/cosmos` is CC-BY-NC-4.0, while `@cosmos.gl/graph` is MIT ([npm][cosmos-npm]).

The map design also draws on five papers:
- degree-of-interest expansion for small screens ([van Ham and Perer 2009][vanham]);
- the disparity filter for pruning edges ([Serrano et al. 2009][disparity]);
- backbone layouts against "hairballs" ([Nocaj et al. 2015][backbone]);
- Leiden communities ([Traag et al. 2019][leiden]);
- kNN graph clustering ([Maier et al. 2009][maier]).

### 2.4 On-device Android building blocks

| Project | Licence | Last activity | What it does | Offline / Android fit | What Satori takes |
|---|---|---|---|---|---|
| [BundledSQLiteDriver][androidx-sqlite] + [Room 2.8][room] | Apache-2.0; SQLite public domain | sqlite 2.7.1, Room 2.8.5, Room3 3.0.3, all 2026-09-09 | Ships its own SQLite (androidx-main pins 3.53.4) with FTS5, JSON1, RTREE and math functions ([build file][sqlite-bundled-gradle]); loads extensions since sqlite 2.6.0; Room 2.7+ accepts any driver | In-process, offline; the AAR is 2.55 MB for all ABIs. Per AOSP source the framework SQLite has no FTS5 and blocks extensions ([AOSP][aosp-sqlite]; not tested on the razr) | The store for everything. [Room3][room3] adds `@Fts5` but is a package rename; on Room 2.8, create FTS5 tables with raw SQL |
| [sqlite-vec][sqlite-vec] | MIT OR Apache-2.0 | v0.1.9 2026-03-31; alpha 2026-05-18 | `vec0` KNN tables for float32, int8 or bit vectors, with metadata columns; brute force in stable releases | Prebuilt Android aarch64 library, entry point `sqlite3_vec_init` ([Android guide][sqlite-vec-android]); pre-v1 | Optional vector channel in the same database |
| [sqlite-vector][sqlite-vector] | Apache-2.0 from 1.1.2 (Elastic-based up to 1.1.0) | 1.1.2 2026-09-11 | Quantized scans over ordinary BLOB columns | Android AAR; vendor-driven | Alternative; pin 1.1.2 or newer; INT8, not UINT8 |
| [llama.cpp][llamacpp] | MIT | b11549 2026-10-10 (Satori: b9574, 2026-06-09) | LLM, embeddings (pooling), reranking (pooling RANK), GBNF and JSON-schema grammars | Already built into Satori for arm64 CPU; EmbeddingGemma 2 needs b11452 or newer ([PR 30054][llamacpp-30054]) | One runtime for extraction, embeddings and optional reranking |
| [LiteRT-LM][litertlm] | Apache-2.0 | v0.18.0 2026-10-06 | Google's on-device LLM runtime with an EmbeddingEngine and JSON-schema output in Kotlin | Offline; AAR 20.9 MB; no NPU files for the razr's SM8635 | Plan B, only if the LLM moves to LiteRT-LM |
| [MediaPipe Semantic Retriever][mediapipe-retriever] | Apache-2.0 | tasks-retrieval 1.1.0 2026-10-06 | Ready-made vector retrieval with EmbeddingGemma 2 | On-device; about 65 MB of AARs; vector-only; chunks by characters, not segments | Prototype only |
| [AppSearch][appsearch] | Apache-2.0 | 1.1.0 stable 2025-07-02; 1.2.0-alpha02 2026-08-26 adds ANN | Jetpack on-device full-text and embedding search | LocalStorage works offline; a second store next to Room | Plan B |
| [ONNX Runtime][ort] | MIT | v1.31.0 2026-10-09 | General ONNX engine | AAR 55.1 MB. sherpa-onnx 1.13.2 already ships ORT 1.24.3 (25.8 MB arm64). NNAPI is deprecated since Android 15 | Do not add the AAR; call the bundled ORT through Satori's own JNI if an ONNX-only model is ever needed |
| [Snowball][snowball], [jWordSplitter][jwordsplitter] | BSD-3-Clause; Apache-2.0 | v3.1.1 2026-06-03; v4.7 2023-09-21 (dormant) | German stemmer; German compound splitter with a bundled dictionary | Pure Java, offline | Normalized text for the German keyword index |

Embedding models are compared in section 4.3.

Rejected:
- ObjectBox (closed native core);
- Couchbase Lite vector search (Enterprise only);
- USearch (no Java JAR since 2.21.1);
- hnswlib (no Android binding);
- FAISS (no Android packaging);
- Cozo (MPL-2.0, dormant since 2023);
- sqlite-memory (Elastic-2.0);
- Turso (no Android binding);
- sqlite-vss (abandoned).

HNSW indexes add memory, rebuild and licence costs with no benefit at 10^3 to 10^5 items.

### 2.5 To-dos, dates and reminders

| Project | Licence | Last activity | What it does | Offline / Android fit | What Satori takes |
|---|---|---|---|---|---|
| [Recognizers-Text][recognizers] | MIT | commit 2026-01-23; NuGet 1.8.13 2025-02-19; `Java/` last changed 2023-04-03 | Rule-based dates, times, durations and recurring sets; ambiguous phrases return a past and a future reading | Java port "in progress", 1.0-SNAPSHOT, not on Maven; it passes 72 of 202 German DateTimeModel spec cases (.NET: 201). Regex behaviour on Android UNVERIFIED | The German and English spec files ([German spec][recognizers-de-spec]) as test corpus; vendoring is the fallback |
| [Duckling][duckling] | BSD-3-Clause | commit 2026-03-15; v0.2.0.0 2021-04-16 | Haskell rule parser with strong German rules | No Android path | DE and EN corpora as fixtures |
| [rustling-ontology][rustling] | Apache-2.0 OR MIT | 0.19.3 2020-01-23 (dormant) | Rust port of Duckling with a German grammar; Snips shipped it on devices | Needs a rebuild with a current toolchain (UNVERIFIED); prebuilt artifacts no longer resolve | Fixtures; a JNI spike only if Satori's own resolver falls short |
| [chrono][chrono] | MIT | v2.10.2 2026-10-03 | TypeScript date parser with a reference instant, a time zone and a forwardDate option | Only inside a JS engine ([JavaScriptSandbox][jsengine]); German support "partial", no halb/Viertel phrases | Reference design for the resolver's inputs |
| [ovos-date-parser][ovos] | Apache-2.0 | 0.34.1a1 2026-10-08 (stable 0.29.0) | Spoken-style date extraction that also returns the leftover words | Python; its German rules depend on its German number parser | Port rules and test phrases with attribution; the leftover words make a to-do title |
| [ctparse][ctparse] | MIT | archived 2024-02-12 | EN+DE time parser that runs both languages' rules together | Python | Run EN and DE rules on every segment; prefer the future, and the near future |
| [Android TextClassifier][textclassifier] | Platform API (libtextclassifier Apache-2.0) | API 28 and 30 | System detection of date-time spans | AOSP ships no German annotator model; no getter for the resolved time; generateLinks ignores the reference time | At most a cross-check |
| [ML Kit Entity Extraction][mlkit-entity] | Proprietary | 16.0.0-beta6 | On-device date-time entities, German included | Downloads its language models at runtime and sends diagnostics | Rejected |
| llama.cpp grammars ([guide][llamacpp-grammars]) | MIT | vendored b9574 | GBNF grammars, plus a JSON-schema converter (enum, const, maxItems, maxLength, integer ranges) | Already compiled into Satori; the sampler needs a per-request rebuild. With Gemma 4 the JSON-schema path can let a thought channel through ([#30256][llamacpp-30256]) | Raw GBNF built per call, with dynamic enums |
| [Omi action items][omi-struct] | MIT | commit 2026-10-10 | Task schema and prompt rules | Cloud | The field list and rules, shortened for a 1-2B model |
| [NuExtract-1.5-tiny][nuextract] | MIT | Hugging Face 2026-05-19 | 0.5B model that fills a JSON template by copying input text; lists German | Community GGUFs only | Fallback extractor if Gemma struggles in German |
| [Mobile Actions][mobile-actions] + FunctionGemma 270M | CC-BY-4.0; FunctionGemma under Gemma terms (gated) | dataset 2025-12-18 | Training data for on-device function calls, incl. create_calendar_event | English only; 7 tools, no reminder tool | State the weekday next to the ISO anchor ("Day of week is Wednesday") |
| [themachine][themachine] | AGPL-3.0 | commit 2026-09-05 | Offline Android voice assistant: whisper.cpp, Gemma 3 1B with a grammar, Piper | Offline; English only | Ideas only: confirms 1B plus grammar for intents; Satori resolves dates in code instead of only validating them |
| [Tasks.org][tasks-api] | GPL-3.0 (talk to it over IPC only) | 15.13-beta02 2026-10-08; provider API since 15.12 (2026-09-11) | To-do app with a content provider, `org.tasks.api` v0 | Local lists work offline; "v0 is unstable"; quick-add date parsing is English-only | Optional push target; its single-next-alarm engine as a pattern |
| [jtx Board][jtx] | GPL-3.0 | v2.17.04-alpha01 2026-10-10 | VJOURNAL and VTODO app | Its provider only accepts sync adapters | `.ics` import through ACTION_VIEW |
| [CalendarContract][calendar-provider] (+ [Etar][etar], GPL-3.0) | Platform API | guide updated 2024-05-22 | The system calendar | ACTION_INSERT needs no permission; local calendars are not synced | Appointments only |
| [biweekly][biweekly] | BSD-2-Clause | 0.6.8 2024-01-07 | iCalendar read and write, incl. VTODO, VALARM, VTIMEZONE | Android-compatible | `.ics` export |

Also checked:
- OpenTasks (Apache-2.0 contract, dormant since 2021).
- Natty and PrettyTime NLP (English-only parsing).
- dateparser (BSD-3, Python): an embedded CPython would work but is heavy.
- HeidelTime, SUTime and Hawking (GPL).
- Markor (todo.txt with `due:YYYY-MM-DD`).

### 2.6 Not for Satori

- Graph databases and HNSW engines: edge tables and brute-force vectors are enough at this scale.
- Agent loops on the phone: Hindsight's reflect, Letta's self-editing tool calls, MIRIX's multi-agent routing. Use precomputed core pages plus one answer call.
- Standard GraphRAG or LightRAG indexing with a small model.
- Cloud pipelines: Omi's backend, the Supermemory engine, Memori's augmentation.
- For dates: ML Kit Entity Extraction, TextClassifier, GPL date taggers and English-only date parsers.
- For reminders: WorkManager delivery, full-screen intents, and events written into a Google-synced calendar.
- A second ONNX Runtime AAR.
- GGUFs of multilingual-e5-small or paraphrase-multilingual-MiniLM, until their tokenization is checked in llama.cpp (UNVERIFIED).
- Licence traps:
  - AGPL or GPL code: Basic Memory, Logseq, Trilium, Tasks.org, themachine, Vaulto;
  - source-available code: Smart Connections, Anytype, SteleKit, and Screenpipe after 2026-06-10;
  - JGraphT (EPL or LGPL);
  - `@cosmograph/cosmos` (CC-BY-NC);
  - sqlite-vector before 1.1.2;
  - Chirp's MP3 path (GPL and LGPL).

## 3. The memory model

### 3.1 The code on 2026-10-10

The researchers and the lead checked these in the repo on 2026-10-10.

| Area | Today | Where |
|---|---|---|
| Database | Room v6 with `transcription_sessions`, `transcription_segments`, `llm_insights` | `database/AppDatabase.kt` |
| Segments | Text plus one wall-clock `timestamp`, set when the segment is emitted. No audio offsets, speaker or language. Audio is not saved | `database/entity/TranscriptionSegmentEntity.kt`; `repository/SttRepositoryImpl.kt` |
| Sessions | `created_at`, mode, languages, topic; no time zone | `database/entity/TranscriptionSessionEntity.kt` |
| Insights | `source_segment_ids` and `tasks` are JSON strings. The domain model documents tasks as objects with task and priority, but the parser writes a bare string array | `domain/.../model/LlmInsight.kt:22`; `domain/.../usecase/llm/InsightOutputParser.kt:57-61` |
| Prompts | Return title, summary and action_items: no owner, due phrase, resolved date or citation. German prompts use localized JSON keys | `app/src/main/res/values*/strings.xml` |
| Search | `LIKE '%q%'` scans; Room 2.6.1 | `database/dao/SearchDao.kt`; `gradle/libs.versions.toml` |
| Background work | No WorkManager dependency yet | `gradle/libs.versions.toml` |
| LLM layer | One global model and one global sampler without a grammar; `n_ctx` fixed at 4096; thinking is switched off only for the `gemma4` architecture. The vendored llama.cpp b9574 already compiles the grammar code and knows the `gemma4` and `gemma-embedding` architectures | `lib-llama-android/src/main/cpp/ai_chat.cpp`; `third_party/llama.cpp` |
| Native libraries | `useLegacyPackaging = true`, so `.so` files are extracted to `nativeLibraryDir`, where a SQLite extension can be loaded by path | `app/build.gradle.kts` |
| Manifest | `POST_NOTIFICATIONS` declared; no exact-alarm or boot permission; targetSdk 35, minSdk 30 | `app/src/main/AndroidManifest.xml`; `app/build.gradle.kts` |

Because `gemma4` and `gemma-embedding` are already registered, Gemma 4 E2B and EmbeddingGemma 1 can be tried on the CPU without the llama.cpp upgrade. Only EmbeddingGemma 2 needs it.

The model study adds two cautions:
- The Gemma 4 QAT GGUF was re-uploaded on 2026-07-17 with a chat template newer than b9574 knows, so render-test it.
- Almost nothing has been measured on the razr yet: only a 2.99 s STT start and one MemAvailable reading of 2.93 GiB in normal use.

### 3.2 Hindsight mapped onto Satori

| Hindsight ([docs][hs-retain]) | Satori |
|---|---|
| Bank | The app database; one user |
| Document, with a stable ID; retaining it again replaces it | A recording (`transcription_sessions.id`). Re-extraction replaces its derived rows |
| Chunk, raw text kept | A transcript segment, with its order, wall clock and, later, `start_ms`, `end_ms` and speaker |
| Context label and retain mission | Per mode: "the user dictating a voice note" or "a meeting with ...". Mission: "extract commitments, appointments, decisions, people, places; ignore filler" |
| Memory unit, typed world or experience by who is speaking | World: what the user said about themselves and the world. Experience: Satori's own actions (reminder created, to-do done, summary written), written by code without an LLM |
| Observation with source fact IDs, quotes and a proof count ([observations][hs-obs]) | A consolidated belief about one person, project or topic |
| Mental model: a question whose stored answer is refreshed when its tag scope changes, with sources and versions ([mental models][hs-mm]) | A core-memory page: "People in my life", "Open commitments", "Current projects" |
| Tags and observation scopes | `lang:de`, `type:meeting`, `category:<id>`, `person:<id>`; one scope per topic |
| Recall: four strategies fused by RRF | Section 4 |
| Reflect: an agent loop of up to 10 tool calls | Not ported. One answer call over recall results and core pages |

Hindsight keeps facts in the language they were spoken in and lets an observation's language follow the majority of its facts ([multilingual][hs-ml]). Satori does the same.

Two Hindsight modes suit a 1B model: "chunks", which uses no LLM, and "verbatim", which keeps the segment text and lets the LLM add only entities, dates and places.

Hindsight's consolidation prompt makes the model copy fact UUIDs verbatim ([prompts][hs-consol]), which is likely fragile at 1-4B. Satori's prompts use short labels (S3, F7, O2) instead, and code maps them back to IDs. That choice is untested and should be measured.

### 3.3 Tiers

| Tier | Holds | Gets there | Leaves | Read cost |
|---|---|---|---|---|
| Source | Recordings and segments (later, audio) | Recording | Only when the user deletes | Search |
| Recent | Facts from the latest recordings that are not yet consolidated | ADD-only extraction after Stop | The nightly pass keeps it as long-term, merges it into an observation, archives it, or asks the user. Omi gives every new memory a 48 h decision deadline ([Omi][omi-memtier]) | Search |
| Long-term | Consolidated facts | Nightly pass | Superseded (`invalid_at` set) or archived | Search |
| Observations | Beliefs about a person, project or topic, with a proof count and quotes | Nightly pass, preferring an update over a new observation | Rewritten, with the old version kept | Search; entity pages |
| Core | Mental-model pages and pinned items | Nightly refresh of pages whose scope changed; pins by the user | Only by the user | A database read, no LLM |
| Archive | Superseded, rejected or hidden items | Consolidation or the user | Never deleted automatically | Recall only; hidden by default |

The recording's merged insight (today's summary) is the episode layer between segments and facts. It matches the "scenario" level in TencentDB's L0-L3 pyramid ([repo][tencentdb]). It becomes a node in the map and should cite its segments, as Omi's notes do ([notes prompt][omi-notes]).

### 3.4 What an entry holds

- The text, in the spoken language, and its language.
- Four timestamps, in the bi-temporal model of Graphiti ([edges][graphiti-edges]) plus Hindsight's split between when something happened and when it was said:
  - when it happened: `occurred_start`, `occurred_end`;
  - when it was said: `mentioned_at`, the segment's wall clock;
  - when it was true: `valid_from`, `invalid_at`;
  - when Satori learned or retired it: `created_at`, `expired_at`.
- Provenance rows: segment ID, character span, quote. Observations point at facts instead.
- Entity mentions and tags.
- A proof count (observations), a pin flag, an access count and last access (for heat), and the extractor's model and prompt version.
- The embedding, with the model ID and prompt prefix used.

To-dos are not memory units. They live in their own table with due fields and link to their source segments and fact. An "Open commitments" page may summarise them, but alarms only ever come from structured rows, never from generated prose. Omi keeps the same split: conversations, memories and workflow items are separate stores ([domain model][omi-domain]).

### 3.5 Links: provenance and the mental map

| Edge | From -> to | Built by | Rule | Evidence kept |
|---|---|---|---|---|
| Source | Memory or to-do -> segment | Extraction | The quote must occur in the cited segment after normalisation, or the item is dropped ([Omi][omi-obs]) | Segment ID, span, quote |
| Mention | Memory or segment -> entity | Alias scan and resolver, no LLM | Hindsight: trigram candidates at 0.3 or more; score = 0.5 x name ratio + 0.3 x co-occurring entities + 0.2 x time proximity (0 after 7 days); reuse an entity at 0.6 or more; a word-by-word check keeps "Jane Smith" apart from "John Smith". Unsure matches stay suggested | Span |
| Same recording, same day | Memory <-> memory | Code | Same session; same day; the weight falls with time (Hindsight's paper uses exp(-dt/sigma)) | Timestamps |
| Semantic | Memory <-> memory | Code | Cosine of 0.7 or more (Hindsight's default); keep mutual nearest neighbours; draw at most 3-5 per node | Score |
| Co-mention | Entity <-> entity | Code | Both named in one segment or recording (Graph Analysis' co-citation) | Segment ID |
| Relates or causes | Memory -> memory | Gemma, overnight | The model picks among the candidate labels of an enum and gives a one-line reason (A-MEM's link step) | Reason, segment |
| Member of | Memory or entity -> topic | Label propagation, overnight | Incremental; Zep chose label propagation because it extends incrementally | Score |
| User | Any | The user | Confirmed links and pins | None |

Rules for every edge:
- It has a state: suggested, confirmed or hidden. A hide is negative feedback. A 1B model's guess never silently becomes a fact (Smart Connections' "scan -> confirm -> act" loop; Anytype's suggest-only contract).
- Machine edges may be replaced when extraction reruns; confirmed edges are kept (the contract Jarvis uses).
- Store one direction and derive the inverse, as Breadcrumbs does.

Cheap signals that need no model:
- Trilium's time proximity: +1 for items created within an hour of each other, but not within 5 s, which marks a bulk import; plus a same-day bonus.
- Ramble's "2 or more shared tags".
- Co-mention.
- Unlinked mentions of known names (Obsidian, [Automatic Linker][autolinker]).

German inflection and compounds ("Arzttermin" vs "Termin beim Arzt") and homonyms ("Bank" is a bank and a bench) defeat literal matching. Those cases go to the embedding or the LLM.

Graph scores come from textbook definitions:
- Adamic-Adar for "you might connect X and Y" prompts;
- label propagation for clusters;
- Personalized PageRank for recall;
- coreness or PageRank to propose core candidates.

Graph Analysis' code is GPL, and JGraphT needs a licence check.

### 3.6 Consolidation

**Right after Stop** (foreground, in the existing finishing step, after STT is released):
1. The existing final or batch insight, extended so that it cites segment IDs.
2. One extraction call per chunk of about 5,000 characters, the chunk size the batch pipeline already uses. In slice 1 it extracts to-do candidates; once the stronger model is in, it also extracts facts and entity strings. With Gemma 3 1B, use Hindsight's verbatim mode for facts: the segment text is the memory, and only entities, dates and places are added.
3. Deterministic steps: time resolution, quote validation, deduplication, experience facts, FTS rows.

**Soon after** (WorkManager, small jobs, no charging constraint):
4. Embeddings for new segment windows and facts.
5. Entity resolution and the alias scan, which write mentions.
6. Links: same recording, same day, shared entity, semantic.

**Overnight** (WorkManager with `requiresCharging` and `requiresDeviceIdle`). This stage uses the stronger model: Gemma 4 E2B as planned, or whichever model wins the model study's A/B against Granite 4.1-3B and Qwen3-4B-Instruct-2507. The study keeps Gemma 4 E4B for heavy after-Stop jobs, and only if memory allows.

7. Route the recent facts in batches of about 20 (Omi's planner size): keep, merge into an observation, archive, or flag for review. Create or update observations by Hindsight's rules:
   - prefer an update to a new observation;
   - keep the history;
   - never compute numbers;
   - merge near-duplicates at cosine 0.97.
8. Refresh only the mental models whose scope changed, and keep a version per refresh.
9. Build topics by incremental label propagation over the semantic and entity graph; Gemma names new or changed topics.
10. Optionally, label "relates" and "causes" links, and run a missed-to-do sweep over the last 3 days (Screenpipe's idea). Both produce suggestions only.
11. Precompute map layouts.

Every job is idempotent and resumable per recording (a `memory_job` row), because Android 16 adjusts job runtime quotas by standby bucket ([behaviour changes][bc16]).

When the user edits a segment, its content hash changes. The items that cite it are then revalidated and re-extracted, as in Hindsight's "append" mode.

Skip memory and to-do extraction for "AI copy" sessions. They copy another recording's segments and would duplicate its items.

### 3.7 Decay, contradictions and deletion

- **No deletion by age.** Hindsight has no age decay ([recall][hs-recall]). Age only lowers the rank: recency adds up to +-10%, linear over 365 days.
- **Recent memory** is a view: items from the last 14 days plus anything not yet consolidated. The 14 days are a lead choice (Ramble treats a topic as stale after 14 days); make it a setting.
- **Core candidates** are proposed, not promoted automatically. Two scores from the literature can propose them:
  - MemoryOS' heat (visits, length and an exponential recency term; promotion above 5);
  - Generative Agents' recency (0.995 per hour since last access) + importance (1-10) + relevance ([paper][genagents]).

  The user pins. A pin is explicit reinforcement, as in LongMemory.
- **Contradictions** never delete. The older fact gets `invalid_at` (Graphiti, Zep), and the observation is rewritten with its history ("previously thought ..."; Hindsight). Under ADD-only extraction both facts coexist until the nightly pass, and ranking prefers the newer one (Mem0 v3).
- **Plans** are facts said now. Graphiti gives a future-tense plan valid_at = the recording time and does not treat a soft deadline as invalid_at. The date belongs on the to-do row. Past appointments drop out of "open" by themselves.
- **Deleting a recording** removes:
  - its segments (the cascade already exists);
  - the provenance rows that point at them;
  - every memory left without a source;
  - its suggested to-dos.

  Observations and mental models that cited it are re-consolidated from their remaining sources (Hindsight; Omi retracts memories that have no other evidence). Whether accepted to-dos survive is an open question (8.4).
- **Rejections** are kept: hidden edges and rejected facts become negative examples, as Omi feeds rejected memories back into its prompt.

### 3.8 Room schema sketch

Normal tables are Room entities. FTS5 and `vec0` tables are created with raw SQL in a migration, because Room 2.8 has no FTS5 annotation. Query them with `@RawQuery` or `@SkipQueryVerification`. Key columns only:

```sql
-- Existing tables, extended. DB v7 is already planned for recording categories:
-- add these columns in the same migration or in the next one.
-- transcription_sessions = Hindsight "document"
ALTER TABLE transcription_sessions ADD COLUMN zone_id TEXT;      -- recording time zone, anchors "morgen"
ALTER TABLE transcription_sessions ADD COLUMN category_id TEXT;  -- recording categories (quick-key work)
ALTER TABLE transcription_sessions ADD COLUMN memory_state TEXT; -- none, extracted, consolidated, failed
-- transcription_segments = Hindsight "chunk"; `timestamp` (wall clock at capture) stays the date anchor
ALTER TABLE transcription_segments ADD COLUMN seq INTEGER;       -- order in the recording; prompt label S<seq>
ALTER TABLE transcription_segments ADD COLUMN start_ms INTEGER;  -- audio offsets, once token timestamps are kept
ALTER TABLE transcription_segments ADD COLUMN end_ms INTEGER;
ALTER TABLE transcription_segments ADD COLUMN speaker TEXT;      -- later, with diarization
ALTER TABLE transcription_segments ADD COLUMN lang TEXT;         -- detected language
ALTER TABLE transcription_segments ADD COLUMN content_hash TEXT; -- re-extract only edited segments

CREATE TABLE memory_unit (
  id INTEGER PRIMARY KEY,            -- integer rowid: stable for FTS5 and vec0 joins
  kind TEXT NOT NULL,                -- world, experience, observation
  tier TEXT NOT NULL,                -- recent, long, archive (core = mental_model + pinned)
  status TEXT NOT NULL,              -- active, superseded, rejected, tombstoned
  text TEXT NOT NULL, lang TEXT,     -- kept in the spoken language
  session_id TEXT,                   -- origin recording; NULL for observations
  occurred_start INTEGER, occurred_end INTEGER,      -- when it happened
  mentioned_at INTEGER NOT NULL,                     -- when it was said
  valid_from INTEGER, invalid_at INTEGER,            -- when it was true
  created_at INTEGER NOT NULL, expired_at INTEGER,   -- when Satori learned or retired it
  proof_count INTEGER NOT NULL DEFAULT 1,
  pinned INTEGER NOT NULL DEFAULT 0, access_count INTEGER NOT NULL DEFAULT 0, last_access_at INTEGER,
  extractor TEXT,                    -- model and prompt version
  embedding BLOB, embed_model TEXT   -- raw vector kept so any index can be rebuilt
);
CREATE TABLE memory_source (         -- provenance
  memory_id INTEGER NOT NULL,
  segment_id TEXT,                   -- a transcript segment, or
  source_memory_id INTEGER,          -- a fact (for observations)
  char_start INTEGER, char_end INTEGER, quote TEXT
);
CREATE TABLE entity (id INTEGER PRIMARY KEY, name TEXT NOT NULL,
  type TEXT NOT NULL,                -- person, place, org, project, topic
  first_seen INTEGER, last_seen INTEGER, mention_count INTEGER, pinned INTEGER);
CREATE TABLE entity_alias (entity_id INTEGER, alias TEXT, norm TEXT, lang TEXT);
CREATE TABLE mention (entity_id INTEGER, memory_id INTEGER, segment_id TEXT,
  char_start INTEGER, char_end INTEGER, state TEXT);     -- suggested, confirmed, hidden
CREATE TABLE link (                  -- the mental map
  src_kind TEXT, src_id TEXT, dst_kind TEXT, dst_id TEXT,  -- memory, entity, session, insight, todo, topic
  kind TEXT,                         -- entity, semantic, temporal, causal, same_recording, user
  weight REAL, state TEXT,           -- suggested, confirmed, hidden
  evidence_segment_id TEXT, reason TEXT, created_at INTEGER
);
CREATE TABLE topic (id INTEGER PRIMARY KEY, label TEXT, summary TEXT, version INTEGER, refreshed_at INTEGER);
CREATE TABLE topic_member (topic_id INTEGER, node_kind TEXT, node_id TEXT, score REAL);
CREATE TABLE mental_model (id INTEGER PRIMARY KEY, title TEXT, question TEXT, scope_tags TEXT,
  text TEXT, version INTEGER, refreshed_at INTEGER, refresh_failed_at INTEGER);
CREATE TABLE mental_model_source (mental_model_id INTEGER, memory_id INTEGER);
CREATE TABLE mental_model_version (mental_model_id INTEGER, version INTEGER, text TEXT, created_at INTEGER);
CREATE TABLE item_tag (node_kind TEXT, node_id TEXT, tag TEXT); -- lang:de, type:meeting, category:<id>, person:<id>

CREATE TABLE todo (
  id INTEGER PRIMARY KEY, uid TEXT UNIQUE,   -- uid stays stable across .ics exports
  title TEXT NOT NULL, lang TEXT,
  kind TEXT,                         -- task, appointment
  status TEXT NOT NULL,              -- suggested, open, done, cancelled, superseded, dismissed
  capture_kind TEXT,                 -- explicit_command, clear_commitment, direct_request, inferred_next_step
  owner TEXT, owner_name TEXT,       -- me, other, unknown
  due_phrase TEXT,                   -- verbatim, e.g. "am Freitag um 9"
  due_local TEXT, zone_id TEXT,      -- wall-clock intent, survives time-zone changes
  due_at INTEGER, due_all_day INTEGER,
  due_certainty TEXT,                -- confirmed, tentative
  anchor_at INTEGER,                 -- the wall clock the phrase was resolved against
  remind_at INTEGER, snoozed_until INTEGER, notified_at INTEGER,
  recurrence TEXT,                   -- RRULE
  session_id TEXT, memory_id INTEGER, external_ref TEXT,   -- e.g. Tasks.org or calendar IDs
  created_at INTEGER, completed_at INTEGER
);
CREATE TABLE todo_source (todo_id INTEGER, segment_id TEXT, quote TEXT);

CREATE TABLE segment_window (id INTEGER PRIMARY KEY, session_id TEXT,
  first_seq INTEGER, last_seq INTEGER, embedding BLOB, embed_model TEXT);
CREATE TABLE map_layout (view_key TEXT, node_kind TEXT, node_id TEXT,
  x REAL, y REAL, pinned INTEGER, computed_at INTEGER);
CREATE TABLE memory_job (session_id TEXT, stage TEXT, status TEXT,
  attempts INTEGER, last_error TEXT, updated_at INTEGER);

-- Search indexes; rows written by Kotlin in the same transaction as their source row
CREATE VIRTUAL TABLE search_word USING fts5(norm, ref_kind UNINDEXED, ref_id UNINDEXED,
  tokenize = 'unicode61 remove_diacritics 2');
CREATE VIRTUAL TABLE search_tri USING fts5(body, ref_kind UNINDEXED, ref_id UNINDEXED,
  tokenize = 'trigram');
-- Optional, with sqlite-vec loaded: CREATE VIRTUAL TABLE memory_vec USING vec0(embedding float[256]);
-- Indexes: memory_unit(kind, tier, status), memory_unit(mentioned_at), memory_unit(occurred_start),
-- memory_source(segment_id), memory_source(memory_id), mention(entity_id), entity_alias(norm),
-- link(src_kind, src_id), link(dst_kind, dst_id), todo(status, due_at), todo(remind_at)
```

Notes:
- New tables use `INTEGER PRIMARY KEY`, because VACUUM may change the rowids of tables without one ([SQLite][vacuum], lead check), and `vec0` joins on rowid.
- The FTS tables keep their own text, keyed by `ref_kind` and `ref_id`, instead of pointing at the TEXT-keyed segment table. The normalized column needs Kotlin (Snowball, jWordSplitter), which SQL triggers cannot call.
- `llm_insights.tasks` and `source_segment_ids` stay for compatibility with older recordings. Existing action items can be imported as suggested to-dos linked to their insight's segments. The Markdown export must then read to-dos from the new table.
- Room on the bundled driver pools its connections, because a bundled-driver connection is not thread-safe.

## 4. Search

### 4.1 Query pipeline

1. Parse the query in code. Take a time window from the same resolver the to-dos use ("letzte Woche", "gestern", "last Monday"), names from the alias table, and normalized keywords.
2. Run four arms in parallel:
   - keyword: FTS5 bm25 over segments, memories and to-dos;
   - vector: cosine over memory and segment-window vectors;
   - graph: from the query's entities and the top semantic hits, walk the link table 1-2 hops, with a depth-limited recursive CTE or in Kotlin. Hindsight scores a neighbour as tanh(0.5 x shared entities) + semantic link weight + causal weight. Personalized PageRank with damping 0.5 ([HippoRAG][hipporag]) is the stronger variant;
   - time: items in the window, spread over time buckets as Hindsight does.
3. Fuse with reciprocal rank fusion, k = 60. Omi does the same ([hybrid][omi-hybrid]), and one SQL statement can fuse FTS5 and `vec0` ([example][sqlite-vec-hybrid]).
4. Multiply in Hindsight's boosts: recency (linear over 365 days, +-10%), temporal proximity (+-10%), proof count (+-5%).
5. Skip reranking at first. Hindsight's default path reranks with a cross-encoder. On the phone that costs more than it gains (4.3), so Satori relies on the RRF scores, as Hindsight does when no reranker is configured.
6. Group the results by recording, show the cited segment as the snippet, and pack them into a token budget. Hindsight's low, mid and high budgets are 100, 300 and 1000 candidates. Progressive disclosure keeps a 1B context small: one-line abstracts first, then only the top hits expanded, with a trace to the raw segment on demand ([agent-memory][agentmem]).

The read path never calls the LLM, as in Hindsight and agent-memory.

### 4.2 Components and sizes

| Arm | Component | Size | Notes |
|---|---|---|---|
| Keyword | FTS5 in the bundled SQLite: `unicode61 remove_diacritics 2` over normalized text, plus a `trigram` index | AAR 2.55 MB; indexes tens of MB (Est.; measure the trigram index) | bm25(), snippet(), highlight() |
| German normalization | Snowball German stemmer, jWordSplitter, ae/oe/ue/ss folding | Small pure-Java libraries | Runs in Kotlin when a row is written and on the query |
| Vectors | EmbeddingGemma 1 (300M) on today's b9574; EmbeddingGemma 2 (270M) on b11452 or newer; GGUF Q8_0, on the CPU | 333.6 MB / 309.9 MB files | 768-d Matryoshka output that can be cut to 256-d; F32 activations |
| Vector store | BLOB column plus cosine in Kotlin, or sqlite-vec `vec0` | 50k items: 77 MB at 768-d fp16, 51 MB at 256-d float32, 12.8 MB at 256-d int8 (Est.) | Exact search. sqlite-vec answered 1M x 128-d in 33 ms on an M1 Mac mini |
| Graph | The `link` table with a recursive CTE, or BFS / PageRank in Kotlin | Rows only | Seeds from query entities and top semantic hits |
| Time | The to-do resolver applied to the query; indexes on `occurred_start` and `mentioned_at` | Code | Picks spread over time buckets |
| Fusion | RRF and boosts in Kotlin | Code | |
| Rerank (not by default) | A cross-encoder such as Qwen3-Reranker-0.6B, or mmarco-mMiniLMv2 as a 118.6 MB qint8 ONNX | 0.12-0.6 GB | Only the top 20-30, and only if a test shows a gain (4.3) |

### 4.3 Embedder choice

| Model | Licence | File | Runs on Satori's llama.cpp | Belebele deu-deu | deu-eng / eng-deu | Wikipedia de |
|---|---|---|---|---|---|---|
| [EmbeddingGemma 2][eg2] (270M) | Apache-2.0 | Q8_0 GGUF 309.9 MB ([ggml-org][eg2-gguf]) | Needs b11452 or newer | No results yet | No results yet | No results yet |
| [EmbeddingGemma 1][eg1] (300M) | Gemma terms | Q8_0 GGUF 333.6 MB ([ggml-org][eg1-gguf]) | Yes, on b9574 | 94.47 | 95.62 / 94.17 | 91.66 |
| [bge-m3][bgem3] (0.57B) | MIT | Q8_0 GGUF 634.6 MB | XLM-R is supported | 93.91 | 92.94 / 92.80 | 90.28 |
| [Qwen3-Embedding-0.6B][qwen3emb] | Apache-2.0 | Q8_0 GGUF 639.2 MB | Yes | 93.21 | 93.99 / 92.00 | 89.03 |
| [multilingual-e5-small][e5small] (118M) | MIT | qint8 ONNX 118 MB | GGUF tokenization UNVERIFIED | 92.47 | 87.05 / 86.60 | Not collected |
| [granite-embedding-97m-multilingual-r2][granite] | Apache-2.0 | quint8 ONNX 98 MB; no official GGUF | The converter supports it; the GGUF must carry `hidden_act` | 90.39 | 88.13 / 84.99 | 86.66 |
| [potion-multilingual-128M][potion] (static) | MIT | 512 MB | Not needed: a table lookup | 71.01 | 64.68 / 57.40 | 69.93 |

Scores are nDCG@10 from the [MTEB results repo][mteb-results]. EmbeddingGemma 2 has no German entries yet. Its MMTEB mean (61.36 at 768-d, 60.41 at 256-d) matches EmbeddingGemma 1 (61.15), so similar German quality is expected (UNVERIFIED).

Recommendation, in line with the model study:
- Start with EmbeddingGemma 1 on b9574. It has the best German scores in this table and runs today, under the Gemma terms like today's Gemma 3 1B. Use the ggml-org Q8_0 build of 2026-04-29, whose header carries the dense layers.
- Swap to EmbeddingGemma 2 after the llama.cpp upgrade: Apache-2.0, the same text quality and the same prefixes. Re-embed everything on the swap.
- Consider Qwen3-Embedding-0.6B only if both disappoint on the owner's notes. It costs about twice as much, with no measured German gain.

Store the full 768-d vector as the source of truth (fp16 or float32). If scans get slow, compare on the first 256 dimensions. For EmbeddingGemma 2 that costs about 1 MMTEB point (60.41 vs 61.36) and does a third of the work.

Rules for embeddings:
- Use the prefixes from the model's `config_sentence_transformers.json`: `task: search result | query: ` for queries and `title: none | text: ` for documents. Google's LiteRT-LM page lists different strings; trust the model config. Store the model ID and prefix with every vector.
- Run EmbeddingGemma on the CPU with F32 activations. The EmbeddingGemma 2 card says activations exceed the float16 range, which gives NaN or silently degraded vectors, and version 1 has the same caveat. Keep both off the Adreno OpenCL and Hexagon paths. Reject vectors with NaN or an abnormal norm. Q8_0 weights are fine.
- Keep the raw vectors so any index can be rebuilt, and re-embed when the model changes.
- llama.cpp allocates a logits buffer for encoder-only models too, about 1 MiB per token for EmbeddingGemma (model study; [issue 29388][llamacpp-29388], open fix [PR 30255][llamacpp-30255]). Cap `n_ubatch` at 512 and embed one chunk per decode.
- No reranker by default. In the model study, Qwen3-Reranker-0.6B gained only 1.7 nDCG points over its dense baseline and would need roughly 10-30 s per top-10 on the razr (extrapolated). bge-reranker-v2-m3 scored below the dense baseline.
- Embed the recording summary and windows of a few segments, the equivalent of Smart Connections' sources and blocks. Reor's "first 500 characters" query is too weak for long transcripts.
- Cross-lingual retrieval is what lets an English note and a German note about the same Arzttermin link up; EmbeddingGemma 1 scores 95.62 deu-eng.

### 4.4 German keyword search

- FTS5's `unicode61` folds case and, with `remove_diacritics 2`, diacritics: "Muller" matches "Müller", "Mueller" does not. The porter stemmer is English-only, and nothing splits compounds ([FTS5][fts5]).
- So index a normalized shadow text written in Kotlin, with three steps: Snowball stems (German or English, by segment language), jWordSplitter's compound parts, and ae/oe/ue/ss folding. Normalize the query the same way. Then "Termin" finds "Arzttermin" and "Anrufe" finds "anrufen".
- The trigram index catches substrings, such as "termin" inside "Arzttermin", for queries of 3 or more characters. It also lets LIKE and GLOB use an index.
- Build MATCH queries from letters and digits only (Unicode-aware). Add `*` to the last token and cap the token count, as Chirp does with 16. User text then can never become FTS syntax. Do not copy Omi's `[a-z0-9]+` tokenizer, which drops umlauts.
- BM25 works within one language, as Hindsight's docs note. Mixed EN and DE notes rely on the vector arm to cross languages.
- A stopgap with no new native code: Room `@Fts4` (unicode61 or ICU tokenizer) on the framework driver.

### 4.5 Questions over the notes

Answer "what did I decide about the flat?" in three steps:
1. Read the core pages (a database read).
2. Run recall.
3. Make one Gemma call over the packed results, with citations.

There is no tool loop. MIRIX's topic-first retrieval, one short call that names the topic before searching, is an optional extra. With `n_ctx` fixed at 4096, the packed context has to stay around 2-3k tokens (Est.). The alternative is a configurable `n_ctx`.

## 5. To-dos and reminders

### 5.1 Pipeline: parse first, then the model chooses

Small models should not do date maths:
- Gemma 3 PT 1B scores 2.04 on MGSM (4B: 34.7) and 24.9 on Global-MMLU-Lite, near chance ([model card][gemma3-card]).
- Omi converts UTC to local time in code because "The LLM is unreliable at converting UTC to the user's timezone" ([Omi][omi-proc]).
- Benchmarks of date arithmetic and German temporal reasoning point the same way ([Date Fragments][datefragments], [MultiTempBench][multitemp]).

The pipeline:
1. **Spot and resolve in code.** A Kotlin EN+DE resolver runs over every segment and emits candidates such as T2 = "am Freitag um neun" -> Fr 16.10. 09:00. Its anchor is the segment's own wall clock and the recording's time zone.
2. **Prompt with labels.** The prompt lists the segments as [S1] to [Sm], with the time candidates inline. It also lists the open to-dos as [K1] to [Kk], so the model can update or complete one instead of duplicating it (Omi's candidate_action).
3. **Constrain the output.** The grammar is generated for each call, with enums: `when` in {none, T1..Tn}, `segment` in {S1..Sm}, `target` in {none, K1..Kk}. The model then cannot output a date the parser did not find or cite a segment that does not exist.
4. **Validate.** Four checks:
   - the cited segment exists and contains the title's evidence;
   - the time candidate comes from the cited segment or a neighbour;
   - duplicates are merged by a canonical key (Unicode-normalized, case-folded, punctuation collapsed) and by shared segments ([Omi identity][omi-identity]);
   - dates more than 731 days ahead are dropped ([Omi temporal][omi-temporal]).
5. **Review.** The user confirms each suggestion on a chip that shows weekday and date (5.4).
6. **Remind.** Accepted to-dos with a reminder time arm the alarm (5.5).

### 5.2 The EN+DE time resolver

The resolver is written in Kotlin on `java.time.ZonedDateTime`, so that daylight-saving changes stay correct. It covers:
- relative days: heute, morgen, übermorgen, today, tomorrow;
- weekdays with diese, nächste, kommende, übernächste, this and next;
- "in N Tagen", "in N Wochen", "in N days";
- explicit dates: "16.10.", "16. Oktober";
- clock phrases, including halb, Viertel nach, Viertel vor and dreiviertel;
- parts of day;
- number words from 1 to 59. Parakeet TDT v3's model card does not document inverse text normalization, so expect both "um neun" and "um 9", and check on real Satori recordings.

Run the EN and DE rules on every segment instead of detecting the language first. ctparse does this because people mix the languages.

Policies (all of them settings):

| Case | Proposed default | Why |
|---|---|---|
| Bare weekday ("am Freitag") | Next occurrence, never the past | Recognizers returns a past and a future date; chrono picks the closest, which can be in the past, unless forwardDate is set |
| "nächsten Freitag" | Ambiguous. Pick one by setting, mark it tentative, show the alternative | chrono: said Monday to Friday, it means the weekday in the following week. Duckling: the next occurrence ("naechsten mittwoch" said on a Tuesday is the next day) |
| Bare hour ("um 3", "at 3") | Next occurrence within waking hours, about 07:00-21:00 | Duckling turns "um halb 4" said at 04:30 into 15:30 |
| "halb 8", "Viertel nach 8", "dreiviertel 8" | 07:30 or 19:30 by the same rule | Recognizers returns both 07:30 and 19:30 for "halb 8" |
| Parts of day | Morning 08:00, noon 12:00, afternoon 15:00, evening 19:00 | Tasks.org's title parser; Recognizers gives "am Morgen" 08:00 |
| Date only | A due date with no time; a reminder at 09:00 or none | Vaulto rings date-only reminders at 09:00 |
| "morgen" and "Morgen" | "morgen" = tomorrow; "Morgen", "morgens", "Freitagmorgen" = morning; "guten Morgen" is no date | Recognizers handles "guten Morgen" correctly |
| Direction | Prefer the future, and the near future | ctparse's defaults |

Worked examples, said on Wed 14.10.2026 at 18:00 in Europe/Berlin. The weekdays and the daylight-saving change on 25.10.2026 are a lead check against the tz database.

| Said | Resolves to | Reason |
|---|---|---|
| "call the bank tomorrow at 3" | Thu 15.10. 15:00 | 03:00 is outside waking hours |
| "Arzttermin am Freitag um 9" | Fri 16.10. 09:00, kind appointment | Next Friday occurrence; 09:00 is inside waking hours |
| "nächsten Freitag" | Fri 16.10. (Duckling reading) or Fri 23.10. (chrono reading) | Genuinely ambiguous: the chip shows the chosen date and offers the other |
| "um halb 8" | Today 19:30 | 07:30 has passed; 19:30 is the next waking occurrence |
| "in zwei Wochen um 9" | Wed 28.10. 09:00 CET | Wall-clock arithmetic. Adding 14 x 24 h to the instant would give 08:00, because summer time ends on 25.10. |
| "Freitagmorgen" | Fri 16.10. 08:00 | "-morgen" is the morning |
| "guten Morgen" | No date | A greeting |

Anchor on the recording, never on the processing time:
- Final and batch insights run after Stop. "morgen" said at 23:55 in a recording that runs past midnight needs the segment's own clock.
- Imported audio anchors on the file's time.
- Sessions need a stored zone ID.

Tests: put JVM tests in `domain/src/test`, built from licence-compatible corpora:
- the Recognizers-Text spec files (MIT; the German DateTimeModel alone has 202 cases with reference times and expected values);
- Duckling's DE and EN corpora (BSD-3; reference time Tue 2013-02-12 04:30);
- rustling-ontology's de and en training files (MIT or Apache-2.0).

Seed the rules from these and from ovos-date-parser's German rules (Apache-2.0).

If coverage falls short, escalate in this order:
1. Vendor Recognizers-Text's Java port (MIT, but it passes only 72 of the 202 German cases).
2. Try a rustling JNI build (dormant).

chrono inside JavaScriptSandbox is fine for English but weak for spoken German. Rule out:
- ML Kit (runtime model download, closed source, diagnostics);
- HeidelTime, SUTime and Hawking (GPL);
- TextClassifier (no AOSP German model, no resolved timestamp).

### 5.3 The extraction call

Prompt layout. The weekday is stated next to the date, as in Google's Mobile Actions data and Calendar Add's reference block ([prompts][caladd-prompts]):

```
Recording: voice note, Wed 14.10.2026 18:02, Europe/Berlin. Day of week: Wednesday.
Segments, with time candidates found by the parser:
[S1] Ich muss morgen um drei die Bank anrufen.     T1 "morgen um drei" = Do 15.10. 15:00
[S2] Und am Freitag um neun ist der Arzttermin.     T2 "am Freitag um neun" = Fr 16.10. 09:00
Open to-dos:
[K1] Unterlagen für den Kredit sammeln
List the user's own commitments and appointments. Use only the S, T and K labels above.
Output JSON with the keys described below.
```

The grammar is generated per call as raw GBNF. The `when`, `segment` and `target` rules change with every recording:

```
root      ::= "{\"todos\":[" ( todo ( "," todo ){0,7} )? "]}"
todo      ::= "{\"title\":" title ",\"kind\":" kind ",\"owner\":" owner
              ",\"capture\":" capture ",\"when\":" when ",\"certainty\":" certainty
              ",\"segment\":" segment ",\"action\":" action ",\"target\":" target "}"
title     ::= "\"" char{1,80} "\""
char      ::= [^"\\\x7F\x00-\x1F]
kind      ::= "\"task\"" | "\"appointment\""
owner     ::= "\"me\"" | "\"other\"" | "\"unknown\""
capture   ::= "\"explicit_command\"" | "\"clear_commitment\"" | "\"direct_request\"" | "\"inferred_next_step\""
certainty ::= "\"confirmed\"" | "\"tentative\""
action    ::= "\"create\"" | "\"update\"" | "\"complete\""
when      ::= "\"none\"" | "\"T1\"" | "\"T2\""
segment   ::= "\"S1\"" | "\"S2\""
target    ::= "\"none\"" | "\"K1\""
```

The negated character class lets Ä, Ö, Ü and ß through. Anarlog's `[A-Z]` classes would reject a capital umlaut ([gbnf crate][anarlog-gbnf]).

Rules from Omi, shortened for a 1-2B model:
- The title is verb-led, at most 15 words, and carries no timing.
- The due date stays empty unless a date was explicitly committed.
- Never invent an hour.
- Mark certainty, and record who owns the task.

Also:
- The JSON keys are English in both languages, which also removes the parser's fallbacks for localized keys. Titles stay in the spoken language.
- New German prompt strings use the project's neutral infinitive phrasing.
- Start from low-temperature sampling, such as Meetily's "tight_structured" preset.

Changes in `ai_chat.cpp`:
- Pass the grammar as raw GBNF (`COMMON_GRAMMAR_TYPE_USER`) and keep `generation_prompt` empty, since the app assembles its own prompts. The JSON-schema path (`json_schema_to_grammar()`, tagged `OUTPUT_FORMAT`) supports enum, const, min/maxItems, min/maxLength and integer ranges. But with Gemma 4 it can let a thought channel through even when thinking is off ([issue 30256][llamacpp-30256]), which is why the model study recommends raw GBNF.
- Catch every exception from grammar parsing and sampler setup. An uncaught C++ exception that crosses JNI aborts the app.
- `g_sampler` is global and rebuilt only in `updateSamplerConfig()`. Add a JNI path that builds a fresh sampler with the grammar for each extraction and restores the chat sampler afterwards.
- Write repetition as `x{0,N}`, never as repeated `x?`. Describe the output in the prompt, because the grammar is not shown to the model. Keep sampling on the CPU: backend sampling is not compatible with grammars ([guide][llamacpp-grammars]).
- A grammar applied from the first token suppresses a thinking model's thought tokens, so test Gemma 4 with its chat template.

If Gemma 3 1B is too weak in German, try a stronger model first:
- Gemma 4 E2B is the planned one: Apache-2.0, 128K context, native function calling and 35+ languages ([model card][gemma4-card]).
- The model study ranks Gemma 3 1B weakest on the EuroEval German leaderboard. It A/B-tests E2B against Granite 4.1-3B and Qwen3-4B-Instruct-2507 on the owner's transcripts.
- NuExtract-1.5-tiny (MIT, 0.5B, German listed) is the fallback second-stage extractor.

Keep the parser-first design either way. Grammars fix the syntax, not the meaning.

### 5.4 Review before save

Omi never writes an automatically extracted task to the task list. It proposes one only when three things hold: the owner is the user, the deliverable is concrete, and both capture and ownership confidence are at least 0.8. The one exception is a spoken command ([capture policy][omi-capture]). Satori follows the same rule:

- After Stop, the recording shows a "Suggested to-dos" block of hairline rows. Each row shows the title, a chip with weekday and date ("Fr 16.10. 09:00") and the quote from the source segment. Actions: add, edit, dismiss, and "add all".
- The home screen gets a To-do section (already decided): open to-dos sorted by due date, plus the number of waiting suggestions. Overdue items get Grille's dot-matrix "!" glyph, not red, because red only ever means the microphone is live.
- Tentative and ambiguous items show the alternative date in the edit sheet. They get no reminder until confirmed.
- An explicit command, "Satori, erinnere mich ..." or "Satori, remind me ...", creates an open to-do with its reminder at once and offers undo. Omi took its wake-word variants (omi, omie, omni) from real transcripts ([wake word][omi-wake]). Collect the variants Parakeet actually produces for "Satori" the same way.
- The edit sheet holds title, date, time, reminder, kind, "add to calendar" for appointments, and a jump to the source segment.
- Dismissed suggestions are stored, so re-extraction does not offer them again.
- The cover screen shows the next reminder with Done and Snooze, and the suggestion list with add and dismiss.

### 5.5 Android reminder mechanics (Android 14 to 16)

| Topic | Do this | Source |
|---|---|---|
| Exact-alarm permission | Declare `USE_EXACT_ALARM`: granted at install and not revocable by the user, and its holders stay in the WORKING_SET standby bucket or better. Add `SCHEDULE_EXACT_ALARM` with `maxSdkVersion="32"`. Play policy does not apply to a sideloaded app. Still call `canScheduleExactAlarms()` and handle `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` | [permissions][permissions], [alarms guide][alarms] |
| Why not `SCHEDULE_EXACT_ALARM` alone | It is not pre-granted to fresh installs of apps targeting Android 13 or higher (Satori targets 35), and revoking it stops the app and cancels its exact alarms | [alarms guide][alarms], [Android 14][exact14] |
| Notifications | `POST_NOTIFICATIONS` is declared but needs the runtime grant; ask when the first reminder is accepted | Manifest |
| Scheduling | One "next due" alarm: `setExactAndAllowWhileIdle(RTC_WAKEUP, ...)` with a PendingIntent to a receiver. When it fires, post everything due by now plus a small slack, then arm the next. This is Tasks.org's pattern | [AlarmManager][alarmmanager] |
| Quotas | AOSP main allows exact allow-while-idle alarms from permission holders 72 times per hour, inexact ones 7 per hour, and 500 alarms per app; OEMs can tune these | [AlarmManagerService][alarm-aosp] |
| Real wake-ups | Use `setAlarmClock` only for true wake-up items, because the system shows it as the next alarm, or hand them to the Clock app with `AlarmClock.ACTION_SET_ALARM` | [AlarmManager][alarmmanager] |
| Re-arming | On `BOOT_COMPLETED` (needs `RECEIVE_BOOT_COMPLETED`, lead check), `MY_PACKAGE_REPLACED`, `TIMEZONE_CHANGED`, `TIME_SET`, the exact-alarm permission broadcast, and every app start. The boot receiver should only re-arm alarms | [alarms guide][alarms] |
| Lost alarms | A force stop removes all alarms. App hibernation cancels them without rescheduling: check `getUnusedAppRestrictionsStatus()` and offer `createManageUnusedAppRestrictionsIntent()`. Motorola rates 3 of 5 on dontkillmyapp: set Satori's battery use to Unrestricted | [hibernation][hibernation], [dontkillmyapp][dontkill] |
| Not WorkManager | Doze defers jobs, and Android 16 adjusts JobScheduler quotas by standby bucket even while a foreground service runs. Use WorkManager for re-extraction, a daily missed-reminder sweep and exports | [Doze][doze], [Android 16][bc16] |
| Channel | A "Reminders" channel at `IMPORTANCE_HIGH` with `Notification.CATEGORY_REMINDER`, which breaks through Do Not Disturb when the user allows reminders there (`PRIORITY_CATEGORY_REMINDERS`) | [Notification][notification], [Policy][dnd-policy] |
| Actions | Done and Snooze (10 minutes, as in themachine) through a BroadcastReceiver; tapping opens the to-do at its source segment and, later, the audio position | |
| Full-screen intents | Skip them: Android 14 limits them to calling and alarm apps | [Android 14 changes][bc14] |
| Privacy | Reminders appear on the lock screen and the cover screen. Offer a public version without the title, as the recording notification has | |

Store the wall-clock intent (`due_local` plus `zone_id`) next to the computed instant. Recompute the instants on `TIMEZONE_CHANGED` and `TIME_SET`, so "9:00" stays 9:00 local.

Omi arms its reminder 1 h before `due_at`, uses the task ID's hash as the notification ID, and re-arms or cancels the reminder on edit and delete ([handler][omi-reminder]). Satori can use the integer to-do ID directly.

### 5.6 Hand-off to other apps

Ranked:
1. **Satori's own list.** Always. It works offline and keeps the segment links.
2. **Tasks.org** through `content://org.tasks.api/v0` ([API][tasks-api]):
   - Insert the task with `due_date` and `due_all_day` plus a reminder row.
   - Declare `org.tasks.permission.READ_TASKS` and `WRITE_TASKS` and `<queries><provider android:authorities="org.tasks.api"/></queries>`, and check the provider's version.
   - v0 is unstable, and Tasks.org does not parse dates from titles, so send resolved dates.
   - The app is GPL: talk to it over IPC and copy no code.
3. **`.ics` files** through a FileProvider and `ACTION_VIEW` with `text/calendar`: VTODO for jtx Board, VEVENT for Etar and other calendar apps.
   - Rules from [RFC 5545][rfc5545]: a stable UID per to-do, so that a re-export updates instead of duplicating; `DUE;VALUE=DATE` for all-day items; every `TZID` needs a matching VTIMEZONE, or write UTC; a VALARM with `TRIGGER;RELATED=END` requires DUE.
   - [RFC 9074][rfc9074] adds alarm UIDs, acknowledgement and snoozing.
   - Put a `satori://` link to the session and segment in URL or DESCRIPTION. That needs a new intent filter.
   - [biweekly][biweekly] (BSD-2) or a few hand-written lines produce the file.
4. **Appointments to the calendar** with `CalendarContract`. Either fire an `ACTION_INSERT` intent (no permission; the user confirms), or keep a Satori-owned local calendar (`ACCOUNT_TYPE_LOCAL`, needs `READ_CALENDAR` and `WRITE_CALENDAR`, never synced). Never write into a Google-account calendar: it syncs the text off the phone. Delivery of calendar reminders depends on the calendar app (UNVERIFIED on the razr).
5. **todo.txt** with `due:YYYY-MM-DD` for Markor ([format][todotxt]).

Pick one notifier per to-do. If Satori and Tasks.org or a calendar both remind, the user gets every reminder twice. jtx Board's provider accepts only sync adapters, and OpenTasks has been dormant since 2021.

## 6. The map UI

### 6.1 Principles

- **Related list first.** Most "automatic linking" in PKM apps is a nearest-neighbour query that runs when a note opens, shown as a ranked list. Reor shows the 20 nearest chunks; Smart Connections compares scores only within one list. Under each recording and memory, Satori lists its neighbours with the reason (same day, shared person, similar) and the matching quote.
- **Mobile apps ship no graph.** Logseq's mobile app and Anytype's Android app have none: capture, search and pages with backlinks come first. Entity pages are the non-graph half of the map: a person or project with its observations, memories, to-dos and recordings, like Logseq's "linked references".
- **Confirm, never assume.** Every edge shows its evidence and state, and the user confirms or hides suggestions with one tap.
- **Time is the spine.** Default views are scoped to today or this week and entered through search (van Ham and Perer's "Search, Show Context, Expand on Demand").
- **Deterministic layouts.** A view looks the same on every visit, so the user remembers where things are, and no physics simulation runs on the phone.
- **A node budget.** Degree of interest = a x a-priori importance (tier, pin, recency, mention count) + b x search relevance + c x distance from the focus in hops. A priority queue grows a connected subgraph of at most S nodes in O(S log S) ([van Ham and Perer][vanham]). S is 8-12 on the cover screen and 30-50 on the inner screen.

### 6.2 Inner screen (6.9")

Views, in build order:
1. The Related list under each recording and memory.
2. Entity pages for people, places and projects.
3. The ego map, with the focus in the centre:
   - above it, what it belongs to (person, project, place, day);
   - below it, what came out of it (to-dos, facts, follow-ups);
   - left and right, co-mentioned and similar items;
   - beside it, siblings from the same recording.

   This is ExcaliBrain's layout, sorted by score or time instead of alphabetically. Tapping a node re-centres the map with one animation.
4. Day and week maps: recordings on a time line, with their to-dos and people.
5. A topic overview with semantic zoom. Zoomed out, it shows topic labels with the summaries written for them overnight; zoomed in, single memories, as GraphRAG's community levels suggest.
6. A per-recording mind map. Gemma writes an indented outline whose leaves carry segment IDs, and Satori parses it into a tree. "Expand this node" asks the model for one branch only, a job small enough for a 1B model (AFFiNE's pipeline).

```
+--------------------------------------------------+
| <   Map       [ Bank ........................ ]  |
|     Today    This week    All       min ..o..    |
|                                                  |
|   Mama               Wohnung           Di 13.10. |  part of: person, project, day
|       \                 |                /       |
|  Kredit ------- ( Bank anrufen ) ------- Bank    |  co-mentioned, similar
|       /                 |                \       |
|  To-do: Unterlagen   Fact: Termin   Note 18:02   |  came out of it
|                                                  |
|  2 more from the same recording           List   |  siblings; list view
+--------------------------------------------------+
```

### 6.3 Cover screen (3.6", 480 x 410 dp usable)

The cover screen gets the list form: a Breadcrumbs-style matrix that groups neighbours by relation type. The same list serves TalkBack. Relevance rings are an option for at most 8-12 nodes: the focus in the centre and its neighbours on 2-3 rings by similarity band, as in Smart Connections Visualizer.

```
+--------------------------------+
| Bank anrufen         Do 15:00  |
| ------------------------------ |
| Person     Bank                |
| Project    Wohnung             |
| Source     "morgen um drei..." |
| Similar    Kredit, 2 more      |
| ------------------------------ |
| Done                 Snooze    |
+--------------------------------+
```

Use taps and mid-screen drags only: on the cover screen, a swipe that starts low is taken as the home gesture (`docs/DEV_SETUP.md`). Editing maps stays on the inner screen.

### 6.4 Rendering

- Draw with Compose Canvas and `TextMeasurer`. Pan and zoom with telephoto's `Modifier.zoomable`. It "can be used for anything such as text, canvas drawings", but it consumes taps, so route them through its onClick.
- Compute layouts off the main thread and cache them in `map_layout`. Let the user pin node positions so a project's map stays put (Juggl's workspaces).
- Prune edges before drawing; a single global threshold fails when weights vary.
  1. Take kNN with k of about 10.
  2. Keep mutual neighbours, or apply the disparity filter.
  3. Draw 3-5 edges per node.

  Cluster on the denser graph: kNN clustering needs a "surprisingly high" k ([Maier et al.][maier]).
- Labels: use Logseq's numbers as a starting spec ([graph code][logseq-pixi]):
  - a label budget, chosen through a 132 x 24 px screen-cell occupancy grid in priority order (pinned, then tags, then degree);
  - labels cut at 19 characters, with the full text on tap;
  - edge labels only at zoom 0.82 or more;
  - cluster hulls behind their nodes.
- For the rare global view, port d3-force (ISC) or a Barnes-Hut Fruchterman-Reingold layout, and precompute it overnight. JGraphT has one, but its licence needs a check.
- For mind maps, vendor compose-mindmap (Apache-2.0) from source, or port GraphView's Buchheim-Walker tidy tree. markmap in a WebView works as a quick prototype but does not match Grille.
- Graph code is cheap at this size: label propagation, PageRank and BFS over a few thousand nodes run in milliseconds (Est.).

### 6.5 Grille styling and accessibility

From the UI spec (`docs/GRILLE_DESIGN.md`):
- True black canvas (`void`). Labels in Space Grotesk, sentence case. Doto only for numbers of 28 sp or more, such as the count of open to-dos.
- Edges are 1 px hairlines (`unlit`). Strength shows as opacity and width, never as colour. Red (`rec`) only ever means the microphone is live, so "today" and "recent" show as brightness (`ink` against `ash`).
- No cards, shadows, gradients or tonal tints; cluster hulls are hairlines.
- Motion only answers an action: the re-centre animation, nothing idle. Respect "remove animations".
- Every node has a content description, touch targets are at least 48 dp, and text contrast is at least 4.5:1. Every 2D view has a list version.

## 7. Runtime budget on the razr

The razr has 11.1 GiB of usable RAM and 408 GB of free storage (`docs/DEV_SETUP.md`).

### 7.1 Memory

| Item | File size | Notes |
|---|---|---|
| Parakeet TDT 0.6B v3 int8 | 670 MB (encoder 652 MB) | Released after Stop; unload it before overnight work |
| Gemma 3 1B Q8_0 | 1.07 GB | Today's LLM |
| Gemma 4 E2B QAT Q4_0 | 3.35 GB, plus a 0.99 GB vision/audio projector that text does not need ([GGUF][gemma4-e2b-gguf]) | The planned extraction and consolidation model. On b9574 the loader maps the whole file with MAP_POPULATE and repacks Q4 weights into heap memory, so expect about 4.5-5 GB RSS; its KV cache is about 75 MB at `n_ctx` 4096 (model study, code reading) |
| Gemma 4 E4B QAT Q4_0 | 5.15 GB | About 7.5 GB RSS on b9574 (model study); only if memory allows |
| EmbeddingGemma 1 or 2, Q8_0 | 0.33 GB / 0.31 GB | Keep it resident next to the LLM during overnight work; load it for search |
| Optional reranker | 0.12-0.6 GB | Skip at first |
| Vectors | 13-150 MB | Depends on item count and int8 or float32 |
| FTS indexes | Tens of MB (Est.) | Measure the trigram index |

By file size, Parakeet, E2B and the embedder hold about 4.3 GB of weights, before KV caches and runtime overhead. By the model study's RSS estimate, E2B alone needs 4.5-5 GB on b9574.

The only RAM reading on the razr is MemAvailable 2.93 GiB with the phone in normal use (model study). Android therefore has to reclaim cached apps before a job of that size runs, and a background process is the first thing the low-memory killer removes. Rules:
- Never load Gemma 3 1B and the stronger model together. Run consolidation with Parakeet unloaded.
- Run the overnight job with foreground priority, for example in WorkManager's foreground mode with a quiet notification (lead view; UNVERIFIED on the razr).
- Measure PSS with `dumpsys meminfo` before choosing between the model study's A/B candidates: E2B, Granite 4.1-3B (1.98 GB file) and Qwen3-4B-Instruct-2507 (2.38 GB file). The razr's real headroom is UNVERIFIED.

### 7.2 Storage

| Item | Size |
|---|---|
| Models: Parakeet, Gemma 3 1B, Gemma 4 E2B, EmbeddingGemma | About 5.4 GB; `NPU_RESEARCH.md` expects 4-9 GB of models overall |
| Transcript text | About 60 KB per hour of speech (Est., at 150 words a minute) |
| Segment-window vectors | About 175 KB per hour at 768-d fp16, 115 KB at 256-d float32, 30 KB at 256-d int8 (Est., one vector per 512 characters, MediaPipe's default chunk size) |
| One year at one recorded hour a day | About 22 MB of text and 40-65 MB of vectors, plus indexes (Est.) |

Storage is not the constraint. RAM and battery are.

### 7.3 Battery and when work runs

| When | What runs | Models loaded | Constraint |
|---|---|---|---|
| While recording | STT and the periodic insights, as today; nothing new | Parakeet (+ Gemma 3 1B) | Recording foreground service |
| Right after Stop | Final or batch insight; to-do extraction; resolver; quote validation; FTS rows; AI title | The LLM | The existing processing service. The missing wake lock for batch inference after Stop must be fixed first |
| Minutes later | Embeddings, entity resolution, links | The embedder | WorkManager, small and resumable jobs |
| Overnight | Consolidation, observations, core pages, topics, layouts, missed-to-do sweep | The stronger model + the embedder | WorkManager with `requiresCharging` and `requiresDeviceIdle`, with foreground priority |
| Any time | Reminders | None | AlarmManager |

Battery:
- STT is the largest cost during recording (`NPU_RESEARCH.md`).
- `NPU_RESEARCH.md` estimates a 3k-token insight at about 25-45 s and 5-8 W (125-360 J) on the CPU with Gemma 3 1B. The model study notes that 3k tokens is the prompt cap; a periodic insight is typically 0.5-0.7k tokens.
- The extraction call reads the transcript in 5,000-character chunks. At the 4.14 characters per token that the model study measured for Gemma 3, each chunk is about 1.2k tokens, plus a short JSON answer. So each chunk costs less than the 3k-token case (Est.).
- Embeddings: the model study extrapolates about 900 tokens/s for EmbeddingGemma 2 Q8_0 on this chip, or about 15 s per hour of speech. The only published phone figure is LiteRT-LM's 27.1 ms per 128-token text on a Galaxy S26 Ultra CPU. llama.cpp on the razr is UNVERIFIED.
- Overnight work runs on the charger and stays out of the battery budget.
- Measure energy with `dumpsys batterystats` or `BatteryManager.CURRENT_NOW`, as the NPU plan does.

## 8. Plan, risks and open questions

### 8.1 Dependencies on other work

- The airplane-mode baseline and in-app model import. New models (EmbeddingGemma, the stronger LLM) arrive the same way: fetched on the phone, never on the Mac.
- Engine fixes: engine recovery after a native failure, C++ exceptions crossing JNI, the insight parser's defects and a wake lock after Stop.
- The multi-model handle refactor (`NPU_RESEARCH.md`, architecture change 1). `ai_chat.cpp` holds one process-wide model, and from slice 3 on an embedder has to sit next to the LLM.
- The llama.cpp upgrade to b11452 or newer (the NPU plan names b11514). Phase 2 needs it only for EmbeddingGemma 2. The model study warns that the jump crosses API changes (`use_mmap` and `use_mlock` replaced by `load_mode`, a new `lazy_mode`) and an unresolved Android CPU regression after b10369. It advises keeping flash attention off for now.
- The model A/B in the model study, which decides the stronger LLM for slices 3 and 4.
- Saving audio with segment start and end offsets (the NPU plan keeps the token timestamps). This is needed only for tap-to-play; tap-to-segment works without it.
- Recording categories, which become memory tags and scopes.

### 8.2 Slices

These are Phase 2 slices, not the NPU plan's phases.

| Slice | Delivers | Needs | Done when |
|---|---|---|---|
| 1. To-dos and reminders | The EN+DE resolver with corpus tests; the grammar path in `ai_chat.cpp`; to-do extraction in every mode, voice notes included; the suggestion block; the home To-do section; reminders and notifications; the explicit-command path; Markdown export reading the new table | A migration with `todo`, `todo_source` and session `zone_id` (with or after the categories migration to v7); Gemma 3 1B on b9574; WorkManager added for sweeps | The worked examples in 5.2 resolve as listed; an accepted reminder fires in airplane mode and after a reboot; no network permission is needed |
| 2. Search | Room 2.8 on the bundled driver; FTS5 word and trigram indexes; the Unicode-safe query builder; time filters from the resolver | Slice 1's resolver | "Termin" finds "Arzttermin", "Mueller" finds "Müller", "Bank letzte Woche" filters by date; no LIKE scans remain |
| 3. Memories and Related | Facts (verbatim mode on 1B, full facts on the stronger model), entities and aliases, links, EmbeddingGemma vectors, hybrid recall, the Related list | Multi-model handles; EmbeddingGemma 1 on b9574 (version 2 after the upgrade) | Every fact opens its source segment; Related shows a reason per item; hybrid recall beats FTS5 alone on the private test set |
| 4. Consolidation and core | The overnight job; observations; core pages with versions; the deletion cascade; questions over the notes | Slice 3; the stronger model imported (Gemma 4 E2B or the A/B winner) | Pages refresh only on the charger; deleting a recording updates the pages that cited it |
| 5. The map | Ego map, cover-screen list, topics, per-recording mind map, layout cache, pins | Slices 3 and 4 | Node budgets hold; layouts are stable between visits; TalkBack reads the list views |
| 6. Hand-offs and extras | Tasks.org push, `.ics` export, calendar insert, todo.txt, a Markdown memory export (an index plus one file per person or topic), the missed-to-do sweep; optional reranker or GLiNER2 test | Slice 1 | A re-export updates instead of duplicating; each to-do has one notifier |

Keep the evaluation set private. A few dozen of the owner's EN and DE notes stay on the phone, or are written specially for testing, and are never committed. MiniRAG's LiHua-World is an English starting set.

### 8.3 Risks

| Risk | Why it matters | Mitigation |
|---|---|---|
| Small-model extraction in German | Gemma 3 1B is near chance on multilingual knowledge; a grammar fixes syntax, not meaning; Graphiti warns that small models break schemas | Parser-first design, review before save, a stronger model from the model study's A/B, NuExtract as fallback, the private test set |
| Wrong or ambiguous dates | A wrong weekday silently moves an appointment | Deterministic resolver, weekday and date on every chip, policy settings, corpus tests |
| Meetings without diarization | Other people's first-person statements become the user's facts or to-dos | The owner field; extract facts from voice notes first; treat meeting facts as speaker unknown until diarization exists |
| The llama.cpp jump from b9574 to b11xxx | API changes under the JNI layer and an unresolved Android CPU regression after b10369 (model study) | Not needed before EmbeddingGemma 2; do it as its own step with tests, as the NPU plan does |
| RAM pressure | E2B needs about 4.5-5 GB RSS on b9574 against one MemAvailable reading of 2.93 GiB; a background job of that size is the first thing the low-memory killer removes | Sequence the models; unload Parakeet; run overnight work with foreground priority; measure PSS; consider the smaller A/B candidates |
| EmbeddingGemma in float16 | NaN or silently degraded vectors | CPU with F32 activations; NaN and norm checks |
| Reminders that never fire | Motorola battery policies, hibernation, force stop | `USE_EXACT_ALARM`, every re-arm trigger, Unrestricted battery, a daily sweep for missed reminders |
| Duplicate reminders | Satori and Tasks.org or a calendar both notify | One notifier per to-do |
| Privacy leaks | Lock-screen and cover-screen notifications; synced calendars; ML Kit diagnostics; transcript text in logs | Notification visibility setting; local calendars only; no ML Kit; no transcript text in any log |
| Graph noise | Similarity edges turn into a "hairball" | Pruning, node budgets, list views, edge states |
| Drift from LLM rewrites | Rewriting older notes (A-MEM's evolution) lets them drift | Keep source text immutable; version generated fields; keep history |
| Young libraries | sqlite-vec is pre-v1; compose-mindmap and KotlinFlow are new single-author projects | Pin or vendor; keep raw vectors; own renderer |
| Licence mistakes | Several close matches are GPL, AGPL or source-available | Section 2.6 list; keep notices for MIT and Apache ports |
| Schema churn | Migrations on a database that keeps changing | Migration tests; an exported schema folder |

### 8.4 Open questions for the user

1. "nächsten Freitag" said on a Wednesday: this week's Friday (16.10.) or next week's (23.10.)?
2. Default times:
   - A bare hour ("um 3"): next occurrence between 07:00 and 21:00?
   - A date-only to-do: remind at 09:00, or not at all?
   - An appointment: how long before should the reminder ring? Omi uses 1 h, Speakr 15 minutes.
3. Explicit commands ("Satori, erinnere mich ..."): create the reminder at once with undo, or still review?
4. Commitments of other people in meetings ("Peter schickt die Folien"): keep them as "waiting on" items, or ignore them?
5. Besides Satori's own list, should to-dos go anywhere else: Tasks.org, jtx Board via `.ics`, or a local Satori calendar for appointments? Which app should notify?
6. Lock screen and cover screen: show the reminder text, or only "Reminder"?
7. Deleting a recording: keep the accepted to-dos and confirmed memories that came from it, or delete them too?
8. Is it fine to add an embedder (about 0.3 GB) and a 2-3.4 GB model for overnight work, and to consolidate only while the phone charges, which in practice means overnight?
9. Numbers said aloud (IBANs, phone numbers) and health details: store them as memories, keep them in a separate locked store, or skip them?
10. Should recording categories act as memory scopes, for example separate core pages for work and private?
11. On the cover screen, is the list form enough, with the visual map on the inner screen only?

[hs]: https://github.com/vectorize-io/hindsight
[hs-retain]: https://raw.githubusercontent.com/vectorize-io/hindsight/main/hindsight-docs/docs/developer/retain.md
[hs-recall]: https://raw.githubusercontent.com/vectorize-io/hindsight/main/hindsight-docs/docs/developer/retrieval.md
[hs-obs]: https://raw.githubusercontent.com/vectorize-io/hindsight/main/hindsight-docs/docs/developer/observations.mdx
[hs-mm]: https://raw.githubusercontent.com/vectorize-io/hindsight/main/hindsight-docs/docs/developer/mental-models.mdx
[hs-ml]: https://raw.githubusercontent.com/vectorize-io/hindsight/main/hindsight-docs/docs/developer/multilingual.mdx
[hs-consol]: https://raw.githubusercontent.com/vectorize-io/hindsight/main/hindsight-api-slim/hindsight_api/engine/consolidation/prompts.py
[hs-paper]: https://arxiv.org/html/2512.12818v1
[graphiti]: https://github.com/getzep/graphiti
[graphiti-edges]: https://raw.githubusercontent.com/getzep/graphiti/main/graphiti_core/edges.py
[letta-code]: https://github.com/letta-ai/letta-code
[memgpt]: https://arxiv.org/html/2310.08560
[mem0]: https://github.com/mem0ai/mem0
[mem0-v3]: https://docs.mem0.ai/migration/oss-v2-to-v3
[amem]: https://github.com/WujiangXu/A-mem-sys
[amem-paper]: https://arxiv.org/html/2502.12110
[cognee]: https://github.com/topoteretes/cognee
[cognee-rs]: https://github.com/topoteretes/cognee-rs
[cognee-events]: https://raw.githubusercontent.com/topoteretes/cognee/main/cognee/tasks/temporal_graph/models.py
[minirag]: https://github.com/HKUDS/MiniRAG
[lightrag]: https://github.com/HKUDS/LightRAG
[graphrag]: https://github.com/microsoft/graphrag
[graphrag-methods]: https://raw.githubusercontent.com/microsoft/graphrag/main/docs/index/methods.md
[hipporag]: https://github.com/OSU-NLP-Group/HippoRAG
[txtai-graph]: https://raw.githubusercontent.com/neuml/txtai/master/docs/embeddings/configuration/graph.md
[memoryos]: https://github.com/BAI-LAB/MemoryOS
[lightmem]: https://github.com/zjunlp/LightMem
[memobase]: https://github.com/memodb-io/memobase
[memos]: https://github.com/MemTensor/MemOS
[agentmem]: https://github.com/tigerless-labs/agent-memory
[gliner2]: https://github.com/fastino-ai/GLiNER2
[longmem]: https://github.com/CaviraOSS/LongMemory
[basicmem]: https://github.com/basicmachines-co/basic-memory
[supermem]: https://github.com/supermemoryai/supermemory
[memagent]: https://huggingface.co/driaforall/mem-agent
[tencentdb]: https://github.com/TencentCloud/TencentDB-Agent-Memory
[kuzu]: https://github.com/kuzudb/kuzu
[genagents]: https://ar5iv.labs.arxiv.org/html/2304.03442
[omi]: https://github.com/BasedHardware/omi
[omi-domain]: https://github.com/BasedHardware/omi/blob/c515ec689c56092b14437942a333cc1f6b38df0a/backend/docs/memory/domain_model.md
[omi-notes]: https://github.com/BasedHardware/omi/blob/c515ec689c56092b14437942a333cc1f6b38df0a/backend/utils/llm/conversation_notes_prompts.py
[omi-struct]: https://github.com/BasedHardware/omi/blob/c515ec689c56092b14437942a333cc1f6b38df0a/backend/models/structured_extraction.py
[omi-capture]: https://github.com/BasedHardware/omi/blob/c515ec689c56092b14437942a333cc1f6b38df0a/backend/utils/task_intelligence/capture_policy.py
[omi-memtier]: https://github.com/BasedHardware/omi/blob/c515ec689c56092b14437942a333cc1f6b38df0a/backend/models/product_memory.py
[omi-obs]: https://github.com/BasedHardware/omi/blob/c515ec689c56092b14437942a333cc1f6b38df0a/backend/utils/llm/working_observations.py
[omi-hybrid]: https://github.com/BasedHardware/omi/blob/c515ec689c56092b14437942a333cc1f6b38df0a/backend/utils/retrieval/hybrid.py
[omi-temporal]: https://github.com/BasedHardware/omi/blob/c515ec689c56092b14437942a333cc1f6b38df0a/backend/utils/llm/temporal.py
[omi-identity]: https://github.com/BasedHardware/omi/blob/c515ec689c56092b14437942a333cc1f6b38df0a/backend/utils/conversations/action_item_identity.py
[omi-wake]: https://github.com/BasedHardware/omi/blob/c515ec689c56092b14437942a333cc1f6b38df0a/backend/utils/conversations/wake_word.py
[omi-proc]: https://github.com/BasedHardware/omi/blob/c515ec689c56092b14437942a333cc1f6b38df0a/backend/utils/llm/conversation_processing.py
[omi-reminder]: https://github.com/BasedHardware/omi/blob/c515ec689c56092b14437942a333cc1f6b38df0a/app/lib/services/notifications/action_item_notification_handler.dart
[caladd]: https://github.com/chpomob/calendar_add
[caladd-prompts]: https://github.com/chpomob/calendar_add/blob/d966d1ac40213667c97c49b6b91154574d33637d/app/src/main/java/com/calendaradd/service/EventAnalysisPrompts.kt
[vox]: https://github.com/Danmoreng/vox-transcribe
[meetily]: https://github.com/Zackriya-Solutions/meetily
[anarlog]: https://github.com/fastrepl/anarlog
[gallery]: https://github.com/google-ai-edge/gallery
[gallery-retrieval]: https://github.com/google-ai-edge/gallery/blob/6f572e32223fb393519801c0f25da48a86682a9a/Android/src/app/src/main/java/com/google/ai/edge/gallery/services/semanticretrieval/SemanticRetrievalService.kt
[vaulto]: https://github.com/dirusanov/vaulto_note_mobile
[ramble]: https://github.com/ziyaad-mallick/ramble
[ramble-miko]: https://github.com/ziyaad-mallick/ramble/blob/c0ef1aad61bf3e1dea7dc11dc8314e1160ca1a28/lib/services/miko_service.dart
[openwhispr]: https://github.com/OpenWhispr/openwhispr
[openwhispr-search]: https://github.com/OpenWhispr/openwhispr/blob/5f9a88b0481a066c147bfd2c38ea1d9acae502ee/src/helpers/noteSearch.js
[scriberr]: https://github.com/rishikanthc/Scriberr
[scriberr-note]: https://github.com/rishikanthc/Scriberr/blob/a353078fd96b8aca4002681813524b7397c90df1/internal/models/note.go
[speakr]: https://github.com/murtaza-nasir/speakr
[screenpipe]: https://github.com/screenpipe/screenpipe
[screenpipe-todos]: https://github.com/screenpipe/screenpipe/blob/d71eb3772355c6b5134fc737fae37520e9ec5cbc/crates/screenpipe-core/assets/pipes/missed-todos/pipe.md
[chirp]: https://github.com/hkay-dev/chirp-android
[chirp-fts]: https://github.com/hkay-dev/chirp-android/blob/a8bed6c38aae6214c550be5b5e58e43231a339b4/data/src/main/java/dev/chirpboard/app/data/search/FtsQuery.kt
[reor]: https://github.com/reorproject/reor
[sc]: https://github.com/brianpetro/obsidian-smart-connections
[scv]: https://github.com/Mossy1022/Smart-Connections-Visualizer
[obsidian-graph]: https://obsidian.md/help/plugins/graph
[excalibrain]: https://github.com/zsviczian/excalibrain
[excalibrain-layout]: https://github.com/zsviczian/excalibrain/blob/master/src/graph/Layout.ts
[breadcrumbs]: https://github.com/michaelpporter/breadcrumbs
[graph-analysis]: https://github.com/SkepticMystic/graph-analysis
[logseq]: https://github.com/logseq/logseq
[logseq-pixi]: https://github.com/logseq/logseq/blob/master/src/main/frontend/extensions/graph/pixi/logic.cljs
[logseq-routes]: https://github.com/logseq/logseq/blob/master/src/main/mobile/routes.cljs
[trilium-sim]: https://github.com/TriliumNext/Trilium/blob/main/packages/trilium-core/src/becca/similarity.ts
[anytype-spec]: https://github.com/anyproto/anytype-kotlin/blob/main/docs/quick-capture-android-spec.md
[khoj-filters]: https://github.com/khoj-ai/khoj/blob/master/documentation/docs/miscellaneous/query-filters.md
[affine-layout]: https://github.com/toeverything/AFFiNE/blob/canary/blocksuite/affine/gfx/mindmap/src/view/layout.ts
[compose-mindmap]: https://github.com/UiHyeon-Kim/compose-mindmap
[kotlinflow]: https://github.com/joaonart/KotlinFlow
[telephoto]: https://saket.github.io/telephoto/zoomable/
[graphview]: https://github.com/oss-bandb/GraphView
[autolinker]: https://github.com/kdnk/obsidian-automatic-linker
[markmap]: https://github.com/markmap/markmap
[obsidian-tasks]: https://github.com/obsidian-tasks-group/obsidian-tasks
[d3-force]: https://github.com/d3/d3-force
[jgrapht]: https://github.com/jgrapht/jgrapht
[cosmos-npm]: https://registry.npmjs.org/@cosmograph/cosmos
[vanham]: https://doi.org/10.1109/tvcg.2009.108
[disparity]: https://doi.org/10.1073/pnas.0808904106
[backbone]: https://doi.org/10.7155/jgaa.00370
[leiden]: https://doi.org/10.1038/s41598-019-41695-z
[maier]: https://doi.org/10.1016/j.tcs.2009.01.009
[androidx-sqlite]: https://developer.android.com/jetpack/androidx/releases/sqlite
[sqlite-bundled-gradle]: https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/sqlite/sqlite-bundled/build.gradle
[aosp-sqlite]: https://android.googlesource.com/platform/external/sqlite/+/refs/heads/main/dist/Android.bp
[room]: https://developer.android.com/jetpack/androidx/releases/room
[room3]: https://developer.android.com/jetpack/androidx/releases/room3
[fts5]: https://www.sqlite.org/fts5.html
[vacuum]: https://www.sqlite.org/lang_vacuum.html
[sqlite-vec]: https://github.com/asg017/sqlite-vec
[sqlite-vec-android]: https://alexgarcia.xyz/sqlite-vec/android-ios.html
[sqlite-vec-hybrid]: https://alexgarcia.xyz/blog/2024/sqlite-vec-hybrid-search/index.html
[sqlite-vector]: https://github.com/sqliteai/sqlite-vector
[llamacpp]: https://github.com/ggml-org/llama.cpp
[llamacpp-grammars]: https://github.com/ggml-org/llama.cpp/blob/master/grammars/README.md
[llamacpp-30054]: https://github.com/ggml-org/llama.cpp/pull/30054
[llamacpp-30256]: https://github.com/ggml-org/llama.cpp/issues/30256
[llamacpp-29388]: https://github.com/ggml-org/llama.cpp/issues/29388
[llamacpp-30255]: https://github.com/ggml-org/llama.cpp/pull/30255
[anarlog-gbnf]: https://github.com/fastrepl/anarlog/blob/02afc2f1a85af196e41ee89aff446f4bcd7869c3/crates/gbnf/src/lib.rs
[litertlm]: https://github.com/google-ai-edge/LiteRT-LM
[mediapipe-retriever]: https://ai.google.dev/edge/mediapipe/solutions/retrieval/semantic_retriever/android
[appsearch]: https://developer.android.com/jetpack/androidx/releases/appsearch
[ort]: https://onnxruntime.ai/docs/install/
[snowball]: https://github.com/snowballstem/snowball
[jwordsplitter]: https://github.com/danielnaber/jwordsplitter
[eg2]: https://huggingface.co/google/embeddinggemma-2
[eg2-gguf]: https://huggingface.co/ggml-org/embeddinggemma-2-GGUF
[eg1]: https://huggingface.co/google/embeddinggemma-300m
[eg1-gguf]: https://huggingface.co/ggml-org/embeddinggemma-300M-GGUF
[bgem3]: https://huggingface.co/BAAI/bge-m3
[qwen3emb]: https://huggingface.co/Qwen/Qwen3-Embedding-0.6B
[e5small]: https://huggingface.co/intfloat/multilingual-e5-small
[granite]: https://huggingface.co/ibm-granite/granite-embedding-97m-multilingual-r2
[potion]: https://huggingface.co/minishlab/potion-multilingual-128M
[mteb-results]: https://github.com/embeddings-benchmark/results
[gemma4-e2b-gguf]: https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf
[recognizers]: https://github.com/microsoft/Recognizers-Text
[recognizers-de-spec]: https://raw.githubusercontent.com/microsoft/Recognizers-Text/master/Specs/DateTime/German/DateTimeModel.json
[duckling]: https://github.com/facebook/duckling
[rustling]: https://github.com/sonos/rustling-ontology
[chrono]: https://github.com/wanasit/chrono
[jsengine]: https://developer.android.com/develop/ui/views/layout/webapps/jsengine
[ovos]: https://github.com/OpenVoiceOS/ovos-date-parser
[ctparse]: https://github.com/comtravo/ctparse
[textclassifier]: https://developer.android.com/reference/android/view/textclassifier/TextClassifier
[mlkit-entity]: https://developers.google.com/ml-kit/language/entity-extraction/android
[nuextract]: https://huggingface.co/numind/NuExtract-1.5-tiny
[mobile-actions]: https://huggingface.co/datasets/google/mobile-actions
[themachine]: https://github.com/hasan-ismail/themachine
[tasks-api]: https://github.com/tasks/tasks/blob/main/CONTENT_PROVIDER.md
[jtx]: https://github.com/TechbeeAT/jtxBoard
[calendar-provider]: https://developer.android.com/identity/providers/calendar-provider
[etar]: https://github.com/Etar-Group/Etar-Calendar
[biweekly]: https://github.com/mangstadt/biweekly
[rfc5545]: https://www.rfc-editor.org/rfc/rfc5545.txt
[rfc9074]: https://www.rfc-editor.org/rfc/rfc9074.html
[gemma3-card]: https://ai.google.dev/gemma/docs/core/model_card_3
[gemma4-card]: https://ai.google.dev/gemma/docs/core/model_card_4
[datefragments]: https://arxiv.org/abs/2505.16088
[multitemp]: https://arxiv.org/abs/2603.19017
[alarms]: https://developer.android.com/develop/background-work/services/alarms/schedule
[exact14]: https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
[permissions]: https://developer.android.com/reference/android/Manifest.permission
[alarmmanager]: https://developer.android.com/reference/android/app/AlarmManager
[alarm-aosp]: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/apex/jobscheduler/service/java/com/android/server/alarm/AlarmManagerService.java
[doze]: https://developer.android.com/training/monitoring-device-state/doze-standby
[bc14]: https://developer.android.com/about/versions/14/behavior-changes-14
[bc16]: https://developer.android.com/about/versions/16/behavior-changes-all
[hibernation]: https://developer.android.com/topic/performance/app-hibernation
[notification]: https://developer.android.com/reference/android/app/Notification
[dnd-policy]: https://developer.android.com/reference/android/app/NotificationManager.Policy
[dontkill]: https://dontkillmyapp.com/motorola
[todotxt]: https://github.com/todotxt/todo.txt
