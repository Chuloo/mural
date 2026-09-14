#!/usr/bin/env python3
"""Validate Play copy/assets and optional AAB native layout; never sign or upload."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
PAGE_SIZE = 16384
TEXT_LIMITS = {"title": 30, "short-description": 80, "full-description": 4000, "release-notes": 500}
SECRET_PATTERNS = {
    "OpenAI secret key": re.compile(rb"sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{20,}"),
    "Stripe secret or restricted key": re.compile(rb"(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{20,}"),
    "Google OAuth client secret": re.compile(rb"GOCSPX-[A-Za-z0-9_-]{20,}"),
    "GitHub token": re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})"),
    "private key material": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----[\r\n]+[A-Za-z0-9+/=\r\n]{80,}"),
}
CREDENTIAL_FILE = re.compile(r"(?:^|/)(?:\.env(?:\.[^/]*)?|[^/]+\.(?:jks|keystore|p12|p8)|service-account(?:-[^/]*)?\.json)$", re.I)
PLACEHOLDER = re.compile(r"\[(?:REQUIRED|TODO|TBD)\b|\b(?:TODO|TBD|CHANGEME)\b|<insert\b", re.I)


class InvalidRelease(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise InvalidRelease(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def below(root: Path, relative: str) -> Path:
    require(isinstance(relative, str), "Asset path must be a string")
    candidate = (root / relative).resolve()
    require(candidate.is_relative_to(root.resolve()), "Asset path escapes release directory")
    return candidate


def check_text(path: Path, maximum: int, single_line: bool = False) -> dict:
    text = path.read_text(encoding="utf-8").rstrip("\n")
    require(0 < len(text) <= maximum, f"{path.name}: expected 1–{maximum} characters, found {len(text)}")
    require(text == text.strip(), f"{path.name}: remove leading/trailing whitespace")
    require(not PLACEHOLDER.search(text), f"{path.name}: unfinished placeholder")
    require(not any(ord(char) < 32 and char != "\n" for char in text), f"{path.name}: control character")
    require(not single_line or "\n" not in text, f"{path.name}: must be one line")
    return {"characters": len(text), "maximum": maximum, "sha256": sha256(path)}


def png_info(path: Path) -> dict:
    """Check PNG framing, CRCs and pixel format without a graphics dependency."""
    require(path.stat().st_size <= 16 * 1024 * 1024, f"{path.name}: PNG exceeds local 16 MiB limit")
    data = path.read_bytes()
    require(data.startswith(b"\x89PNG\r\n\x1a\n"), f"{path.name}: expected PNG")
    cursor, chunks, header = 8, [], None
    while cursor < len(data):
        require(cursor + 12 <= len(data), f"{path.name}: truncated PNG chunk")
        length = struct.unpack_from(">I", data, cursor)[0]
        kind = data[cursor + 4:cursor + 8]
        end = cursor + 12 + length
        require(end <= len(data), f"{path.name}: truncated PNG data")
        payload = data[cursor + 8:cursor + 8 + length]
        crc = struct.unpack_from(">I", data, cursor + 8 + length)[0]
        require(zlib.crc32(kind + payload) & 0xFFFFFFFF == crc, f"{path.name}: invalid PNG CRC")
        if not chunks:
            require(kind == b"IHDR" and length == 13, f"{path.name}: missing PNG header")
            header = struct.unpack(">IIBBBBB", payload)
        else:
            require(kind != b"IHDR", f"{path.name}: duplicate PNG header")
        chunks.append(kind)
        cursor = end
        if kind == b"IEND":
            require(length == 0 and cursor == len(data), f"{path.name}: invalid PNG ending")
            break
    require(header is not None and chunks[-1] == b"IEND" and b"IDAT" in chunks, f"{path.name}: incomplete PNG")
    width, height, depth, color, compression, filtering, interlace = header
    require(width > 0 and height > 0 and compression == 0 and filtering == 0 and interlace in (0, 1), f"{path.name}: invalid PNG header")
    return {"width": width, "height": height, "bitDepth": depth, "colorType": color,
            "hasTransparency": color in (4, 6) or b"tRNS" in chunks,
            "bytes": len(data), "sha256": sha256(path)}


def check_asset(path: Path, kind: str) -> dict:
    info = png_info(path)
    size = (info["width"], info["height"])
    if kind == "icon":
        require(size == (512, 512), "Store icon must be 512 × 512")
        require(info["bitDepth"] == 8 and info["colorType"] == 6, "Store icon must be 32-bit RGBA PNG")
        require(info["bytes"] <= 1024 * 1024, "Store icon exceeds 1024 KB")
    else:
        require(info["bitDepth"] == 8 and info["colorType"] == 2 and not info["hasTransparency"],
                f"{path.name}: use an opaque 24-bit RGB PNG")
        if kind == "featureGraphic":
            require(size == (1024, 500), "Feature graphic must be 1024 × 500")
        else:
            require(min(size) >= 320 and max(size) <= 3840 and max(size) <= 2 * min(size),
                    f"{path.name}: screenshot dimensions must be 320–3840 px and no taller/wider than 2:1")
    return info


def check_branding(root: Path, spec: dict) -> dict:
    """Verify exact launcher artwork and its source references; record visual-review inputs."""
    branding = spec["branding"]
    icon_set = below(root, branding["iosIconSet"])
    ios_icon = below(icon_set, branding["iosIconFile"])
    catalog = json.loads((icon_set / "Contents.json").read_text())
    references = [item["filename"] for item in catalog["images"] if item.get("filename")]
    require(references and set(references) == {branding["iosIconFile"]},
            "iOS AppIcon catalog no longer uses only the declared Mural source")
    android_icon = below(root, branding["androidIconFile"])
    ios_info, android_info = png_info(ios_icon), png_info(android_icon)
    require(ios_info["width"] == 1024 and ios_info["height"] == 1024, "Canonical iOS launcher artwork must be 1024 × 1024")
    require(ios_info["sha256"] == android_info["sha256"], "Android launcher artwork differs from the exact iOS source")

    def xml(field: str) -> ET.Element:
        source = below(root, branding[field])
        text = source.read_text()
        require(len(text) <= 1024 * 1024 and "<!DOCTYPE" not in text.upper(), "Unexpected branding XML document")
        return ET.fromstring(text)

    app = xml("androidManifest").find("application")
    adaptive_path = Path(branding["androidAdaptiveIcon"])
    expected_icon = "@mipmap/" + adaptive_path.stem
    require(app is not None and app.get(ANDROID + "icon") == expected_icon and app.get(ANDROID + "roundIcon") == expected_icon,
            "Android manifest no longer references the inspected Mural launcher icon")
    adaptive = xml("androidAdaptiveIcon")
    require(adaptive.tag == "adaptive-icon", "Expected the inspected Android adaptive icon")
    foreground_ref = adaptive.find("foreground")
    require(foreground_ref is not None and foreground_ref.get(ANDROID + "drawable") == "@drawable/" + Path(branding["androidForeground"]).stem,
            "Android adaptive icon no longer references the inspected foreground")
    foreground = xml("androidForeground")
    bitmaps = list(foreground.iter("bitmap"))
    require(len(bitmaps) == 1 and bitmaps[0].get(ANDROID + "src") == "@drawable/" + android_icon.stem,
            "Android foreground no longer references the exact Mural artwork")
    return {"iosIconSource": branding["iosIconSet"] + "/" + branding["iosIconFile"],
            "androidIconSource": branding["androidIconFile"], "identicalIconSHA256": ios_info["sha256"],
            "androidAdaptiveIconSHA256": sha256(below(root, branding["androidAdaptiveIcon"])),
            "androidForegroundSHA256": sha256(below(root, branding["androidForeground"])),
            "androidForegroundInset": foreground.get(ANDROID + "inset", "none"),
            "designSources": {branding[field]: sha256(below(root, branding[field])) for field in ("iosDesignSource", "androidDesignSource")},
            "visualReviewRequired": "Launcher masks and scale, in-app wordmark and store artwork must match the current iOS reference."}


def elf_info(header: bytes, file_size: int, expected_machine: int) -> dict:
    """Inspect ELF64 program headers, including LOAD and GNU_RELRO boundaries."""
    require(len(header) >= 64 and header[:4] == b"\x7fELF", "Native library is not ELF")
    require(header[4] == 2 and header[5] == 1 and header[6] == 1, "Expected little-endian ELF64")
    fields = struct.unpack_from("<HHIQQQIHHHHHH", header, 16)
    elf_type, machine, version, _, phoff, _, _, ehsize, phentsize, phnum, *_ = fields
    require(elf_type == 3 and machine == expected_machine and version == 1 and ehsize == 64,
            "Unexpected ELF identity or ABI")
    require(phnum > 0 and phnum < 1024 and phentsize == 56, "Invalid ELF program headers")
    require(phoff >= 64 and phoff + phnum * phentsize <= len(header), "ELF program headers exceed inspected prefix")
    loads, relro = [], []
    for index in range(phnum):
        kind, flags, offset, address, _, filesz, memsz, alignment = struct.unpack_from("<IIQQQQQQ", header, phoff + index * phentsize)
        require(offset + filesz <= file_size, "ELF segment extends beyond library")
        if kind == 1:
            require(memsz >= filesz, "ELF LOAD memory size is smaller than file size")
            require(alignment >= PAGE_SIZE and alignment & (alignment - 1) == 0, "ELF LOAD alignment is below 16 KB")
            require(offset % PAGE_SIZE == address % PAGE_SIZE, "ELF LOAD file/virtual offsets disagree at 16 KB")
            loads.append({"offset": offset, "virtualAddress": address, "memorySize": memsz, "flags": flags, "alignment": alignment})
        if kind == 0x6474E552:
            relro.append({"virtualAddress": address, "memorySize": memsz, "endAligned16KB": (address + memsz) % PAGE_SIZE == 0})
    require(bool(loads), "ELF has no LOAD segments")
    # Bionic protects every whole page touched by RELRO. An unaligned end is a
    # review signal, not proof of a crash: padding/gaps can contain no writable
    # data. Reject only a rounded protection range that overlaps a writable LOAD
    # outside all declared RELRO regions. Runtime testing remains mandatory.
    coverage = sorted((segment["virtualAddress"], segment["virtualAddress"] + segment["memorySize"]) for segment in relro)
    for segment in relro:
        protected_start = segment["virtualAddress"] // PAGE_SIZE * PAGE_SIZE
        protected_end = ((segment["virtualAddress"] + segment["memorySize"] + PAGE_SIZE - 1) // PAGE_SIZE) * PAGE_SIZE
        for load in loads:
            if load["flags"] & 2 == 0:
                continue
            cursor = max(protected_start, load["virtualAddress"])
            end = min(protected_end, load["virtualAddress"] + load["memorySize"])
            if cursor >= end:
                continue
            for covered_start, covered_end in coverage:
                if covered_start > cursor or cursor >= end:
                    break
                if covered_end > cursor:
                    cursor = min(covered_end, end)
            require(cursor >= end, "ELF GNU_RELRO rounding overlaps writable LOAD data outside RELRO")
    warnings = ["GNU_RELRO end is not 16 KB aligned; no writable LOAD overlap found. Verify in a 16 KB runtime."] if any(
        not segment["endAligned16KB"] for segment in relro) else []
    return {"machine": machine, "loadSegments": loads, "relroSegments": relro, "warnings": warnings}


def check_embedded_secrets(bundle: zipfile.ZipFile) -> dict:
    """Bounded pattern scan. Never include matched credential bytes in errors or evidence."""
    scanned = 0
    for info in bundle.infolist():
        if info.is_dir():
            continue
        require(not CREDENTIAL_FILE.search(info.filename), f"Credential-shaped file packaged in AAB: {info.filename}")
        require(info.file_size <= 256 * 1024 * 1024, "AAB entry exceeds secret-inspection limit")
        with bundle.open(info) as source:
            overlap = b""
            while chunk := source.read(1024 * 1024):
                data = overlap + chunk
                for kind, pattern in SECRET_PATTERNS.items():
                    require(pattern.search(data) is None, f"Possible {kind} packaged in {info.filename}; inspect privately")
                overlap = data[-1024:]
        scanned += 1
    return {"entriesScanned": scanned, "patterns": list(SECRET_PATTERNS), "findings": 0,
            "scope": "Known credential files and high-confidence secret patterns only; not proof that every possible credential format is absent."}


def check_aab(path: Path, licenses: list[str]) -> dict:
    require(path.suffix == ".aab", "Expected .aab file")
    libraries, abis = {}, set()
    with zipfile.ZipFile(path) as bundle:
        names = bundle.namelist()
        require(len(names) == len(set(names)), "AAB contains duplicate entries")
        require("BundleConfig.pb" in names and "base/manifest/AndroidManifest.xml" in names, "Missing AAB structure")
        require(any(name.startswith("base/dex/") and name.endswith(".dex") for name in names), "Missing base dex")
        for name in names:
            parts = PurePosixPath(name).parts
            require(not name.startswith("/") and ".." not in parts and "\\" not in name, "Unsafe AAB entry path")
            if len(parts) == 4 and parts[1] == "lib" and parts[3].endswith(".so"):
                abi = parts[2]
                abis.add(abi)
                if abi not in ("arm64-v8a", "x86_64"):
                    continue
                info = bundle.getinfo(name)
                require(info.file_size <= 256 * 1024 * 1024, "Native library exceeds inspection limit")
                with bundle.open(info) as library:
                    prefix = library.read(128 * 1024)
                    digest = hashlib.sha256(prefix)
                    for chunk in iter(lambda: library.read(1024 * 1024), b""):
                        digest.update(chunk)
                try:
                    libraries[name] = elf_info(prefix, info.file_size, 183 if abi == "arm64-v8a" else 62)
                    libraries[name]["sha256"] = digest.hexdigest()
                    libraries[name]["bytes"] = info.file_size
                except InvalidRelease as error:
                    raise InvalidRelease(f"{name}: {error}") from error
        require("arm64-v8a" in abis and bool(libraries), "Mural AAB must include arm64-v8a native libraries")
        secret_scan = check_embedded_secrets(bundle)
        signatures = sorted(name for name in names if re.match(r"^META-INF/[^/]+\.(?:SF|RSA|DSA|EC)$", name, re.I))
        for notice in licenses:
            require(f"base/assets/{notice}" in names and bundle.getinfo(f"base/assets/{notice}").file_size > 0,
                    f"Missing bundled notice: {notice}")
    return {"sha256": sha256(path), "bytes": path.stat().st_size, "abis": sorted(abis), "nativeLibraries": libraries,
            "embeddedSecretScan": secret_scan, "jarSignatureEntries": signatures}


def check_bundle_manifest(text: str, spec: dict) -> dict:
    require("<!DOCTYPE" not in text.upper() and len(text) <= 1024 * 1024, "Unexpected manifest document")
    root = ET.fromstring(text)
    require(root.tag == "manifest" and root.get("package") == spec["packageName"], "AAB package differs from release specification")
    require(root.get(ANDROID + "versionCode") == str(spec["versionCode"]), "AAB versionCode differs from release specification")
    require(root.get(ANDROID + "versionName") == spec["versionName"], "AAB versionName differs from release specification")
    sdk, app = root.find("uses-sdk"), root.find("application")
    require(sdk is not None and sdk.get(ANDROID + "minSdkVersion") == str(spec["minSdk"]) and
            sdk.get(ANDROID + "targetSdkVersion") == str(spec["targetSdk"]), "AAB SDK levels differ from release specification")
    require(app is not None and app.get(ANDROID + "debuggable", "false") == "false", "Release AAB is debuggable")
    require(app.get(ANDROID + "testOnly", "false") == "false", "Release AAB is test-only")
    require(app.get(ANDROID + "allowBackup") == "false" and app.get(ANDROID + "usesCleartextTraffic") == "false",
            "Release must disable automatic backup and cleartext traffic")
    permissions = sorted(node.get(ANDROID + "name", "") for node in root if node.tag in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m"))
    exported = [{"type": node.tag, "name": node.get(ANDROID + "name"), "permission": node.get(ANDROID + "permission")}
                for node in app if node.tag in ("activity", "activity-alias", "service", "receiver", "provider")
                and node.get(ANDROID + "exported") == "true"]
    return {"packageName": spec["packageName"], "versionCode": spec["versionCode"], "versionName": spec["versionName"],
            "minSdk": spec["minSdk"], "targetSdk": spec["targetSdk"], "permissions": permissions, "exportedComponents": exported,
            "debuggable": False, "testOnly": False, "allowBackup": False, "usesCleartextTraffic": False}


def run_bundletool(jar: Path | None, aab: Path, spec: dict, classpath_file: Path | None = None) -> dict:
    dependencies = []
    if classpath_file is not None:
        raw = json.loads(classpath_file.read_text())
        require(isinstance(raw, list) and 1 <= len(raw) <= 64 and all(isinstance(value, str) for value in raw), "Invalid local bundletool classpath")
        paths = [Path(value).resolve() for value in raw]
        require(len(set(paths)) == len(paths) and all(path.is_file() and path.suffix == ".jar" and os.pathsep not in str(path) for path in paths), "Invalid bundletool dependency file")
        command = ["java", "-cp", os.pathsep.join(str(path) for path in paths), "com.android.tools.build.bundletool.BundleToolMain"]
        dependencies = [{"file": path.name, "sha256": sha256(path)} for path in paths]
    else:
        require(jar is not None, "bundletool JAR or classpath is required")
        command = ["java", "-jar", str(jar)]
    def dump(kind: str, *extra: str) -> str:
        completed = subprocess.run([*command, "dump", kind, f"--bundle={aab}", *extra],
                                   check=False, capture_output=True, text=True, timeout=60)
        require(completed.returncode == 0, f"bundletool dump {kind} failed; check the local tool and bundle")
        return completed.stdout
    config = json.loads(dump("config"))
    alignment = config.get("optimizations", {}).get("uncompressNativeLibraries", {}).get("alignment")
    require(alignment == "PAGE_ALIGNMENT_16K", "AAB does not request PAGE_ALIGNMENT_16K")
    manifest = check_bundle_manifest(dump("manifest", "--module=base"), spec)
    return {"alignment": alignment, "manifest": manifest,
            "bundletoolSHA256": sha256(jar) if jar else None, "cachedDependencies": dependencies}


def git_state(root: Path) -> dict:
    def git(*args: str) -> str:
        return subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True, text=True, timeout=10).stdout.strip()
    try:
        return {"commit": git("rev-parse", "HEAD"), "dirty": bool(git("status", "--porcelain"))}
    except (OSError, subprocess.SubprocessError):
        return {"commit": None, "dirty": None}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-dir", type=Path, default=ROOT / "release/android")
    parser.add_argument("--aab", type=Path, help="Inspect every 64-bit native library and required notice in this bundle")
    bundletool = parser.add_mutually_exclusive_group()
    bundletool.add_argument("--bundletool-jar", type=Path, help="Also verify the bundle's own manifest and 16 KB packaging request")
    bundletool.add_argument("--bundletool-classpath-file", type=Path, help="JSON array of trusted local cached bundletool/dependency JAR paths")
    parser.add_argument("--require-unsigned", action="store_true", help="Reject any JAR signature entries in a local inspection candidate")
    parser.add_argument("--require-assets", action="store_true", help="Fail if any final store asset is missing")
    parser.add_argument("--require-bundle", action="store_true", help="Require an AAB and bundletool checks")
    parser.add_argument("--output", type=Path, help="Write a public-safe JSON evidence report")
    args = parser.parse_args(argv)
    result = {"schemaVersion": 1, "validationScope": "store-files-and-native-layout", "source": git_state(ROOT),
              "checks": {}, "notVerified": ["upload signature and key custody", "generated split APK alignment and install",
                  "16 KB runtime", "physical microphone, speaker and Bluetooth", "live accounts, trial and purchases",
                  "launcher mask/scale, in-app logo and Play artwork visual parity with iOS",
                  "store declarations and owner release approval"]}
    try:
        spec = json.loads((args.release_dir / "release-spec.json").read_text())
        require(spec["schemaVersion"] == 1, "Unsupported release specification")
        require(spec["scope"] in ("internal-byok-preview", "hosted-guest-preview", "hosted-minute-release"), "Unknown release scope")
        result["scope"] = spec["scope"]
        result["checks"]["branding"] = check_branding(ROOT, spec)
        locale = below(args.release_dir / "metadata", spec["metadataLocale"])
        result["checks"]["metadata"] = {name: check_text(locale / f"{name}.txt", limit, name in ("title", "short-description"))
                                            for name, limit in TEXT_LIMITS.items()}
        result["checks"]["assets"] = {}
        pending = []
        assets = spec["assets"]
        require(2 <= len(assets["phoneScreenshots"]) <= 8, "Expected 2–8 phone screenshots")
        paths = [(kind, assets[kind]) for kind in ("icon", "featureGraphic")]
        paths += [("screenshot", path) for path in assets["phoneScreenshots"]]
        require(len(paths) == len(set(path for _, path in paths)), "Duplicate store asset path")
        for kind, relative in paths:
            asset = below(args.release_dir, relative)
            if asset.exists():
                result["checks"]["assets"][relative] = check_asset(asset, kind)
            else:
                pending.append(relative)
        require(not args.require_assets or not pending, "Missing final store assets: " + ", ".join(pending))
        if pending:
            result["notVerified"].append("missing store assets: " + ", ".join(pending))
        require(not args.require_bundle or (args.aab is not None and (args.bundletool_jar is not None or args.bundletool_classpath_file is not None)),
                "--require-bundle needs --aab and a bundletool JAR or classpath")
        require((args.bundletool_jar is None and args.bundletool_classpath_file is None) or args.aab is not None, "bundletool requires --aab")
        require(not args.require_unsigned or args.aab is not None, "--require-unsigned requires --aab")
        if args.aab:
            result["checks"]["aab"] = check_aab(args.aab, spec["requiredLicenses"])
            require(not args.require_unsigned or not result["checks"]["aab"]["jarSignatureEntries"], "Inspection candidate must be unsigned")
            if args.bundletool_jar or args.bundletool_classpath_file:
                result["checks"]["bundletool"] = run_bundletool(args.bundletool_jar, args.aab, spec, args.bundletool_classpath_file)
            else:
                result["notVerified"].append("AAB manifest and PAGE_ALIGNMENT_16K packaging request")
        else:
            result["notVerified"].append("release AAB")
        result["passed"] = True
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, ET.ParseError, subprocess.SubprocessError) as error:
        result["passed"] = False
        result["error"] = str(error)
    rendered = json.dumps(result, indent=2, ensure_ascii=False) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered)
    print(rendered, end="")
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
