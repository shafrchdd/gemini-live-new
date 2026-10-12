#!/usr/bin/env python3
"""Offline structural and block-signature validation of the GeminiLive test AIA.

Block schemas follow MIT's authoritative components.js and lexical-variables.js:
https://github.com/mit-cml/appinventor-sources/tree/56a5b1a205c496511ec1c43b27843394148e53aa/appinventor/blocklyeditor/src/blocks
Standard signatures are read from the authentic AndroidRuntime.jar annotations,
descriptors and parameter debug tables; extension signatures come from the AIX.
This is not a substitute for importing/building the project in Kodular.
"""
import argparse
import hashlib
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXTENSION = "com.fabiyon.geminilive.GeminiLive"
# Form/YA/language baseline comes from the official Kodular Pollfish export;
# active native versions come from current Kodular component documentation.
KODULAR_VERSIONS = {"Form": "42", "VerticalArrangement": "10", "HorizontalArrangement": "10",
                    "Label": "10", "Button": "13", "CheckBox": "6", "Clock": "4", "TextBox": "13",
                    "Camera": "4", "ImagePicker": "12", "Image": "7"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


class ClassReader:
    """Minimal classfile reader, without loading Android stub classes on the JVM."""

    def __init__(self, data, constants=None):
        self.data, self.offset, self.constants = data, 0, constants or []

    def number(self, size):
        result = int.from_bytes(self.data[self.offset:self.offset + size], "big")
        self.offset += size
        return result

    def take(self, count):
        data = self.data[self.offset:self.offset + count]
        self.offset += count
        return data

    def attrs(self):
        return [(self.constants[self.number(2)], self.take(self.number(4)))
                for _ in range(self.number(2))]

    def annotation_value(self):
        tag = chr(self.number(1))
        if tag == "@":
            return self.annotation()
        if tag == "[":
            return [self.annotation_value() for _ in range(self.number(2))]
        if tag == "e":
            return self.constants[self.number(2)], self.constants[self.number(2)]
        return self.constants[self.number(2)]

    def annotation(self):
        name = self.constants[self.number(2)]
        fields = {}
        for _ in range(self.number(2)):
            field = self.constants[self.number(2)]
            fields[field] = self.annotation_value()
        return name, fields

    def annotations(self, attrs):
        result = {}
        for name, data in attrs:
            if name == "RuntimeVisibleAnnotations":
                reader = ClassReader(data, self.constants)
                result.update(reader.annotation() for _ in range(reader.number(2)))
        return result

    def parse(self):
        require(self.number(4) == 0xcafebabe, "Invalid runtime classfile")
        self.take(4)
        count = self.number(2)
        cp = [None] * count
        index = 1
        while index < count:
            tag = self.number(1)
            if tag == 1:
                cp[index] = self.take(self.number(2)).decode("utf-8", errors="replace")
            elif tag in (3, 4):
                cp[index] = self.number(4)
            elif tag in (5, 6):
                self.take(8)
                index += 1
            elif tag in (7, 8, 16, 19, 20):
                cp[index] = (tag, self.number(2))
            elif tag in (9, 10, 11, 12, 17, 18):
                self.take(4)
            elif tag == 15:
                self.take(3)
            else:
                raise ValueError("Unsupported classfile constant tag: " + str(tag))
            index += 1
        self.constants = cp
        self.take(4)
        super_index = self.number(2)
        superclass = cp[cp[super_index][1]] if super_index else None
        self.take(self.number(2) * 2)
        for _ in range(self.number(2)):
            self.take(6)
            self.attrs()
        methods = []
        for _ in range(self.number(2)):
            access, name, descriptor = self.number(2), cp[self.number(2)], cp[self.number(2)]
            attrs = self.attrs()
            annotations = self.annotations(attrs)
            parameters = []
            locals_ = {}
            for attr, data in attrs:
                reader = ClassReader(data, cp)
                if attr == "MethodParameters":
                    for _ in range(reader.number(1)):
                        parameters.append(cp[reader.number(2)])
                        reader.take(2)
                if attr == "Code":
                    reader.take(4)
                    reader.take(reader.number(4))
                    reader.take(reader.number(2) * 8)
                    for nested, payload in reader.attrs():
                        if nested == "LocalVariableTable":
                            local_reader = ClassReader(payload, cp)
                            for _ in range(local_reader.number(2)):
                                start = local_reader.number(2)
                                local_reader.take(2)
                                local_name = cp[local_reader.number(2)]
                                local_reader.take(2)
                                slot = local_reader.number(2)
                                if start == 0:
                                    locals_[slot] = local_name
            types = argument_types(descriptor)
            if not parameters:
                slot = 0 if access & 8 else 1
                for type_ in types:
                    parameters.append(locals_.get(slot))
                    slot += 2 if type_ in ("J", "D") else 1
            methods.append({"name": name, "descriptor": descriptor, "params": parameters,
                            "arity": len(types), "annotations": annotations})
        return {"super": superclass, "methods": methods,
                "annotations": self.annotations(self.attrs())}


def argument_types(descriptor):
    require(descriptor.startswith("("), "Invalid method descriptor")
    result, index = [], 1
    while descriptor[index] != ")":
        start = index
        while descriptor[index] == "[":
            index += 1
        if descriptor[index] == "L":
            index = descriptor.index(";", index) + 1
        else:
            index += 1
        result.append(descriptor[start:index])
    return result


class RuntimeMetadata:
    def __init__(self, jar):
        self.jar, self.cache = zipfile.ZipFile(jar), {}

    def read(self, type_):
        path = "com/google/appinventor/components/runtime/" + type_.replace(".", "/")
        result = {"methods": {}, "events": {}, "properties": {}}
        while path and (path + ".class") in self.jar.namelist():
            if path not in self.cache:
                self.cache[path] = ClassReader(self.jar.read(path + ".class")).parse()
            data = self.cache[path]
            for method in data["methods"]:
                for kind, annotation in (("methods", "SimpleFunction"), ("events", "SimpleEvent"),
                                         ("properties", "SimpleProperty")):
                    info = method["annotations"].get("Lcom/google/appinventor/components/annotations/" + annotation + ";")
                    if info is None or info.get("userVisible", 1) == 0:
                        continue
                    name = method["name"]
                    if kind == "properties":
                        properties = result[kind].setdefault(name, set())
                        properties.add("get" if method["arity"] == 0 else "set")
                    else:
                        result[kind].setdefault(name, method)
            path = data["super"]
        require(any(result.values()), "Unknown standard component type: " + type_)
        if type_ == "ImagePicker":
            # Kodular differs from MIT's zero-argument event; official current docs:
            # https://docs.kodular.io/components/Media/Image_Picker/#after-picking
            result["events"]["AfterPicking"] = {"arity": 1, "params": ["selection"]}
            result["properties"]["AllowMultipleSelections"] = {"get", "set"}
        return result


def extension_metadata(info):
    return {
        "methods": {item["name"]: {"arity": len(item["params"]), "params": [p["name"] for p in item["params"]],
                                    "returns": "returnType" in item}
                    for item in info["methods"]},
        "events": {item["name"]: {"arity": len(item["params"]), "params": [p["name"] for p in item["params"]]}
                   for item in info["events"]},
        "properties": {item["name"]: {mode for mode in ("get", "set")
                                       if (mode == "get" and item["rw"] != "write-only")
                                       or (mode == "set" and item["rw"] != "read-only")}
                       for item in info["blockProperties"]}}


def local_tag(element):
    return element.tag.rsplit("}", 1)[-1]


def child(element, tag):
    return next((item for item in element if local_tag(item) == tag), None)


def children(element, tag):
    return [item for item in element if local_tag(item) == tag]


def fields(block):
    return {field.get("name"): field.text or "" for field in children(block, "field")}


def descendants(block, type_=None):
    return [item for item in block.iter() if local_tag(item) == "block"
            and (type_ is None or item.get("type") == type_)]


def properties(text):
    result = {}
    for line in text.splitlines():
        if line.strip() and not line.lstrip().startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def validate_basic_block(block):
    type_ = block.get("type")
    inputs = {item.get("name") for item in children(block, "value")}
    statements = {item.get("name") for item in children(block, "statement")}
    values = fields(block)
    fixed = {"text": set(), "logic_boolean": set(), "math_number": set(),
             "text_length": {"VALUE"}, "text_trim": {"TEXT"}, "text_segment": {"TEXT", "START", "LENGTH"},
             "text_starts_at": {"TEXT", "PIECE"},
             "math_subtract": {"A", "B"}, "math_compare": {"A", "B"},
             "logic_negate": {"BOOL"}, "global_declaration": {"VALUE"},
             "lexical_variable_get": set(), "lexical_variable_set": {"VALUE"}}
    if type_ in fixed:
        require(inputs == fixed[type_] and not statements, "Invalid basic block inputs: " + type_)
        if type_ == "logic_boolean":
            require(values.get("BOOL") in ("TRUE", "FALSE"), "Invalid boolean constant")
        elif type_ == "math_number":
            float(values["NUM"])
        elif type_ == "math_compare":
            require(values.get("OP") in ("EQ", "NEQ", "LT", "LTE", "GT", "GTE"), "Invalid comparison operation")
        elif type_ == "text_trim":
            require(values.get("MODE") in ("BOTH", "LEFT", "RIGHT"), "Invalid trim mode")
        elif type_ == "text_starts_at":
            require(not values and child(block, "mutation") is None, "text_starts_at has no fields or mutation")
    elif type_ == "text_join":
        mutation = child(block, "mutation")
        count = int(mutation.get("items", "2")) if mutation is not None else 2
        require(count >= 1 and inputs == {"ADD" + str(i) for i in range(count)} and not statements,
                "Invalid MIT text_join mutation/inputs")
    elif type_ == "controls_if":
        mutation = child(block, "mutation")
        additional = int(mutation.get("elseif", "0")) if mutation is not None else 0
        otherwise = int(mutation.get("else", "0")) if mutation is not None else 0
        require(additional >= 0 and otherwise in (0, 1), "Invalid controls_if mutation")
        require(inputs == {"IF" + str(i) for i in range(additional + 1)}, "Invalid controls_if conditions")
        require(statements == ({"DO" + str(i) for i in range(additional + 1)} | ({"ELSE"} if otherwise else set())),
                "Invalid controls_if branches")
    elif type_ not in ("component_event", "component_method", "component_set_get"):
        raise ValueError("Unknown or unsupported test block type: " + str(type_))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aia", type=pathlib.Path, nargs="?", default=ROOT / "examples/GeminiLiveTest.aia")
    args = parser.parse_args()
    checksum = args.aia.with_suffix(args.aia.suffix + ".sha256")
    if checksum.exists():
        require(checksum.read_text().split()[0] == hashlib.sha256(args.aia.read_bytes()).hexdigest(),
                "AIA SHA-256 sidecar mismatch")
    runtime_path = ROOT / "tools/vendor/extension-deps/AndroidRuntime.jar"
    require(runtime_path.exists(), "Run tools/build.py first to fetch authentic component metadata")
    lock = json.loads((ROOT / "tools/dependencies.json").read_text())
    require(hashlib.sha256(runtime_path.read_bytes()).hexdigest() == lock["jars"]["AndroidRuntime.jar"],
            "Authentic runtime metadata checksum mismatch")
    runtime = RuntimeMetadata(runtime_path)
    with zipfile.ZipFile(args.aia) as project, zipfile.ZipFile(ROOT / "dist/com.fabiyon.geminilive.aix") as extension:
        names = project.namelist()
        require(len(names) == len(set(names)), "Duplicate AIA ZIP entry")
        require(all(not name.startswith("/") and ".." not in pathlib.PurePosixPath(name).parts for name in names),
                "Unsafe AIA ZIP path")
        require("youngandroidproject/project.properties" in names, "Missing project.properties")
        settings = properties(project.read("youngandroidproject/project.properties").decode())
        require(settings.get("main", "").endswith(".Screen1"), "Project main must be Screen1")
        require(settings.get("source") == "../src" and settings.get("assets") == "../assets",
                "Project source/assets paths do not match App Inventor layout")
        screen_path = "src/" + settings["main"].replace(".", "/")
        require(screen_path + ".scm" in names and screen_path + ".bky" in names, "Missing Screen1 sources")
        require({name for name in names if name.startswith("src/")} == {screen_path + ".scm", screen_path + ".bky"},
                "Unexpected or orphan screen source files")
        expected_assets = {"assets/external_comps/" + name for name in extension.namelist()}
        require(set(names) == expected_assets | {"youngandroidproject/project.properties",
                                               screen_path + ".scm", screen_path + ".bky"},
                "Unexpected/missing project archive entry")
        require({name for name in names if name.startswith("assets/")} == expected_assets,
                "Extension assets missing, extra, or stored at the wrong path")
        for name in extension.namelist():
            require(project.read("assets/external_comps/" + name) == extension.read(name),
                    "Embedded AIX payload differs from the current extension: " + name)
        info = json.loads(extension.read("com.fabiyon.geminilive/components.json"))[0]
        require(info["type"] == EXTENSION, "Unexpected extension type")
        scm = project.read(screen_path + ".scm").decode()
        require(scm.startswith("#|") and "$JSON\n" in scm and scm.rstrip().endswith("|#"), "Invalid SCM wrapper")
        screen = json.loads(scm.split("$JSON\n", 1)[1].rsplit("|#", 1)[0])
        components = {}

        def visit(component):
            name, type_ = component.get("$Name"), component.get("$Type")
            require(name and type_, "Designer component has no name/type")
            require(name not in components, "Duplicate component instance: " + name)
            require(type_ != "PasswordTextBox", "Deprecated Kodular PasswordTextBox blocks APK builds")
            require(type_ != "Camera" or "UseFront" not in component, "Deprecated Camera.UseFront must be omitted")
            external = type_ in (EXTENSION, info["name"])
            metadata = extension_metadata(info) if external else runtime.read(type_)
            require(str(component.get("$Version", "")).isdigit(), "Missing/invalid Designer component version")
            if external:
                require(component["$Version"] == str(info["version"]), "Embedded extension instance version mismatch")
            else:
                require(type_ in KODULAR_VERSIONS and component["$Version"] == KODULAR_VERSIONS[type_],
                        "Designer component version differs from validated Kodular profile: " + type_)
            components[name] = {"type": type_, "block_type": info["name"] if external else type_,
                                "metadata": metadata, "designer": component}
            for nested in component.get("$Components", []):
                visit(nested)

        visit(screen["Properties"])
        require(components.get("Screen1", {}).get("type") == "Form", "Missing Screen1 Form")
        require(components.get("GeminiLive1", {}).get("type") in (EXTENSION, info["name"]), "Missing GeminiLive1 instance")
        require(components.get("ApiKeyInput", {}).get("type") == "TextBox", "API key input must use active Kodular TextBox")
        require(components["ApiKeyInput"]["designer"].get("InputType") == "2", "API key TextBox must use password InputType 2")
        require(components["ApiKeyInput"]["designer"].get("Text", "") == "", "API key default must be blank")
        for name in (screen_path + ".scm", screen_path + ".bky", "youngandroidproject/project.properties"):
            require(not re.search(rb"AIza[0-9A-Za-z_-]{30,}", project.read(name)), "Embedded API credential detected")
        blocks = ET.fromstring(project.read(screen_path + ".bky"))
        require(local_tag(blocks) == "xml", "Invalid Blockly XML root")
        versions = child(blocks, "yacodeblocks")
        require(versions is not None and versions.get("ya-version") == str(screen.get("YaVersion")),
                "Designer/Blockly YaVersion mismatch")
        require(versions.get("language-version", "").isdigit(), "Missing/invalid Blockly language version")
        require(str(screen.get("YaVersion")) == "222" and versions.get("language-version") == "29",
                "Project version differs from the official Kodular sample baseline")
        require(blocks.tag == "{http://www.w3.org/1999/xhtml}xml", "Kodular sample uses the XHTML Blockly namespace")
        parent = {item: owner for owner in blocks.iter() for item in owner}
        all_blocks = descendants(blocks)
        require(all(item.get("type") in ("component_event", "global_declaration")
                    for item in children(blocks, "block")), "Orphan executable block outside an event")
        ids = [block.get("id") for block in all_blocks]
        require(all(ids) and len(ids) == len(set(ids)), "Missing or duplicate block IDs")
        require(not any(local_tag(item) == "shadow" for item in blocks.iter()), "Shadow blocks obscure test logic")
        declarations = [block for block in all_blocks if block.get("type") == "global_declaration"]
        global_names = [fields(block).get("NAME") for block in declarations]
        require(all(name and re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", name) for name in global_names), "Invalid global variable declaration")
        require(len(global_names) == len(set(global_names)), "Duplicate global variable declaration")
        globals_ = set(global_names)
        require("micStartPending" in globals_, "Expected microphone start-pending state global")
        microphone_declaration = next(block for block in declarations if fields(block).get("NAME") == "micStartPending")
        initial_value = child(microphone_declaration, "value")
        initial_block = None if initial_value is None else child(initial_value, "block")
        require(initial_block is not None and initial_block.get("type") == "logic_boolean"
                and fields(initial_block).get("BOOL") == "FALSE",
                "Microphone start-pending state must initialize false")
        events = {}
        for block in all_blocks:
            validate_basic_block(block)
            output = block.get("type") not in ("component_event", "component_method", "component_set_get",
                                                "controls_if", "global_declaration", "lexical_variable_set")
            require(block.get("disabled", "false") != "true", "Disabled test block: " + block.get("id"))
            for item in block:
                if local_tag(item) in ("value", "statement", "next"):
                    require(len(children(item, "block")) == 1, "Disconnected/ambiguous input: " + block.get("id"))
            type_ = block.get("type", "")
            if type_ in ("component_event", "component_method", "component_set_get"):
                mutation = child(block, "mutation")
                require(mutation is not None, "Missing component block mutation")
                name, component_type = mutation.get("instance_name"), mutation.get("component_type")
                require(mutation.get("is_generic") == "false", "Test component block must specify its instance")
                require(name in components, "Unknown block component instance: " + str(name))
                require(components[name]["block_type"] == component_type, "Component database type/instance mismatch: " + name)
                require(fields(block).get("COMPONENT_SELECTOR") == name, "Component selector/mutation mismatch")
                metadata = components[name]["metadata"]
                inputs = {item.get("name") for item in children(block, "value")}
                if type_ == "component_method":
                    method = mutation.get("method_name")
                    require(method in metadata["methods"], "Unknown method: " + name + "." + str(method))
                    require(inputs == {"ARG" + str(i) for i in range(metadata["methods"][method]["arity"])},
                            "Method argument count/names mismatch: " + name + "." + method)
                    signature = metadata["methods"][method]
                    output = signature.get("returns", not signature.get("descriptor", ")V").endswith(")V"))
                elif type_ == "component_set_get":
                    property_, mode = mutation.get("property_name"), mutation.get("set_or_get")
                    require(property_ in metadata["properties"] and mode in metadata["properties"][property_],
                            "Unknown or inaccessible property: " + name + "." + str(property_))
                    require(fields(block).get("PROP") == property_, "Property field/mutation mismatch")
                    require(inputs == ({"VALUE"} if mode == "set" else set()), "Property input mismatch")
                    output = mode == "get"
                else:
                    event = mutation.get("event_name")
                    require(event in metadata["events"], "Unknown event: " + name + "." + str(event))
                    require((name, event) not in events, "Duplicate event handler: " + name + "." + event)
                    events[name, event] = block
                    require(not inputs, "Unexpected event value arguments")
                    require({item.get("name") for item in children(block, "statement")} <= {"DO"}, "Invalid event statement input")
            elif type_ in ("lexical_variable_get", "lexical_variable_set"):
                variable = fields(block).get("VAR", "")
                if variable.startswith("global "):
                    require(variable[7:] in globals_, "Unknown global variable: " + variable)
                    require(child(block, "mutation") is None, "Global variable reference has an event-parameter mutation")
                else:
                    owner = parent.get(block)
                    while owner is not None and owner.get("type") != "component_event":
                        owner = parent.get(owner)
                    require(owner is not None, "Local variable outside event scope: " + variable)
                    event = child(owner, "mutation")
                    signature = components[event.get("instance_name")]["metadata"]["events"][event.get("event_name")]
                    defaults = signature["params"]
                    require(all(defaults), "Runtime debug table missing event parameter names")
                    names_ = [event.get("param_name" + str(i), item) for i, item in enumerate(defaults)]
                    require(variable in names_, "Unknown event variable: " + variable)
                    mutation = child(block, "mutation")
                    eventparam = None if mutation is None else child(mutation, "eventparam")
                    require(eventparam is not None and eventparam.get("name") in defaults, "Missing/invalid event parameter mutation")
            location = local_tag(parent[block])
            if location == "value":
                require(output, "Statement block inserted into a value input: " + block.get("id"))
            elif location in ("statement", "next"):
                require(not output and type_ != "component_event", "Value/event block inserted into statement chain: " + block.get("id"))
        required_events = {("Screen1", name) for name in ("Initialize", "PermissionDenied", "ErrorOccurred")}
        required_events |= {("GeminiLive1", name) for name in ("Connected", "SetupComplete", "ListeningStarted",
                           "ListeningStopped", "InputTranscription", "OutputTranscription", "TextReceived",
                           "TurnComplete", "Error", "Disconnected")}
        required_events |= {(name, "Click") for name, data in components.items() if data["type"] == "Button"}
        required_events.add(("LevelClock", "Timer"))
        required_events.add(("MuteCheck", "Changed"))
        if int(info["version"]) >= 3:
            cameras = [name for name, item in components.items() if item["type"] == "Camera"]
            pickers = [name for name, item in components.items() if item["type"] == "ImagePicker"]
            previews = [name for name, item in components.items() if item["type"] == "Image"]
            require(cameras and pickers and previews, "Image-enabled extension project must provide camera, gallery and preview")
            required_events |= {(name, "AfterPicture") for name in cameras}
            required_events |= {(name, "AfterPicking") for name in pickers}
            required_events.add(("GeminiLive1", "ImageSent"))
        require(required_events.issubset(events), "Required handlers missing: " + str(sorted(required_events - events.keys())))

        def methods(block):
            return [(child(item, "mutation").get("instance_name"), child(item, "mutation").get("method_name"))
                    for item in descendants(block, "component_method")]

        if int(info["version"]) >= 3:
            require(("GeminiLive1", "SendImage") in methods(events["SendImageButton", "Click"]), "Photo send button is not wired")
            for name in cameras:
                require(("GeminiLive1", "SendImage") not in methods(events[name, "AfterPicture"]), "Camera callback must wait for reconnect and readiness")
            for name in pickers:
                require((name, "BeforePicking") in events, "Gallery must handle disconnect before external activity")
                require(("GeminiLive1", "Disconnect") in methods(events[name, "BeforePicking"]), "Gallery activity must reset connection")
                require(("GeminiLive1", "SendImage") not in methods(events[name, "AfterPicking"]), "Gallery callback must wait for reconnect and readiness")
            require(("GeminiLive1", "Disconnect") in methods(events["CameraButton", "Click"]), "Camera activity must reset connection")

        require(("GeminiLive1", "Connect") in methods(events["ConnectButton", "Click"]), "Connect button is not wired")
        if "ToolAskButton" in components:
            for key in (("ToolAskButton", "Click"), ("ToolReplyButton", "Click"),
                        ("GeminiLive1", "FunctionCall"), ("GeminiLive1", "FunctionCallCancelled")):
                require(key in events, "Missing function-test handler: " + str(key))
            sequence = methods(events["ConnectButton", "Click"])
            require(("GeminiLive1", "ClearFunctions") in sequence and ("GeminiLive1", "AddFunction") in sequence,
                    "Function test must register its tool")
            require(sequence.index(("GeminiLive1", "AddFunction")) < sequence.index(("GeminiLive1", "Connect")),
                    "Tool must be registered before connection setup")
            require(("GeminiLive1", "SendText") in methods(events["ToolAskButton", "Click"]), "Missing tool request prompt")
            require(("GeminiLive1", "SendFunctionResult") in methods(events["ToolReplyButton", "Click"]), "Missing manual tool result")
        require(("GeminiLive1", "StartListening") not in methods(events["ConnectButton", "Click"]),
                "Microphone start must wait for SetupComplete")
        require(("GeminiLive1", "StartListening") in methods(events["GeminiLive1", "SetupComplete"]),
                "SetupComplete is not wired to automatic listening")
        for method_block in descendants(events["ConnectButton", "Click"], "component_method"):
            mutation = child(method_block, "mutation")
            if mutation.get("instance_name") == "GeminiLive1" and mutation.get("method_name") == "Connect":
                argument = child(method_block, "value")
                getters = descendants(argument, "component_set_get")
                require(any(child(item, "mutation").get("instance_name") == "ApiKeyInput"
                            and child(item, "mutation").get("property_name") == "Text"
                            and child(item, "mutation").get("set_or_get") == "get" for item in getters),
                        "Connect must read the runtime API key input")
        for key in (("GeminiLive1", "Error"), ("Screen1", "PermissionDenied"), ("Screen1", "ErrorOccurred")):
            require(any(child(item, "mutation").get("set_or_get") == "set"
                        and child(item, "mutation").get("property_name") == "Text"
                        and components[child(item, "mutation").get("instance_name")]["type"] in ("Label", "TextBox")
                        for item in descendants(events[key], "component_set_get")), "Missing visible error display: " + str(key))
        clock = components["LevelClock"]["designer"]
        require(str(clock.get("TimerAlwaysFires", "")).lower() == "false", "Meter clock must stop in background")
        require(100 <= int(clock.get("TimerInterval", "0")) <= 1000, "Invalid microphone meter interval")
        if int(info["version"]) >= 4:
            require(("PlaybackClock", "Timer") in events, "Missing playback queue meter")
            require(("GeminiLive1", "PlaybackBufferedMilliseconds") in methods(events["PlaybackClock", "Timer"]), "Playback meter is not wired")
            require(str(components["PlaybackClock"]["designer"].get("TimerAlwaysFires", "")).lower() == "false", "Playback meter must stop in background")
            require(str(components["MuteCheck"]["designer"].get("Checked", "")).lower() == "true", "Playback diagnostic baseline should mute microphone during AI speech")
        if int(info["version"]) >= 5:
            setters = [child(item, "mutation") for item in descendants(events["ConnectButton", "Click"], "component_set_get")]
            for property_ in ("InputLanguageCodes", "ReplyLanguage", "TranscriptionLanguageHints"):
                require(any(m.get("instance_name") == "GeminiLive1" and m.get("property_name") == property_
                            and m.get("set_or_get") == "set" for m in setters), "Missing language setup block: " + property_)
        print("PASS AIA structure, current extension payload, authentic component signatures, variable scopes, enabled blocks, permission/error handlers and connection/listening workflow")
        print(json.dumps({"aia": str(args.aia), "components": len(components), "blocks": len(all_blocks),
                          "events": len(events), "sha256": hashlib.sha256(args.aia.read_bytes()).hexdigest(),
                          "kodular_import_test": "not_run", "apk_build_test": "not_run"}, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, ET.ParseError, zipfile.BadZipFile, FileNotFoundError) as error:
        print("FAIL AIA validation: " + str(error), file=sys.stderr)
        sys.exit(1)
