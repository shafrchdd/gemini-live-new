# Audit of GeminiLive version 5

## Scope and evidence

The original Java attachment was preserved. The supplied repository initially contained no commits or application files; the repaired source and build/validation tooling were created from that attachment. Repository publication is performed separately on the user's explicit request.

The user reports that the exported APK connects to Gemini, sends text, and plays responses, but does not listen; the same project listens in Companion, and manually requesting microphone permission did not resolve it. This narrows the failure to capture or audio input delivery. It does not establish one device-specific root cause without the APK and a phone test.

The original already declared `RECORD_AUDIO` and requested it through `Form.askPermission`. App Inventor trims the comma-separated annotation values. Adding another generic permission prompt, or changing whitespace in the permission annotation, does not fix the verified source defects.

## Phase 1: microphone and permission behavior

| Verified defect in the original | Change |
| --- | --- |
| A delayed permission grant could start capture after stop/disconnect. | Each start has a session and generation; stale permission responses are ignored. |
| `StopListening` cleared the global recorder without releasing it. | The capture worker owns and releases its recorder and effects in every exit path. |
| An old worker released whichever global recorder existed at cleanup time. | Cleanup only releases its own captured resources; starts are serialized. |
| `startRecording` was not followed by a recording-state check. | Both initialized and actually recording states are required before `ListeningStarted`. |
| Negative and zero read results were ignored indefinitely. | Read failures stop capture and produce an actionable error. |
| Failed startup could leak native audio resources. | Every failed source attempt releases its resources before fallback. |
| Only `VOICE_COMMUNICATION` was attempted. | Try standard `MIC`, then `VOICE_COMMUNICATION` on startup failure. This is a compatibility improvement, not proof of the user's device cause. |
| No screen/lifecycle cleanup was registered. | Screen stop, destruction, and deletion cancel pending starts and close session resources. |

The new code also reports three seconds of exactly zero PCM samples. Android microphone privacy restrictions or device behavior can produce silence even when permission appears granted. The diagnostic is intentionally visible rather than treating an open recorder as proof of captured speech.

## Phase 2: streaming, playback, sessions, and protocol

| Verified defect or limitation | Change |
| --- | --- |
| At least 3,200 input bytes were read per batch, about 100 ms at 16 kHz. | Separate the hardware buffer from 640-byte reads: 20 ms per complete chunk. |
| Socket sends stalled the capture worker. | A single writer uses a bounded queue. At most eight unsent audio tasks are accepted; prolonged congestion produces a diagnostic. |
| Every text/tool/end operation created a new sender thread. | All session application messages use an ordered, bounded writer. |
| `AudioTrack.write` blocked the incoming WebSocket reader and ignored partial writes. | Dedicated playback worker, short writes, checked return values, bounded playback memory. |
| Audio stop could leave `assistantSpeaking` true; generation completion could clear it before buffered playback drained. | Speaking reflects queued/buffered speaker audio, with immediate reset on flush. |
| Multiple reconnecting workers shared socket fields. | Each connection owns its transport, writer, pending calls, and playback resources. Stale events and failures cannot act on a replacement session. |
| WebSocket accept headers were not verified. | Validate status, upgrade tokens, accept digest, extensions, and TLS hostname. |
| UTF-8 was decoded separately for each fragment. | Reassemble bytes before strict UTF-8 decoding; support text and binary JSON. |
| Frame lengths and fragmentation were only partly validated. | Bound frame/message/header sizes and validate opcodes, lengths, control frames, server masking, close codes, and continuation sequencing. |
| Silent JSON parse failures hid errors. | Surface parsing/server errors; retain server close reasons. |
| Tool-call cancellation was ignored. | Notify `FunctionCallCancelled`, suppress cancelled queued callbacks, and recheck reserved responses immediately before sending. |
| Catalogs were unpaginated and models were filtered only by the word `live`. | Paginate; prefer `bidiGenerateContent` capability and support native-audio name fallback. |
| Voice display names could be supplied as prebuilt voice IDs. | Request prebuilt voices and return canonical IDs, excluding custom voices. |

The model default remains `gemini-3.8-live`, confirmed in the current official Google cookbook. The transport uses the standard Developer API `v1beta` WebSocket route. Both the old setup fields and microphone JSON shape were valid; they were not identified as the APK-only root cause.

