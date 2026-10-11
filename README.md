# GeminiLive for Kodular

This is the repaired version of the uploaded `com.fabiyon.geminilive.GeminiLive` extension. Existing block signatures are preserved. The extension version is 2.

Downloads:

- [Kodular test project (.aia)](https://github.com/shafrchdd/gemini-live-new/raw/refs/heads/main/examples/GeminiLiveTest.aia) — extension and example blocks included; [usage instructions](examples/README.md).
- [Compiled Kodular extension (.aix)](https://github.com/shafrchdd/gemini-live-new/raw/refs/heads/main/dist/com.fabiyon.geminilive.aix)
- [Complete source and build bundle](https://github.com/shafrchdd/gemini-live-new/raw/refs/heads/main/dist/GeminiLive-v2.zip)
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
