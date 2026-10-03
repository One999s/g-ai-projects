# B14 · Reviewed local narration

Optional narration is disabled by default. It does not generate speech, download files, change the answer deadline, grant identity, or write scores. The repository's twenty English FLAC files remain unlistened development candidates and cannot be loaded by this runtime. No reviewed production audio bundle is supplied.

## Bundle and startup

Set `QUIZ_NARRATION_ENABLED=true`, `QUIZ_NARRATION_DIRECTORY` to an absolute, normalized, non-symlink local directory, and `QUIZ_NARRATION_MANIFEST_SHA256` to the SHA-256 of its exact `manifest.json` bytes. Mount the reviewed bundle read-only through the authorized deployment process. Changing a bundle requires a new review/hash and process restart; there is no runtime file fetch or mutable cache.

Manifest schema is `{ "schemaVersion": 1, "entries": [...] }`. Each entry has exactly these fields:

- `bankVersion`, `questionId`, `locale`, `text`, `options`, `readingMillis`: exact existing session snapshot binding. Options remain in A–D order; explanations and correct answers must not be spoken or included in the manifest
- `audioSha256`: lowercase 64-hex digest; the only accepted file name is `<digest>.wav`
- `durationMillis`: ceiling of decoded PCM sample duration, 100 ms minimum; duration plus a 1,000 ms margin must fit the question reading window
- `listened: true`, `reviewedBy`, `reviewedAtMillis`, `rightsReference`: a genuine listening/wording/rights review record. Do not set these on unlistened files; tests use explicitly synthetic fixture records only

Files must be canonical 44-byte-header, mono 16 kHz PCM16 WAV with exact lengths and no metadata/trailing chunks. Up to 100 entries, a 1 MiB manifest, 1,920,044 bytes per WAV and 32 MiB total loaded WAV bytes. Paths, symlinks, unknown fields, scalar coercion, duplicate keys/bindings, future review times, content hash drift, invalid PCM, incompatible prompt/options/window and absent review fields fail closed. Invalid explicitly enabled bundle prevents startup. Bundle bytes are loaded into bounded memory once; sizing and actual concurrency still need deployment load validation.

The reviewed manifest is an operator-supplied trust input, not a cryptographic proof that listening occurred. Its hash must be checked against the independently reviewed delivery record. Software can prove byte/binding consistency, not whether speech faithfully pronounces the prompt or that a person/assistant actually listened.

## API and clocks

`GET /api/quiz/sessions/{id}/rounds/{round}/narration` returns only availability or a descriptor with current session/round, digest, byte length, duration, reading window and server timestamps. No asset path, bank identifier, future question, solution or reviewer details are exposed. Absent or unmatched bundle yields `available=false`.

`GET .../narration/audio` returns the matched WAV, with no-store/nosniff. Both endpoints use existing identity, quota and Origin gates, then locked ownership/current-round checks. Allowed only while LOADING or READING; expired loading, answer phase, stale round and closed session are rejected. Binary delivery rechecks identity/window before publishing bytes. There are no public static audio routes, range requests, cross-origin redirects or provider URLs.

The server frontend prepares narration only when the user turns sound on and presses Ready. Preparation has a 3-second bound within the existing 15-second loading deadline. It verifies descriptor scope, byte length, SHA-256 and decoded duration, then sends ordinary Ready. Playback starts only after the accepted server READING snapshot and seeks relative to the original server reading start. Mute, hidden page, round/scope change, expiry and unmount stop playback and release memory. Duplicate or late setup results cannot extend time or answer automatically. Missing/blocked playback leaves text usable with an explicit notice. Browser autoplay, actual audio devices and listening are not validated by fake AudioContext tests.

## Text review

`content/reviews/world-foundations-r1.assistant.json` is the new hash-bound assistant review of all 20 question pairs, checked on 2026-10-03 against the item-level NASA, NOAA, BIPM and UNESCO links. It supersedes the old draft's historical `pending-human` text-review metadata without rewriting the original candidate bytes or breaking the B11 audio provenance. It is assistant review, not human approval or a legal opinion. Text review does not approve deployment, make public answers secret, or certify unlistened audio.

Run `python3 tools/content_review.py content/candidates/world-foundations-r1.json content/reviews/world-foundations-r1.assistant.json --output /new/reviewed-text-directory` from this project. Outputs have actual assistant attribution and reviewed timestamps. The tool does not connect to a database, create approval audit events, or import content. The independent schema/content owner still chooses an authorized target and explicit release transaction. Original draft export remains intentionally ineligible.
