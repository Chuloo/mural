#!/usr/bin/env python3
"""Capture real, offline UI screens in the isolated Android test application."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True, help='An already running emulator, such as emulator-5554')
parser.add_argument('--output', type=Path, default=root / 'verification' / 'android-design')
args = parser.parse_args()
if not args.serial.startswith('emulator-') or not args.serial.removeprefix('emulator-').isdigit():
    parser.error('This capture script only operates on an emulator.')
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
adb = str(Path(sdk) / 'platform-tools' / 'adb') if sdk else shutil.which('adb')
if not adb:
    parser.error('Set ANDROID_HOME or add adb to PATH.')


def device(*values):
    return subprocess.check_output([adb, '-s', args.serial, *values])


android = root / 'apps' / 'android'
subprocess.run([str(android / 'gradlew'), ':app:assembleUiTest', ':app:assembleUiTestAndroidTest'], cwd=android, check=True)
for apk in ['app/build/outputs/apk/uiTest/app-uiTest.apk', 'app/build/outputs/apk/androidTest/uiTest/app-uiTest-androidTest.apk']:
    device('install', '-r', str(android / apk))
result = device('shell', 'am', 'instrument', '-w', '-e', 'class', 'chat.mural.DesignReviewTest',
                'chat.mural.android.uitest.test/androidx.test.runner.AndroidJUnitRunner').decode()
if 'OK (1 test)' not in result:
    raise SystemExit('The design verification failed. Run the instrumented test for details.')
args.output.mkdir(parents=True, exist_ok=True)
for name in ['01-onboarding', '02-meaning', '03-talk', '04-themes', '05-words']:
    data = device('exec-out', 'run-as', 'chat.mural.android.uitest', 'cat', f'files/design-review/{name}.png')
    if not data.startswith(b'\x89PNG\r\n\x1a\n'):
        raise SystemExit(f'No valid screenshot was returned for {name}.')
    (args.output / f'{name}.png').write_bytes(data)
print(f'Five emulator screenshots saved to {args.output}.')
