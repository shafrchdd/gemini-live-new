#!/usr/bin/env python3
"""Build the extension with pinned, authentic MIT and Temurin tools.

Requires Python 3.12+, Git and network access on the first run. Downloads use
normal TLS verification; every used JAR and the JDK archive is SHA-256 checked.
No dependency or source files are changed by this build.
"""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"
VENDOR = TOOLS / "vendor"
DEPS = VENDOR / "extension-deps"
JDK = VENDOR / "jdk8u472-b08"
JDK_URL = (
    "https://github.com/adoptium/temurin8-binaries/releases/download/jdk8u472-b08/"
    "OpenJDK8U-jdk_x64_linux_hotspot_8u472b08.tar.gz"
)
JDK_SHA256 = "5becaa4ac660e844c5a39e2ebc39ff5ac824c37ff1b625af8c8b111dc13c3592"
PACKAGE = "com.fabiyon.geminilive"


def check_digest(path, expected):
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        raise RuntimeError("SHA-256 mismatch: " + str(path))


def prepare():
    lock = json.loads((TOOLS / "dependencies.json").read_text())
    VENDOR.mkdir(parents=True, exist_ok=True)
    if not DEPS.exists():
        subprocess.run([
            "git", "clone", "--no-checkout", "--filter=blob:none",
            "https://github.com/mit-cml/extension-deps.git", str(DEPS)
        ], check=True)
        subprocess.run(["git", "-C", str(DEPS), "checkout", "--detach",
                        lock["extension_deps_commit"]], check=True)
    for name, digest in lock["jars"].items():
        check_digest(DEPS / name, digest)
    archive = VENDOR / "jdk8.tar.gz"
    if not archive.exists():
        print("Downloading pinned Temurin JDK 8...", flush=True)
        with urllib.request.urlopen(JDK_URL, timeout=90) as response:
            with archive.open("wb") as destination:
                shutil.copyfileobj(response, destination)
    check_digest(archive, JDK_SHA256)
    if not JDK.exists():
        with tarfile.open(archive) as bundle:
            bundle.extractall(VENDOR, filter="data")
    return JDK / "bin" / "java", JDK / "bin" / "javac"


def run(command, log):
    completed = subprocess.run([str(x) for x in command], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True)
    log.write_text(completed.stdout)
    if completed.returncode:
        print(completed.stdout, file=sys.stderr)
        raise RuntimeError("Build failed; see " + str(log))
    print("Passed: " + log.stem, flush=True)


def jar_tree(source, destination):
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as archive:
        for item in sorted(source.rglob("*.class")):
            archive.write(item, item.relative_to(source).as_posix())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=pathlib.Path,
                        default=ROOT / "src/com/fabiyon/geminilive/GeminiLive.java")
    parser.add_argument("--build-dir", type=pathlib.Path, default=ROOT / "build")
    parser.add_argument("--compile-only", action="store_true")
    args = parser.parse_args()
    java, javac = prepare()
    build = args.build_dir.resolve()
    if build == ROOT or ROOT.is_relative_to(build):
        raise RuntimeError("Unsafe build directory")
    build.mkdir(parents=True, exist_ok=True)
    classes = build / "classes"
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    classpath = ":".join(str(x) for x in sorted(DEPS.glob("*.jar")))
    processors = "com.google.appinventor.components.scripts."
    run([
        javac, "-source", "7", "-target", "7", "-Xlint:-options",
        "-encoding", "UTF-8", "-cp", classpath,
        "-processorpath", DEPS / "AnnotationProcessors.jar",
        "-processor", processors + "ComponentDescriptorGenerator," +
        processors + "ComponentListGenerator",
        "-d", classes, args.source.resolve()
    ], build / "compile.log")
    if args.compile_only:
        return
    external = build / "externalComponents"
    external_classes = build / "externalComponents-classes"
    dependencies = build / "deps"
    for path in (external, external_classes, dependencies):
        if path.exists():
            shutil.rmtree(path)
        path.mkdir()
    run([
        java, "-cp", DEPS / "AnnotationProcessors.jar",
        processors + "ExternalComponentGenerator",
        classes / "simple_components.json",
        classes / "simple_components_build_info.json",
        external, classes, dependencies, external_classes, "false"
    ], build / "metadata.log")
    runtime_jar = external / PACKAGE / "files/AndroidRuntime.jar"
    jar_tree(external_classes / PACKAGE, runtime_jar)
    run([java, "-jar", DEPS / "dx.jar", "--dex", "--no-strict", "--output",
         external / PACKAGE / "classes.jar", runtime_jar], build / "dex.log")
    dex_archive = external / PACKAGE / "classes.jar"
    with zipfile.ZipFile(dex_archive) as archive:
        assert archive.read("classes.dex").startswith(b"dex\n"), "Missing valid DEX header"
    build_info = json.loads((external / PACKAGE / "files/component_build_infos.json").read_text())
    required = {"android.permission.INTERNET", "android.permission.RECORD_AUDIO"}
    permissions = {permission for component in build_info
                   for permission in component.get("permissions", [])}
    assert required.issubset(permissions), "Microphone/network permissions missing from build metadata"
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    output = dist / (PACKAGE + ".aix")
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for item in sorted((external / PACKAGE).rglob("*")):
            if item.is_file():
                archive.write(item, item.relative_to(external).as_posix())
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    (dist / (output.name + ".sha256")).write_text(digest + "  " + output.name + "\n")
    print("Built " + str(output) + " (SHA-256 " + digest + ")")


if __name__ == "__main__":
    main()
