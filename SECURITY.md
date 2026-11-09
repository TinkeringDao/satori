# Security Policy

## Supported Versions

Satori is under active development. Security fixes are applied to the `main` branch and included in the next tagged release.

| Version | Supported |
|---|---|
| `0.8.x` (`main`) | Yes |
| `< 0.8.0` | No |

## Threat Model & Local-Only Architecture

Satori is an offline-first Android application (`com.satori`) that processes audio recordings, transcripts, translations, and local LLM notes strictly on-device:

- **Network Access:** Network access (`INTERNET`, `ACCESS_NETWORK_STATE`) is used solely by `ModelDownloadManager` to fetch speech, translation, and LLM model archives from configured HTTPS endpoints and verify their byte lengths and SHA-256 checksums before installation.
- **Exported Components:**
  - `MainActivity`: Standard launcher activity; treats all incoming intent extras (`QuickKeyRequest`) as untrusted input and validates category IDs and language codes against known local values.
  - `QuickKeyActivity`: Exported trampoline activity (`com.satori.action.QUICK_KEY`) invoked by Motorola My Key on a hardware power-key double-press (`docs/QUICK_KEY.md`). It reads no payload from the incoming intent, drops repeats within 1,000 ms (`QuickKeyRepeatFilter`), and cannot exfiltrate any user data.
- **Data Storage & Backup Exclusion:** All Room SQLite databases (`AppDatabase`), DataStore preferences, and downloaded models reside in app-private storage and are excluded from cloud backup and device-to-device transfer (`app/src/main/res/xml/data_extraction_rules.xml` and `backup_rules.xml`).
- **Diagnostic Logs:** Production log tags (`SatoriNotes`, `SatoriFirstRun`, `SatoriNudge`, `SatoriQuickKey`) log only numeric counts, stage transitions, latencies in milliseconds, and memory sizes in megabytes (`docs/FIELD_LOG.md`), never raw transcript text or user notes.

## Reporting a Vulnerability

Please **do not** report security vulnerabilities through public GitHub issues.

Instead, report vulnerabilities privately via **GitHub Private Vulnerability Reporting** (`Security` tab -> `Report a vulnerability`) on this repository.

When submitting a report, please include:
1. Affected module and file path (e.g., `:app`, `:data`, `:domain`, `:lib-llama-android`, `:lib-reader-onnx`, `:lib-sherpa-onnx`).
2. Android API level and device model if hardware-specific.
3. Minimal reproduction steps or proof-of-concept intent/input.

Reports are reviewed on a best-effort basis by the maintainer (`@TinkeringDao`).
