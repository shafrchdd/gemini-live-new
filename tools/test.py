#!/usr/bin/env python3
"""Compile authentic production bytecode, then run deterministic JVM regressions."""
import argparse
import pathlib
import shutil
import subprocess
import build

ROOT = pathlib.Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    java, javac = build.prepare()
    if not args.skip_build:
        subprocess.run(["python3", str(ROOT / "tools/build.py"), "--compile-only"], check=True)
    classes = ROOT / "build/classes"
    test_classes = ROOT / "build/test-classes"
    if test_classes.exists():
        shutil.rmtree(test_classes)
    test_classes.mkdir()
    jars = sorted(build.DEPS.glob("*.jar"))
    classpath = ":".join(str(item) for item in [classes] + jars)
    sources = list((ROOT / "validation/fakes").rglob("*.java"))
    sources += [ROOT / "validation/RuntimeTests.java", ROOT / "validation/TransportTests.java"]
    subprocess.run([str(javac), "-source", "8", "-target", "8", "-proc:none", "-cp", classpath,
                    "-d", str(test_classes)] + [str(item) for item in sources], check=True)
    # Fakes take precedence, but production bytecode comes from the authentic Android/MIT build.
    runtime = ":".join(str(item) for item in [test_classes, classes,
                         build.DEPS / "AnnotationProcessors.jar", build.DEPS / "AndroidRuntime.jar"])
    subprocess.run([str(java), "-cp", runtime, "TransportTests", "com.fabiyon.geminilive.GeminiLive$Transport"], check=True)
    subprocess.run([str(java), "-cp", runtime, "RuntimeTests"], check=True)


if __name__ == "__main__":
    main()
