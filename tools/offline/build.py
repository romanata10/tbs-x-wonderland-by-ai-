#!/usr/bin/env python3
"""Offline build + verification harness for ForgeBridge.

The sandbox this project was developed in has no JDK and no access to Maven Central or
the NeoForge maven, so `./gradlew build` cannot run here. This harness reproduces the
same steps with the tools that *are* available:

  * javac replacement ... Janino 3.1.9 (targets Java 21, emits class file version 65)
  * ASM .................. the bundled asm.jar (asm + asm-tree + asm-commons + asm-util)
  * JVM .................. any Java 21+ runtime (JAVA_HOME, JDK4PY, or `java` on PATH)

It compiles the real sources against compile-only API stubs, packages the real jar into
build/libs, compiles the test suite and runs it inside a JVM. The stubs are never part of
the produced artifact - exactly like `compileOnly` dependencies in the Gradle build.

Usage:
    python3 tools/offline/build.py [--clean] [--no-tests] [--verbose]
"""
import argparse
import glob
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LIB = os.path.join(ROOT, "tools", "offline", "lib")
STUBS = os.path.join(ROOT, "tools", "offline", "stubs", "java")
TESTS = os.path.join(ROOT, "tools", "offline", "tests", "java")
MAIN = os.path.join(ROOT, "src", "main", "java")
RES = os.path.join(ROOT, "src", "main", "resources")
BUILD = os.path.join(ROOT, "build", "offline")
JANINO = os.path.join(LIB, "janino-3.1.9.jar")
COMMONS_COMPILER = os.path.join(LIB, "commons-compiler-3.1.9.jar")
ASM = os.path.join(LIB, "asm.jar")


def fail(message):
    print("\n[BUILD FAILED] " + message)
    sys.exit(1)