The second review additionally fixed pause/flush races: cancelled writes do not terminate playback, new chunks wait until flush cleanup completes, speaking updates use a consistent lock order, and expected teardown errors cannot leak an audio track. Temporary chunk playback errors are visible and later chunks can recreate the track.

## Phase 3: photo input

Version 3 adds `SendImage(imagePath, text)`, `IsSendingImage()`, and `ImageSent(imagePath)` without changing existing blocks. It follows the current Google Live cookbook: send `realtimeInput.video` with JPEG data, then `realtimeInput.text`. One shared writer task keeps these frames consecutive. This guarantees client transmission order, not cross-modal server processing order or acknowledgment.

Photo preparation runs on a separate single-thread worker with one queued job, and one pending image is reserved per session until writing finishes. A bounded app-private temporary file holds at most 20 MiB of source data. Bounds inspection and sampled decoding limit bitmap dimensions, followed by scaling to a 1024-pixel edge, EXIF rotation/mirroring, and JPEG encoding capped at 512 KiB. All streams, temporary files, bitmaps, and reservations have cleanup paths. Disconnect cancels work, closes an active source asynchronously, and prevents a stale image entering a replacement session. Remote/data URLs are rejected; local media still requires Android access rights.

The AIA embeds version 3 and adds native Camera, ImagePicker, preview, prompt, and send controls. It disconnects before launching external camera/gallery activities and waits for reconnection/readiness before sending. The existing foreground-only microphone lifecycle remains in effect. Continuous camera streaming is outside this release.

## Phase 4: playback and responsiveness

The user reported word-by-word buffering, skipped audio, growing delays, and Android's unresponsive-app warning. Source review found that `AudioTrack.write` held a lock also acquired by UI volume/flush operations. Flush additionally ran native pause/release from the caller. Independently, the two-second PCM queue cleared buffered speech when normal generation bursts exceeded that cap. These are verified defects, not proof of the user's complete device-specific cause without phone traces.

Version 4 makes the playback worker the owner of all native speaker operations. UI volume/stop/disable operations only signal state; they do not wait for native cleanup. Microphone cancellation likewise schedules native stop outside the UI thread; a per-capture control lock coordinates stop and worker-owned release. Android API 23+ uses nonblocking writes, including ordinary zero-write backpressure and partial writes. A startup prebuffer of up to 120 ms and a native buffer of at least 200 ms address short arrival gaps. Up to 30 seconds/4 MiB of unwritten speech can be held for fast generation bursts. Exceeding this cap stops the affected response and discards its remaining audio until `turnComplete`, then permits later turns; it does not repeatedly clear and resume fragments of the same response.

Raw events default off, and explicitly enabled raw diagnostics have a bounded UI budget with a visible warning. Text/transcript deltas are combined over 100 ms and drained before `TurnComplete`, avoiding a separate UI runnable for each word. `PlaybackBufferedMilliseconds()` reads the software queue without native audio access. The test AIA enables microphone muting during assistant speech as its baseline to reduce feedback-triggered interruptions, while the extension's existing mute default remains false.

## Phase 5: language configuration

Version 5 adds runtime `InputLanguageCodes`, `ReplyLanguage`, `TranscriptionLanguageHints`, and `SetLanguages(list, replyCode)` blocks. Codes are normalized/deduplicated, malformed lists and multiple fixed reply languages are rejected, and dynamic list updates leave previous settings unchanged on validation failure. The AIA starts with Malayalam/English (`ml-IN,en-IN`) and `auto` replies. Configuration changes apply on reconnect; the current socket's immutable setup is not silently replaced.

The current official Python SDK describes `AudioTranscriptionConfig.language_codes` as BCP-47 hints. The public v1beta protobuf still represents transcription configuration as an empty message; field availability can vary by backend/model. The checkbox provides an explicit fallback that omits the hints field while retaining expected-input and reply-language instructions. These settings do not constitute strict speech recognition exclusion. No unsupported native speech synthesis language field is added. SDK hint acceptance, Malayalam/English recognition, mixed-language behavior, and reply compliance require real-key/device validation.

## Phase 6: validation and limits

The reproducible build uses genuine MIT annotation processors and extension packaging tools. It checks that generated extension metadata contains both required permissions and that the package contains valid DEX bytecode. Runtime tests execute the production extension bytecode with deterministic Android/App Inventor doubles. They test asynchronous permission and lifecycle behavior, not real hardware.

