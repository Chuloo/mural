import importlib.util
import io
import json
from contextlib import redirect_stdout
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import zipfile
import zlib

SPEC = importlib.util.spec_from_file_location("check_android_release", Path(__file__).parents[1] / "check_android_release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


def elf(alignment=16384, relro_end=16384, machine=183, writable=True, memory_size=16384):
    data = bytearray(256)
    data[:16] = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into("<HHIQQQIHHHHHH", data, 16, 3, machine, 1, 0, 64, 0, 0, 64, 56, 2, 0, 0, 0)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 6 if writable else 5, 0, 0, 0, 256, memory_size, alignment)
    struct.pack_into("<IIQQQQQQ", data, 120, 0x6474E552, 4, 0, 0, 0, 0, relro_end, 1)
    return bytes(data)


def png(path, width, height, color=2, transparency=False):
    def chunk(kind, content):
        return struct.pack(">I", len(content)) + kind + content + struct.pack(">I", zlib.crc32(kind + content) & 0xFFFFFFFF)
    channels = 4 if color == 6 else 3
    body = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, color, 0, 0, 0))
    if transparency:
        body += chunk(b"tRNS", bytes(6))
    body += chunk(b"IDAT", zlib.compress((b"\x00" + bytes(width * channels)) * height)) + chunk(b"IEND", b"")
    path.write_bytes(body)


class AndroidReleaseTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def test_metadata_handles_non_ascii_and_rejects_long_or_unfinished_copy(self):
        path = self.root / "title.txt"
        path.write_text("Mural: Bokmål\n")
        self.assertEqual(release.check_text(path, 30, True)["characters"], 13)
        for text in ("x" * 31, "Mural\nPractice", "Mural [REQUIRED: title]", " Mural", "Mural\t"):
            path.write_text(text)
            with self.subTest(text=text), self.assertRaises(release.InvalidRelease):
                release.check_text(path, 30, True)

    def test_final_png_formats_pass(self):
        cases = [("icon", 512, 512, 6), ("featureGraphic", 1024, 500, 2), ("screenshot", 1080, 1920, 2)]
        for kind, width, height, color in cases:
            path = self.root / f"{kind}.png"
            png(path, width, height, color)
            with self.subTest(kind=kind):
                self.assertEqual(release.check_asset(path, kind)["width"], width)

    def branding(self):
        icon_set = self.root / "ios/AppIcon.appiconset"
        icon_set.mkdir(parents=True)
        (icon_set / "Contents.json").write_text(json.dumps({"images": [{"filename": "MuralIcon.png"}]}))
        png(icon_set / "MuralIcon.png", 1024, 1024, 6)
        android_icon = self.root / "android/mural_icon.png"
        android_icon.parent.mkdir()
        android_icon.write_bytes((icon_set / "MuralIcon.png").read_bytes())
        namespace = 'xmlns:android="http://schemas.android.com/apk/res/android"'
        (self.root / "android/AndroidManifest.xml").write_text(f'<manifest {namespace}><application android:icon="@mipmap/ic_mural" android:roundIcon="@mipmap/ic_mural"/></manifest>')
        (self.root / "android/ic_mural.xml").write_text(f'<adaptive-icon {namespace}><foreground android:drawable="@drawable/ic_mural_foreground"/></adaptive-icon>')
        (self.root / "android/ic_mural_foreground.xml").write_text(f'<inset {namespace} android:inset="10%"><bitmap android:src="@drawable/mural_icon"/></inset>')
        (self.root / "ios/Design.swift").write_text("struct Brand {}")
        (self.root / "android/Design.kt").write_text("fun Brand() {}")
        return {"branding": {"iosIconSet": "ios/AppIcon.appiconset", "iosIconFile": "MuralIcon.png",
                "androidIconFile": "android/mural_icon.png", "androidManifest": "android/AndroidManifest.xml",
                "androidAdaptiveIcon": "android/ic_mural.xml", "androidForeground": "android/ic_mural_foreground.xml",
                "iosDesignSource": "ios/Design.swift", "androidDesignSource": "android/Design.kt"}}

    def test_branding_requires_exact_ios_artwork_and_records_mask_review(self):
        spec = self.branding()
        result = release.check_branding(self.root, spec)
        self.assertEqual(result["androidForegroundInset"], "10%")
        self.assertEqual(result["identicalIconSHA256"], release.sha256(self.root / "android/mural_icon.png"))
        self.assertEqual(len(result["designSources"]), 2)
        png(self.root / "android/mural_icon.png", 1024, 1024, 2)
        with self.assertRaisesRegex(release.InvalidRelease, "differs from the exact iOS"):
            release.check_branding(self.root, spec)

    def test_branding_rejects_reference_changes_that_bypass_the_inspected_artwork(self):
        spec = self.branding()
        paths_and_replacements = [
            ("ios/AppIcon.appiconset/Contents.json", "MuralIcon.png", "OtherIcon.png"),
            ("android/AndroidManifest.xml", "@mipmap/ic_mural", "@mipmap/other"),
            ("android/ic_mural.xml", "@drawable/ic_mural_foreground", "@drawable/other"),
            ("android/ic_mural_foreground.xml", "@drawable/mural_icon", "@drawable/other")
        ]
        for relative, old, new in paths_and_replacements:
            path = self.root / relative
            original = path.read_text()
            path.write_text(original.replace(old, new))
            with self.subTest(path=relative), self.assertRaises(release.InvalidRelease):
                release.check_branding(self.root, spec)
            path.write_text(original)

    def test_tall_review_capture_cannot_be_used_as_store_screenshot(self):
        path = self.root / "review.png"
        png(path, 1080, 2424)
        with self.assertRaisesRegex(release.InvalidRelease, "2:1"):
            release.check_asset(path, "screenshot")

    def test_feature_and_screenshot_alpha_and_corrupt_crc_are_rejected(self):
        path = self.root / "test.png"
        png(path, 1024, 500, 6)
        with self.assertRaisesRegex(release.InvalidRelease, "opaque"):
            release.check_asset(path, "featureGraphic")
        png(path, 1080, 1920, transparency=True)
        with self.assertRaisesRegex(release.InvalidRelease, "opaque"):
            release.check_asset(path, "screenshot")
        data = bytearray(path.read_bytes()); data[20] ^= 1; path.write_bytes(data)
        with self.assertRaisesRegex(release.InvalidRelease, "CRC"):
            release.png_info(path)

    def test_truncated_png_and_data_after_end_are_rejected(self):
        path = self.root / "test.png"
        png(path, 512, 512, 6)
        original = path.read_bytes()
        for body in (original[:24], original[:-3], original + b"extra"):
            path.write_bytes(body)
            with self.assertRaises(release.InvalidRelease):
                release.png_info(path)

    def test_elf_16kb_load_and_relro_pass_for_supported_abis(self):
        for machine in (183, 62):
            result = release.elf_info(elf(machine=machine), 256, machine)
            self.assertEqual(result["loadSegments"][0]["alignment"], 16384)
            self.assertEqual(len(result["relroSegments"]), 1)

    def test_elf_4kb_and_relro_overlapping_writable_data_fail_independently(self):
        with self.assertRaisesRegex(release.InvalidRelease, "LOAD alignment"):
            release.elf_info(elf(alignment=4096), 256, 183)
        with self.assertRaisesRegex(release.InvalidRelease, "GNU_RELRO rounding overlaps"):
            release.elf_info(elf(relro_end=12288), 256, 183)

    def test_unaligned_relro_with_only_padding_is_reported_not_rejected(self):
        result = release.elf_info(elf(relro_end=12288, memory_size=12288), 256, 183)
        self.assertFalse(result["relroSegments"][0]["endAligned16KB"])
        self.assertIn("no writable LOAD overlap", result["warnings"][0])

    def test_malformed_elf_and_wrong_abi_fail(self):
        for data, expected in ((b"bad", 183), (elf()[:120], 183), (elf(machine=62), 183)):
            with self.subTest(expected=expected), self.assertRaises(release.InvalidRelease):
                release.elf_info(data, 256, expected)
        data = bytearray(elf())
        struct.pack_into("<Q", data, 64 + 8, 1)
        with self.assertRaisesRegex(release.InvalidRelease, "beyond library"):
            release.elf_info(data, 256, 183)

    def bundle(self, native=None, notices=True, extra=None):
        path = self.root / "release.aab"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("BundleConfig.pb", b"config")
            archive.writestr("base/manifest/AndroidManifest.xml", b"protobuf")
            archive.writestr("base/dex/classes.dex", b"dex")
            archive.writestr("base/lib/arm64-v8a/libmural.so", native or elf())
            if notices:
                archive.writestr("base/assets/LICENSE.txt", "MIT")
            if extra:
                archive.writestr(extra, "unsafe")
        return path

    def test_aab_checks_native_libraries_and_required_notices(self):
        result = release.check_aab(self.bundle(), ["LICENSE.txt"])
        self.assertEqual(result["abis"], ["arm64-v8a"])
        self.assertEqual(len(result["nativeLibraries"]), 1)
        self.assertEqual(result["nativeLibraries"]["base/lib/arm64-v8a/libmural.so"]["sha256"], release.hashlib.sha256(elf()).hexdigest())
        with self.assertRaisesRegex(release.InvalidRelease, "Missing bundled notice"):
            release.check_aab(self.bundle(notices=False), ["LICENSE.txt"])
        with self.assertRaisesRegex(release.InvalidRelease, "GNU_RELRO"):
            release.check_aab(self.bundle(native=elf(relro_end=4096)), ["LICENSE.txt"])

    def test_embedded_secret_checks_never_print_credential_values(self):
        for prefix in ("sk-", "sk_test_", "GOCSPX-", "ghp_"):
            secret = prefix + "x" * 50
            path = self.bundle()
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("base/assets/config.txt", secret)
            with self.subTest(prefix=prefix), self.assertRaises(release.InvalidRelease) as result:
                release.check_aab(path, ["LICENSE.txt"])
            self.assertNotIn(secret, str(result.exception))
            self.assertIn("inspect privately", str(result.exception))

    def test_secret_scan_handles_chunk_boundary_and_private_key_material(self):
        for value in (b"." * (1024 * 1024 - 4) + b"sk-" + b"a" * 50,
                      b"-----BEGIN PRIVATE KEY-----\n" + b"A" * 100):
            path = self.bundle()
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr("base/assets/config.txt", value)
            with self.assertRaises(release.InvalidRelease):
                release.check_aab(path, ["LICENSE.txt"])

    def test_public_oauth_id_is_allowed_but_credential_files_are_not(self):
        path = self.bundle()
        with zipfile.ZipFile(path, "a") as archive:
            archive.writestr("base/assets/public-config.json", "{\"clientID\":\"123-public.apps.googleusercontent.com\",\"origin\":\"https://api.example.test\"}")
        self.assertEqual(release.check_aab(path, ["LICENSE.txt"])["embeddedSecretScan"]["findings"], 0)
        for name in ("base/assets/.env.production", "base/assets/upload.jks", "base/assets/service-account.json"):
            with self.subTest(name=name), self.assertRaisesRegex(release.InvalidRelease, "Credential-shaped"):
                release.check_aab(self.bundle(extra=name), ["LICENSE.txt"])

    def test_bundle_signatures_are_reported_without_treating_a_manifest_as_a_signature(self):
        path = self.bundle(extra="META-INF/MANIFEST.MF")
        self.assertEqual(release.check_aab(path, ["LICENSE.txt"])["jarSignatureEntries"], [])
        with zipfile.ZipFile(path, "a") as archive:
            archive.writestr("META-INF/UPLOAD.SF", "signature")
        self.assertEqual(release.check_aab(path, ["LICENSE.txt"])["jarSignatureEntries"], ["META-INF/UPLOAD.SF"])

    def test_cached_bundletool_classpath_is_validated_and_executed_without_a_shell(self):
        jar = self.root / "bundletool.jar"
        jar.write_bytes(b"local test fixture")
        config = self.root / "classpath.json"
        config.write_text(json.dumps([str(jar)]))
        calls = []
        def execute(command, **kwargs):
            calls.append((command, kwargs))
            return SimpleNamespace(returncode=0, stdout=json.dumps({"optimizations": {"uncompressNativeLibraries": {"alignment": "PAGE_ALIGNMENT_16K"}}}) if "config" in command else "<manifest/>")
        with patch.object(release.subprocess, "run", execute), patch.object(release, "check_bundle_manifest", return_value={"packageName": "test"}):
            result = release.run_bundletool(None, self.root / "test.aab", {}, config)
        self.assertEqual(result["cachedDependencies"][0]["sha256"], release.sha256(jar))
        self.assertEqual(calls[0][0][:4], ["java", "-cp", str(jar.resolve()), "com.android.tools.build.bundletool.BundleToolMain"])
        self.assertNotIn("shell", calls[0][1])
        config.write_text(json.dumps([str(jar), str(jar)]))
        with self.assertRaisesRegex(release.InvalidRelease, "dependency file"):
            release.run_bundletool(None, self.root / "test.aab", {}, config)

    def test_aab_unsafe_paths_are_rejected_without_extraction(self):
        for extra in ("../outside", "/absolute", "base\\outside"):
            with self.subTest(extra=extra), self.assertRaisesRegex(release.InvalidRelease, "Unsafe"):
                release.check_aab(self.bundle(extra=extra), ["LICENSE.txt"])

    def test_assets_cannot_escape_release_directory(self):
        with self.assertRaises(release.InvalidRelease):
            release.below(self.root, "../../elsewhere")
        (self.root / "link").symlink_to(self.root.parent)
        with self.assertRaises(release.InvalidRelease):
            release.below(self.root, "link/outside")

    def test_bundle_manifest_rejects_debug_test_backup_and_wrong_candidate(self):
        spec = {"packageName": "chat.mural.android", "versionCode": 1, "versionName": "0.1", "minSdk": 26, "targetSdk": 36}
        xml = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="chat.mural.android" android:versionCode="1" android:versionName="0.1">
          <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
          <uses-permission android:name="android.permission.RECORD_AUDIO"/>
          <application android:allowBackup="false" android:usesCleartextTraffic="false" />
        </manifest>'''
        self.assertEqual(release.check_bundle_manifest(xml, spec)["permissions"], ["android.permission.RECORD_AUDIO"])
        for malformed in (xml.replace("<application ", '<application android:debuggable="true" '),
                          xml.replace("<application ", '<application android:testOnly="true" '),
                          xml.replace('allowBackup="false"', 'allowBackup="true"'),
                          xml.replace('versionCode="1"', 'versionCode="2"'),
                          xml.replace("chat.mural.android", "chat.mural.android.uitest")):
            with self.assertRaises(release.InvalidRelease):
                release.check_bundle_manifest(malformed, spec)

    def test_checked_in_copy_passes_and_required_assets_remain_a_gate(self):
        spec = json.loads((release.ROOT / "release/android/release-spec.json").read_text())
        for name, limit in release.TEXT_LIMITS.items():
            release.check_text(release.ROOT / "release/android/metadata" / spec["metadataLocale"] / f"{name}.txt", limit,
                               name in ("title", "short-description"))

    def cli_fixture(self):
        spec = self.branding() | {"schemaVersion": 1, "scope": "hosted-guest-preview",
            "packageName": "chat.mural.android", "versionCode": 5, "versionName": "0.1",
            "minSdk": 26, "targetSdk": 36, "metadataLocale": "en-US", "requiredLicenses": ["LICENSE.txt"],
            "assets": {"icon": "assets/icon.png", "featureGraphic": "assets/feature.png",
                       "phoneScreenshots": ["assets/one.png", "assets/two.png"]}}
        directory = self.root / "release"
        metadata = directory / "metadata/en-US"
        metadata.mkdir(parents=True)
        for name in release.TEXT_LIMITS:
            (metadata / f"{name}.txt").write_text("Mural preview\n")
        assets = directory / "assets"
        assets.mkdir()
        for name, width, height, color in [("icon", 512, 512, 6), ("feature", 1024, 500, 2),
                                           ("one", 1080, 1920, 2), ("two", 1080, 1920, 2)]:
            png(assets / f"{name}.png", width, height, color)
        (directory / "release-spec.json").write_text(json.dumps(spec))
        historical = directory / "specs/play-v4.json"
        historical.parent.mkdir()
        historical.write_text(json.dumps(spec | {"versionCode": 4}))
        jar = self.root / "bundletool.jar"
        jar.write_bytes(b"local fixture")
        return directory, historical, self.bundle(), jar

    def run_cli(self, directory, aab, jar, manifest_version, *extra):
        def execute(command, **kwargs):
            config = {"optimizations": {"uncompressNativeLibraries": {"alignment": "PAGE_ALIGNMENT_16K"}}}
            manifest = f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="chat.mural.android" android:versionCode="{manifest_version}" android:versionName="0.1">
                <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
                <application android:allowBackup="false" android:usesCleartextTraffic="false"/>
            </manifest>'''
            return SimpleNamespace(returncode=0, stdout=json.dumps(config) if "config" in command else manifest)
        output = io.StringIO()
        with patch.object(release, "ROOT", self.root), patch.object(release, "git_state", return_value={}), \
                patch.object(release.subprocess, "run", execute), redirect_stdout(output):
            status = release.main(["--release-dir", str(directory), "--aab", str(aab),
                                   "--bundletool-jar", str(jar), "--require-bundle", "--require-assets", *extra])
        return status, json.loads(output.getvalue())

    def test_cli_default_keeps_current_version_and_rejects_archived_bundle(self):
        directory, _, aab, jar = self.cli_fixture()
        status, result = self.run_cli(directory, aab, jar, 5)
        self.assertEqual(status, 0)
        self.assertEqual(result["specification"]["versionCode"], 5)
        self.assertEqual(result["specification"]["sha256"], release.sha256(directory / "release-spec.json"))
        status, result = self.run_cli(directory, aab, jar, 4)
        self.assertEqual(status, 1)
        self.assertIn("versionCode differs", result["error"])

    def test_cli_explicit_historical_spec_uses_shared_assets_and_enforces_v4(self):
        directory, historical, aab, jar = self.cli_fixture()
        status, result = self.run_cli(directory, aab, jar, 4, "--spec", str(historical))
        self.assertEqual(status, 0)
        self.assertEqual(result["specification"]["file"], "play-v4.json")
        self.assertEqual(result["specification"]["sha256"], release.sha256(historical))
        self.assertEqual(result["checks"]["bundletool"]["manifest"]["versionCode"], 4)
        self.assertEqual(len(result["checks"]["assets"]), 4)
        self.assertEqual(json.loads((directory / "release-spec.json").read_text())["versionCode"], 5)
        status, result = self.run_cli(directory, aab, jar, 5, "--spec", str(historical))
        self.assertEqual(status, 1)
        self.assertIn("versionCode differs", result["error"])

    def test_cli_explicit_missing_or_invalid_spec_does_not_fall_back(self):
        directory, historical, aab, jar = self.cli_fixture()
        for path, content in [(historical, '{"schemaVersion": 99}'),
                              (historical, '{"schemaVersion": 1, "scope": "unknown"}'),
                              (directory / "missing.json", None)]:
            if content is not None:
                path.write_text(content)
            with self.subTest(content=content):
                status, result = self.run_cli(directory, aab, jar, 5, "--spec", str(path))
                self.assertEqual(status, 1)
                self.assertFalse(result["passed"])

    def test_checked_in_specs_separate_current_default_direct_and_historical_versions(self):
        directory = release.ROOT / "release/android"
        current = json.loads((directory / "release-spec.json").read_text())
        direct = json.loads((directory / "specs/direct-v7.json").read_text())
        previous_direct = json.loads((directory / "specs/direct-v6.json").read_text())
        historical = json.loads((directory / "specs/play-v4.json").read_text())
        submitted = json.loads((directory / "evidence/play-submission-2026-09-14.json").read_text())
        build = (release.ROOT / "apps/android/app/build.gradle.kts").read_text()
        self.assertRegex(build, rf"versionCode\s*=\s*{current['versionCode']}\b")
        self.assertEqual(historical["versionCode"], submitted["versionCode"])
        self.assertEqual(historical["scope"], "hosted-guest-preview")
        self.assertEqual(previous_direct["versionCode"], 6)
        self.assertEqual(direct["versionCode"], 7)
        self.assertEqual(current["versionCode"], direct["versionCode"])
        self.assertGreater(current["versionCode"], previous_direct["versionCode"])
        self.assertGreater(previous_direct["versionCode"], historical["versionCode"])
        self.assertEqual(previous_direct["scope"], "hosted-minute-release")
        self.assertEqual(direct["scope"], "hosted-minute-release")


if __name__ == "__main__":
    unittest.main()
