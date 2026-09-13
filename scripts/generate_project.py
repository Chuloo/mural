#!/usr/bin/env python3
"""Generate Mural.xcodeproj using only Python's standard library."""
from pathlib import Path
import hashlib
import json
import re

root = Path(__file__).resolve().parents[1]
existing_project = root/'Mural.xcodeproj'/'project.pbxproj'
existing_team = re.search(r'DEVELOPMENT_TEAM\s*=\s*"?([A-Z0-9]+)', existing_project.read_text()) if existing_project.exists() else None
if existing_team:
    local_settings = root/'Config'/'Local.xcconfig'
    local_settings.parent.mkdir(exist_ok=True)
    contents = local_settings.read_text() if local_settings.exists() else '// Personal signing settings. Do not commit.\n'
    setting = 'DEVELOPMENT_TEAM = ' + existing_team.group(1)
    if re.search(r'^DEVELOPMENT_TEAM\s*=.*$', contents, re.MULTILINE):
        contents = re.sub(r'^DEVELOPMENT_TEAM\s*=.*$', setting, contents, flags=re.MULTILINE)
    else:
        contents += '\n' + setting + '\n'
    local_settings.write_text(contents)
objects = {}
def uid(name): return hashlib.sha1(name.encode()).hexdigest()[:24].upper()
def add(identifier, isa, **fields):
    i = uid(identifier); objects[i] = dict(isa=isa, **fields); return i
def encode(value):
    if isinstance(value, dict): return '{ ' + ' '.join(f'{k} = {encode(v)};' for k,v in value.items()) + ' }'
    if isinstance(value, list): return '( ' + ', '.join(encode(v) for v in value) + ', )' if value else '()'
    return json.dumps(str(value), ensure_ascii=False)

sources, refs = [], []
for file in sorted((root/'App').rglob('*.swift')):
    path = str(file.relative_to(root))
    ref = add(path, 'PBXFileReference', lastKnownFileType='sourcecode.swift', path=path, sourceTree='<group>')
    refs.append(ref); sources.append(add(path+'build','PBXBuildFile',fileRef=ref))
asset = add('assets','PBXFileReference',lastKnownFileType='folder.assetcatalog',path='App/Assets.xcassets',sourceTree='<group>')
refs.append(asset)
notices = add('notices','PBXFileReference',lastKnownFileType='text',path='App/ThirdPartyNotices.txt',sourceTree='<group>')
refs.append(notices)
privacy = add('privacy','PBXFileReference',lastKnownFileType='text.xml',path='App/PrivacyInfo.xcprivacy',sourceTree='<group>')
refs.append(privacy)
signing = add('signing','PBXFileReference',lastKnownFileType='text.xcconfig',path='Config/Signing.xcconfig',sourceTree='<group>')
refs.append(signing)
testSource = add('testSource','PBXFileReference',lastKnownFileType='sourcecode.swift',path='UITests/MuralUITests.swift',sourceTree='<group>')
refs.append(testSource)
product = add('product','PBXFileReference',explicitFileType='wrapper.application',path='Mural.app',sourceTree='BUILT_PRODUCTS_DIR')
testProduct = add('testProduct','PBXFileReference',explicitFileType='wrapper.cfbundle',path='MuralUITests.xctest',sourceTree='BUILT_PRODUCTS_DIR')
products = add('products','PBXGroup',children=[product,testProduct],name='Products',sourceTree='<group>')
group = add('main','PBXGroup',children=refs+[products],sourceTree='<group>')
corePackage = add('corePackage','XCLocalSwiftPackageReference',relativePath='.')
rtcPackage = add('rtcPackage','XCRemoteSwiftPackageReference',repositoryURL='https://github.com/stasel/WebRTC.git',requirement={'kind':'exactVersion','version':'152.0.0'})
core = add('core','XCSwiftPackageProductDependency',package=corePackage,productName='MuralCore')
rtc = add('rtc','XCSwiftPackageProductDependency',package=rtcPackage,productName='WebRTC')
frameworks = add('frameworks','PBXFrameworksBuildPhase',buildActionMask=2147483647,files=[add('coreBuild','PBXBuildFile',productRef=core),add('rtcBuild','PBXBuildFile',productRef=rtc)],runOnlyForDeploymentPostprocessing=0)
sourcePhase = add('sources','PBXSourcesBuildPhase',buildActionMask=2147483647,files=sources,runOnlyForDeploymentPostprocessing=0)
resources = add('resources','PBXResourcesBuildPhase',buildActionMask=2147483647,files=[add('assetsBuild','PBXBuildFile',fileRef=asset),add('noticesBuild','PBXBuildFile',fileRef=notices),add('privacyBuild','PBXBuildFile',fileRef=privacy)],runOnlyForDeploymentPostprocessing=0)
common = {'SDKROOT':'iphoneos','IPHONEOS_DEPLOYMENT_TARGET':'26.1','SWIFT_VERSION':'5.0','CLANG_ENABLE_MODULES':'YES','CLANG_ENABLE_OBJC_ARC':'YES','SWIFT_STRICT_CONCURRENCY':'targeted','"EXCLUDED_ARCHS[sdk=iphonesimulator*]"':'x86_64'}
targetSettings = {'PRODUCT_BUNDLE_IDENTIFIER':'no.william.mural','PRODUCT_NAME':'$(TARGET_NAME)','TARGETED_DEVICE_FAMILY':'1','GENERATE_INFOPLIST_FILE':'NO','INFOPLIST_FILE':'App/Info.plist','CODE_SIGN_STYLE':'Automatic','MARKETING_VERSION':'0.1.0','CURRENT_PROJECT_VERSION':'1','ASSETCATALOG_COMPILER_APPICON_NAME':'AppIcon','ASSETCATALOG_COMPILER_GLOBAL_ACCENT_COLOR_NAME':'AccentColor','LD_RUNPATH_SEARCH_PATHS':['$(inherited)','@executable_path/Frameworks'],'ENABLE_PREVIEWS':'YES','SUPPORTED_PLATFORMS':'iphoneos iphonesimulator'}
targetSettings.update({'CODE_SIGN_ENTITLEMENTS':'$(MURAL_APPLE_ENTITLEMENTS)',
                      'SWIFT_ACTIVE_COMPILATION_CONDITIONS':'$(inherited) $(MURAL_APPLE_SWIFT_FLAGS)'})
