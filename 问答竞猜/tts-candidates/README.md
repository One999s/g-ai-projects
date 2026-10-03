# Chinese narration pilot (development candidate only)

One original Chinese question is rendered with a pinned, inference-only subset of the official [MeloTTS](https://github.com/myshell-ai/MeloTTS/tree/209145371cff8fc3bd60d7be902ea69cbdb7965a) PyTorch implementation. No ONNX, cloud TTS, voice cloning or reference-speaker recording. The pilot is **not listened to or approved** and is not loaded by the game runtime.

## Evidence and limits

- Official Chinese checkpoint: [MeloTTS-Chinese](https://huggingface.co/myshell-ai/MeloTTS-Chinese), revision `af5d207a364ea4208c6f589c89f57f88414bdd16`, MIT declaration
- BERT: [google-bert/bert-base-multilingual-uncased](https://huggingface.co/google-bert/bert-base-multilingual-uncased), revision `7cbf9a625e29989f6b9c6c2fa68234c304f7e38f`, Apache-2.0 declaration; safetensors only
- Seven model-data files total 882,608,084 bytes, with exact lengths/SHA-256 in model-source.json. Weights, virtual environments and caches are not committed or packaged
- Fixed Linux x86_64 CPython 3.12 CPU PyTorch wheel from the official index plus hash-locked PyPI packages. `hf-xet` is intentionally absent despite the Hub package's optional download-backend requirement; `pip check` can report this omission. The download tool explicitly disables Xet and the runtime never downloads. No UI/training/torchaudio stack
- Measured one-question model phase 5.76 seconds; peak RSS 1,863,900 KiB (about 1.78 GiB); tooling/model/cache footprint about 2.5 GiB in this environment. These are observed values, not universal requirements or real-time guarantees
- Output 7.593 seconds, 16 kHz mono PCM16. Two same-environment runs, before/after the tokenizer constructor correction, produced identical audio SHA-256. No cross-machine/version reproducibility claim
- All 20 Chinese candidate transcripts have matching fast/reference WordPiece token IDs, no unknown tokens and matching phoneme alignment. These checks do not establish pronunciation or actual speech/text accuracy

The initial fast-tokenizer loader emitted a Mistral-regex warning for this BERT model. Static inspection found transformers 4.57.6 classifies a local configuration without `transformers_version` as Mistral even when `model_type=bert`. Direct construction from the verified BERT tokenizer/vocabulary avoids that generic model-detection path; the regex/model were not patched. BERT checkpoint pooler/next-sentence-head keys are intentionally unused by BertForMaskedLM, consistent with the official upstream loader.

The environment refused native `strace` tracing (`PTRACE_TRACEME` and `PTRACE_SETOPTIONS` not permitted). No elevated access or alternative native tracing was attempted. Normal synthesis was separately authorized and succeeded. Python-level network/subprocess audit operations are denied before imports, but that is **not proof of zero native-library network traffic**. Native network acceptance remains unverified.

## Explicit preparation

Use a dedicated working directory with at least 4 GiB free disk and enough memory for the observed process plus other work. Prepare one process at a time; the renderer sets 2 compute threads and observes its own RSS every 100 ms, exiting at 3 GiB. This is an observation/stop threshold, not a kernel-enforced memory partition. Predicted frame count is also bounded before large attention allocations.

From this project directory:

```sh
python3 tts-candidates/install_environment.py --directory "$WORK/new-tts-env"
"$WORK/new-tts-env/venv/bin/python" tts-candidates/prepare_models.py --directory "$WORK/new-tts-models" --cache "$WORK/tts-hf-cache"
mkdir -p "$WORK/tts-temp"
TMPDIR="$WORK/tts-temp" HF_HOME="$WORK/tts-hf-cache" timeout 180s \
  "$WORK/new-tts-env/venv/bin/python" tts-candidates/render_one.py \
  --models "$WORK/new-tts-models" \
  --candidate content/candidates/world-foundations-r1.json \
  --question-id world-001 --output "$WORK/new-chinese-pilot"
```

Preparation downloads only named public package/model data. Running the renderer does not install or fetch anything. Missing/corrupt local files fail before native model imports. ONNX presence is rejected without importing it. Telemetry/offline/thread settings precede imports; implicit HF tokens are disabled and token variables cleared for this process. The checkpoint uses explicit `weights_only=True`; no pickle cache, custom deserialization allowlist, remote-code trust or unsafe fallback.

`vendor-provenance.json` records original/adapted file hashes. The inference subset retains upstream MIT and PaddlePaddle Apache notices. Chinese text cleaning is upstream code; A–D option-label phonemes are explicitly bounded. Arbitrary English/multilingual narration is not supported by this pilot renderer. See NOTICE and vendor license files; these record upstream declarations, not exclusive voice rights or a training-data legal guarantee.

Only the first question is supplied pending real listening and device evaluation. Do not bulk-promote candidates or set `listened=true` based on hash/duration/ASR checks. Once actually reviewed, use the separate B14 reviewed-WAV binding process; this renderer cannot approve a runtime bundle or mutate the database.

## Tests

`python3 -m unittest discover -s tts-candidates -p 'test_*.py' -v` uses standard library checks only. CI does not install PyTorch, download models or synthesize speech. The checked-in pilot's source text, byte/hash/PCM/window checks are distinct from the one local real-model run and from still-missing listening/network/device acceptance.
