# GeminiLive for Kodular

This is the repaired version of the uploaded `com.fabiyon.geminilive.GeminiLive` extension. Existing block signatures are preserved. Version 3 adds camera-photo and selected-image input with text prompts.

Downloads:

- [Kodular test project (.aia)](https://github.com/shafrchdd/gemini-live-new/raw/refs/heads/main/examples/GeminiLiveTest.aia) — extension and example blocks included; [usage instructions](examples/README.md).
- [Compiled Kodular extension (.aix)](https://github.com/shafrchdd/gemini-live-new/raw/refs/heads/main/dist/com.fabiyon.geminilive.aix)
- [Complete source and build bundle](https://github.com/shafrchdd/gemini-live-new/raw/refs/heads/main/dist/GeminiLive-v3.zip)
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

`RawMessagesEnabled` defaults to true to preserve the old event behavior. Set it to false when you do not use `RawMessage` to avoid dispatching large Base64 audio messages to the UI. `MuteMicWhileSpeaking` defaults to false; enabling it prevents voice interruption during assistant playback.

Build on Linux x86-64 with Python 3.12+, Git, and network access:

```sh
cd /workspace/gemini-live-new
python3 tools/build.py
python3 tools/test.py
```

The build downloads a pinned Temurin JDK 8 and pinned official MIT App Inventor extension dependencies, verifies their SHA-256 digests, compiles Java 7 bytecode, runs the official metadata generators, and packages the DEX and `.aix`. Dependency versions and checksums are in `tools/dependencies.json`. Build caches and dependency downloads are ignored by Git; the verified distribution files are included for direct download. No API key is needed to compile or run the local regression tests.

See `docs/AUDIT.md` for audit findings and the APK acceptance checklist. Local compilation and simulated regression tests cannot confirm microphone behavior on a physical phone or measure Gemini/network response latency.
