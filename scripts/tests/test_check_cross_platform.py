import contextlib
import io
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import check_cross_platform as ccp

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]


class PromptsTests(unittest.TestCase):
    SWIFT = '''import Foundation

public enum TeachingPolicy {
    public static func greeting(language: LanguageModule) -> String {
        "Begin now in \\(language.name) and wait."
    }
}
'''

    def test_identical_prompts_pass(self):
        kotlin = '''package chat.mural.core

object TeachingPolicy {
    fun greeting(language:LanguageModule) = "Begin now in " + language.name + " and wait."
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            swift_path = pathlib.Path(tmp) / 'TeachingPolicy.swift'
            kotlin_path = pathlib.Path(tmp) / 'TeachingPolicy.kt'
            swift_path.write_text(self.SWIFT)
            kotlin_path.write_text(kotlin)
            self.assertEqual(ccp.check_prompts(swift_path, kotlin_path), [])

    def test_changed_prompt_word_fails(self):
        kotlin = '''package chat.mural.core

object TeachingPolicy {
    fun greeting(language:LanguageModule) = "Begin right now in " + language.name + " and wait."
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            swift_path = pathlib.Path(tmp) / 'TeachingPolicy.swift'
            kotlin_path = pathlib.Path(tmp) / 'TeachingPolicy.kt'
            swift_path.write_text(self.SWIFT)
            kotlin_path.write_text(kotlin)
            failures = ccp.check_prompts(swift_path, kotlin_path)
            self.assertEqual(len(failures), 1)
            self.assertTrue(failures[0].startswith('prompts:'))
            self.assertIn(str(kotlin_path), failures[0])
            self.assertIn(str(swift_path), failures[0])

