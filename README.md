# GeminiLive for Kodular

This is the repaired version of the uploaded `com.fabiyon.geminilive.GeminiLive` extension. Existing block signatures are preserved. Version 5 adds configurable input and reply languages while retaining playback fixes, photo input, and tool calling.

Downloads:

- [Kodular test project (.aia)](https://github.com/shafrchdd/gemini-live-new/raw/refs/heads/main/examples/GeminiLiveTest.aia) — extension and example blocks included; [usage instructions](examples/README.md).
- [Compiled Kodular extension (.aix)](https://github.com/shafrchdd/gemini-live-new/raw/refs/heads/main/dist/com.fabiyon.geminilive.aix)
- [Complete source and build bundle](https://github.com/shafrchdd/gemini-live-new/raw/refs/heads/main/dist/GeminiLive-v5.zip)
- [Java source](src/com/fabiyon/geminilive/GeminiLive.java)
- [Audit and device checklist](docs/AUDIT.md)

Import `dist/com.fabiyon.geminilive.aix` into Kodular, replace the earlier extension, and **export a new APK**. An APK exported before the replacement still contains the old extension. The build includes `INTERNET` and `RECORD_AUDIO` permission metadata.

Use these blocks in this order:

1. Your connect button calls `Connect(API key)`.
2. `SetupComplete` calls `StartListening` if you want automatic listening once the session is ready.
3. `ListeningStarted` confirms Android actually started recording.
4. Display the `Error` event message in a label during testing.
5. Use `StopListening` to finish capture, and `Disconnect` when done.

Do not start listening from `Connected`: that event confirms the WebSocket, while `SetupComplete` confirms Gemini has accepted the session. Calling `StartListening` requests the APK's microphone permission; a successful grant continues startup automatically. Companion and the installed APK have separate permission grants.

The extension closes its connection and audio resources when its screen stops or is destroyed. Reconnect after returning to the screen. It does not change global Android audio mode, speaker routing, or volume, so it avoids taking control of other Kodular audio components. Other apps and extensions can still compete for Android microphone access.

For vision, use `Camera.TakePicture` or `ImagePicker`, keep the returned image path/URI, connect after returning, then call `SendImage(imagePath, text)` after `SetupComplete`. The example includes these blocks, a preview, and a **Send photo** button. For a photo-only test, uncheck automatic listening before connecting. This release sends snapshots or selected photos; continuous camera streaming is not implemented.

`SendImage` prepares the photo on a separate worker, reads at most 20 MiB, corrects EXIF orientation, and creates a JPEG up to 1024 pixels on its longest edge and 512 KiB. It accepts assets, accessible local paths, `file://`, and granted `content://` URIs. Camera/picker components handle their Android permissions; the extension does not bypass scoped storage. It sends a realtime image frame and its prompt consecutively, using the current Live cookbook protocol. Server processing order across media streams is not guaranteed. `ImageSent(imagePath)` confirms both network writes, not Gemini acknowledgment. `IsSendingImage()` covers preparation and transmission; only one photo can be pending per session. Wait for completion before sending other text if turn order matters.

`MicrophoneLevel()` reports the latest captured peak, from 0 to 100. While speaking near the phone, a consistently zero level plus the zero-sample diagnostic suggests a device capture/privacy/route issue. Check Android's microphone privacy switch, this APK's microphone permission, and competing recording apps. Optional acoustic echo cancellation and noise suppression depend on the device; disable those properties before another microphone start if investigating device-specific effects.

For languages, set `InputLanguageCodes` to comma-separated codes such as `ml-IN,en-IN`. Set `ReplyLanguage` to `auto` to reply in the same selected language as the current utterance, or to one code such as `ml-IN` to request Malayalam replies every time. You can change these properties at runtime; disconnect and reconnect to apply them to the Live session. `SetLanguages(a Kodular list of codes, reply code or auto)` also accepts dynamically constructed lists of up to eight codes. The example starts with Malayalam and English and `auto` replies. Empty input codes retain automatic recognition; empty reply code prefers the first selected input language.

The current Google SDK exposes `inputAudioTranscription.languageCodes` as **hints**, not an exclusive recognition whitelist. `TranscriptionLanguageHints` controls sending this field; disable it if the API/model rejects setup, while keeping the language policy instructions. Reply language and expected-input instructions are appended to your system instruction; the native model's recognition and compliance cannot be guaranteed. Strict exclusion of every other spoken language requires a separate speech-recognition path with language restrictions. No separate recognizer is included here.

`RawMessagesEnabled` now defaults to false. Set it explicitly to false in existing projects, which can retain the earlier saved value. Opt-in raw diagnostics are capped at four queued events and 256 KiB per message; excess diagnostics produce one warning per session. Normal audio delivery continues. Transcript/text deltas are combined for up to 100 ms, retaining their concatenated content and delivering the final text before `TurnComplete`.

Native speaker writes, volume changes, pause, flush, and release run on the playback worker. `StopAudio` requests cleanup and returns promptly. Android 6+ uses nonblocking writes with partial-write handling. A startup prebuffer of up to 120 ms and a hardware buffer of at least 200 ms absorb short arrival gaps. Fast-generated responses can occupy up to 30 seconds/4 MiB of software backlog without the earlier two-second queue clearing. Exceeding that safety cap stops the current response visibly and resumes at the next completed turn. `PlaybackBufferedMilliseconds()` reports audio awaiting writes, excluding hardware/network buffers.

`MuteMicWhileSpeaking` still defaults to false in the extension; the example enables it to reduce speaker feedback triggering interruptions. Disable its checkbox when intentionally testing voice barge-in. For diagnosing broken playback, first stop the microphone and send a text prompt; then compare with microphone capture enabled and muted during AI speech.

Build on Linux x86-64 with Python 3.12+, Git, and network access:

```sh
cd /workspace/gemini-live-new
python3 tools/build.py
python3 tools/test.py
```

The build downloads a pinned Temurin JDK 8 and pinned official MIT App Inventor extension dependencies, verifies their SHA-256 digests, compiles Java 7 bytecode, runs the official metadata generators, and packages the DEX and `.aix`. Dependency versions and checksums are in `tools/dependencies.json`. Build caches and dependency downloads are ignored by Git; the verified distribution files are included for direct download. No API key is needed to compile or run the local regression tests.

See `docs/AUDIT.md` for audit findings and the APK acceptance checklist. Local compilation and simulated regression tests cannot confirm microphone behavior on a physical phone or measure Gemini/network response latency.
