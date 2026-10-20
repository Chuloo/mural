from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase
from unittest.mock import patch
import os

from scripts.play_capture_publish import publish_capture_assets


class PlayCapturePublishTests(TestCase):
    def test_failed_evidence_publish_restores_all_previous_assets(self):
        with TemporaryDirectory() as directory:
            root = Path(directory) / 'release'
            screenshot = root / 'assets/en-US/01-conversation.png'
            evidence = root / 'assets/capture-evidence.json'
            icon = root / 'assets/icon.png'
            for path, data in [(screenshot, b'old screenshot'), (evidence, b'old evidence'), (icon, b'icon')]:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            new_image = root / 'assets/en-US/02-word-meaning.png'
            payloads = [(Path('assets/en-US/01-conversation.png'), b'new screenshot'),
                        (Path('assets/en-US/02-word-meaning.png'), b'new image'),
                        (Path('assets/capture-evidence.json'), b'new evidence')]
            replace = os.replace
            failed = False

            def fail_once(source, target):
                nonlocal failed
                if Path(target) == evidence and not failed:
                    failed = True
                    raise OSError('simulated publish failure')
                replace(source, target)

            with patch('scripts.play_capture_publish.os.replace', side_effect=fail_once):
                with self.assertRaisesRegex(OSError, 'simulated publish failure'):
                    publish_capture_assets(root, payloads)
            self.assertEqual(screenshot.read_bytes(), b'old screenshot')
            self.assertFalse(new_image.exists())
            self.assertEqual(evidence.read_bytes(), b'old evidence')
            self.assertEqual(icon.read_bytes(), b'icon')

    def test_success_publishes_complete_set_and_keeps_unrelated_assets(self):
        with TemporaryDirectory() as directory:
            root = Path(directory) / 'release'
            icon = root / 'assets/icon.png'
            icon.parent.mkdir(parents=True, exist_ok=True)
            icon.write_bytes(b'icon')
            payloads = [(Path('assets/en-US/01-conversation.png'), b'image'),
                        (Path('assets/capture-evidence.json'), b'evidence')]
            publish_capture_assets(root, payloads)
            self.assertEqual((root / payloads[0][0]).read_bytes(), b'image')
            self.assertEqual((root / payloads[1][0]).read_bytes(), b'evidence')
            self.assertEqual(icon.read_bytes(), b'icon')
