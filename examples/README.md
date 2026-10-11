# Kodular test project

[Download GeminiLiveTest.aia](https://github.com/shafrchdd/gemini-live-new/raw/refs/heads/main/examples/GeminiLiveTest.aia)

In Kodular Creator, use **Projects → Import project (.aia) from my computer**, then select this file. It includes the compiled GeminiLive version 4 extension and the complete screen/blocks; you do not need to import the `.aix` separately.

To test an image with a prompt:

1. Enter your API key, then tap **Take photo** or **Choose photo** and allow the camera/gallery permissions when requested.
2. Check the preview. Capture/selection happens before connecting: opening the native camera/gallery disconnects the previous Live session, even if you cancel.
3. Uncheck **Start listening automatically** for a photo-only test. Enter a question in the photo prompt, for example “What objects are in this image?”
4. Tap **Connect**, wait for **Connection: ready**, then tap **Send photo**.
5. Read **AI speech transcript** and listen to the response. **Photo sent** confirms network transmission; errors appear in the error label.

You can send another question with the same photo after its previous send finishes. To change the photo, capture/select again and reconnect. This project sends camera snapshots or selected images, rather than a continuous camera stream. Returned paths/URIs are retained only in memory; the native camera or picker may save a photo on the device.

For microphone and text tests:

1. Open the project in Companion or export and install a fresh APK.
2. Enter your Gemini API key in the app's masked input. The project contains no key and does not save the value to storage.
3. Keep a Live model that is available to your key; the default is `gemini-3.8-live`, with voice `Puck`.
4. Tap **Connect**. With **Start listening automatically** checked, the app waits for `SetupComplete`, then requests microphone access and starts capture after the grant.
5. Speak and watch **Microphone level**, **You said**, and **AI speech transcript**. Text responses and audio playback also work through **Send text**; stop the microphone first if isolating the text path.

The Companion and installed APK have separate microphone permissions. Test the APK's first grant, denial, and retry. The app displays the extension's `Error` event and Android permission errors. **Stop mic** also cancels a pending start, including a permission dialog that has not returned yet.

If the input remains silent, check the displayed error and level while speaking. Check Android's microphone privacy switch and other active recording apps. Turn off echo cancellation/noise suppression, stop the mic, then start again to compare capture without device effects. The **Mute microphone during AI speech** checkbox applies immediately and is now enabled by default to avoid speaker feedback; uncheck it to test voice barge-in.

For choppy audio, first uncheck automatic listening, connect, and send a text prompt with the microphone stopped. Watch **Playback waiting** while listening: it shows unwritten PCM backlog rather than network or hardware delay. Compare with microphone capture enabled afterward. Raw diagnostic events are disabled and transcripts update in small batches to reduce UI work. Stop audio and volume changes request worker cleanup rather than waiting on speaker writes. The example remains a diagnostic app; test actual audio and responsiveness on your phone.

Use **Stop AI audio** to verify interruptions and future playback, **Disconnect** for cleanup, and **Clear display** to reset only the displayed transcripts/errors. Each transcript retains the most recent 2,000 characters. Returning after backgrounding requires a fresh connection; the extension closes its session when the screen stops.

The key input uses the active Kodular `TextBox` with password input type. The deprecated `PasswordTextBox` is excluded because current Kodular documentation says it prevents APK/AAB builds.

To regenerate and validate from the repository root:

```sh
python3 tools/create_test_aia.py
python3 tools/validate_aia.py
```

On a fresh clone, run `python3 tools/build.py` first to fetch the pinned component metadata required by the validator.

The generator uses the verified `dist/com.fabiyon.geminilive.aix`, copies its extension files unchanged into `assets/external_comps`, and writes editable screen/property files under `examples/project`. Regeneration replaces those generated screen/property files; edit the generator to make reproducible changes. The standalone `examples/project` folder excludes duplicate extension binaries; import the generated `.aia`, which includes them.

Offline validation checks the archive, component/method/property/event signatures, block connections and event-variable scope, included extension bytes, permission metadata, empty key input, and readiness-before-listening. It uses the pinned official AndroidRuntime library for common signatures and current Kodular documentation for the app's Designer components. Actual import into an authenticated Kodular Creator account, Kodular APK export, and physical microphone capture have not been executed in this cloud environment.

Format references: [official Kodular AIA examples](https://github.com/Kodular/Documentation/tree/master/docs/media/aia), [Kodular TextBox](https://docs.kodular.io/components/user-interface/text-box/), [deprecated PasswordTextBox](https://docs.kodular.io/components/deprecated/password-text-box/), and [App Inventor component blocks](https://github.com/mit-cml/appinventor-sources/blob/master/appinventor/blocklyeditor/src/blocks/components.js).
