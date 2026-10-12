#!/usr/bin/env python3
"""Compile genuine DeskBuddy bytecode, exercise pure state/motion and render sample SVGs."""
from pathlib import Path
import subprocess
import build

ROOT = Path(__file__).resolve().parent.parent
java, javac = build.prepare()
subprocess.run(["python3", str(ROOT / "tools/build.py"), "--source", str(ROOT / "src/com/fabiyon/deskbuddy/DeskBuddy.java"),
                "--package", "com.fabiyon.deskbuddy", "--build-dir", str(ROOT / "build/deskbuddy")], check=True)
out = ROOT / "build/deskbuddy/test-classes"; out.mkdir(parents=True, exist_ok=True)
classpath = ":".join(str(p) for p in [ROOT / "build/deskbuddy/classes"] + sorted(build.DEPS.glob("*.jar")))
sources = list((ROOT / "validation/deskbuddy-fakes").rglob("*.java")) + [ROOT / "validation/DeskBuddyTests.java"]
subprocess.run([str(javac), "-source", "8", "-target", "8", "-proc:none", "-cp", classpath,
                "-d", str(out)] + [str(p) for p in sources], check=True)
subprocess.run([str(java), "-cp", str(out) + ":" + classpath, "DeskBuddyTests", str(ROOT / "examples/deskbuddy/gallery")], check=True)