def read_properties():
    props = {}
    with open(os.path.join(ROOT, "gradle.properties"), "r", encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def find_java():
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(os.path.join(os.environ["JAVA_HOME"], "bin", "java"))
    which = shutil.which("java")
    if which:
        candidates.append(which)
    try:
        import jdk4py  # optional helper used in the sandbox
        candidates.append(os.path.join(str(jdk4py.JAVA_HOME), "bin", "java"))
    except Exception:
        pass
    for candidate in candidates:
        if candidate and os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    fail("no java executable found; set JAVA_HOME to a Java 21+ runtime")


def start_janino(java_executable):
    try:
        import jpype
    except ImportError:
        fail("jpype1 is required to drive the compiler (pip install jpype1)")
    java_home = os.path.dirname(os.path.dirname(os.path.abspath(java_executable)))
    os.environ["JAVA_HOME"] = java_home
    jvm_candidates = [
        os.path.join(java_home, "lib", "server", "libjvm.so"),
        os.path.join(java_home, "jre", "lib", "server", "libjvm.so"),
        os.path.join(java_home, "bin", "server", "jvm.dll"),
    ]
    jvm_path = None
    for candidate in jvm_candidates:
        if os.path.isfile(candidate):
            jvm_path = candidate
            break
    if jvm_path is None:
        jvm_path = jpype.getDefaultJVMPath()
    if not jpype.isJVMStarted():
        jpype.startJVM(
            jvm_path,
            "-Djava.class.path=" + JANINO + os.pathsep + COMMONS_COMPILER,
            convertStrings=True,
        )
    import jpype.imports  # noqa: F401  (installs the Java import hook)
    from org.codehaus.janino import Compiler  # noqa: F401  (import proves the jar is usable)
    return jpype


def compile_sources(jpype, sources, out_dir, classpath, label):
    import jpype.imports  # noqa: F401
    from java.io import File
    from org.codehaus.janino import Compiler

    os.makedirs(out_dir, exist_ok=True)
    files = sorted(sources)
    if not files:
        fail("no source files for " + label)
    compiler = Compiler()
    compiler.setClassPath([File(path) for path in classpath])
    compiler.setDestinationDirectory(File(out_dir), False)
    compiler.setWarningHandler(None)
    compiler.setDebugSource(False)
    compiler.setDebugLines(True)
    compiler.setDebugVars(False)
    compiler.setSourceVersion(21)
    compiler.setTargetVersion(21)
    try:
        compiler.compile([File(path) for path in files])
    except Exception as error:  # org.codehaus.commons.compiler.CompileException
        message = str(error).replace("org.codehaus.commons.compiler.CompileException: ", "")
        # Janino stops at the first error per file but keeps going across files in one call,
        # so everything it reports is worth printing.
        print(message)
        fail(label + " failed (" + str(len(files)) + " files)")
    print("  compiled %3d file(s) -> %s" % (len(files), os.path.relpath(out_dir, ROOT)))


def package_jar(version, classes_dir, jar_path):
    os.makedirs(os.path.dirname(jar_path), exist_ok=True)
    manifest = (
        "Manifest-Version: 1.0\r\n"
        "Specification-Title: forgebridge\r\n"
        "Implementation-Title: forgebridge\r\n"
        "Implementation-Version: " + version + "\r\n"
        "ForgeBridge-Version: " + version + "\r\n"
        "ForgeBridge-MappingSet: forge-1.21.1->neoforge-21.1.176\r\n"
        "FMLModType: MOD\r\n"
        "\r\n"
    ).encode("utf-8")
    count = 0
    with zipfile.ZipFile(jar_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as jar:
        jar.writestr("META-INF/MANIFEST.MF", manifest)
        for base, _dirs, files in os.walk(classes_dir):
            for name in sorted(files):
                full = os.path.join(base, name)
                rel = os.path.relpath(full, classes_dir).replace(os.sep, "/")
                if rel == "META-INF/MANIFEST.MF":
                    continue
                jar.write(full, rel)
                count += 1
    return count


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--clean", action="store_true")
    parser.add_argument("--no-tests", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    props = read_properties()
    version = props.get("mod_version", "1.0.0")
    java = find_java()
    print("ForgeBridge offline build")
    print("  java      : " + java)
    print("  compiler  : janino 3.1.9 driven via JPype (target 21)")
    print("  asm       : " + os.path.basename(ASM))

    out = BUILD
    if args.clean and os.path.isdir(out):
        shutil.rmtree(out)
    stub_out = os.path.join(out, "stub-classes")
    classes_out = os.path.join(out, "classes")
    test_out = os.path.join(out, "test-classes")
    for directory in (stub_out, classes_out, test_out):
        if os.path.isdir(directory):
            shutil.rmtree(directory)
        os.makedirs(directory)

    jpype = start_janino(java)

    print("\n[1/5] compiling compile-only API stubs (not shipped)")
    compile_sources(jpype, glob.glob(os.path.join(STUBS, "**", "*.java"), recursive=True),
                    stub_out, [ASM], "stubs")

    print("\n[2/5] compiling bridge sources")
    compile_sources(jpype, glob.glob(os.path.join(MAIN, "**", "*.java"), recursive=True),
                    classes_out, [stub_out, ASM], "bridge")

    print("\n[3/5] copying resources")
    copied = 0
    for base, _dirs, files in os.walk(RES):
        for name in files:
            source = os.path.join(base, name)
            rel = os.path.relpath(source, RES)
            target = os.path.join(classes_out, rel)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copyfile(source, target)
            copied += 1
    print("  copied %d resource(s)" % copied)

    print("\n[4/5] packaging jar")
    jar_path = os.path.join(ROOT, "build", "libs", "forgebridge-" + version + ".jar")
    entries = package_jar(version, classes_out, jar_path)
    size = os.path.getsize(jar_path)
    print("  %s (%d entries, %.1f KiB)" % (os.path.relpath(jar_path, ROOT), entries, size / 1024.0))

    if args.no_tests:
        print("\nskipping tests (--no-tests)")
        return 0

    print("\n[5/5] compiling and running the verification suite")
    compile_sources(jpype, glob.glob(os.path.join(TESTS, "**", "*.java"), recursive=True),
                    test_out, [jar_path, stub_out, ASM], "tests")

    classpath = os.pathsep.join([jar_path, test_out, stub_out, ASM])
    # Run from a scratch directory: the bridge resolves its game directory from user.dir
    # when nothing else is available, and tests must not scatter config/ into the repo.
    run_dir = os.path.join(ROOT, "build", "offline", "run")
    os.makedirs(run_dir, exist_ok=True)
    result = subprocess.run([java, "-Dforgebridge.debug=false", "-cp", classpath,
                             "dev.forgebridge.tests.TestMain"],
                            cwd=run_dir, capture_output=True, text=True)
    sys.stdout.write(result.stdout)
    if result.returncode != 0:
        sys.stderr.write(result.stderr)
        fail("verification suite reported failures (exit " + str(result.returncode) + ")")
    print("\n[OK] build and verification completed")
    print("     artifact: " + os.path.relpath(jar_path, ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
