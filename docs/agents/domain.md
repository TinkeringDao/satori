# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`GLOSSARY.md`** at the repo root: defines canonical product, hardware (`Cover Screen`, `Inner Screen`, `Grille`, `Quick Key`, `"The Recorder Wins"`), speech recognition (`PARAKEET_V3`, `PARAKEET_V2_EN`, `COHERE_TRANSCRIBE`, `INDICCONFORMER_ML`), Malayalam reading bridge (`segment_readings`, `IndicTrans2`), finishing pipeline (`RecordingFinisher`, `TodoTimeResolver`, `TodoGrounding`, `MindMap`), and memory governance (`ModelLoadPacing`, `MemoryFloor`, `forgetPromptCache`) terminology.
- **`docs/`**: read design and architecture specs that touch the area you're about to work in (`docs/GRILLE_DESIGN.md`, `docs/QUICK_KEY.md`, `docs/FIRSTRUN_CONTRACT.md`, `docs/PERFORMANCE_DIAGNOSTICS.md`, `docs/DEV_SETUP.md`).
- **`docs/adr/`**: read ADRs if present that touch the area you're about to work in.

If any optional files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

## File structure

Single-context multi-module Android repo:

```
/
├── GLOSSARY.md
├── docs/
│   ├── GRILLE_DESIGN.md
│   ├── QUICK_KEY.md
│   ├── FIRSTRUN_CONTRACT.md
│   ├── PERFORMANCE_DIAGNOSTICS.md
│   └── agents/
├── app/
├── presentation/
├── domain/
├── data/
├── feature-stt/
├── feature-reader/
├── feature-llm/
├── lib-sherpa-onnx/
├── lib-reader-onnx/
└── lib-llama-android/
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `GLOSSARY.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR or design-spec conflicts

If your output contradicts an existing specification in `docs/` or an ADR, surface it explicitly rather than silently overriding:

> _Contradicts `docs/GRILLE_DESIGN.md` (camera band clearance on Cover Screen), but worth reopening because…_
