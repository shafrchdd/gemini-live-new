# GeminiLive function-only demo

Import ../GeminiFunctionDemo.aia into Kodular. It embeds the exact uploaded GeminiLive v6 extension; no DeskBuddy, camera, microphone controls or API key is included.

1. Enter your API key and a compatible Live model ID. Model input defaults to the uploaded extension's default gemini-3.8-live; change it if unavailable to your account.
2. Leave get_test_status and its description/schema unchanged for the first test.
3. Tap Register function and connect; wait for Ready.
4. Tap Request function / send prompt. The function event shows its name, arguments JSON and call ID.
5. Inspect the arguments; tap Return function result to send the editable result JSON using the saved call ID/name.
6. Gemini responds using that result. Text/output transcription is shown; audio playback is enabled.

The example is deliberately manual: it demonstrates registration → FunctionCall → SendFunctionResult without claiming to perform a hardware action. Replace the result-return blocks with your app's actual function implementation when ready. You can edit name, description, schema and prompt; reconnect to apply declaration changes. The parameter field takes only the JSON Schema object, not the entire function declaration.

A second call while one is pending receives a busy error. Do not send another prompt while awaiting a result. Invalid result JSON can be corrected and resubmitted; duplicate completed results are rejected by the extension. Disconnect resets pending IDs. The uploaded extension does not expose FunctionCallCancelled, so this demo cannot react to cancellation events; stale results may be rejected by that extension. Errors appear at the bottom of the screen.

Generated project/block signatures and embedded bytes were checked locally. Kodular import, Companion and exported APK operation still require device testing. No API key is stored by the project.

Rebuild: python3 tools/create_function_demo.py /path/to/com.fabiyon.geminilive.aix
