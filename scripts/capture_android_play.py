#!/usr/bin/env python3
"""Capture actual offline Play screens from prebuilt isolated test APKs; never build or upload."""
from pathlib import Path
import argparse, subprocess, json, hashlib, datetime, os, re, tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--app-apk', type=Path, required=True)
parser.add_argument('--test-apk', type=Path, required=True)
parser.add_argument('--output', type=Path)
args = parser.parse_args()
if not re.fullmatch(r'emulator-[0-9]+', args.serial):
    parser.error('Use an already running emulator, never a physical-device identifier.')
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:
    parser.error('Set ANDROID_HOME or ANDROID_SDK_ROOT.')

root = Path(__file__).resolve().parents[1]
output_root = args.output or root/'release/android'
app_apk, test_apk = args.app_apk.resolve(), args.test_apk.resolve()
work = Path(tempfile.mkdtemp(prefix='mural-play-capture-'))
adb = str(Path(sdk)/'platform-tools/adb')
aapt2 = Path(sdk)/'build-tools/36.0.0/aapt2'
for apk, expected in [(app_apk, 'chat.mural.android.uitest'), (test_apk, 'chat.mural.android.uitest.test')]:
    if not apk.is_file():
        parser.error('A supplied APK is missing.')
    badging = subprocess.check_output([str(aapt2), 'dump', 'badging', str(apk)], text=True)
    if not badging.startswith("package: name='" + expected + "'"):
        parser.error('Refusing to install a non-isolated APK.')
def device(*values, check=True, timeout=30):
    return subprocess.run([adb, '-s', args.serial, *values], capture_output=True, check=check, timeout=timeout)
def shell(*args):
    return device('shell', *args).stdout.decode().strip()
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

original = {'size': shell('wm', 'size'), 'density': shell('wm', 'density'),
            'user': shell('am', 'get-current-user'),
            'demoAllowed': shell('settings', 'get', 'global', 'sysui_demo_allowed'),
            'fontScale': shell('settings', 'get', 'system', 'font_scale')}
assert original['user'] == '0'
assert 'Override size:' not in original['size'], 'Preserve any pre-existing display override explicitly.'
assert 'Override density:' not in original['density']
evidence = {'recordedAtUTC': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'serial': args.serial, 'originalDeviceSettings': original,
            'uiTestAPK': {'sha256': digest(app_apk)},
            'testAPK': {'sha256': digest(test_apk)},
            'fixture': {'path': 'apps/android/app/src/androidTest/java/chat/mural/PlayStoreCaptureTest.kt',
                        'sha256': digest(root/'apps/android/app/src/androidTest/java/chat/mural/PlayStoreCaptureTest.kt'),
                        'syntheticConversationAndVocabulary': True, 'providerCalls': False, 'purchaseCalls': False},
            'sourceBaseline': subprocess.check_output(['git','rev-parse','HEAD'],cwd=root).decode().strip(),
            'cleanSourceBuildClaimed': False,
            'sourceSnapshotAfterBuild': [{'path': str(p.relative_to(root)), 'sha256': digest(p)}
                for p in sorted((root/'apps/android/app/src/main').rglob('*')) if p.is_file()],
            'assets': []}
try:
    device('install', '-r', str(app_apk))
    device('install', '-r', str(test_apk))
    shell('wm', 'size', '1080x1920')
    shell('settings', 'put', 'global', 'sysui_demo_allowed', '1')
    shell('am','broadcast','-a','com.android.systemui.demo','--es','command','enter')
    shell('am','broadcast','-a','com.android.systemui.demo','--es','command','clock','--es','hhmm','0900')
    shell('am','broadcast','-a','com.android.systemui.demo','--es','command','battery','--es','level','100','--es','plugged','false')
    shell('am','broadcast','-a','com.android.systemui.demo','--es','command','notifications','--es','visible','false')
    result = device('shell','am','instrument','-w','-e','class',
        'chat.mural.PlayStoreCaptureTest,chat.mural.PlayFeatureGraphicTest,chat.mural.LargeTypeOnboardingTest',
        'chat.mural.android.uitest.test/androidx.test.runner.AndroidJUnitRunner', check=False, timeout=120)
    (work/'capture-test.log').write_bytes(result.stdout + result.stderr)
    print(result.stdout.decode(), flush=True)
    assert b'OK (5 tests)' in result.stdout, 'Capture and layout tests did not all pass.'
    for name in ['01-greeting.png','02-conversation.png','03-themes.png','04-words.png','05-languages.png','06-settings.png','feature-graphic.png']:
        output = output_root/'assets'/('' if name=='feature-graphic.png' else 'en-US')/name
        output.parent.mkdir(parents=True,exist_ok=True)
        png = device('exec-out','run-as','chat.mural.android.uitest','cat','files/play-store/'+name).stdout
        assert png.startswith(b'\x89PNG\r\n\x1a\n')
        output.write_bytes(png)
        evidence['assets'].append({'path':str(output.relative_to(output_root)),'sha256':digest(output),'bytes':len(png)})
finally:
    device('shell','am','broadcast','-a','com.android.systemui.demo','--es','command','exit',check=False)
    if original['demoAllowed']=='null':
        device('shell','settings','delete','global','sysui_demo_allowed',check=False)
    else:
        device('shell','settings','put','global','sysui_demo_allowed',original['demoAllowed'],check=False)
    device('shell','wm','size','reset',check=False)
    device('shell','wm','density','reset',check=False)
    device('shell','am','start','--user','0','-n','chat.mural.android/chat.mural.MainActivity',check=False)
    evidence['restoredDeviceSettings']={'size':shell('wm','size'),'density':shell('wm','density'),
        'user':shell('am','get-current-user'),'fontScale':shell('settings','get','system','font_scale'),
        'demoAllowed':shell('settings','get','global','sysui_demo_allowed')}
    evidence['testLogSHA256'] = digest(work/'capture-test.log') if (work/'capture-test.log').exists() else None
    (work/'capture-evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
    print('Capture evidence: ' + str(work/'capture-evidence.json'), flush=True)
