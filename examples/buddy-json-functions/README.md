# GeminiLive + DeskBuddy + JsonUtil automatic functions

Import ../GeminiBuddyJsonFunctions.aia into Kodular. It embeds the exact uploaded GeminiLive v6 and DeskBuddy v2 extensions, plus the compiled JsonUtil v1 extension.

1. Enter your API key and an available Live model ID. The prefilled model is the uploaded extension default; change it if unsupported by your account.
2. Tap Register buddy functions and connect. Wait for Ready.
3. Tap Test happy emotion or Test wave reaction, or send a prompt such as “Become sad, then wave hello.”
4. Gemini issues buddy_set_emotion({"emotion":"happy"}) or buddy_play_reaction({"reaction":"wave"}). The handler validates arguments with JsonUtil.IsValid, checks for an object root with ValueType, reads the value using GetValue, checks it against the corresponding DeskBuddy catalog, applies the action, and builds its result with SetBoolean, SetText and SetNumber. It automatically sends the result with the same name/callId.
5. Function, arguments and returned JSON are displayed. Gemini replies using the returned result. Reactions run for 3000 milliseconds and return status started, not animation completed. Emotions persist after temporary gestures.

All 26 emotions and 28 interactive reactions are declared as enum values. Missing/unsupported arguments or unknown function names return ok:false. Action errors are shown and return failure. No microphone/camera controls or API key storage are included. Battery and Wi-Fi symbols start hidden; checkboxes can enable them. Autonomous idle is disabled to make tests easy to observe. Attach uses the actual BuddyPanel component block in Screen.Initialize; if you edit that block, pass the arrangement itself rather than its Visible property or a boolean.

The AIA does not install a general-purpose dispatcher for all 17 functions in the separate JSON pack: it intentionally implements only emotion and reaction controls. Add extra declarations only when you implement their handlers.

Local validation checked native/extension signatures, exact uploaded payloads and eight executions of the generated handler using a block interpreter with simulated extension calls. It does not establish Android/Kodular import or device rendering behavior. Test Companion and an exported APK on your phone.

Rebuild: python3 tools/create_buddy_json_function_demo.py /path/to/GeminiLive.aix /path/to/DeskBuddy.aix

JsonUtil replaces the Web JSON decoder and dictionary lookup blocks. The result contains ok, emotion/reaction, current_emotion and status; reactions also include duration_ms=3000. Invalid JSON/object roots and unsupported values receive an error result. No manual Return result button is needed. All three extensions are embedded; do not import duplicate copies into this project.
