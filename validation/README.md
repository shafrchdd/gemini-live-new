The production extension is compiled with genuine Android and MIT App Inventor
libraries before any regression test runs. The JVM tests then execute that same
bytecode with deterministic Android and Form doubles. They do not replace the
production code or ship inside the AIX.

Run from the repository root:

```sh
python3 tools/build.py
python3 tools/test.py --skip-build
```

The first build downloads the pinned official Temurin JDK 8 and MIT extension
dependencies. TLS verification stays enabled and SHA-256 hashes are checked.
Python 3.12+, Git and network access are required initially. Later builds reuse
the verified local tools. Build logs and test bytecode are under `build/`.

`TransportTests.java` covers 53 cases: client masking, text/binary messages,
fragmentation, UTF-8, control frames, concurrent writers, handshake validation,
length limits, malformed frames and cancellation of a blocked socket writer.

`RuntimeTests.java` covers 58 cases: asynchronous APK permission and Companion
permission flows, denials and late grants, recorder source fallback, recording
and effect failures, repeated start/stop, lifecycle cleanup, audio stream
ordering, queue limits, write watchdog, transcription, tool cancellation,
playback buffer accounting, blocked AudioTrack writes, PCM arriving during a
flush, and recovery after a playback write error. Photo cases cover readiness,
image/prompt protocol, off-UI preparation, resizing and EXIF transforms, source
and JPEG limits, encoder/OOM failures, one pending send through network writes,
source cancellation, replacement sessions, queue rejection, and camera lifecycle.
Playback regressions also exercise a full fifteen-second response burst,
nonblocking backpressure and partial writes, blocked native cleanup with responsive
UI controls, overflow recovery at the next turn, startup priming, lossless batching
of 1,000 transcript deltas, and bounded opt-in raw diagnostic events.
They also cover a stalled native recorder stop without UI blocking and a
disconnect during batched event delivery suppressing remaining stale callbacks.

`python3 tools/validate_aia.py` checks the test project archive, exact embedded
extension bytes, native/extension block signatures, event/global scopes,
camera/gallery reconnection flow, and runtime API-key input. Kodular's documented
ImagePicker event/property differences from MIT are explicitly handled.

These checks establish API compatibility, packaged microphone permission,
control flow, ordering and resource ownership. They cannot establish hardware
microphone routing, measured end-to-end latency, current API-key/model access,
photo decoding/URI access or behavior of a specific Kodular-generated APK. Validate those on a physical
Android device using a clean rebuild and first-launch permission grant.
