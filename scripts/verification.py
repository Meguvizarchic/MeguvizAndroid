"""Current-input, non-empty verification receipts. Missing evidence never defaults to PASS."""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import tempfile
import time
import uuid
import unittest
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('android-runtime', 'android-window', 'android-lifecycle')
TEST_MODULES = MODULES + ('android-resources-loader',)
GROUP = 'io.github.jichuo1.meguviz'
VERSION = '1.2.0'
FORBIDDEN = ('com.highcapable.betterandroid', 'com.highcapable.hikage', 'com.highcapable.kavaref',
             'io.github.libxposed', 'LumenCoacervationEngine')
FORBIDDEN_API = FORBIDDEN + ('io.github.jichuo1.meguviz.android.testfixture', 'meguviz.ui.', 'meguviz.ref.', 'meguviz.dex.')
DEVICE_SCENARIOS = ('rebind', 'close', 'theme-in-place', 'pause-resume', 'activity-recreate',
                    'fragment-destroy-view', 'dialog-dismiss', 'list-rebind', 'multi-window',
                    'ime-focus-input', 'rtl-font-density', 'insets-sibling-cutout', 'listener-ownership')
DEVICE_SCENARIO_TESTS = dict(zip(DEVICE_SCENARIOS, (
    'io.github.jichuo1.meguviz.samples.PlatformContractTest#oldRebindResultRejected',
    'io.github.jichuo1.meguviz.samples.PlatformContractTest#closeIsTerminal',
    'io.github.jichuo1.meguviz.samples.PlatformContractTest#themeIdentityDoesNotHideMutation',
    'io.github.jichuo1.meguviz.samples.PlatformContractTest#pauseDoesNotCloseAndResumeRestoresAdmission',
    'io.github.jichuo1.meguviz.samples.DeviceMatrixContractTest#activityRecreationClosesOldScopes',
    'io.github.jichuo1.meguviz.samples.DeviceMatrixContractTest#fragmentViewDestructionReleasesBinding',
    'io.github.jichuo1.meguviz.samples.DeviceMatrixContractTest#dialogDismissClosesOnlyDialogSession',
    'io.github.jichuo1.meguviz.samples.DeviceMatrixContractTest#listRebindRejectsRecycledResults',
    'io.github.jichuo1.meguviz.samples.DeviceMatrixContractTest#twoNativeWindowsKeepIndependentOwnership',
    'io.github.jichuo1.meguviz.samples.DeviceMatrixContractTest#realImeVisibilityFocusAndInput',
    'io.github.jichuo1.meguviz.samples.DeviceMatrixContractTest#localRtlFontDensityPreservesWindowDomain',
    'io.github.jichuo1.meguviz.samples.DeviceMatrixContractTest#nativeInsetsReachSiblingAndReportCutout',
    'io.github.jichuo1.meguviz.samples.DeviceMatrixContractTest#ownedListenersReleaseAndRespectSuccessors',
)))
WINDOW_FIXTURE = 'verification/fixtures/window-compatibility/WindowCompatibilityFixture.kt'
ERRORS = (ValueError, OSError, ET.ParseError, zipfile.BadZipFile, KeyError, TypeError)

def legacy_abi():
    """Loaded by path so it also works when this file itself was loaded by path (the unit tests do)."""
    spec = importlib.util.spec_from_file_location('legacy_abi', Path(__file__).with_name('legacy_abi.py'))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module

def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()