    def test_prompt_without_kotlin_counterpart_fails(self):
        kotlin = '''package chat.mural.core

object TeachingPolicy {
    fun farewell(language:LanguageModule) = "Goodbye."
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            swift_path = pathlib.Path(tmp) / 'TeachingPolicy.swift'
            kotlin_path = pathlib.Path(tmp) / 'TeachingPolicy.kt'
            swift_path.write_text(self.SWIFT)
            kotlin_path.write_text(kotlin)
            failures = ccp.check_prompts(swift_path, kotlin_path)
            self.assertTrue(any(f.startswith('prompts:') and 'greeting()' in f for f in failures), failures)
            self.assertTrue(any('farewell()' in f for f in failures), failures)


class ConstantsTests(unittest.TestCase):
    GAP_ONLY = [c for c in ccp.CONSTANTS if c[0] == 'transcript_gap_ms']

    def write_models(self, root, swift_gap, kotlin_gap):
        core = root / 'Core'
        core.mkdir(parents=True)
        (core / 'Models.swift').write_text(
            'public enum Transcript {\n'
            '    public static func passages(_ fragments: [Fragment]) -> [Passage] {\n'
            '        if fragment.startMS - result[i].endMS <= %d {}\n'
            '    }\n'
            '}\n' % swift_gap)
        android = root / 'android/app/src/main/java/chat/mural/core'
        android.mkdir(parents=True)
        (android / 'Models.kt').write_text(
            'object Transcript {\n'
            '    fun passages(fragments: List<Fragment>): List<Passage> {\n'
            '        if (f.startMS - p.endMS <= %d) {}\n'
            '    }\n'
            '}\n' % kotlin_gap)

    def test_matching_constant_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self.write_models(root, 2200, 2200)
            self.assertEqual(ccp.check_constants(root, self.GAP_ONLY), [])

    def test_mismatched_constant_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self.write_models(root, 2200, 2500)
            failures = ccp.check_constants(root, self.GAP_ONLY)
            self.assertEqual(len(failures), 1)
            self.assertTrue(failures[0].startswith('constants:'))
            self.assertIn('transcript_gap_ms', failures[0])

    def test_missing_constant_file_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self.write_models(root, 2200, 2200)
            (root / 'android/app/src/main/java/chat/mural/core/Models.kt').unlink()
            failures = ccp.check_constants(root, self.GAP_ONLY)
            self.assertEqual(len(failures), 1)
            self.assertTrue(failures[0].startswith('constants: transcript_gap_ms'), failures)
            self.assertIn('Models.kt', failures[0])

    def test_unmatched_constant_pattern_is_reported_instead_of_crashing(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self.write_models(root, 2200, 2200)
            path = root / 'Core/Models.swift'
            path.write_text(path.read_text().replace('result[i].endMS <= 2200', 'gap(result[i]) <= 2200'))
            failures = ccp.check_constants(root, self.GAP_ONLY)
            self.assertEqual(len(failures), 1)
            self.assertTrue(failures[0].startswith('constants: transcript_gap_ms'), failures)
            self.assertIn('Models.swift', failures[0])


class ArchiveFieldsTests(unittest.TestCase):
    SWIFT_OK = '''public struct SessionRecord: Codable, Sendable {
    public var id = "x"
    public var languageID: String
    public var providerID: String?
    public var title: String
}
'''
    KOTLIN_OK = '''data class SessionRecord(
    val id: String = "x", val languageID: String = "nb",
    var providerID: String? = null, var title: String = "A conversation"
)
'''

    def test_identical_fields_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            swift_path = pathlib.Path(tmp) / 'Models.swift'
            kotlin_path = pathlib.Path(tmp) / 'Models.kt'
            swift_path.write_text(self.SWIFT_OK)
            kotlin_path.write_text(self.KOTLIN_OK)
            self.assertEqual(ccp.check_archive_fields(swift_path, kotlin_path), [])

    def test_new_required_swift_field_fails(self):
        swift = self.SWIFT_OK.replace(
            'public var title: String\n', 'public var title: String\n    public var foo = 0\n')
        with tempfile.TemporaryDirectory() as tmp:
            swift_path = pathlib.Path(tmp) / 'Models.swift'
            kotlin_path = pathlib.Path(tmp) / 'Models.kt'
            swift_path.write_text(swift)
            kotlin_path.write_text(self.KOTLIN_OK)
            failures = ccp.check_archive_fields(swift_path, kotlin_path)
            self.assertTrue(any('SessionRecord.foo' in f for f in failures))
            self.assertTrue(any('Models.kt' in f for f in failures))

    def test_optional_swift_field_missing_from_kotlin_fails(self):
        # providerID is `String?` in Swift: the key may be absent, but a present value must survive a Kotlin round trip.
        kotlin = self.KOTLIN_OK.replace(
            'var providerID: String? = null, var title', 'var title')
        with tempfile.TemporaryDirectory() as tmp:
            swift_path = pathlib.Path(tmp) / 'Models.swift'
            kotlin_path = pathlib.Path(tmp) / 'Models.kt'
            swift_path.write_text(self.SWIFT_OK)
            kotlin_path.write_text(kotlin)
            failures = ccp.check_archive_fields(swift_path, kotlin_path)
            self.assertTrue(any('SessionRecord.providerID' in f and 'optional' in f for f in failures), failures)


class ArchiveFieldsReverseTests(unittest.TestCase):
    def check(self, swift, kotlin):
        with tempfile.TemporaryDirectory() as tmp:
            swift_path = pathlib.Path(tmp) / 'Models.swift'
            kotlin_path = pathlib.Path(tmp) / 'Models.kt'
            swift_path.write_text(swift)
            kotlin_path.write_text(kotlin)
            return ccp.check_archive_fields(swift_path, kotlin_path)

    def test_kotlin_fields_check_requiring_an_unknown_swift_field_fails(self):
        kotlin = ArchiveFieldsTests.KOTLIN_OK + 'fun check(s: JsonObject) { fields(s, "id languageID title reviewCount") }\n'
        failures = self.check(ArchiveFieldsTests.SWIFT_OK, kotlin)
        self.assertTrue(any('SessionRecord.reviewCount' in f and 'fields()' in f for f in failures), failures)

    def test_kotlin_fields_check_requiring_an_optional_swift_field_fails(self):
        kotlin = ArchiveFieldsTests.KOTLIN_OK + 'fun check(s: JsonObject) { fields(s, "id languageID title providerID") }\n'
        failures = self.check(ArchiveFieldsTests.SWIFT_OK, kotlin)
        self.assertTrue(any('SessionRecord.providerID' in f for f in failures), failures)

    def test_kotlin_parameter_without_default_absent_from_swift_fails(self):
        kotlin = ArchiveFieldsTests.KOTLIN_OK.replace('var title: String = "A conversation"', 'var title: String = "A conversation", val mood: String')
        failures = self.check(ArchiveFieldsTests.SWIFT_OK, kotlin)
        self.assertTrue(any('SessionRecord.mood' in f for f in failures), failures)

    def test_missing_required_field_check_fails_when_kotlin_validates_keys(self):
        kotlin = ArchiveFieldsTests.KOTLIN_OK + 'fun fields(obj: JsonObject, names: String) {}\n'
        failures = self.check(ArchiveFieldsTests.SWIFT_OK, kotlin)
        self.assertTrue(any('fields(s, ...)' in f and 'SessionRecord' in f for f in failures), failures)

    def test_matching_fields_check_passes(self):
        kotlin = ArchiveFieldsTests.KOTLIN_OK + 'fun check(s: JsonObject) { fields(s, "id languageID title") }\n'
        self.assertEqual(self.check(ArchiveFieldsTests.SWIFT_OK, kotlin), [])


class RealRepoTests(unittest.TestCase):
    def test_real_repo_has_no_drift(self):
        failures = ccp.run_checks(REPO_ROOT)
        self.assertEqual(failures, [], '\n'.join(failures))


class ReportTests(unittest.TestCase):
    def test_failures_name_repository_relative_paths(self):
        root = REPO_ROOT.resolve()
        failure = ccp.format_failure('prompts', 'voice() wording differs between platforms',
                                     root / 'android/app/src/main/java/chat/mural/core/TeachingPolicy.kt', 4,
                                     root / 'Core/TeachingPolicy.swift', 4)
        output = io.StringIO()
        with mock.patch.object(ccp, 'run_checks', return_value=[failure]), contextlib.redirect_stdout(output):
            self.assertEqual(ccp.main(['--root', str(root)]), 1)
        self.assertNotIn(str(root), output.getvalue())
        self.assertIn('Update android/app/src/main/java/chat/mural/core/TeachingPolicy.kt:4 to match Core/TeachingPolicy.swift:4.', output.getvalue())


if __name__ == '__main__':
    unittest.main()
