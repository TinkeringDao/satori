# ONNX Runtime 1.28.2 C API headers

The headers `lib-reader-onnx` compiles against. The library itself is the `libonnxruntime.so` that
sherpa-onnx ships in `lib-sherpa-onnx/src/main/jniLibs/arm64-v8a/` (ONNX Runtime 1.28.2, which
exports `OrtGetApiBase@@VERS_1.28.2`). Headers and library must be the same version: the reader asks
for `ORT_API_VERSION` 28, and its build check looks for `U OrtGetApiBase@VERS_1.28.2`. Update both
together.

- Source: the GitHub release `v1.28.2` of microsoft/onnxruntime, tag commit
  `33ca9628233dc8f002435e868d4c2e9f82766ca1`, asset `onnxruntime-osx-arm64-1.28.2.tgz` (sha256
  `c4fceacfc53765d0869dc9180c31ec91054d149017a99d1e80ffe28dc79596de`, the release's own digest),
  folder `include/`. Each file is byte-identical to `include/onnxruntime/core/session/<file>` at the
  tag commit.
- Licence: MIT, `LICENSE` from the same archive.

| File | Bytes | sha256 |
|---|---|---|
| `onnxruntime_c_api.h` | 394,991 | `b69a133143c0da61782b2b02cc8a620d21ac139231fd211719776d10385135c1` |
| `onnxruntime_ep_c_api.h` | 152,985 | `db86df0f846b8d3bdbf429a5c76c5805293196575ca3c475878612591cd18e51` |
| `onnxruntime_error_code.h` | 2,775 | `5ce3b054e798eced8d14f5b86e98692fd33470463f96194ce0700a2d53dd8721` |
| `onnxruntime_session_options_config_keys.h` | 41,738 | `f3a80fcec7fa9e38de3191fc48714231892bc9c5dc8a06adecebf946a1937a29` |
| `onnxruntime_run_options_config_keys.h` | 2,952 | `973037c14b0f1af0854ae25cd676776f79dbcfd2a6791f6ee17a5374c2a62550` |
| `LICENSE` | 1,073 | `2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c` |

`onnxruntime_c_api.h` includes `onnxruntime_error_code.h` and `onnxruntime_ep_c_api.h`; the two
config-key headers hold the option names the reader sets (`session.disable_prepacking`,
`memory.enable_memory_arena_shrinkage`).
