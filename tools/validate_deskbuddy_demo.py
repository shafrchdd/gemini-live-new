#!/usr/bin/env python3
"""Validate DeskBuddy AIA against authentic native and extension block metadata."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
import argparse
from validate_aia import RuntimeMetadata, extension_metadata, require, children, child, fields, descendants, local_tag, validate_basic_block

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aia", type=Path, nargs="?", default=ROOT / "examples/DeskBuddyDemo.aia")
    target = parser.parse_args().aia
    checksum = target.with_suffix(".aia.sha256")
    if checksum.exists():
        require(checksum.read_text().split()[0] == hashlib.sha256(target.read_bytes()).hexdigest(), "AIA checksum mismatch")
    runtime_path = ROOT / "tools/vendor/extension-deps/AndroidRuntime.jar"
    lock = json.loads((ROOT / "tools/dependencies.json").read_text())
    require(hashlib.sha256(runtime_path.read_bytes()).hexdigest() == lock["jars"]["AndroidRuntime.jar"], "Runtime metadata checksum mismatch")
    runtime = RuntimeMetadata(runtime_path)
    with zipfile.ZipFile(target) as project, zipfile.ZipFile(ROOT / "dist/com.fabiyon.deskbuddy.aix") as extension:
        names = project.namelist()
        require(len(names) == len(set(names)), "Duplicate zip entries")
        require(all(not n.startswith("/") and ".." not in Path(n).parts for n in names), "Unsafe zip path")
        require(project.testzip() is None, "Corrupt archive")
        for name in extension.namelist():
            if not name.endswith("/"):
                require(project.read("assets/external_comps/" + name) == extension.read(name), "Embedded extension mismatch")
        info = json.loads(extension.read("com.fabiyon.deskbuddy/components.json"))[0]
        build = json.loads(extension.read("com.fabiyon.deskbuddy/files/component_build_infos.json"))
        require(any("android.permission.ACCESS_NETWORK_STATE" in x.get("permissions", []) for x in build), "Missing network-state permission")
        require(not any("RECORD_AUDIO" in p or "CAMERA" in p for x in build for p in x.get("permissions", [])), "Avatar acquired unrelated audio/camera permissions")
        source = "src/appinventor/ai_shafrchdd/DeskBuddyDemo/Screen1"
        raw = project.read(source + ".scm").decode()
        screen = json.loads(raw.split("$JSON\n", 1)[1].rsplit("\n|#", 1)[0])
        require(screen["YaVersion"] == "222", "Unexpected export baseline")
        components = {}
        versions = {"Form": "42", "VerticalArrangement": "10", "HorizontalArrangement": "10", "Label": "10", "Button": "13", "TextBox": "13", "CheckBox": "6", "ListPicker": "17", "DeskBuddy": "2"}

        def visit(component):
            name, type_ = component["$Name"], component["$Type"]
            require(name not in components, "Duplicate component")
            require(str(component["$Version"]) == versions[type_], "Designer version mismatch")
            metadata = extension_metadata(info) if type_ == "DeskBuddy" else runtime.read(type_)
            if type_ == "ListPicker":
                # Current Kodular ListPicker17 differs from MIT's zero-argument event.
                metadata["events"]["AfterPicking"] = {"arity": 1, "params": ["selection"]}
            components[name] = {"type": type_, "metadata": metadata, "designer": component}
            for item in component.get("$Components", []):
                visit(item)

        visit(screen["Properties"])
        require(components["BuddyPanel"]["designer"]["Height"] == "340", "Avatar needs a nonzero height")
        blocks = ET.fromstring(project.read(source + ".bky"))
        all_blocks = descendants(blocks)
        require(all(b.get("type") in ("component_event", "global_declaration") for b in children(blocks, "block")), "Orphan executable block")
        ids = [b.get("id") for b in all_blocks]
        require(all(ids) and len(ids) == len(set(ids)), "Duplicate/missing block IDs")
        require(not any(local_tag(b) == "shadow" for b in blocks.iter()), "Hidden shadow block")
        parent = {item: owner for owner in blocks.iter() for item in owner}
        global_names = {fields(b).get("NAME") for b in all_blocks if b.get("type") == "global_declaration"}
        events = {}
        for b in all_blocks:
            type_ = b.get("type")
            if type_ != "component_component_block":
                validate_basic_block(b)
            require(b.get("disabled", "false") != "true", "Disabled block")
            for item in b:
                if local_tag(item) in ("value", "statement", "next"):
                    require(len(children(item, "block")) == 1, "Disconnected input")
            if type_ in ("component_event", "component_method", "component_set_get", "component_component_block"):
                m = child(b, "mutation")
                require(m is not None, "Missing component mutation")
                name = m.get("instance_name")
                require(name in components and m.get("component_type") == components[name]["type"], "Unknown component")
                require(fields(b).get("COMPONENT_SELECTOR") == name, "Selector mismatch")
                inputs = {item.get("name") for item in children(b, "value")}
                data = components[name]["metadata"]
                if type_ == "component_method":
                    method = m.get("method_name")
                    require(method in data["methods"], "Unknown method: " + str(method))
                    require(inputs == {"ARG" + str(i) for i in range(data["methods"][method]["arity"])}, "Wrong method arguments")
                elif type_ == "component_set_get":
                    prop, mode = m.get("property_name"), m.get("set_or_get")
                    require(prop in data["properties"] and mode in data["properties"][prop], "Invalid property")
                    require(fields(b).get("PROP") == prop and inputs == ({"VALUE"} if mode == "set" else set()), "Wrong property shape")
                elif type_ == "component_event":
                    event = m.get("event_name")
                    require(event in data["events"] and (name, event) not in events, "Invalid/duplicate event")
                    events[name, event] = b
                else:
                    require(not inputs and not children(b, "statement"), "Invalid component reference")
            elif type_ in ("lexical_variable_get", "lexical_variable_set"):
                name = fields(b).get("VAR", "")
                if name.startswith("global "):
                    require(name[7:] in global_names, "Unknown global")
                else:
                    owner = parent.get(b)
                    while owner is not None and owner.get("type") != "component_event":
                        owner = parent.get(owner)
                    require(owner is not None, "Unscoped event variable")
                    m = child(owner, "mutation")
                    params = components[m.get("instance_name")]["metadata"]["events"][m.get("event_name")]["params"]
                    require(name in params, "Unknown event parameter")
                    mutation = child(b, "mutation")
                    require(mutation is not None and child(mutation, "eventparam").get("name") == name, "Missing event-variable mutation")
        require(("Screen1", "Initialize") in events, "Missing initialization")
        initial = events["Screen1", "Initialize"]
        require(any(child(b, "mutation").get("method_name") == "Attach" for b in descendants(initial, "component_method")), "Avatar is not attached")
        attachment = next(b for b in descendants(initial, "component_method") if child(b, "mutation").get("method_name") == "Attach")
        reference = child(child(attachment, "value"), "block")
        require(reference.get("type") == "component_component_block" and child(reference, "mutation").get("instance_name") == "BuddyPanel", "Attach must reference the empty avatar arrangement")
        for name, component in components.items():
            if component["type"] == "Button": require((name, "Click") in events, "Unwired button: " + name)
            if component["type"] == "ListPicker": require((name, "AfterPicking") in events, "Unwired catalog picker")
        print("PASS DeskBuddy demo: " + str(len(components)) + " components, " + str(len(all_blocks)) + " blocks, " + str(len(events)) + " events; genuine extension bytes and signatures verified")


if __name__ == "__main__":
    main()
