#!/usr/bin/env python3
"""Generate a deterministic Kodular test project containing GeminiLive v2.

Uses a known Kodular export baseline and canonical component Blockly XML.
No API key is stored in the project. Extension assets are copied byte-for-byte
from the repository's compiled AIX.
"""
from __future__ import annotations

import hashlib
import itertools
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PROJECT = "GeminiLiveTest"
PACKAGE = "appinventor.ai_shafrchdd." + PROJECT
EXTENSION = "GeminiLive"
XMLNS = "http://www.w3.org/1999/xhtml"
MUTATION_XMLNS = "http://www.w3.org/1999/xhtml"
YA_VERSION = "222"
LANGUAGE_VERSION = "29"
VERSIONS = {
    "Form": "42", "VerticalArrangement": "10", "HorizontalArrangement": "10",
    "Label": "10", "Button": "13", "CheckBox": "6", "Clock": "4",
    "TextBox": "13", EXTENSION: "2",
}


class ProjectBuilder:
    def __init__(self):
        self.block_ids = itertools.count(1)
        self.component_ids = itertools.count(10001)
        self.types = {"Screen1": "Form"}
        self.xml = ET.Element("xml", {"xmlns": XMLNS})
        self.top_count = 0

    def component(self, name, kind, children=None, **props):
        if name in self.types:
            raise ValueError("Duplicate component: " + name)
        self.types[name] = kind
        result = {"$Name": name, "$Type": kind, "$Version": VERSIONS[kind],
                  "Uuid": str(next(self.component_ids))}
        result.update({k: str(v) for k, v in props.items()})
        if children is not None:
            result["$Components"] = children
        return result

    def block(self, kind, **attrs):
        return ET.Element("block", {"type": kind, "id": "gl_" + str(next(self.block_ids)),
                                   **{k: str(v) for k, v in attrs.items()}})

    @staticmethod
    def field(block, name, text):
        ET.SubElement(block, "field", {"name": name}).text = str(text)

    @staticmethod
    def mutation(block, **attrs):
        return ET.SubElement(block, "mutation", {"xmlns": MUTATION_XMLNS,
                             **{k: str(v) for k, v in attrs.items()}})

    @staticmethod
    def value(block, name, child):
        ET.SubElement(block, "value", {"name": name}).append(child)

    @staticmethod
    def chain(blocks):
        blocks = list(blocks)
        if not blocks:
            raise ValueError("Empty statement chain")
        for current, following in zip(blocks, blocks[1:]):
            ET.SubElement(current, "next").append(following)
        return blocks[0]

    def statements(self, block, name, statements):
        ET.SubElement(block, "statement", {"name": name}).append(self.chain(statements))

    def literal(self, text):
        b = self.block("text")
        self.field(b, "TEXT", text)
        return b

    def number(self, number):
        b = self.block("math_number")
        self.field(b, "NUM", number)
        return b

    def boolean(self, value):
        b = self.block("logic_boolean")
        self.field(b, "BOOL", "TRUE" if value else "FALSE")
        return b

    def parameter(self, name):
        b = self.block("lexical_variable_get")
        ET.SubElement(self.mutation(b), "eventparam", {"name": name})
        self.field(b, "VAR", name)
        return b

    def pending(self, value=None):
        b = self.block("lexical_variable_get" if value is None else "lexical_variable_set")
        self.field(b, "VAR", "global micStartPending")
        if value is not None:
            self.value(b, "VALUE", self.boolean(value))
        return b

    def prop(self, instance, name, value=None):
        b = self.block("component_set_get")
        self.mutation(b, component_type=self.types[instance], instance_name=instance,
                      property_name=name, set_or_get="get" if value is None else "set", is_generic="false")
        self.field(b, "COMPONENT_SELECTOR", instance)
        self.field(b, "PROP", name)
        if value is not None:
            self.value(b, "VALUE", value)
        return b

    def method(self, instance, name, *args):
        b = self.block("component_method")
        self.mutation(b, component_type=self.types[instance], instance_name=instance,
                      method_name=name, is_generic="false")
        self.field(b, "COMPONENT_SELECTOR", instance)
        for index, value in enumerate(args):
            self.value(b, "ARG" + str(index), value)
        return b

    def event(self, instance, name, statements):
        b = self.block("component_event")
        self.mutation(b, component_type=self.types[instance], instance_name=instance,
                      event_name=name, is_generic="false")
        self.field(b, "COMPONENT_SELECTOR", instance)
        self.statements(b, "DO", statements)
        b.set("x", str(40 + (self.top_count % 3) * 900))
        b.set("y", str(240 + (self.top_count // 3) * 1800))
        self.top_count += 1
        self.xml.append(b)
        return b

    def join(self, *values):
        b = self.block("text_join")
        self.mutation(b, items=len(values))
        for i, value in enumerate(values):
            self.value(b, "ADD" + str(i), value)
        return b

    def negate(self, value):
        b = self.block("logic_negate")
        self.value(b, "BOOL", value)
        return b

    def conditional(self, condition, yes, no=None):
        b = self.block("controls_if")
        if no is not None:
            self.mutation(b, **{"else": "1"})
        self.value(b, "IF0", condition)
        self.statements(b, "DO0", yes)
        if no is not None:
            self.statements(b, "ELSE", no)
        return b

    def set_text(self, instance, text):
        return self.prop(instance, "Text", self.literal(text))

    def enabled(self, instance, value):
        return self.prop(instance, "Enabled", self.boolean(value))

    def length(self, text):
        b = self.block("text_length")
        self.value(b, "VALUE", text)
        return b

    def compare(self, left, operator, right):
        b = self.block("math_compare")
        self.field(b, "OP", operator)
        self.value(b, "A", left)
        self.value(b, "B", right)
        return b

    def append_transcript(self, label, parameter):
        append = self.prop(label, "Text", self.join(self.prop(label, "Text"), self.parameter(parameter)))
        subtract = self.block("math_subtract")
        self.value(subtract, "A", self.length(self.prop(label, "Text")))
        self.value(subtract, "B", self.number(1999))
        segment = self.block("text_segment")
        self.value(segment, "TEXT", self.prop(label, "Text"))
        self.value(segment, "START", subtract)
        self.value(segment, "LENGTH", self.number(2000))
        trim = self.conditional(self.compare(self.length(self.prop(label, "Text")), "GT", self.number(2000)),
                                [self.prop(label, "Text", segment)])
        return [append, trim]

    def idle_controls(self):
        return [self.enabled("ConnectButton", True), self.enabled("DisconnectButton", False),
                self.enabled("StartButton", False), self.enabled("StopButton", False),
                self.enabled("SendButton", False), self.enabled("StopAudioButton", False),
                self.prop("LevelClock", "TimerEnabled", self.boolean(False)),
                self.pending(False),
                self.set_text("MicStatusLabel", "Microphone: stopped"),
                self.set_text("LevelLabel", "Microphone level: 0 / 100")]

    def start_capture(self):
        return [self.set_text("ErrorLabel", ""),
                self.pending(True),
                self.set_text("InputTranscriptLabel", ""),
                self.set_text("MicStatusLabel", "Microphone: requesting permission / starting..."),
                self.enabled("StartButton", False), self.enabled("StopButton", True),
                self.prop("GeminiLive1", "EchoCancellation", self.prop("EchoCheck", "Checked")),
                self.prop("GeminiLive1", "NoiseSuppression", self.prop("NoiseCheck", "Checked")),
                self.prop("GeminiLive1", "MuteMicWhileSpeaking", self.prop("MuteCheck", "Checked")),
                self.method("GeminiLive1", "StartListening")]

    def failed_capture(self):
        return [self.pending(False), self.set_text("MicStatusLabel", "Microphone: stopped; see error"),
                self.prop("StartButton", "Enabled", self.method("GeminiLive1", "IsReady")),
                self.enabled("StopButton", False),
                self.prop("LevelClock", "TimerEnabled", self.boolean(False)),
                self.set_text("LevelLabel", "Microphone level: 0 / 100")]

    def screen(self):
        c = self.component
        label = lambda name, text, **props: c(name, "Label", Text=text, Width="-2", **props)
        button = lambda name, text, enabled=True: c(name, "Button", Text=text,
                                                   Enabled="True" if enabled else "False", FontSize="15")
        children = [
            label("HeadingLabel", "Gemini Live - microphone test", FontSize="22", FontBold="True"),
            label("InstructionsLabel", "Enter your API key, then Connect. Auto listen waits for SetupComplete. Compare Companion and a freshly built APK.", FontSize="14"),
            label("ApiKeyLabel", "API key (not saved)", FontBold="True"),
            c("ApiKeyInput", "TextBox", Text="", InputType="2", Hint="Enter your Gemini API key", Width="-2", FontSize="16"),
            label("ModelLabel", "Live model", FontBold="True"),
            c("ModelInput", "TextBox", Text="gemini-3.8-live", Hint="A Live model available to your key", Width="-2", FontSize="16"),
            label("VoiceLabel", "Prebuilt voice", FontBold="True"),
            c("VoiceInput", "TextBox", Text="Puck", Hint="For example, Puck", Width="-2", FontSize="16"),
            c("AutoListenCheck", "CheckBox", Text="Start listening automatically when Gemini is ready", Checked="True", Width="-2"),
            c("EchoCheck", "CheckBox", Text="Acoustic echo cancellation (apply on next Start)", Checked="True", Width="-2"),
            c("NoiseCheck", "CheckBox", Text="Noise suppression (apply on next Start)", Checked="True", Width="-2"),
            c("MuteCheck", "CheckBox", Text="Mute microphone during AI speech (no barge-in)", Checked="False", Width="-2"),
            label("StatusLabel", "Connection: disconnected", FontBold="True"),
            label("MicStatusLabel", "Microphone: stopped"),
            label("LevelLabel", "Microphone level: 0 / 100"),
            label("ErrorHeadingLabel", "Last error / permission result", FontBold="True"),
            label("ErrorLabel", "", TextColor="&HFFB00020", FontSize="15"),
            c("ConnectionRow", "HorizontalArrangement", children=[button("ConnectButton", "Connect"), button("DisconnectButton", "Disconnect", False)], Width="-2"),
            c("MicrophoneRow", "HorizontalArrangement", children=[button("StartButton", "Start mic", False), button("StopButton", "Stop mic", False), button("StopAudioButton", "Stop AI audio", False)], Width="-2"),
            label("MessageHeadingLabel", "Text test", FontBold="True"),
            c("MessageInput", "TextBox", Text="Hello. Please answer briefly.", Hint="Type a message for Gemini", MultiLine="True", Width="-2", FontSize="16"),
            c("TextRow", "HorizontalArrangement", children=[button("SendButton", "Send text", False), button("ClearButton", "Clear display")], Width="-2"),
            label("InputHeadingLabel", "You said", FontBold="True"),
            label("InputTranscriptLabel", "", FontSize="16"),
            label("OutputHeadingLabel", "AI speech transcript", FontBold="True"),
            label("OutputTranscriptLabel", "", FontSize="16"),
            label("TextHeadingLabel", "AI text part", FontBold="True"),
            label("TextReplyLabel", "", FontSize="16"),
            label("FooterLabel", "No microphone indicator after Start? Read the error above. A level of zero can mean silence, privacy blocking, or another recorder. Stop before changing audio effects; reconnect after returning from the background.", FontSize="13"),
        ]
        panel = c("MainPanel", "VerticalArrangement", children=children, Width="-2")
        clock = c("LevelClock", "Clock", TimerInterval="300", TimerEnabled="False", TimerAlwaysFires="False")
        extension = c("GeminiLive1", EXTENSION, Model="gemini-3.8-live", Voice="Puck",
                      RawMessagesEnabled="False", EchoCancellation="True", NoiseSuppression="True",
                      MuteMicWhileSpeaking="False", AudioEnabled="True", Volume="100")
        props = {"$Name": "Screen1", "$Type": "Form", "$Version": VERSIONS["Form"], "Uuid": "0",
                 "AppName": "Gemini Live Test", "Title": "Gemini Live Test", "Scrollable": "True",
                 "Sizing": "Responsive", "BackgroundColor": "&HFFFFFFFF", "ShowListsAsJson": "True",
                 "Theme": "AppTheme.Light.DarkActionBar", "PrimaryColor": "&HFF245A81",
                 "PrimaryColorDark": "&HFF153951", "AccentColor": "&HFF007D77",
                 "$Components": [panel, clock, extension]}
        data = {"authURL": ["creator.kodular.io"], "YaVersion": YA_VERSION, "Source": "Form", "Properties": props}
        return "#|\n$JSON\n" + json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n|#\n"

    def blocks(self):
        pending = self.block("global_declaration", x="40", y="40")
        self.field(pending, "NAME", "micStartPending")
        self.value(pending, "VALUE", self.boolean(False))
        self.xml.append(pending)
        self.event("Screen1", "Initialize", [
            self.prop("GeminiLive1", "RawMessagesEnabled", self.boolean(False)),
            self.prop("GeminiLive1", "AudioEnabled", self.boolean(True)),
            self.prop("GeminiLive1", "Volume", self.number(100)),
            *self.idle_controls()])
        key_trim = self.block("text_trim")
        self.field(key_trim, "MODE", "BOTH")
        self.value(key_trim, "TEXT", self.prop("ApiKeyInput", "Text"))
        self.event("ConnectButton", "Click", [self.conditional(
            self.compare(self.length(self.prop("ApiKeyInput", "Text")), "EQ", self.number(0)),
            [self.set_text("ErrorLabel", "Enter your API key first.")],
            [self.set_text("StatusLabel", "Connection: connecting..."),
             self.set_text("ErrorLabel", ""), self.set_text("InputTranscriptLabel", ""),
             self.set_text("OutputTranscriptLabel", ""), self.set_text("TextReplyLabel", ""),
             self.enabled("ConnectButton", False), self.enabled("DisconnectButton", True),
             self.enabled("StartButton", False), self.enabled("StopButton", False),
             self.enabled("SendButton", False), self.enabled("StopAudioButton", False),
             self.prop("GeminiLive1", "Model", self.prop("ModelInput", "Text")),
             self.prop("GeminiLive1", "Voice", self.prop("VoiceInput", "Text")),
             self.prop("GeminiLive1", "SystemInstruction", self.literal("You are a helpful voice assistant. Keep replies brief.")),
             self.method("Screen1", "HideKeyboard"),
             self.method("GeminiLive1", "Connect", key_trim)])])
        self.event("GeminiLive1", "Connected", [self.set_text("StatusLabel", "Connection: connected; waiting for SetupComplete...")])
        self.event("GeminiLive1", "SetupComplete", [
            self.set_text("StatusLabel", "Connection: ready"), self.enabled("StartButton", True),
            self.enabled("SendButton", True), self.enabled("StopAudioButton", True),
            self.conditional(self.prop("AutoListenCheck", "Checked"), self.start_capture())])
        self.event("StartButton", "Click", [self.conditional(self.method("GeminiLive1", "IsReady"),
                   self.start_capture(), [self.set_text("ErrorLabel", "Connect and wait for SetupComplete first.")])])
        self.event("StopButton", "Click", [self.method("GeminiLive1", "StopListening"),
            self.pending(False),
            self.prop("LevelClock", "TimerEnabled", self.boolean(False)),
            self.set_text("MicStatusLabel", "Microphone: stopped / pending start cancelled"),
            self.set_text("LevelLabel", "Microphone level: 0 / 100"),
            self.prop("StartButton", "Enabled", self.method("GeminiLive1", "IsReady")), self.enabled("StopButton", False)])
        self.event("GeminiLive1", "ListeningStarted", [
            self.pending(False),
            self.set_text("MicStatusLabel", "Microphone: listening - speak now"),
            self.enabled("StartButton", False), self.enabled("StopButton", True),
            self.prop("LevelClock", "TimerEnabled", self.boolean(True))])
        self.event("GeminiLive1", "ListeningStopped", [self.conditional(self.negate(self.pending()),
            [self.conditional(self.negate(self.method("GeminiLive1", "IsListening")), [
                self.set_text("MicStatusLabel", "Microphone: stopped"),
                self.prop("LevelClock", "TimerEnabled", self.boolean(False)),
                self.set_text("LevelLabel", "Microphone level: 0 / 100"),
                self.prop("StartButton", "Enabled", self.method("GeminiLive1", "IsReady")), self.enabled("StopButton", False)])])])
        self.event("LevelClock", "Timer", [self.prop("LevelLabel", "Text", self.join(
            self.literal("Microphone level: "), self.method("GeminiLive1", "MicrophoneLevel"), self.literal(" / 100")))])
        self.event("SendButton", "Click", [self.conditional(self.method("GeminiLive1", "IsReady"), [
            self.set_text("ErrorLabel", ""), self.set_text("OutputTranscriptLabel", ""), self.set_text("TextReplyLabel", ""),
            self.method("Screen1", "HideKeyboard"), self.method("GeminiLive1", "SendText", self.prop("MessageInput", "Text"))],
            [self.set_text("ErrorLabel", "Connect and wait for SetupComplete first.")])])
        self.event("StopAudioButton", "Click", [self.method("GeminiLive1", "StopAudio")])
        self.event("DisconnectButton", "Click", [self.method("GeminiLive1", "Disconnect"),
            self.set_text("StatusLabel", "Connection: disconnected"), *self.idle_controls()])
        self.event("GeminiLive1", "Disconnected", [
            self.prop("StatusLabel", "Text", self.join(self.literal("Connection: disconnected - "), self.parameter("reason"))),
            *self.idle_controls()])
        microphone_prefix = self.block("text_starts_at")
        self.value(microphone_prefix, "TEXT", self.parameter("message"))
        self.value(microphone_prefix, "PIECE", self.literal("Microphone"))
        self.event("GeminiLive1", "Error", [
            self.prop("ErrorLabel", "Text", self.parameter("message")),
            self.conditional(self.negate(self.method("GeminiLive1", "IsConnected")),
                [self.set_text("StatusLabel", "Connection: not connected; see error"), *self.idle_controls()],
                [self.conditional(self.method("GeminiLive1", "IsReady"), [
                    self.conditional(self.negate(self.method("GeminiLive1", "IsListening")), [
                        self.conditional(self.pending(), [
                            self.conditional(self.compare(microphone_prefix, "EQ", self.number(1)), self.failed_capture())],
                            self.failed_capture())])])])])
        self.event("Screen1", "PermissionDenied", [
            self.prop("ErrorLabel", "Text", self.join(self.literal("Permission denied: "), self.parameter("permissionName"),
                                                       self.literal(". Allow microphone access for this installed APK in Android settings.")))])
        self.event("Screen1", "ErrorOccurred", [
            self.prop("ErrorLabel", "Text", self.join(self.parameter("functionName"), self.literal(": "), self.parameter("message")))])
        self.event("GeminiLive1", "InputTranscription", self.append_transcript("InputTranscriptLabel", "text"))
        self.event("GeminiLive1", "OutputTranscription", self.append_transcript("OutputTranscriptLabel", "text"))
        self.event("GeminiLive1", "TextReceived", self.append_transcript("TextReplyLabel", "text"))
        self.event("GeminiLive1", "TurnComplete", [self.set_text("StatusLabel", "Connection: ready - AI turn complete")])
        self.event("MuteCheck", "Changed", [self.prop("GeminiLive1", "MuteMicWhileSpeaking", self.prop("MuteCheck", "Checked"))])
        self.event("ClearButton", "Click", [self.set_text(name, "") for name in [
            "ErrorLabel", "InputTranscriptLabel", "OutputTranscriptLabel", "TextReplyLabel"]])
        ET.SubElement(self.xml, "yacodeblocks", {"ya-version": YA_VERSION, "language-version": LANGUAGE_VERSION})
        ET.indent(self.xml, space="  ")
        return ET.tostring(self.xml, encoding="unicode") + "\n"


def main():
    builder = ProjectBuilder()
    scm = builder.screen()
    bky = builder.blocks()
    properties = "\n".join([
        "main=" + PACKAGE + ".Screen1", "name=" + PROJECT, "assets=../assets", "source=../src",
        "build=../build", "icon=", "versioncode=1", "versionname=1.0", "useslocation=False",
        "aname=Gemini Live Test", "androidminsdk=23", "actionbar=True", "sizing=Responsive",
        "showlistsasjson=True", "theme=AppTheme.Light.DarkActionBar", "color.primary=&HFF245A81",
        "color.primary.dark=&HFF153951", "color.accent=&HFF007D77", "",
    ])
    source_dir = "src/" + PACKAGE.replace(".", "/")
    entries = {"youngandroidproject/project.properties": properties.encode(),
               source_dir + "/Screen1.scm": scm.encode(), source_dir + "/Screen1.bky": bky.encode()}
    aix = ROOT / "dist/com.fabiyon.geminilive.aix"
    with zipfile.ZipFile(aix) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            path = Path(entry.filename)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError("Unsafe extension asset path")
            entries["assets/external_comps/" + entry.filename] = archive.read(entry)
    editable = ROOT / "examples/project"
    for name, data in entries.items():
        # Keep the large extension binaries in the AIA; its authoritative separate AIX is tracked already.
        if name.startswith("assets/"):
            continue
        destination = editable / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    output = ROOT / "examples/GeminiLiveTest.aia"
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (2026, 10, 11, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, data)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(".aia.sha256").write_text(digest + "  " + output.name + "\n")
    print("Generated " + str(output))
    print(str(len(builder.types)) + " components, " + str(builder.top_count) + " event handlers, " + str(len(entries)) + " archive entries")
    print("SHA256: " + digest)


if __name__ == "__main__":
    main()
