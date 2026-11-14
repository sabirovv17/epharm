#!/usr/bin/env python3
"""Offline trust-boundary checks. Git/API/process calls are mocked."""
import copy
from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
import zipfile
from unittest.mock import patch

P=Path(__file__).with_name('pr166-qualify.py')
spec=importlib.util.spec_from_file_location('guard',P);g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)

def candidate():
    main=copy.deepcopy(g.PREVIOUS)
    main.update(version='1.0.86',commit='0123456789abcdef'*2+'01234567',ciRun=1234567,ciWindowsJob=2345678,artifactId=3456789,runtimeVersion='10.0.15',outerSha256='123456789abcdef0'*4,sha256='abcdef0123456789'*4,dllSha256='fedcba9876543210'*4,bytes=550000)
    return {'schemaVersion':2,'mode':{'windows':True},'exactMain':main['commit'],'main':main,'previous':copy.deepcopy(g.PREVIOUS),'qualification':copy.deepcopy(g.QUALIFICATION)}

class Guards(unittest.TestCase):
    def test_candidate_positive_and_unfilled_or_old85_rejected(self):
        c=candidate();self.assertEqual(g.validate(c),c)
        for mutate in [lambda c:c.update(exactMain=None),lambda c:c['main'].update(version='1.0.85'),lambda c:c['main'].update(ciRun=True),lambda c:c['main'].update(sha256='a'*64),lambda c:c['main'].update(runtimeVersion='10.0.x'),lambda c:c['main'].update(commit=g.PREVIOUS['commit'])]:
            bad=copy.deepcopy(c);mutate(bad)
            with self.assertRaises(g.GuardError):g.validate(bad)
    def test_windows_only_no_server_or_linux_scope_and_no_publication(self):
        for mutate in [lambda c:c['mode'].update(linux=True),lambda c:c['mode'].update(windows='true'),lambda c:c.update(server={}),lambda c:c['previous'].update(version='1.0.84'),lambda c:c['qualification'].update(fullMainCiRequiredBeforePromotion=False),lambda c:c['qualification'].update(productionPublicationAllowed=True),lambda c:c['qualification'].update(serverDeploymentRequired=True)]:
            c=candidate();mutate(c)
            with self.assertRaises(g.GuardError):g.validate(c)
    def api_map(self,c):
        def api(path):
            if path=='/git/ref/heads/main':return {'object':{'sha':c['exactMain']}}
            for role in ['main','previous']:
                m=c[role]
                if path=='/actions/runs/'+str(m['ciRun']):return {'repository':{'full_name':g.REPO},'head_repository':{'full_name':g.REPO},'head_sha':m['commit'],'head_branch':'main','event':'push','path':'.github/workflows/ci.yml','status':'in_progress' if role=='main' else 'completed','conclusion':None if role=='main' else 'success'}
                if path=='/actions/jobs/'+str(m['ciWindowsJob']):return {'run_id':m['ciRun'],'head_sha':m['commit'],'name':'posm / Windows production build','status':'completed','conclusion':'success'}
                if path=='/actions/artifacts/'+str(m['artifactId']):return {'workflow_run':{'id':m['ciRun'],'head_sha':m['commit']},'name':'epharm-posm-'+m['commit'],'expired':False,'digest':'sha256:'+m['outerSha256']}
            raise AssertionError('unexpected API path')
        return api
    def test_early_qualification_never_claims_full_main_ci_or_publication(self):
        c=candidate()
        with patch.object(g,'api',side_effect=self.api_map(c)):
            p=g.live_windows(c)
        self.assertFalse(p['productionPublicationAllowed']);self.assertTrue(p['fullMainCiRequiredBeforePromotion']);self.assertEqual(p['observations']['main']['ciStatus'],'in_progress')
    def test_wrong_event_failed_main_pending_windows_expired_or_wrong_artifact_refused(self):
        c=candidate();base=self.api_map(c)
        mutations=[('/actions/runs/'+str(c['main']['ciRun']),{'event':'pull_request'}),('/actions/runs/'+str(c['main']['ciRun']),{'conclusion':'failure'}),('/actions/runs/'+str(c['main']['ciRun']),{'status':'completed','conclusion':None}),('/actions/jobs/'+str(c['main']['ciWindowsJob']),{'status':'in_progress','conclusion':None}),('/actions/artifacts/'+str(c['main']['artifactId']),{'expired':True}),('/actions/artifacts/'+str(c['main']['artifactId']),{'digest':'sha256:'+'0'*64})]
        for target,delta in mutations:
            def api(path):
                r=base(path)
                if path==target:r.update(delta)
                return r
            with patch.object(g,'api',side_effect=api),self.assertRaises(g.GuardError):g.live_windows(c)
    def test_product_change_refused_even_with_unchanged_mandatory_ci(self):
        c=candidate()
        with patch.object(g.subprocess,'run'),patch.object(g,'git',return_value='App/MainWindow.Staff.cs'),self.assertRaises(g.GuardError):g.source_guard(c)
    def test_literal_allowlist_and_mandatory_ci_are_independent_guards(self):
        c=candidate()
        def git(*args):
            if args[0]=='diff':return '.github/qa/pr166-candidate.json'
            if args[0]=='rev-parse':return 'unchanged-ci'
            if args[0]=='show':return '<Version>1.0.86</Version>'
            raise AssertionError('unexpected Git call')
        with patch.object(g.subprocess,'run'),patch.object(g,'git',side_effect=git):g.source_guard(c)
        with patch.object(g.subprocess,'run'),patch.object(g,'git',side_effect=git),patch.object(g.json,'loads',return_value=list(g.ALLOWED)+['App/MainWindow.Staff.cs']),self.assertRaises(g.GuardError):g.source_guard(c)
        def changed_ci(*args):
            return args[1] if args[0]=='rev-parse' else git(*args)
        with patch.object(g.subprocess,'run'),patch.object(g,'git',side_effect=changed_ci),self.assertRaises(g.GuardError):g.source_guard(c)
    def test_main_moving_during_metadata_checks_refused(self):
        c=candidate();base=self.api_map(c);calls=0
        def api(path):
            nonlocal calls
            if path=='/git/ref/heads/main':
                calls+=1
                if calls>1:return {'object':{'sha':g.PREVIOUS['commit']}}
            return base(path)
        with patch.object(g,'api',side_effect=api),self.assertRaises(g.GuardError):g.live_windows(c)
    def test_exact_bridge_crc_allowlists_provenance_and_runtime(self):
        with tempfile.TemporaryDirectory(dir=P.parent) as td:
            out=Path(td)/'outer.zip'
            for fault in [None,'extra-file','duplicate-file','wrong-provenance','wrong-runtime','wrong-dll','wrong-sidecar']:
                item=copy.deepcopy(candidate()['main'])
                files={name:b'synthetic fixture' for name in g.FILES}
                files['CustomerDisplay.runtimeconfig.json']=json.dumps({'runtimeOptions':{'tfm':'net10.0','includedFrameworks':[{'name':'Microsoft.NETCore.App','version':'10.0.14' if fault=='wrong-runtime' else '10.0.15'},{'name':'Microsoft.WindowsDesktop.App','version':'10.0.15'}]}}).encode()
                item['dllSha256']=hashlib.sha256(files['CustomerDisplay.dll']).hexdigest()
                if fault=='wrong-dll':item['dllSha256']='a1'*32
                data=io.BytesIO()
                with zipfile.ZipFile(data,'w') as z:
                    for name,body in files.items():z.writestr(name,body)
                    if fault=='extra-file':z.writestr('extra.dll',b'not permitted')
                    if fault=='duplicate-file':
                        import warnings
                        with warnings.catch_warnings():
                            warnings.simplefilter('ignore',UserWarning);z.writestr('CustomerDisplay.exe',b'duplicate')
                bridge=data.getvalue();item['sha256']=hashlib.sha256(bridge).hexdigest();item['bytes']=len(bridge)
                name='epharm-posm-'+item['version']+'-'+item['commit'][:12]+'-compat.zip'
                meta={'version':item['version'],'commit':item['commit'],'sha256':item['sha256'],'files':sorted(g.FILES),'archive':name}
                if fault=='wrong-provenance':meta['commit']=g.PREVIOUS['commit']
                with zipfile.ZipFile(out,'w') as z:
                    z.writestr(name,bridge);z.writestr('provenance.json',json.dumps(meta));z.writestr(name+'.sha256',('incorrect' if fault=='wrong-sidecar' else item['sha256']+'  '+name)+'\n')
                item['outerSha256']=hashlib.sha256(out.read_bytes()).hexdigest()
                if fault:
                    with self.assertRaises(g.GuardError):g.verify_local_bridge(item,out)
                else:
                    g.verify_local_bridge(item,out)
                    item['outerSha256']='a1'*32
                    with self.assertRaises(g.GuardError):g.verify_local_bridge(item,out)
    def test_wrong_branch_repo_event_or_input_fails_before_any_api(self):
        c=candidate()
        with tempfile.TemporaryDirectory(dir=P.parent) as td:
            p=Path(td)/'candidate.json';p.write_text(json.dumps(c))
            env={'GITHUB_REPOSITORY':g.REPO,'GITHUB_REF':g.BRANCH,'GITHUB_EVENT_NAME':'workflow_dispatch','EXACT_COMMIT':c['exactMain'],'CLIENT_VERSION':'1.0.86','GITHUB_OUTPUT':str(Path(td)/'output')}
            for delta in [{'GITHUB_REF':'refs/heads/main'},{'GITHUB_REPOSITORY':'other/repo'},{'GITHUB_EVENT_NAME':'push'},{'EXACT_COMMIT':g.PREVIOUS['commit']},{'CLIENT_VERSION':'1.0.85'}]:
                e=dict(env);e.update(delta)
                with patch.object(g,'CONFIG',p),patch.dict(os.environ,e,clear=True),patch('sys.argv',['guard','guard']),patch.object(g,'api') as api,patch.object(g,'source_guard') as source:
                    self.assertEqual(g.main(),1);api.assert_not_called();source.assert_not_called()
    def test_promotion_path_keeps_repository_full_main_ci_verifier(self):
        text=P.read_text();self.assertIn("ROOT/'tools/release/verify-release-source.py'",text);self.assertNotIn('linux-config',text)
    def test_upstream_failure_never_prints_raw_error_or_token(self):
        c=candidate()
        with tempfile.TemporaryDirectory(dir=P.parent) as td:
            config=Path(td)/'candidate.json';config.write_text(json.dumps(c))
            env={'GITHUB_REPOSITORY':g.REPO,'GITHUB_REF':g.BRANCH,'GITHUB_EVENT_NAME':'workflow_dispatch','EXACT_COMMIT':c['exactMain'],'CLIENT_VERSION':'1.0.86','RUNNER_TEMP':td,'GH_TOKEN':'synthetic-token-must-never-print'}
            output=io.StringIO()
            with patch.object(g,'CONFIG',config),patch.dict(os.environ,env,clear=True),patch('sys.argv',['guard','live-windows']),patch.object(g,'source_guard'),patch.object(g,'git',return_value=''),patch.object(g,'api',side_effect=RuntimeError('synthetic-token-must-never-print / upstream raw body')),redirect_stdout(output):
                self.assertEqual(g.main(),1)
            self.assertEqual(output.getvalue(),'PR166_PRIVATE_GUARD_FAIL:private qualification unavailable\n')

if __name__=='__main__':unittest.main()
