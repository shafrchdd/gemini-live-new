# GeminiLive → DeskBuddy function integration

Register each entry from DeskBuddy-AddFunction-inputs.json using GeminiLive1.AddFunction(name, description, parametersJson) **before Connect**. parametersJson is the schema object encoded as text, not the entire declaration. Alternatively decode DeskBuddy-functions.json and encode each declaration's parameters object as JSON text. ClearFunctions first if rebuilding the function list. Reconnect after changing declarations.

Declarations request actions; they do not execute DeskBuddy blocks automatically. Handle GeminiLive1.FunctionCall(name, argumentsJson, callId), decode argumentsJson with a JSON dictionary decoder, validate required keys, allowed enum values, types and ranges, and dispatch using the following mapping. Never turn arbitrary model strings into arbitrary component/method access.

| Function name | Kodular handler |
|---|---|
| `buddy_set_emotion` | DeskBuddy1.SetEmotion(emotion) |
| `buddy_play_reaction` | DeskBuddy1.PlayReaction(reaction, duration_ms) |
| `buddy_stop_reaction` | DeskBuddy1.StopReaction() |
| `buddy_sleep` | DeskBuddy1.Sleep() |
| `buddy_wake_up` | DeskBuddy1.WakeUp() |
| `buddy_start` | DeskBuddy1.Start() |
| `buddy_stop` | DeskBuddy1.Stop() |
| `buddy_set_overlay` | DeskBuddy1.SetSystemOverlay(overlay, active) |
| `buddy_set_conversation_state` | DeskBuddy1.SetConversationState(state) |
| `buddy_set_boolean_setting` | Branch on setting and set the matching DeskBuddy1 property to enabled. |
| `buddy_set_frame_rate` | Set DeskBuddy1.FramesPerSecond to value. |
| `buddy_set_sleep_timeout` | Set DeskBuddy1.SleepAfterSeconds to value. |
| `buddy_set_shake_threshold` | Set DeskBuddy1.ShakeThreshold to value. |
| `buddy_set_speech_level` | DeskBuddy1.SpeechLevel(level) |
| `buddy_simulate_power` | Set DeskBuddy1.DeviceMonitoring to false, then DeskBuddy1.SetPowerState(level, plugged). |
| `buddy_get_status` | Return CurrentEmotion(), BatteryLevel(), IsPluggedIn(), MotionSensorAvailable(), and relevant boolean/numeric property getters. BatteryLevel=-1 means unknown. |
| `buddy_get_catalogs` | Return Emotions(), Reactions(), SystemStates(), ConversationReactions(), IdleBehaviors(). |

After a successful action, call GeminiLive1.SendFunctionResult(callId, name, "{\"ok\":true}"). For status/catalog queries return a JSON object containing real getter values. For invalid arguments, unsupported names or failed actions, return an object such as {"ok":false,"error":"Unsupported emotion"}. Do not report success after DeskBuddy reports Error. Ignore pending calls whose IDs were delivered through FunctionCallCancelled; the extension rejects canceled results.

Attach(BuddyPanel) once from Screen.Initialize using the actual arrangement component block. Attach/Detach are application lifecycle controls, so they are not exposed as model functions: a component reference cannot be transported in function JSON. Native touch/lift/shake and battery/Wi-Fi events remain event blocks, not model-callable sensor triggers. Enable sensor/monitoring preferences as appropriate. Keep microphone, playback, camera and connection indicators synchronized through GeminiLive/app events. Function calls do not create that event wiring.

Suggested system instruction addition:
“Use buddy_set_emotion and buddy_play_reaction sparingly to express appropriate emotions. Only change settings, stop, sleep or simulate power when requested. Never pretend a visual overlay changes hardware. Device and audio activity are owned by the application. Do not speak tool names or JSON.”

All declarations target DeskBuddy v2 and the current GeminiLive AddFunction/FunctionCall/SendFunctionResult API. This is a JSON registration pack and wiring guide, not a prewired AIA. Register only the tools your project handles; for normal conversations, emotion, reaction, sleep, wake and status are a useful small set.