def configs(prefix, settings):
    ids=[]
    for name in ['Debug','Release']:
        s=dict(settings)
        if prefix=='project':
            s.update({'SWIFT_OPTIMIZATION_LEVEL':'-Onone' if name=='Debug' else '-O','DEBUG_INFORMATION_FORMAT':'dwarf' if name=='Debug' else 'dwarf-with-dsym'})
            if name=='Debug': s['SWIFT_ACTIVE_COMPILATION_CONDITIONS']='DEBUG'
        fields = {'buildSettings':s,'name':name}
        if prefix in ['target', 'tests']: fields['baseConfigurationReference'] = signing
        ids.append(add(prefix+name,'XCBuildConfiguration',**fields))
    return add(prefix+'configs','XCConfigurationList',buildConfigurations=ids,defaultConfigurationIsVisible=0,defaultConfigurationName='Release')
target=add('target','PBXNativeTarget',buildConfigurationList=configs('target',targetSettings),buildPhases=[sourcePhase,frameworks,resources],buildRules=[],dependencies=[],name='Mural',packageProductDependencies=[core,rtc],productName='Mural',productReference=product,productType='com.apple.product-type.application')
testSources = add('testSources','PBXSourcesBuildPhase',buildActionMask=2147483647,files=[add('testBuild','PBXBuildFile',fileRef=testSource)],runOnlyForDeploymentPostprocessing=0)
proxy = add('testProxy','PBXContainerItemProxy',containerPortal=uid('project'),proxyType=1,remoteGlobalIDString=target,remoteInfo='Mural')
dependency=add('testDependency','PBXTargetDependency',target=target,targetProxy=proxy)
testTarget=add('testTarget','PBXNativeTarget',buildConfigurationList=configs('tests',{'PRODUCT_BUNDLE_IDENTIFIER':'no.william.mural.uitests','PRODUCT_NAME':'$(TARGET_NAME)','GENERATE_INFOPLIST_FILE':'YES','TEST_TARGET_NAME':'Mural','TARGETED_DEVICE_FAMILY':'1','CODE_SIGN_STYLE':'Automatic'}),buildPhases=[testSources],buildRules=[],dependencies=[dependency],name='MuralUITests',productName='MuralUITests',productReference=testProduct,productType='com.apple.product-type.bundle.ui-testing')
project=add('project','PBXProject',attributes={'BuildIndependentTargetsInParallel':'YES','LastUpgradeCheck':'2640','TargetAttributes':{target:{'CreatedOnToolsVersion':'26.4'},testTarget:{'CreatedOnToolsVersion':'26.4','TestTargetID':target}}},buildConfigurationList=configs('project',common),compatibilityVersion='Xcode 14.0',developmentRegion='en',hasScannedForEncodings=0,knownRegions=['en','nb','Base'],mainGroup=group,packageReferences=[corePackage,rtcPackage],productRefGroup=products,projectDirPath='',projectRoot='',targets=[target,testTarget])
folder=root/'Mural.xcodeproj';folder.mkdir(exist_ok=True)
folder.joinpath('project.pbxproj').write_text('// !$*UTF8*$!\n'+encode({'archiveVersion':1,'classes':{},'objectVersion':60,'objects':objects,'rootObject':project})+'\n')
scheme=folder/'xcshareddata'/'xcschemes';scheme.mkdir(parents=True,exist_ok=True)
scheme.joinpath('Mural.xcscheme').write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2640" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="Mural.app" BlueprintName="Mural" ReferencedContainer="container:Mural.xcodeproj"/></BuildActionEntry></BuildActionEntries></BuildAction>
<TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables><TestableReference skipped="NO"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{testTarget}" BuildableName="MuralUITests.xctest" BlueprintName="MuralUITests" ReferencedContainer="container:Mural.xcodeproj"/></TestableReference></Testables></TestAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0"><BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="Mural.app" BlueprintName="Mural" ReferencedContainer="container:Mural.xcodeproj"/></BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"/>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>''')
print('Generated Mural.xcodeproj')
