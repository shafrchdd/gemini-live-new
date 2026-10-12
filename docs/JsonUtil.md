# JsonUtil for Kodular

[Download com.fabiyon.jsonutil.aix](https://github.com/shafrchdd/gemini-live-new/raw/refs/heads/main/dist/com.fabiyon.jsonutil.aix). Import under Extensions, then drag JsonUtil onto your screen. It is independent of GeminiLive/DeskBuddy and requires no permissions.

## Read Gemini function arguments

For argumentsJson = `{"emotion":"happy","duration_ms":3000}`:

```text
emotion = JsonUtil1.GetValue(argumentsJson, "emotion", "idle")
duration = JsonUtil1.GetValue(argumentsJson, "duration_ms", 3000)
```

Validate names with DeskBuddy's supported lists before applying them. GetValue returns native strings, numbers and booleans. Nested objects/arrays return JSON text; JSON null or a missing key returns your fallback. This is a top-level key lookup: dots and slashes are literal key characters, not paths. For nested access, call GetJson first, then GetValue on the returned object JSON.

## Build a function result

Setter blocks return new JSON text; save each return value:

```text
result = "{}"
result = JsonUtil1.SetBoolean(result, "ok", true)
result = JsonUtil1.SetText(result, "emotion", emotion)
GeminiLive1.SendFunctionResult(callId, name, result)
```

SetText escapes quotes, backslashes, newlines and Unicode correctly. Do not concatenate unescaped user text into JSON. SetJson inserts a nested JSON value; use `"null"` to insert JSON null.

## Blocks

| Block | Purpose |
|---|---|
| IsValid(json) | Check JSON syntax without firing Error; inspect LastError on false |
| GetValue(json, key, fallback) | Read a native primitive or nested JSON text |
| GetJson(json, key, fallbackJson) | Read a value as encoded JSON, including string quotes/null |
| HasKey(json, key) | Check existence; JSON null still counts as present |
| ValueType(json, key) | string, number, boolean, object, array, null, missing; error for invalid input |
| Keys(json) | Sorted Kodular list of object keys |
| SetText(json, key, value) | Add/replace a string |
| SetNumber(json, key, value) | Add/replace a finite number |
| SetBoolean(json, key, value) | Add/replace a boolean |
| SetJson(json, key, valueJson) | Add/replace any encoded JSON value |
| RemoveKey(json, key) | Return object with key removed |
| Quote(text) | Encode text as a quoted JSON string |
| ArrayLength(json) | Array length; -1 on failure |
| ArrayGet(json, index, fallback) | Read an array item using **1-based** indexes |

Object operations require an object root; array operations require an array root. Each operation clears LastError first. Invalid input raises Error(operation, message), except IsValid, which is a silent check. Failed setters/removal return the original input; failed getters return the fallback. Check LastError or handle Error before reporting an action as successful. Missing object keys are normal and do not fire Error. Out-of-range array indexes do fire Error.

Inputs are limited to 262144 characters and 64 nested containers. JSON syntax validation rejects trailing content, comments, unquoted keys, single quotes and trailing commas. Numbers use Android org.json numeric representation; very large/precise numbers may lose precision, and nonfinite/overflow values are rejected. Keep exact identifiers and decimal amounts as strings when necessary. Operations are synchronous; this extension is intended for small function arguments/results.

## Build and verification

```text
python3 tools/build.py --source src/com/fabiyon/jsonutil/JsonUtil.java --package com.fabiyon.jsonutil --build-dir build/jsonutil
python3 tools/test_jsonutil.py
```

Authentic extension compile, metadata processing and DEX conversion passed. 44 pure implementation checks passed using a checksum-pinned host org.json implementation (test-only dependency, not bundled). These checks cover JSON escaping/Unicode, nested values, scalar types, invalid syntax, nesting/size limits and nonfinite numbers. Kodular import and Android block/event execution still require device testing.