def read_json(path: Path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def write_json(path: Path, value: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

def inside(path: str) -> Path:
    value = (ROOT/path).resolve()
    if not value.is_relative_to(ROOT.resolve()): raise ValueError('Evidence path escapes project')
    return value

def source_hash() -> str:
    files = []
    for directory in (*TEST_MODULES, 'gradle', 'scripts', 'samples', 'testkit', 'shared'):
        base = ROOT/directory
        if base.exists():
            files.extend(p for p in base.rglob('*') if p.is_file() and
                         not any(part in ('build', '.gradle', '__pycache__') for part in p.relative_to(base).parts))
    # API dumps and build reports are outputs, not self-referential source inputs.
    for name in ('build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'TASKS.json',
                 'verification/required-host-tests.json', WINDOW_FIXTURE):
        path = ROOT/name
        if path.is_file(): files.append(path)
    digest = hashlib.sha256()
    for path in sorted(set(files)):
        digest.update(path.relative_to(ROOT).as_posix().encode()); digest.update(b'\0'); digest.update(path.read_bytes())
    return digest.hexdigest()

def begin_host() -> dict:
    value = dict(schema_version=1, run_id=str(uuid.uuid4()), started_unix=time.time(), source_tree_sha256=source_hash())
    write_json(ROOT/'build/verification/host-run.json', value)
    return value

def current_run() -> dict:
    path = ROOT/'build/verification/host-run.json'
    if not path.is_file(): raise ValueError('No current host execution marker')
    value = read_json(path)
    if (value.get('schema_version') != 1 or not value.get('run_id')
            or value.get('source_tree_sha256') != source_hash()
            or not 0 < value.get('started_unix', 0) <= time.time()+1):
        raise ValueError('Stale or invalid host execution marker')
    return value

def stamp(path: Path) -> dict:
    run = current_run()
    value = dict(run, started_unix=time.time())
    write_json(path, value)
    return value

def bound_marker(path: Path) -> dict:
    run = current_run()
    if not path.is_file(): raise ValueError(f'No current execution marker: {path}')
    value = read_json(path)
    if (value.get('run_id') != run['run_id'] or value.get('source_tree_sha256') != run['source_tree_sha256']
            or not run['started_unix'] <= value.get('started_unix', 0) <= time.time()+1):
        raise ValueError(f'Stale execution marker: {path}')
    return value

def fresh(path: Path, marker: dict):
    if not path.is_file() or path.stat().st_size == 0: raise ValueError(f'Missing or empty evidence: {path}')
    if path.stat().st_mtime + .02 < marker['started_unix']: raise ValueError(f'Stale evidence: {path}')

def file_record(path: Path) -> dict:
    if not path.is_file() or path.stat().st_size == 0: raise ValueError(f'Missing or empty artifact: {path}')
    return dict(path=path.relative_to(ROOT).as_posix(), sha256=sha(path), bytes=path.stat().st_size)

def validate_record(record: dict) -> Path:
    path = inside(record['path'])
    if not path.is_file() or record.get('sha256') != sha(path) or record.get('bytes') != path.stat().st_size:
        raise ValueError(f'Artifact changed since execution: {path}')
    return path

def receipt(name: str, status: str, checks: list[dict], **extra) -> dict:
    value = dict(schema_version=1, gate=name, status=status, source_tree_sha256=source_hash(),
                 timestamp_unix=time.time(), checks=checks, **extra)
    write_json(ROOT/'verification/gates'/f'{name}.json', value)
    print(json.dumps(value, ensure_ascii=False, indent=2))
    return value

def expected_cases(module: str) -> set[tuple[str, str]]:
    ledger = read_json(ROOT/'verification/required-host-tests.json')['modules'][module]
    expected = set()
    for classname, suite in ledger.items():
        if not suite['methods']: raise ValueError('Empty required suite')
        suffixes = [f'[{sdk}]' for sdk in suite['sdks'][:-1]] + ['']
        for method in suite['methods']:
            for suffix in suffixes: expected.add((classname, method+suffix))
        if suite['expected_cases'] != len(suite['methods'])*len(suffixes): raise ValueError('Required suite ledger inconsistent')
    if not expected: raise ValueError('No required tests declared')
    return expected

def inspect_tests(module: str) -> dict:
    marker = bound_marker(ROOT/module/'build/verification/testDebugUnitTest.started.json')
    reports = sorted((ROOT/module/'build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    if not reports: raise ValueError(f'{module}: no current test XML reports')
    expected = expected_cases(module); observed = set(); totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for report in reports:
        fresh(report, marker); element = ET.parse(report).getroot()
        if element.tag != 'testsuite': raise ValueError('Unexpected test report root')
        cases = element.findall('testcase')
        actual = dict(tests=len(cases), failures=sum(c.find('failure') is not None for c in cases),
                      errors=sum(c.find('error') is not None for c in cases), skipped=sum(c.find('skipped') is not None for c in cases))
        for key, count in actual.items():
            if int(element.get(key, '0')) != count: raise ValueError(f'Invented or inconsistent test counts: {report}')
            totals[key] += count
        for case in cases:
            identity = (case.get('classname'), case.get('name'))
            if not all(identity) or identity in observed: raise ValueError('Missing or duplicate testcase identity')
            observed.add(identity)
    if observed != expected or any(totals[k] for k in ('failures', 'errors', 'skipped')):
        raise ValueError(f'{module}: incomplete test set: actual={len(observed)}, required={len(expected)}, missing={sorted(expected-observed)}, unexpected={sorted(observed-expected)}, totals={totals}')
    if module == 'android-window':
        original = ROOT/WINDOW_FIXTURE; copied = ROOT/'android-window/build/generated/window-fixture/WindowCompatibilityFixture.kt'
        if not original.is_file() or not copied.is_file() or sha(original) != sha(copied):
            raise ValueError('Window compatibility fixture does not match its bundled source')
    return dict(name=module, status='PASS', expected_tests=len(expected), **totals, reports=[file_record(p) for p in reports])

def inspect_lint(module: str) -> dict:
    marker = bound_marker(ROOT/module/'build/verification/lintReportDebug.started.json')
    report = ROOT/module/'build/reports/lint-results-debug.xml'; fresh(report, marker)
    root = ET.parse(report).getroot()
    if root.tag != 'issues': raise ValueError('Invalid lint report')
    if any(i.get('severity') in ('Error','Fatal') for i in root.findall('issue')): raise ValueError(f'Lint errors {module}')
    return dict(name=f'{module}-lint', status='PASS', report=file_record(report))

def artifact_path(module: str, extension: str, classifier: str = '') -> Path:
    if module not in MODULES or extension not in ('aar','pom','jar','module') or classifier not in ('','sources'):
        raise ValueError('Unknown publication artifact')
    artifact = f'meguviz-{module}'
    artifact_root = ROOT/'build/repo/io/github/jichuo1/meguviz'/artifact
    directory = artifact_root/VERSION
    metadata = artifact_root/'maven-metadata.xml'
    if not metadata.is_file(): raise ValueError(f'Missing publication metadata: {metadata}')
    document = ET.parse(metadata).getroot()
    if document.tag != 'metadata' or any(document.findtext(key) != value for key,value in [('groupId',GROUP),('artifactId',artifact)]):
        raise ValueError('Incorrect publication metadata coordinates')
    versions = [node.text or '' for node in document.findall('./versioning/versions/version')]
    if (not versions or len(versions) != len(set(versions)) or versions.count(VERSION) != 1 or
            any(not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z._-]{0,80}', version) or '..' in version for version in versions)):
        raise ValueError('Invalid publication release metadata versions')
    return directory/f'{artifact}-{VERSION}{("-"+classifier) if classifier else ""}.{extension}'

def inspect_pom(path: Path, module: str):
    root = ET.parse(path).getroot()
    if root.tag.rsplit('}',1)[-1] != 'project': raise ValueError('Invalid POM root')
    for key,value in [('groupId',GROUP),('artifactId',f'meguviz-{module}'),('version',VERSION),('packaging','aar')]:
        if root.findtext('{*}'+key) != value: raise ValueError(f'Incorrect POM {key}: {path}')
    dependencies = [(d.findtext('{*}groupId'),d.findtext('{*}artifactId'),d.findtext('{*}version')) for d in root.findall('./{*}dependencies/{*}dependency')]
    if any(not all(d) for d in dependencies): raise ValueError('Incomplete POM dependency')
    if any('SNAPSHOT' in version.upper() for _,_,version in dependencies): raise ValueError('SNAPSHOT dependency in release POM')
    if any(value in path.read_text() for value in FORBIDDEN): raise ValueError('Forbidden runtime dependency in POM')
    required = {'android-window':'meguviz-android-runtime','android-lifecycle':'meguviz-android-window'}.get(module)
    if required and (GROUP,required,VERSION) not in dependencies: raise ValueError('Missing required transitive runtime dependency')
    if not root.findall('./{*}licenses/{*}license/{*}name'): raise ValueError('Missing publication license')

def publication(require_run: bool = False) -> dict:
    files = []; counts = {}
    for module in MODULES:
        marker = bound_marker(ROOT/module/'build/verification/publishReleasePublicationToCandidateRepository.started.json') if require_run else None
        paths = {(ext, classifier):artifact_path(module,ext,classifier) for ext,classifier in (('aar',''),('pom',''),('jar','sources'),('module',''))}
        for path in paths.values():
            if marker: fresh(path, marker)
            files.append(file_record(path))
        inspect_pom(paths[('pom','')], module)
        with zipfile.ZipFile(paths[('aar','')]) as aar:
            if 'AndroidManifest.xml' not in aar.namelist() or 'classes.jar' not in aar.namelist(): raise ValueError('Invalid AAR structure')
            with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as classes:
                names = [n for n in classes.namelist() if n.endswith('.class')]
                if not names: raise ValueError('Empty AAR classes.jar')
                for name in names:
                    raw = classes.read(name)
                    if any(value.replace('.','/').encode() in raw for value in FORBIDDEN_API): raise ValueError('Forbidden compiled dependency')
                counts[module] = len(names)
        with zipfile.ZipFile(paths[('jar','sources')]) as sources:
            if not any(n.endswith(('.java','.kt')) for n in sources.namelist()): raise ValueError('Empty published sources')
        metadata = read_json(paths[('module','')]); component = metadata.get('component',{})
        if (component.get('group'),component.get('module'),component.get('version')) != (GROUP,f'meguviz-{module}',VERSION): raise ValueError('Incorrect Gradle module coordinates')
        declared = {f.get('sha256') for variant in metadata.get('variants',[]) for f in variant.get('files',[])}
        if not {sha(paths[('aar','')]),sha(paths[('jar','sources')])} <= declared: raise ValueError('Module metadata artifact hashes do not match publication')
        if 'SNAPSHOT' in json.dumps(metadata).upper(): raise ValueError('SNAPSHOT dependency in release Gradle metadata')
        if any(value in json.dumps(metadata) for value in FORBIDDEN): raise ValueError('Forbidden Gradle module dependency')
    return dict(name='local-publication', status='PASS', artifacts=files, bytecode_classes=counts)

def consumer_paths(flavor: str) -> dict[str,Path]:
    base=ROOT/'samples'/flavor/'build/outputs'
    return {'apk':base/f'apk/release/{flavor}-release-unsigned.apk','mapping':base/'mapping/release/mapping.txt',
            'debug_apk':base/f'apk/debug/{flavor}-debug.apk','test_apk':base/f'apk/androidTest/debug/{flavor}-debug-androidTest.apk'}

def pin_consumer_publication() -> dict:
    """Bind exact local candidate checksums explicitly, then require a fresh host verification run."""
    current_run()
    publication(require_run=True)
    target = ROOT/'samples/gradle/verification-metadata.xml'
    with target.open('rb') as stream:
        raw = stream.read(2 * 1024 * 1024 + 1)
    if len(raw) > 2 * 1024 * 1024:
        raise ValueError('Consumer verification metadata is too large')
    original = raw.decode('utf-8')
    if '<!DOCTYPE' in original.upper() or '<!ENTITY' in original.upper():
        raise ValueError('Consumer verification metadata must not declare DTDs or entities')
    # Do not parse and reserialize XML: all third-party records and line endings stay byte-for-byte.
    if re.findall(r'<verify-metadata>\s*(true|false)\s*</verify-metadata>', original) != ['true']:
        raise ValueError('Consumer metadata verification must remain enabled')
    updated = original
    hashes = {}
    for module in MODULES:
        artifact = f'meguviz-{module}'
        aar = artifact_path(module, 'aar')
        built = ROOT/module/'build/outputs/aar'/f'{module}-release.aar'
        if not built.is_file() or sha(built) != sha(aar):
            raise ValueError(f'Published candidate differs from current release output: {module}')
        pattern = (r'<component group="' + re.escape(GROUP) + r'" name="' + re.escape(artifact)
                   + r'" version="' + re.escape(VERSION) + r'">.*?</component>')
        matches = list(re.finditer(pattern, updated, re.DOTALL))
        if len(matches) != 1:
            raise ValueError(f'Exactly one pinned candidate component is required: {artifact}')
        match = matches[0]
        block = match.group(0)
        expected = {f'{artifact}-{VERSION}.{extension}' for extension in ('aar', 'module', 'pom')}
        declarations = re.findall(r'<artifact name="([^"]+)">', block)
        if len(declarations) != 3 or set(declarations) != expected or len(re.findall(r'<artifact\b', block)) != 3:
            raise ValueError(f'Candidate artifact set is not exact: {artifact}')
        for extension in ('aar', 'module', 'pom'):
            name = f'{artifact}-{VERSION}.{extension}'
            entry_pattern = r'<artifact name="' + re.escape(name) + r'">(.*?)</artifact>'
            entries = list(re.finditer(entry_pattern, block, re.DOTALL))
            if len(entries) != 1 or len(re.findall(r'<sha256\b', entries[0].group(1))) != 1:
                raise ValueError(f'Exactly one candidate checksum is required: {name}')
            digest = sha(artifact_path(module, extension))
            entry = r'(<artifact name="' + re.escape(name) + r'">\s*<sha256 value=")[0-9a-fA-F]{64}("[^>]*?/>)'
            block, count = re.subn(entry, lambda value: value.group(1) + digest + value.group(2), block)
            if count != 1:
                raise ValueError(f'Candidate checksum declaration is ambiguous: {name}')
            hashes[name] = digest
        updated = updated[:match.start()] + block + updated[match.end():]
    current_run()
    if target.read_bytes() != raw:
        raise ValueError('Consumer verification metadata changed while preparing pins')
    if updated != original:
        staged = None
        try:
            with tempfile.NamedTemporaryFile(dir=target.parent, prefix='.candidate-pins-', delete=False) as stream:
                staged = Path(stream.name)
                stream.write(updated.encode('utf-8'))
            os.replace(staged, target)
        finally:
            if staged is not None and staged.exists():
                staged.unlink()
    return dict(scope='Exact local candidates only; third-party verification unchanged', checksums=hashes,
                next_step='Start a fresh verifyHost run after this explicit preparation step')


def finish_consumer() -> dict:
    marker = bound_marker(ROOT/'build/verification/consumer.started.json')
    consumed = ROOT/'samples/build/consumed-artifacts.json'; fresh(consumed,marker)
    files = {flavor:{name:file_record(path) for name,path in consumer_paths(flavor).items()} for flavor in ('plain','embedded')}
    value = dict(marker, completed_unix=time.time(), consumption=file_record(consumed), flavors=files)
    write_json(ROOT/'build/verification/consumer.json',value)
    return value

def inspect_consumer() -> list[dict]:
    marker = bound_marker(ROOT/'build/verification/consumer.started.json')
    data = bound_marker(ROOT/'build/verification/consumer.json')
    if not marker['started_unix'] <= data.get('completed_unix',0) <= time.time()+1: raise ValueError('Consumer build did not finish in this run')
    consumed = read_json(validate_record(data['consumption'])); checks=[]
    for flavor, required in [('plain',set(MODULES)),('embedded',{'android-runtime','android-window'})]:
        rows = consumed.get(flavor)
        if not isinstance(rows,list) or not rows: raise ValueError(f'Missing {flavor} resolved dependencies')
        if any(any(bad in json.dumps(row) for bad in FORBIDDEN) for row in rows): raise ValueError('Forbidden consumer dependency')
        actual={};
        for row in rows:
            if row.get('group') != GROUP: continue
            if row.get('component_kind') != 'module' or row.get('version') != VERSION: raise ValueError('Consumer used project/fallback dependency')
            name=row.get('name'); module=name.removeprefix('meguviz-') if isinstance(name,str) else None
            if module in actual: raise ValueError('Duplicate consumed artifact')
            artifact=Path(row['file'])
            if not artifact.is_file() or row.get('sha256') != sha(artifact): raise ValueError('Resolved artifact content changed')
            actual[module]=row['sha256']
        expected={module:sha(artifact_path(module,'aar')) for module in required}
        if actual != expected: raise ValueError(f'{flavor} consumer is not using this publication')
        records=data['flavors'][flavor]
        for name,path in consumer_paths(flavor).items():
            if validate_record(records[name]).resolve() != path.resolve(): raise ValueError('Consumer artifact path mismatch')
        for key in ('apk','debug_apk','test_apk'):
            with zipfile.ZipFile(consumer_paths(flavor)[key]) as apk:
                names=apk.namelist()
                if 'AndroidManifest.xml' not in names or not any(re.fullmatch(r'classes\d*\.dex', n) for n in names): raise ValueError('APK lacks manifest or DEX')
        checks.append(dict(name=f'{flavor}-R8',status='PASS',apk_path=records['apk']['path'],apk_sha256=records['apk']['sha256'],mapping_path=records['mapping']['path'],mapping_sha256=records['mapping']['sha256']))
        checks.append(dict(name=f'{flavor}-device-artifacts',status='PASS',apk_path=records['debug_apk']['path'],apk_sha256=records['debug_apk']['sha256'],test_apk_path=records['test_apk']['path'],test_apk_sha256=records['test_apk']['sha256']))
    return checks

def inspect_api() -> dict:
    manifest=bound_marker(ROOT/'build/verification/api.json'); checks=[]
    for module in MODULES:
        item=manifest['modules'][module]
        if item.get('aar_sha256') != sha(artifact_path(module,'aar')) or item.get('class_count',0) <= 0: raise ValueError('API dump uses stale or empty AAR')
        path=validate_record(item['dump'])
        if path != (ROOT/f'api/{module}.txt').resolve(): raise ValueError('Unexpected API dump path')
        text=path.read_text()
        forbidden=FORBIDDEN_API + (('io.github.jichuo1.meguviz.android.window.','io.github.jichuo1.meguviz.android.lifecycle.') if module=='android-runtime' else ())
        if not re.search(r'public (?:final |abstract )?(?:class|interface)',text) or any(value in text for value in forbidden): raise ValueError('Empty or forbidden public API')
        lost=legacy_abi().check_dump(module,text,ROOT)
        if lost: raise ValueError('Public API no longer contains published members: '+'; '.join(lost[:5]))
        checks.append(dict(module=module,**item))
    return dict(name='public-api-boundary',status='PASS',modules=checks)

def gate_negatives() -> int:
    run=current_run(); output=io.StringIO()
    suite=unittest.defaultTestLoader.discover(str(ROOT/'scripts'),pattern='test_verification.py')
    result=unittest.TextTestRunner(stream=output,verbosity=2).run(suite)
    log=ROOT/'build/verification/gate-negatives.log'; log.parent.mkdir(parents=True,exist_ok=True)
    log.write_text(output.getvalue(),encoding='utf-8'); print(output.getvalue())
    expected=read_json(ROOT/'verification/required-host-tests.json')['gate_negative_tests']
    passed=result.wasSuccessful() and result.testsRun==expected and not result.skipped
    current_run()
    write_json(ROOT/'build/verification/gate-negatives.json',dict(run,started_unix=time.time(),status='PASS' if passed else 'FAIL',
               tests=result.testsRun,expected_tests=expected,failures=len(result.failures),errors=len(result.errors),skipped=len(result.skipped),log=file_record(log)))
    return 0 if passed else 1

def inspect_negatives() -> dict:
    value=bound_marker(ROOT/'build/verification/gate-negatives.json')
    expected=read_json(ROOT/'verification/required-host-tests.json')['gate_negative_tests']
    validate_record(value['log'])
    if value.get('status')!='PASS' or value.get('tests')!=expected or any(value.get(k)!=0 for k in ('failures','errors','skipped')):
        raise ValueError('Gate rejection tests did not execute completely')
    return dict(name='gate-negatives',status='PASS',expected_tests=expected,tests=expected,log=value['log'])

def host_checks() -> list[dict]:
    current_run()
    checks=[inspect_tests(module) for module in TEST_MODULES]
    checks.extend(inspect_lint(module) for module in TEST_MODULES)
    checks.append(publication(require_run=True)); checks.extend(inspect_consumer()); checks.append(inspect_api()); checks.append(inspect_negatives())
    return checks

def host() -> int:
    checks=[]
    try:
        checks=host_checks(); run=current_run()
        receipt('host','PASS',checks,run_id=run['run_id'],expected_test_count=sum(c.get('expected_tests',0) for c in checks),test_count=sum(c.get('tests',0) for c in checks))
        return 0
    except ERRORS as error:
        receipt('host','FAIL',checks,reason=str(error)); return 1

def parse_instrumentation(text: str) -> dict:
    block={}; started=set(); terminal={}; diagnostics=[]; declared=set()
    for line in text.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            value=line[len('INSTRUMENTATION_STATUS: '):]
            if '=' in value:
                key,value=value.split('=',1); block[key]=value.strip()
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code=int(line.split(':',1)[1]); identity=block.get('class','')+'#'+block.get('test','')
            if 'numtests' in block: declared.add(int(block['numtests']))
            if code==2:
                diagnostics.append({k:v for k,v in block.items() if k.startswith('mvx_')})
            elif code==1:
                if identity=='#' or identity in started: raise ValueError('Missing or duplicate instrumentation start')
                started.add(identity)
            elif code in (0,-1,-2,-3,-4):
                if identity=='#' or identity in terminal: raise ValueError('Missing or duplicate instrumentation terminal')
                terminal[identity]=code
            block={}
    if len(declared)>1: raise ValueError('Inconsistent instrumentation test count')
    return dict(tests=next(iter(declared),0),started=sorted(started),successful=sorted(k for k,v in terminal.items() if v==0),
                failures=sorted(k for k,v in terminal.items() if v==-2),errors=sorted(k for k,v in terminal.items() if v==-1),
                skipped=sorted(k for k,v in terminal.items() if v in (-3,-4)),diagnostics=diagnostics,
                finished_successfully='INSTRUMENTATION_CODE: -1' in text and 'FAILURES!!!' not in text)

def validate_device_identity(data: dict, api: int, target: int, host_data: dict) -> str:
    if data.get('api') != api or data.get('target') != target: raise ValueError('Wrong device API/target cell')
    if data.get('source_tree_sha256') != source_hash() or host_data.get('source_tree_sha256') != source_hash() or host_data.get('status') != 'PASS': raise ValueError('Stale source/host receipt')
    flavor='plain' if target==37 else 'embedded'
    record=next((c for c in host_data.get('checks',[]) if c.get('name')==f'{flavor}-device-artifacts'),None)
    if not record: raise ValueError('No current host-built APK evidence')
    for field,hash_field in [('apk','apk_sha256'),('test_apk','test_apk_sha256')]:
        path=inside(data.get(field,'__missing__'))
        if data.get(field)!=record.get(field+'_path') or not path.is_file() or sha(path)!=record.get(hash_field) or data.get(hash_field)!=record.get(hash_field): raise ValueError('Old or mismatched device APK')
    if data.get('installed_apk_sha256')!=record['apk_sha256'] or data.get('installed_test_apk_sha256')!=record['test_apk_sha256']: raise ValueError('Installed APK identity unproven')
    raw=inside(data.get('raw_log','__missing__'))
    if not raw.is_file() or data.get('raw_sha256')!=sha(raw): raise ValueError('Missing or changed device log')
    if not data.get('fingerprint') or not data.get('serial'): raise ValueError('Missing device identity')
    return raw.read_text()

def validate_device(data: dict, api: int, target: int, host_data: dict) -> None:
    text=validate_device_identity(data,api,target,host_data)
    matches=re.findall(r'OK \((\d+) tests?\)',text)
    if len(matches)!=1 or int(matches[0])!=data.get('tests') or data.get('tests',0)<len(DEVICE_SCENARIOS): raise ValueError('Empty or incomplete instrumentation test count')
    if 'INSTRUMENTATION_CODE: -1' not in text or 'FAILURES!!!' in text or any(data.get(k)!=0 for k in ('failures','errors','skipped')): raise ValueError('Failed or skipped instrumentation')
    if not data.get('fingerprint') or not data.get('serial') or data.get('status')!='PASS': raise ValueError('Device run is not full acceptance')
    if not all(data.get('scenarios',{}).get(s)=='PASS' for s in DEVICE_SCENARIOS): raise ValueError('Required device scenarios not executed')
    parsed=parse_instrumentation(text); successful=set(parsed['successful'])
    if parsed['tests']!=data['tests'] or parsed['failures'] or parsed['errors'] or parsed['skipped'] or len(successful)!=data['tests']:
        raise ValueError('Raw instrumentation outcomes disagree with receipt')
    for scenario in DEVICE_SCENARIOS:
        evidence=data.get('scenario_tests',{}).get(scenario,[])
        if not evidence or not set(evidence)<=successful: raise ValueError('Scenario labels lack raw successful test identities')
        if evidence != [DEVICE_SCENARIO_TESTS[scenario]]: raise ValueError('Scenario is not bound to its required contract method')

def classify_device(data: dict, api: int, target: int, host_data: dict) -> dict:
    if data.get('api') != api or data.get('target') != target: raise ValueError('Wrong device API/target cell')
    if data.get('source_tree_sha256') != source_hash():
        return dict(api=api,target=target,status='NOT_RUN',reason='Only a previous-source receipt exists')
    if data.get('status')=='NOT_RUN':
        return dict(api=api,target=target,status='NOT_RUN',reason=data.get('reason','Device test not run'))
    if data.get('status')=='BLOCKED':
        text=validate_device_identity(data,api,target,host_data)
        parsed=parse_instrumentation(text); expected=set(DEVICE_SCENARIO_TESTS.values())
        terminal=set(parsed['successful'])|set(parsed['skipped'])|set(parsed['failures'])|set(parsed['errors'])
        if (not parsed['finished_successfully'] or parsed['tests']!=len(expected) or data.get('tests')!=parsed['tests']
                or set(parsed['started'])!=expected or terminal!=expected
                or parsed['failures'] or parsed['errors'] or not parsed['skipped']
                or any(data.get(k)!=0 for k in ('failures','errors')) or len(parsed['skipped'])!=data.get('skipped')):
            raise ValueError('Blocked receipt lacks matching raw skipped outcomes')
        return dict(api=api,target=target,status='BLOCKED',tests=parsed['tests'],
                    successful=len(parsed['successful']),skipped=len(parsed['skipped']),
                    reason=data.get('reason','Required device scenarios skipped'))
    validate_device(data,api,target,host_data)
    return dict(api=api,target=target,status='PASS')

def device() -> int:
    checks=[]; host_path=ROOT/'verification/gates/host.json'
    try: host_data=read_json(host_path) if host_path.is_file() else {}
    except ERRORS: host_data={}
    for api,target in ((27,29),(29,29),(30,29),(30,37),(37,29),(37,37)):
        path=ROOT/f'verification/device-results/api-{api}-target-{target}.json'
        if not path.is_file(): checks.append(dict(api=api,target=target,status='NOT_RUN',reason='No device execution receipt')); continue
        try: checks.append(classify_device(read_json(path),api,target,host_data))
        except ERRORS as error: checks.append(dict(api=api,target=target,status='FAIL',reason=str(error)))
    status='PASS' if all(c['status']=='PASS' for c in checks) else 'BLOCKED'
    receipt('device',status,checks,reason=None if status=='PASS' else 'Complete current API/target device matrix required')
    return 0 if status=='PASS' else 2

RELEASE_PROFILES = {
    'full_replacement': ('host','device','performance','bridge','integration-exit','upgrade'),
    'core-candidate': ('host','device','performance-core'),
    'bridge-profile': ('host','bridge'),
    'integration-exit-profile': ('host','device','performance','bridge','integration-exit','upgrade'),
}

def verify_gate(name: str) -> dict:
    path=ROOT/f'verification/gates/{name}.json'; valid=False
    try:
        data=read_json(path) if path.exists() else {}
        valid=(data.get('gate')==name and data.get('status')=='PASS' and data.get('source_tree_sha256')==source_hash()
               and bool(data.get('checks')) and all(c.get('status')=='PASS' for c in data['checks']))
        if valid and name in ('performance','performance-core','bridge','integration-exit','upgrade'):
            evidence=data.get('evidence',[])
            valid=bool(evidence)
            for record in evidence: validate_record(record)
        if valid and name=='host': host_checks()
        if valid and name=='device':
            host_data=read_json(ROOT/'verification/gates/host.json')
            for api,target in ((27,29),(29,29),(30,29),(30,37),(37,29),(37,37)):
                validate_device(read_json(ROOT/f'verification/device-results/api-{api}-target-{target}.json'),api,target,host_data)
    except ERRORS: valid=False
    return dict(name=name,status='PASS' if valid else 'BLOCKED')

def release(profile: str = 'full_replacement') -> int:
    if profile not in RELEASE_PROFILES: raise ValueError('Unknown release profile')
    checks=[verify_gate(name) for name in RELEASE_PROFILES[profile]]
    status='PASS' if all(c['status']=='PASS' for c in checks) else 'BLOCKED'
    gate='release' if profile=='full_replacement' else 'release-'+profile
    receipt(gate,status,checks,execution_target='full_replacement',selected_profile=profile)
    return 0 if status=='PASS' else 2

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('gate',choices=('host','device','release','hash','publication','start-host','stamp','consumer-start','consumer-end','consumer-pin','negatives'))
    parser.add_argument('--module',choices=TEST_MODULES); parser.add_argument('--task')
    parser.add_argument('--profile',choices=tuple(RELEASE_PROFILES),default='full_replacement'); args=parser.parse_args()
    if args.gate=='hash': print(source_hash())
    elif args.gate=='publication': print(json.dumps(publication(),indent=2))
    elif args.gate=='start-host': print(json.dumps(begin_host()))
    elif args.gate=='stamp':
        if not args.module or args.task not in ('testDebugUnitTest','lintReportDebug','publishReleasePublicationToCandidateRepository'): parser.error('Known module/task required')
        stamp(ROOT/args.module/f'build/verification/{args.task}.started.json')
    elif args.gate=='consumer-start': stamp(ROOT/'build/verification/consumer.started.json')
    elif args.gate=='consumer-end': finish_consumer()
    elif args.gate=='consumer-pin': print(json.dumps(pin_consumer_publication(), indent=2))
    elif args.gate=='negatives': raise SystemExit(gate_negatives())
    elif args.gate=='release': raise SystemExit(release(args.profile))
    else: raise SystemExit(globals()[args.gate]())