Final validation passed: authentic Java compilation, official component metadata generation, DEX packaging, **53 transport tests, and 65 runtime tests** (118 passed, 0 failed). Image tests cover protocol shape, worker placement, resizing, orientation, byte budgets, cleanup, failures, pending reservations, disconnection/replacement, writer rejection, and camera activity lifecycle. Playback tests prove retention of a fifteen-second burst, complete partial writes, responsive controls while native speaker/recorder cleanup is blocked, bounded overflow, jitter priming, lossless transcript batching, stale-callback suppression, and bounded raw diagnostics. All original public block signatures were retained. `dist/validation-results.json` ties this result to the final source and `.aix` SHA-256 digests. The AIA passes offline archive and block validation; actual Kodular import/export remains untested.

The WebSocket tests exercise framing/handshake parsing and concurrent output using local streams. They do not authenticate to Gemini or validate a live TLS connection on an Android device. No API key was available in this task.

Twenty-millisecond capture batches reduce this extension's original capture batching overhead by about 80 ms. Device audio buffers, server turn detection, network congestion, and model inference still determine total latency. Eight queued chunks bound unsent audio duration; network stalls can still delay those chunks. There is no measured end-to-end latency claim.

## APK acceptance checklist

1. Import the new `.aix`, export a fresh APK, and confirm this APK's microphone permission is allowed. For a clean first-permission test, use a fresh test installation or clear permission through Android settings.
2. Display `Error`, `ListeningStarted`, `ListeningStopped`, and `InputTranscription` during testing. Connect and call `StartListening` from `SetupComplete`.
3. Grant the APK's first microphone prompt and verify listening starts without another button press. Speak; verify a nonzero `MicrophoneLevel` and input transcription or a spoken response.
4. Deny permission, retry, then grant it in settings. Stop while the permission dialog is pending; a late grant must not restart capture.
5. Repeat start/stop and connect/disconnect. Confirm Android's microphone indicator turns off after stop, backgrounding, and leaving the screen.
6. Interrupt assistant playback by speaking with `MuteMicWhileSpeaking=false`; subsequent playback must still work. Toggle audio off/on and test another response.
7. Test with another recording component stopped, then with the intended combination of components. Android may enforce microphone exclusivity; the extension cannot override that policy.
8. Test the actual target phone/Android versions and measure response latency there. If the new diagnostics report zero PCM or initialization failure, use that message and the phone's Android version to isolate the remaining device issue.
9. Capture a portrait photo and select a gallery image. Reconnect, wait for readiness, and send each with a prompt. Confirm visual recognition, orientation, and spoken/transcribed response with the actual Live model/API key. Test URI grants, canceled camera/gallery, unreadable media, and disconnect during photo preparation on the phone.
10. With the microphone stopped, request a longer spoken response and listen for complete continuous speech. Repeat with microphone capture and its mute-during-speech option enabled. Exercise volume, stop, audio disable/re-enable, backgrounding, and reconnect while audio is buffered; verify responsiveness and inspect errors/backlog. Compare headphones or microphone muting if server interruptions occur while the speaker plays.

## Authoritative references checked

- [App Inventor permission processor](https://github.com/mit-cml/appinventor-sources/blob/master/appinventor/components/src/com/google/appinventor/components/scripts/ComponentProcessor.java)
- [App Inventor Form permission handling](https://github.com/mit-cml/appinventor-sources/blob/master/appinventor/components/src/com/google/appinventor/components/runtime/Form.java)
- [Android AudioRecord](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/media/java/android/media/AudioRecord.java)
- [Google Live quickstart](https://github.com/google-gemini/cookbook/blob/main/quickstarts/Get_started_LiveAPI.py)
- [Google Live protocol types](https://github.com/googleapis/python-genai/blob/main/google/genai/types.py)
- [Google Live client](https://github.com/googleapis/python-genai/blob/main/google/genai/live.py)
- [Google voices client](https://github.com/googleapis/python-genai/blob/main/google/genai/_gaos/voices.py)
- [MIT extension template](https://github.com/mit-cml/extension-template)

Language references: [current SDK AudioTranscriptionConfig](https://github.com/googleapis/python-genai/blob/main/google/genai/types.py), [Live setup conversion](https://github.com/googleapis/python-genai/blob/main/google/genai/_live_converters.py), and [public v1beta setup protocol](https://github.com/googleapis/googleapis/blob/master/google/ai/generativelanguage/v1beta/generative_service.proto).
