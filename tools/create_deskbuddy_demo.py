#!/usr/bin/env python3
"""Build the independent DeskBuddy Kodular demo from the compiled extension."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import zipfile
from create_test_aia import ProjectBuilder, VERSIONS, YA_VERSION, LANGUAGE_VERSION

ROOT = Path(__file__).resolve().parent.parent
PROJECT = "DeskBuddyDemo"
PACKAGE = "appinventor.ai_shafrchdd." + PROJECT
VERSIONS.update(DeskBuddy="2", ListPicker="17")


class Demo(ProjectBuilder):
    def ref(self, name):
        block = self.block("component_component_block")
        self.mutation(block, component_type=self.types[name], instance_name=name)
        self.field(block, "COMPONENT_SELECTOR", name)
        return block

    def screen(self):
        c = self.component
        label = lambda name, text: c(name, "Label", Text=text, TextColor="&HFF00E5FF", Width="-2", FontSize="15")
        picker = lambda name, title: c(name, "ListPicker", Text=title, ShowFilterBar="True", Width="-2", BackgroundColor="&HFF16313A", TextColor="&HFF00E5FF")
        button = lambda name, title: c(name, "Button", Text=title, BackgroundColor="&HFF16313A", TextColor="&HFF00E5FF")
        controls = [label("TitleLabel", "Desk Buddy - independent avatar"),
                    c("BuddyPanel", "VerticalArrangement", Width="-2", Height="340", BackgroundColor="&HFF000000"),
                    label("StatusLabel", "Touch, hold or drag the avatar. Lift/shake detection is device dependent."),
                    label("StateLabel", "Emotion: idle"),
                    picker("EmotionPicker", "Choose base emotion"), picker("ReactionPicker", "Play reaction"),
                    picker("IdlePicker", "Preview idle behavior"), picker("ConversationPicker", "Preview AI conversation reaction"),
                    picker("SystemPicker", "Preview system state / select overlay"),
                    c("OverlayRow", "HorizontalArrangement", children=[button("OverlayOnButton", "Overlay on"), button("OverlayOffButton", "Overlay off")], Width="-2"),
                    c("AutonomousCheck", "CheckBox", Text="Autonomous idle behaviors", Checked="True", TextColor="&HFF00E5FF"),
                    c("AwakeCheck", "CheckBox", Text="Keep screen awake while buddy is running", Checked="False", TextColor="&HFF00E5FF"),
                    c("SensorsCheck", "CheckBox", Text="Touch/motion automatic reactions and motion sensors", Checked="True", TextColor="&HFF00E5FF"),
                    c("DeviceCheck", "CheckBox", Text="Monitor real battery and Wi-Fi (off for power simulation)", Checked="True", TextColor="&HFF00E5FF"),
                    c("BatteryIconCheck", "CheckBox", Text="Show battery symbol", Checked="True", TextColor="&HFF00E5FF"),
                    c("WifiIconCheck", "CheckBox", Text="Show Wi-Fi symbol", Checked="True", TextColor="&HFF00E5FF"),
                    c("ChargeCheck", "CheckBox", Text="Simulate connected charger", Checked="False", TextColor="&HFF00E5FF"),
                    c("BatteryInput", "TextBox", Text="65", Hint="Simulated battery 0-100", NumbersOnly="True", Width="-2"),
                    button("PowerButton", "Apply simulated power"),
                    c("LifeRow", "HorizontalArrangement", children=[button("SleepButton", "Sleep"), button("WakeButton", "Wake up"), button("StopButton", "Stop"), button("StartButton", "Start")], Width="-2"),
                    c("TalkRow", "HorizontalArrangement", children=[button("SpeakButton", "Speaking"), button("QuietButton", "Clear AI state")], Width="-2"),
                    label("HelpLabel", "Each picker lists the extension's complete catalog. Base emotion, temporary reaction and system overlays remain independent. Speaking uses synthetic mouth motion here; feed SpeechLevel for audio amplitude animation. This demo has no AI/key requirement."),
                    label("ErrorLabel", "")]
        panel = c("ControlsPanel", "VerticalArrangement", children=controls, Width="-2")
        buddy = c("DeskBuddy1", "DeskBuddy", Enabled="True", Autonomous="True", NaturalBlink="True", AutoReactions="True",
                  SensorsEnabled="True", DeviceMonitoring="True", FramesPerSecond="30", SleepAfterSeconds="120", ShakeThreshold="9.5")
        props = {"$Name": "Screen1", "$Type": "Form", "$Version": "42", "Uuid": "0", "Title": "Desk Buddy Demo",
                 "AppName": "Desk Buddy Demo", "Scrollable": "True", "Sizing": "Responsive", "BackgroundColor": "&HFF000000",
                 "Theme": "AppTheme.Light.DarkActionBar", "PrimaryColor": "&HFF000000", "PrimaryColorDark": "&HFF000000",
                 "AccentColor": "&HFF00E5FF", "$Components": [panel, buddy]}
        return "#|\n$JSON\n" + json.dumps({"authURL": ["creator.kodular.io"], "YaVersion": YA_VERSION, "Source": "Form", "Properties": props}, separators=(",", ":")) + "\n|#\n"

    def blocks(self):
        self.declare_global("overlay", self.literal("charging"), "40")
        self.event("Screen1", "Initialize", [
            self.method("DeskBuddy1", "Attach", self.ref("BuddyPanel")),
            *[self.prop(picker, "Elements", self.method("DeskBuddy1", method)) for picker, method in (
                ("EmotionPicker", "Emotions"), ("ReactionPicker", "Reactions"), ("IdlePicker", "IdleBehaviors"),
                ("ConversationPicker", "ConversationReactions"), ("SystemPicker", "SystemStates"))]])
        self.event("EmotionPicker", "AfterPicking", [self.method("DeskBuddy1", "SetEmotion", self.prop("EmotionPicker", "Selection"))])
        for picker in ("ReactionPicker", "IdlePicker", "ConversationPicker"):
            self.event(picker, "AfterPicking", [self.method("DeskBuddy1", "PlayReaction", self.prop(picker, "Selection"), self.number(3000))])
        self.event("SystemPicker", "AfterPicking", [self.global_variable("overlay", self.prop("SystemPicker", "Selection")),
                   self.method("DeskBuddy1", "PlayReaction", self.global_variable("overlay"), self.number(3000)),
                   self.prop("StatusLabel", "Text", self.join(self.literal("Selected overlay: "), self.global_variable("overlay")))])
        for button, active in (("OverlayOnButton", True), ("OverlayOffButton", False)):
            self.event(button, "Click", [self.method("DeskBuddy1", "SetSystemOverlay", self.global_variable("overlay"), self.boolean(active))])
        self.event("AutonomousCheck", "Changed", [self.prop("DeskBuddy1", "Autonomous", self.prop("AutonomousCheck", "Checked"))])
        self.event("AwakeCheck", "Changed", [self.prop("DeskBuddy1", "KeepScreenOn", self.prop("AwakeCheck", "Checked"))])
        self.event("SensorsCheck", "Changed", [self.prop("DeskBuddy1", "SensorsEnabled", self.prop("SensorsCheck", "Checked")),
                   self.prop("DeskBuddy1", "AutoReactions", self.prop("SensorsCheck", "Checked"))])
        self.event("DeviceCheck", "Changed", [self.prop("DeskBuddy1", "DeviceMonitoring", self.prop("DeviceCheck", "Checked"))])
        self.event("BatteryIconCheck", "Changed", [self.prop("DeskBuddy1", "ShowBatteryIcon", self.prop("BatteryIconCheck", "Checked"))])
        self.event("WifiIconCheck", "Changed", [self.prop("DeskBuddy1", "ShowWifiIcon", self.prop("WifiIconCheck", "Checked"))])
        self.event("PowerButton", "Click", [self.prop("DeviceCheck", "Checked", self.boolean(False)),
                   self.prop("DeskBuddy1", "DeviceMonitoring", self.boolean(False)),
                   self.method("DeskBuddy1", "SetPowerState", self.prop("BatteryInput", "Text"), self.prop("ChargeCheck", "Checked"))])
        for button, method in (("SleepButton", "Sleep"), ("WakeButton", "WakeUp"), ("StopButton", "Stop"), ("StartButton", "Start")):
            self.event(button, "Click", [self.method("DeskBuddy1", method)])
        self.event("SpeakButton", "Click", [self.method("DeskBuddy1", "SetConversationState", self.literal("speaking"))])
        self.event("QuietButton", "Click", [self.method("DeskBuddy1", "SetConversationState", self.literal(""))])
        for event, parameter, prefix in (("EmotionChanged", "emotion", "Emotion: "), ("ReactionStarted", "reaction", "Reaction: "),
                                          ("IdleBehaviorChanged", "behavior", "Idle: "), ("Error", "message", "Error: ")):
            self.event("DeskBuddy1", event, [self.prop("ErrorLabel" if event == "Error" else "StateLabel", "Text", self.join(self.literal(prefix), self.parameter(parameter)))])
        for event, text in (("Lifted", "Lift detected"), ("Shaken", "Shake detected"), ("Touched", "Touch detected"), ("LongPressed", "Long press detected")):
            self.event("DeskBuddy1", event, [self.set_text("StatusLabel", text)])
        self.event("DeskBuddy1", "ChargingChanged", [self.prop("StatusLabel", "Text", self.join(self.literal("Plugged in: "), self.parameter("plugged"), self.literal(" | Battery: "), self.parameter("level")))])
        self.event("DeskBuddy1", "WifiChanged", [self.prop("StatusLabel", "Text", self.join(self.literal("Wi-Fi connected: "), self.parameter("connected")))])
        self.event("Screen1", "ErrorOccurred", [self.prop("ErrorLabel", "Text", self.join(self.parameter("functionName"), self.literal(": "), self.parameter("message")))])
        ET.SubElement(self.xml, "yacodeblocks", {"ya-version": YA_VERSION, "language-version": LANGUAGE_VERSION})
        ET.indent(self.xml, space="  ")
        return ET.tostring(self.xml, encoding="unicode") + "\n"


def main():
    b = Demo(); scm = b.screen(); bky = b.blocks()
    properties = "\n".join(["main=" + PACKAGE + ".Screen1", "name=" + PROJECT, "assets=../assets", "source=../src", "build=../build",
                              "icon=", "versioncode=2", "versionname=2.0", "useslocation=False", "aname=Desk Buddy Demo", "androidminsdk=23",
                              "sizing=Responsive", "theme=AppTheme.Light.DarkActionBar", "color.primary=&HFF000000", "color.primary.dark=&HFF000000", "color.accent=&HFF00E5FF", ""])
    source = "src/" + PACKAGE.replace(".", "/")
    entries = {"youngandroidproject/project.properties": properties.encode(), source + "/Screen1.scm": scm.encode(), source + "/Screen1.bky": bky.encode()}
    for name, value in entries.items():
        path = ROOT / "examples/deskbuddy/project" / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(value)
    with zipfile.ZipFile(ROOT / "dist/com.fabiyon.deskbuddy.aix") as z:
        entries.update({"assets/external_comps/" + n: z.read(n) for n in z.namelist() if not n.endswith("/")})
    target = ROOT / "examples/DeskBuddyDemo.aia"; target.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(target, "w") as z:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (2026, 10, 12, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED; info.external_attr = 0o644 << 16; z.writestr(info, data)
    target.with_suffix(".aia.sha256").write_text(hashlib.sha256(target.read_bytes()).hexdigest() + "  " + target.name + "\n")
    print("Generated DeskBuddyDemo.aia: " + str(len(b.types)) + " components, " + str(b.top_count) + " event handlers")


if __name__ == "__main__":
    main()
