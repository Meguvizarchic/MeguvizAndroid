"""Synthetic evidence fixtures test rejection logic; they are not library/device execution results."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile

spec=importlib.util.spec_from_file_location('verification',Path(__file__).with_name('verification.py'))
gate=importlib.util.module_from_spec(spec); spec.loader.exec_module(gate)

class GateRejectionTest(unittest.TestCase):
    def setUp(self):
        self.original=gate.ROOT
        scratch=self.original/'build/gate-tests'; scratch.mkdir(parents=True,exist_ok=True)
        self.directory=tempfile.TemporaryDirectory(dir=scratch); gate.ROOT=Path(self.directory.name)
        self.put('build.gradle.kts','// fixture input')
        self.put(gate.WINDOW_FIXTURE,'// real fixture input')
        self.put('android-window/build/generated/window-fixture/WindowCompatibilityFixture.kt','// real fixture input')
        self.json('verification/required-host-tests.json',{'schema_version':1,'gate_negative_tests':36,'modules':{m:{'example.ContractTest':{'methods':['required'],'sdks':[],'expected_cases':1}} for m in gate.TEST_MODULES}})
        gate.begin_host()
    def tearDown(self):
        gate.ROOT=self.original; self.directory.cleanup()
    def put(self,path,text):
        target=gate.ROOT/path; target.parent.mkdir(parents=True,exist_ok=True); target.write_text(text,encoding='utf-8'); return target
    def json(self,path,value): return self.put(path,json.dumps(value))
    def zip(self,path,files):
        target=gate.ROOT/path; target.parent.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(target,'w') as archive:
            for name,value in files.items(): archive.writestr(name,value)
        return target
    def marker(self,module,task): return gate.stamp(gate.ROOT/module/f'build/verification/{task}.started.json')
    def reports(self,module='android-runtime',body=None):
        self.marker(module,'testDebugUnitTest')
        if body is None: body='<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="example.ContractTest" name="required"/></testsuite>'
        return self.put(f'{module}/build/test-results/testDebugUnitTest/TEST-contract.xml',body)
    def publications(self):
        for module in gate.API_MODULES:
            self.marker(module,'publishReleasePublicationToCandidateRepository')
            artifact='meguviz-'+module; artifact_root=f'build/repo/io/github/jichuo1/meguviz/{artifact}'
            base=f'{artifact_root}/{gate.VERSION}'; value=gate.VERSION
            self.put(artifact_root+'/maven-metadata.xml',f'<metadata><groupId>{gate.GROUP}</groupId><artifactId>{artifact}</artifactId><versioning><versions><version>{gate.VERSION}</version></versions></versioning></metadata>')
            classes=io.BytesIO()
            with zipfile.ZipFile(classes,'w') as jar: jar.writestr('example/Contract.class',b'\xca\xfe\xba\xbe\x00\x00\x00\x3dfixture')
            self.zip(f'{base}/{artifact}-{value}.aar',{'AndroidManifest.xml':'manifest','classes.jar':classes.getvalue()})
            self.zip(f'{base}/{artifact}-{value}-sources.jar',{'Contract.java':'class Contract {}'})
            dependency={'android-window':'meguviz-android-runtime','android-lifecycle':'meguviz-android-window'}.get(module)
            dependencies=f'<dependencies><dependency><groupId>{gate.GROUP}</groupId><artifactId>{dependency}</artifactId><version>{gate.VERSION}</version><scope>compile</scope></dependency></dependencies>' if dependency else ''
            self.put(f'{base}/{artifact}-{value}.pom',f'<project><groupId>{gate.GROUP}</groupId><artifactId>{artifact}</artifactId><version>{gate.VERSION}</version><packaging>aar</packaging><licenses><license><name>Apache</name></license></licenses>{dependencies}</project>')
            files=[{'sha256':gate.sha(gate.artifact_path(module,'aar'))},{'sha256':gate.sha(gate.artifact_path(module,'jar','sources'))}]
            self.json(f'{base}/{artifact}-{value}.module',{'component':{'group':gate.GROUP,'module':artifact,'version':gate.VERSION},'variants':[{'files':files}]})
    def consumer(self):
        gate.stamp(gate.ROOT/'build/verification/consumer.started.json'); rows={}
        for flavor,modules in [('plain',gate.MODULES),('embedded',gate.MODULES[:2])]:
            rows[flavor]=[dict(group=gate.GROUP,name='meguviz-'+module,version=gate.VERSION,component_kind='module',file=str(gate.artifact_path(module,'aar')),sha256=gate.sha(gate.artifact_path(module,'aar'))) for module in modules]
            for name,path in gate.consumer_paths(flavor).items():
                if name=='mapping': self.put(path.relative_to(gate.ROOT),'# R8 fixture mapping')
                else: self.zip(path.relative_to(gate.ROOT),{'AndroidManifest.xml':'manifest','classes.dex':b'dex\n035\0fixture'})
        self.json('samples/build/consumed-artifacts.json',rows); gate.finish_consumer()
    def api(self):
        modules={}
        for module in gate.API_MODULES:
            contract='public final class example.Contract {\n  public void call();\n  public int count();\n}\n'
            path=self.put('api/'+module+'.txt',contract)
            self.put('api/legacy/1.0.0/'+module+'.txt','// published release fixture\n\n'+contract)
            modules[module]={'aar_sha256':gate.sha(gate.artifact_path(module,'aar')),'class_count':1,'dump':gate.file_record(path)}
        self.json('build/verification/api.json',dict(gate.current_run(),modules=modules))
    def lint(self,module='android-runtime'):
        self.marker(module,'lintReportDebug'); return self.put(module+'/build/reports/lint-results-debug.xml','<issues format="6"/>')
    def consumer_metadata(self, duplicate=False):
        blocks = []
        for module in gate.MODULES:
            artifact = 'meguviz-' + module
            entries = ''.join(f'<artifact name="{artifact}-{gate.VERSION}.{extension}"><sha256 value="{"0"*64}" origin="fixture"/></artifact>'
                              for extension in ('aar','module','pom'))
            blocks.append(f'<component group="{gate.GROUP}" name="{artifact}" version="{gate.VERSION}">{entries}</component>')
        if duplicate: blocks.append(blocks[0])
        third_party = '<component group="example.external" name="dependency" version="1"><artifact name="dependency.jar"><sha256 value="' + 'a'*64 + '"/></artifact></component>'
        return self.put('samples/gradle/verification-metadata.xml',
            '<verification-metadata xmlns="https://schema.gradle.org/dependency-verification"><configuration><verify-metadata>true</verify-metadata></configuration><components>'
            + third_party + ''.join(blocks) + '</components></verification-metadata>')
    def candidate_release_outputs(self):
        for module in gate.MODULES:
            target = gate.ROOT/module/'build/outputs/aar'/f'{module}-release.aar'
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(gate.artifact_path(module,'aar').read_bytes())
    def test_consumer_pin_preserves_third_party_and_requires_fresh_host(self):
        target=self.consumer_metadata(); gate.begin_host()
        self.publications(); self.candidate_release_outputs(); before=target.read_bytes()
        result=gate.pin_consumer_publication(); after=target.read_bytes()
        self.assertEqual(9,len(result['checksums']))
        own=r'<component group="'+gate.GROUP+r'".*?</component>'
        self.assertEqual(gate.re.sub(own,'',before.decode()),gate.re.sub(own,'',after.decode()))
        for digest in result['checksums'].values(): self.assertIn(digest.encode(),after)
        with self.assertRaisesRegex(ValueError,'Stale or invalid host'): gate.current_run()
    def test_consumer_pin_rejects_mismatched_release_without_writing(self):
        target=self.consumer_metadata(); gate.begin_host()
        self.publications(); self.candidate_release_outputs(); before=target.read_bytes()
        self.put('android-runtime/build/outputs/aar/android-runtime-release.aar','foreign bytes')
        with self.assertRaisesRegex(ValueError,'differs from current release'): gate.pin_consumer_publication()
        self.assertEqual(before,target.read_bytes())
    def test_consumer_pin_rejects_ambiguous_components_without_writing(self):
        target=self.consumer_metadata(duplicate=True); gate.begin_host()
        self.publications(); self.candidate_release_outputs(); before=target.read_bytes()
        with self.assertRaisesRegex(ValueError,'Exactly one pinned candidate'): gate.pin_consumer_publication()
        self.assertEqual(before,target.read_bytes())
    def test_complete_structural_host_evidence_is_accepted(self):
        for module in gate.TEST_MODULES: self.reports(module); self.lint(module)
        self.publications(); self.consumer(); self.api()
        log=self.put('build/verification/gate-negatives.log','synthetic successful rejection-test evidence')
        self.json('build/verification/gate-negatives.json',dict(gate.current_run(),status='PASS',tests=36,failures=0,errors=0,skipped=0,log=gate.file_record(log)))
        checks=gate.host_checks(); self.assertEqual(40,sum(c.get('tests',0) for c in checks))
        self.assertEqual(2,len([c for c in checks if c['name'].endswith('-device-artifacts')]))
    def test_zero_tests_rejected(self):
        self.reports(body='<testsuite tests="0"/>')
        with self.assertRaisesRegex(ValueError,'incomplete test set'): gate.inspect_tests('android-runtime')
    def test_test_count_without_real_cases_rejected(self):
        self.reports(body='<testsuite tests="20"/>')
        with self.assertRaisesRegex(ValueError,'inconsistent test counts'): gate.inspect_tests('android-runtime')
    def test_missing_tests_rejected(self):
        with self.assertRaisesRegex(ValueError,'No current execution marker'): gate.inspect_tests('android-runtime')
    def test_stale_report_rejected(self):
        report=self.reports(); os.utime(report,(1,1))
        with self.assertRaisesRegex(ValueError,'Stale evidence'): gate.inspect_tests('android-runtime')
    def test_filtered_wrong_test_rejected_even_when_count_matches(self):
        self.reports(body='<testsuite tests="1"><testcase classname="example.ContractTest" name="other"/></testsuite>')
        with self.assertRaisesRegex(ValueError,'incomplete test set'): gate.inspect_tests('android-runtime')
    def test_missing_sdk_variant_rejected(self):
        self.json('verification/required-host-tests.json',{'modules':{'android-runtime':{'example.ContractTest':{'methods':['required'],'sdks':[27,29,30,37],'expected_cases':4}}}})
        gate.begin_host(); self.reports()
        with self.assertRaisesRegex(ValueError,'required=4'): gate.inspect_tests('android-runtime')
    def test_skipped_test_rejected(self):
        self.reports(body='<testsuite tests="1" skipped="1"><testcase classname="example.ContractTest" name="required"><skipped/></testcase></testsuite>')
        with self.assertRaisesRegex(ValueError,'incomplete test set'): gate.inspect_tests('android-runtime')
    def test_previous_run_marker_rejected(self):
        self.reports(); gate.begin_host()
        with self.assertRaisesRegex(ValueError,'Stale execution marker'): gate.inspect_tests('android-runtime')
    def test_changed_source_rejected(self):
        self.reports(); self.put('build.gradle.kts','// changed input')
        with self.assertRaisesRegex(ValueError,'Stale or invalid host'): gate.inspect_tests('android-runtime')
    def test_optional_resource_module_changes_source_hash(self):
        before=gate.source_hash(); self.put('android-resources-loader/src/main/java/Loader.java','// changed optional module')
        self.assertNotEqual(before,gate.source_hash())
    def test_real_integration_fixture_changes_source_hash(self):
        before=gate.source_hash(); self.put(gate.WINDOW_FIXTURE,'// changed real delegate')
        self.assertNotEqual(before,gate.source_hash())
    def test_old_copied_integration_fixture_rejected(self):
        self.put(gate.WINDOW_FIXTURE,'// changed real delegate'); gate.begin_host(); self.reports('android-window')
        with self.assertRaisesRegex(ValueError,'Window compatibility fixture'): gate.inspect_tests('android-window')
    def test_api_output_is_not_self_referential_source_input(self):
        before=gate.source_hash(); self.put('api/android-runtime.txt','generated output')
        self.assertEqual(before,gate.source_hash())
    def test_old_lint_report_rejected(self):
        report=self.lint(); os.utime(report,(1,1))
        with self.assertRaisesRegex(ValueError,'Stale evidence'): gate.inspect_lint('android-runtime')
    def test_lint_error_rejected(self):
        self.lint(); self.put('android-runtime/build/reports/lint-results-debug.xml','<issues><issue severity="Error"/></issues>')
        with self.assertRaisesRegex(ValueError,'Lint errors'): gate.inspect_lint('android-runtime')
    def test_missing_publication_rejected(self):
        with self.assertRaisesRegex(ValueError,'Missing publication'): gate.publication()
    def test_wrong_pom_coordinates_rejected(self):
        self.publications(); path=gate.artifact_path('android-runtime','pom'); path.write_text(path.read_text().replace(gate.GROUP,'wrong.group'))
        with self.assertRaisesRegex(ValueError,'Incorrect POM'): gate.publication()
    def test_broken_pom_xml_rejected(self):
        self.publications(); gate.artifact_path('android-runtime','pom').write_text('<project>')
        with self.assertRaises(gate.ET.ParseError): gate.publication()
    def test_missing_transitive_dependency_rejected(self):
        self.publications(); path=gate.artifact_path('android-window','pom'); path.write_text(path.read_text().replace('meguviz-android-runtime','not-the-runtime'))
        with self.assertRaisesRegex(ValueError,'Missing required transitive'): gate.publication()
    def test_forbidden_pom_dependency_rejected(self):
        self.publications(); path=gate.artifact_path('android-runtime','pom'); path.write_text(path.read_text().replace('</project>','<dependencies><dependency><groupId>com.highcapable.betterandroid</groupId><artifactId>x</artifactId><version>1</version></dependency></dependencies></project>'))
        with self.assertRaisesRegex(ValueError,'Forbidden runtime'): gate.publication()
    def test_snapshot_pom_dependency_rejected(self):
        self.publications(); path=gate.artifact_path('android-window','pom'); path.write_text(path.read_text().replace(f'<version>{gate.VERSION}</version><scope>compile</scope>','<version>0.1.0-SNAPSHOT</version><scope>compile</scope>'))
        with self.assertRaisesRegex(ValueError,'SNAPSHOT dependency in release POM'): gate.publication()
    def test_snapshot_gradle_metadata_dependency_rejected(self):
        self.publications(); path=gate.artifact_path('android-window','module'); data=gate.read_json(path); data['variants'][0]['dependencies']=[{'group':gate.GROUP,'module':'meguviz-android-runtime','version':{'requires':'0.1.0-SNAPSHOT'}}]; gate.write_json(path,data)
        with self.assertRaisesRegex(ValueError,'SNAPSHOT dependency in release Gradle metadata'): gate.publication()
    def test_empty_classes_jar_rejected(self):
        self.publications(); empty=io.BytesIO()
        with zipfile.ZipFile(empty,'w'): pass
        self.zip(gate.artifact_path('android-runtime','aar').relative_to(gate.ROOT),{'AndroidManifest.xml':'x','classes.jar':empty.getvalue()})
        with self.assertRaisesRegex(ValueError,'Empty AAR'): gate.publication()
    def test_gradle_metadata_hash_mismatch_rejected(self):
        self.publications(); path=gate.artifact_path('android-runtime','module'); data=gate.read_json(path); data['variants'][0]['files']=[]; gate.write_json(path,data)
        with self.assertRaisesRegex(ValueError,'hashes do not match'): gate.publication()
    def test_release_metadata_path_traversal_rejected(self):
        self.publications(); path=gate.artifact_path('android-runtime','pom').parent.parent/'maven-metadata.xml'; path.write_text(path.read_text().replace(gate.VERSION,'../../secret'))
        with self.assertRaisesRegex(ValueError,'Invalid publication release metadata versions'): gate.artifact_path('android-runtime','aar')
    def test_release_metadata_wrong_component_rejected(self):
        self.publications(); path=gate.artifact_path('android-runtime','pom').parent.parent/'maven-metadata.xml'; path.write_text(path.read_text().replace(gate.GROUP,'wrong.group'))
        with self.assertRaisesRegex(ValueError,'Incorrect publication metadata coordinates'): gate.artifact_path('android-runtime','aar')
    def test_release_metadata_duplicate_version_rejected(self):
        self.publications(); path=gate.artifact_path('android-runtime','pom').parent.parent/'maven-metadata.xml'; path.write_text(path.read_text().replace('</versions>',f'<version>{gate.VERSION}</version></versions>'))
        with self.assertRaisesRegex(ValueError,'Invalid publication release metadata versions'): gate.artifact_path('android-runtime','aar')
    def test_old_api_dump_rejected(self):
        self.publications(); self.api(); path=gate.ROOT/'build/verification/api.json'; data=gate.read_json(path); data['modules']['android-runtime']['aar_sha256']='0'*64; gate.write_json(path,data)
        with self.assertRaisesRegex(ValueError,'stale or empty AAR'): gate.inspect_api()
    def test_api_that_lost_a_published_member_rejected(self):
        self.publications(); self.api()
        dump=gate.ROOT/'api/android-runtime.txt'; dump.write_text(dump.read_text().replace('  public int count();\n',''),encoding='utf-8')
        data=gate.read_json(gate.ROOT/'build/verification/api.json'); data['modules']['android-runtime']['dump']=gate.file_record(dump); gate.write_json(gate.ROOT/'build/verification/api.json',data)
        with self.assertRaisesRegex(ValueError,'no longer contains published members.*count'): gate.inspect_api()
    def test_optional_module_api_that_lost_a_published_member_rejected(self):
        # android-resources-loader is not in MODULES (no consumer pins) but its published API is just as binding.
        self.publications(); self.api()
        dump=gate.ROOT/'api/android-resources-loader.txt'; dump.write_text(dump.read_text().replace('  public int count();\n',''),encoding='utf-8')
        data=gate.read_json(gate.ROOT/'build/verification/api.json'); data['modules']['android-resources-loader']['dump']=gate.file_record(dump); gate.write_json(gate.ROOT/'build/verification/api.json',data)
        with self.assertRaisesRegex(ValueError,'android-resources-loader.*count'): gate.inspect_api()
    def test_api_gate_requires_a_published_release_baseline(self):
        self.publications(); self.api(); (gate.ROOT/'api/legacy/1.0.0/android-window.txt').unlink()
        with self.assertRaisesRegex(ValueError,'Missing published-release API baseline'): gate.inspect_api()
    def test_api_that_only_added_members_accepted(self):
        self.publications(); self.api()
        dump=gate.ROOT/'api/android-runtime.txt'; dump.write_text(dump.read_text().replace('  public int count();\n','  public int count();\n  public int added();\n'),encoding='utf-8')
        data=gate.read_json(gate.ROOT/'build/verification/api.json'); data['modules']['android-runtime']['dump']=gate.file_record(dump); gate.write_json(gate.ROOT/'build/verification/api.json',data)
        self.assertEqual('PASS',gate.inspect_api()['status'])
    def test_old_apk_replacement_after_successful_build_rejected(self):
        self.publications(); self.consumer(); gate.consumer_paths('plain')['debug_apk'].write_bytes(b'old apk')
        with self.assertRaisesRegex(ValueError,'Artifact changed'): gate.inspect_consumer()
    def test_missing_embedded_consumption_rejected(self):
        self.publications(); self.consumer(); path=gate.ROOT/'samples/build/consumed-artifacts.json'; data=gate.read_json(path); data.pop('embedded'); gate.write_json(path,data); gate.finish_consumer()
        with self.assertRaisesRegex(ValueError,'Missing embedded'): gate.inspect_consumer()
    def test_project_dependency_fallback_rejected(self):
        self.publications(); self.consumer(); path=gate.ROOT/'samples/build/consumed-artifacts.json'; data=gate.read_json(path); data['plain'][0]['component_kind']='project'; gate.write_json(path,data); gate.finish_consumer()
        with self.assertRaisesRegex(ValueError,'project/fallback'): gate.inspect_consumer()
    def test_consumer_rejects_same_name_old_aar(self):
        self.publications(); self.consumer(); path=gate.ROOT/'samples/build/consumed-artifacts.json'; data=gate.read_json(path); data['plain'][0]['sha256']='0'*64; gate.write_json(path,data); gate.finish_consumer()
        with self.assertRaisesRegex(ValueError,'Resolved artifact content'): gate.inspect_consumer()
    def device_fixture(self,tests=13):
        self.publications(); self.consumer(); checks=gate.inspect_consumer(); record=next(c for c in checks if c['name']=='plain-device-artifacts')
        methods=list(gate.DEVICE_SCENARIO_TESTS.values())[:tests]
        raw='\n'.join(f'INSTRUMENTATION_STATUS: class={identity.split("#")[0]}\nINSTRUMENTATION_STATUS: test={identity.split("#")[1]}\nINSTRUMENTATION_STATUS: numtests={tests}\nINSTRUMENTATION_STATUS_CODE: {code}' for identity in methods for code in (1,0))+f'\nOK ({tests} tests)\nINSTRUMENTATION_CODE: -1\n'
        log=self.put('verification/device-results/raw.log',raw)
        data=dict(api=37,target=37,status='PASS',source_tree_sha256=gate.source_hash(),serial='fixture',fingerprint='fixture',tests=tests,failures=0,errors=0,skipped=0,
                  apk=record['apk_path'],apk_sha256=record['apk_sha256'],test_apk=record['test_apk_path'],test_apk_sha256=record['test_apk_sha256'],installed_apk_sha256=record['apk_sha256'],installed_test_apk_sha256=record['test_apk_sha256'],raw_log=log.relative_to(gate.ROOT).as_posix(),raw_sha256=gate.sha(log),scenarios={s:'PASS' for s in gate.DEVICE_SCENARIOS},scenario_tests={s:[method] for s,method in gate.DEVICE_SCENARIO_TESTS.items()})
        host=dict(status='PASS',source_tree_sha256=gate.source_hash(),checks=checks); return data,host
    def test_complete_synthetic_device_validator_shape(self):
        data,host=self.device_fixture(); gate.validate_device(data,37,37,host)
    def test_four_smoke_tests_cannot_be_full_device_gate(self):
        data,host=self.device_fixture(4)
        with self.assertRaisesRegex(ValueError,'incomplete instrumentation'): gate.validate_device(data,37,37,host)
    def test_old_device_apk_hash_cannot_be_relabelled_current_source(self):
        data,host=self.device_fixture(); data['apk_sha256']='0'*64
        with self.assertRaisesRegex(ValueError,'Old or mismatched'): gate.validate_device(data,37,37,host)
    def test_device_status_partial_cannot_pass(self):
        data,host=self.device_fixture(); data['status']='PARTIAL'
        with self.assertRaisesRegex(ValueError,'not full acceptance'): gate.validate_device(data,37,37,host)
    def test_device_scenario_labels_require_raw_successful_test_ids(self):
        data,host=self.device_fixture(); data['scenario_tests']['ime-focus-input']=['invented#test']
        with self.assertRaisesRegex(ValueError,'raw successful'): gate.validate_device(data,37,37,host)
    def test_device_matrix_cell_identity_is_checked(self):
        data,host=self.device_fixture()
        with self.assertRaisesRegex(ValueError,'API/target'): gate.validate_device(data,30,37,host)

    def test_other_successful_method_cannot_relabel_a_scenario(self):
        data,host=self.device_fixture();data['scenario_tests']['ime-focus-input']=data['scenario_tests']['close']
        with self.assertRaisesRegex(ValueError,'required contract method'):gate.validate_device(data,37,37,host)

    def test_diagnostic_status_two_is_not_a_successful_test(self):
        raw='INSTRUMENTATION_STATUS: mvx_scenario=ime\nINSTRUMENTATION_STATUS_CODE: 2\n'
        parsed=gate.parse_instrumentation(raw)
        self.assertEqual([],parsed['successful']);self.assertEqual(1,len(parsed['diagnostics']))

    def test_raw_skip_cannot_be_hidden_by_receipt_zero(self):
        data,host=self.device_fixture();p=gate.ROOT/data['raw_log'];text=p.read_text();p.write_text(text.replace('INSTRUMENTATION_STATUS_CODE: 0','INSTRUMENTATION_STATUS_CODE: -3',1));data['raw_sha256']=gate.sha(p)
        with self.assertRaisesRegex(ValueError,'outcomes disagree'):gate.validate_device(data,37,37,host)
    def test_previous_source_device_receipt_is_not_run(self):
        data,host=self.device_fixture();data['source_tree_sha256']='0'*64
        self.assertEqual('NOT_RUN',gate.classify_device(data,37,37,host)['status'])
    def test_actual_skipped_device_case_is_blocked(self):
        data,host=self.device_fixture();p=gate.ROOT/data['raw_log'];text=p.read_text()
        p.write_text(text.replace('INSTRUMENTATION_STATUS_CODE: 0','INSTRUMENTATION_STATUS_CODE: -4',1))
        data.update(status='BLOCKED',skipped=1,raw_sha256=gate.sha(p))
        result=gate.classify_device(data,37,37,host)
        self.assertEqual(('BLOCKED',12,1),(result['status'],result['successful'],result['skipped']))
    def test_release_does_not_accept_empty_pass_receipts(self):
        for name in ('host','device','performance','bridge','integration-exit','upgrade'):
            self.json(f'verification/gates/{name}.json',{'gate':name,'status':'PASS','source_tree_sha256':gate.source_hash(),'checks':[]})
        with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(2,gate.release())

    def test_external_profile_labels_without_artifact_evidence_are_blocked(self):
        self.json('verification/gates/bridge.json',{'gate':'bridge','status':'PASS','source_tree_sha256':gate.source_hash(),'checks':[{'status':'PASS','name':'claimed'}]})
        with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(2,gate.release())
        release=gate.read_json(gate.ROOT/'verification/gates/release.json')
        self.assertEqual('BLOCKED',next(c for c in release['checks'] if c['name']=='bridge')['status'])

    def test_core_candidate_has_no_future_integration_or_upgrade_cycle(self):
        with patch.object(gate,'verify_gate',side_effect=lambda name: {'name':name,'status':'PASS'}) as check:
            with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(0,gate.release('core-candidate'))
        self.assertEqual(['host','device','performance-core'],[c.args[0] for c in check.call_args_list])

    def test_default_release_still_requires_complete_replacement(self):
        with patch.object(gate,'verify_gate',side_effect=lambda name: {'name':name,'status':'BLOCKED' if name=='integration-exit' else 'PASS'}) as check:
            with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(2,gate.release())
        self.assertEqual(['host','device','performance','bridge','integration-exit','upgrade'],[c.args[0] for c in check.call_args_list])

    def test_scoped_pass_cannot_overwrite_full_release_receipt(self):
        self.json('verification/gates/release.json',{'status':'BLOCKED','selected_profile':'full_replacement'})
        with patch.object(gate,'verify_gate',side_effect=lambda name: {'name':name,'status':'PASS'}):
            with contextlib.redirect_stdout(io.StringIO()): gate.release('bridge-profile')
        self.assertEqual('BLOCKED',gate.read_json(gate.ROOT/'verification/gates/release.json')['status'])
        scoped=gate.read_json(gate.ROOT/'verification/gates/release-bridge-profile.json')
        self.assertEqual('full_replacement',scoped['execution_target'])
        self.assertEqual('bridge-profile',scoped['selected_profile'])

    def test_core_candidate_still_blocks_missing_core_performance(self):
        with patch.object(gate,'verify_gate',side_effect=lambda name: {'name':name,'status':'BLOCKED' if name=='performance-core' else 'PASS'}):
            with contextlib.redirect_stdout(io.StringIO()): self.assertEqual(2,gate.release('core-candidate'))

if __name__=='__main__': unittest.main()
