#!/usr/bin/env python3
"""Read-only private Windows qualification guard. Never authorizes publication."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request
import zipfile

REPO = 'sabirovv17/epharm'
BRANCH = 'refs/heads/codex/pr166-qualification-20261114'
ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / '.github/qa/pr166-candidate.json'
FILES = {'CustomerDisplay.exe','CustomerDisplay.dll','CustomerDisplay.deps.json','CustomerDisplay.runtimeconfig.json','QRCoder.dll'}
PREVIOUS = {'version':'1.0.85','commit':'addb2c97bf752038688e91a6902369da5194009b','ciRun':43153930079,'ciWindowsJob':130969355166,'artifactId':13915316619,'outerSha256':'43eecf4d200b9d8acfce26bae6406913fbe3e8c572ad9b64e62bc2b3cce716d2','sha256':'dc41228626e0a0cc7970d0aee070605ec244a1ee639ae42932ee177e189f6d38','dllSha256':'0f373ab83deafceafbcb982578429f7ef3eb20208848e11b46a1a574ee61e6c4','bytes':546164,'files':sorted(FILES)}
ALLOWED = ('.github/qa/pr166-allowed-files.json', '.github/qa/pr166-candidate.json', '.github/qa/pr166-prepare-windows.ps1', '.github/qa/pr166-qualify.py', '.github/qa/pr166-ui/Epharm.PrivateStaffPilot.csproj', '.github/qa/pr166-ui/NuGet.Config', '.github/qa/pr166-ui/Program.cs', '.github/qa/test_pr166_qualify.py', '.github/workflows/release-images.yml', 'tools/release/ci-display-pilot-assets/driver-source.json', 'tools/release/ci-display-pilot-assets/pilot-customer-display.mp4', 'tools/release/ci-display-pilot-assets/usbmmidd_v2.zip', 'tools/release/ci-display-pilot-prepare.ps1', 'tools/release/ci-display-render.ps1', 'tools/release/ci-display-topology-native.cs', 'tools/release/ci-display-topology.ps1')
QUALIFICATION = {'fullMainCiRequiredBeforePromotion':True,'windowsOnlyMayStartWhileMainCiRuns':True,'newRuntimeAcceptance':'PENDING','trueOsReboot':'PENDING_SEPARATE_OWNED_VM','productionPublicationAllowed':False,'serverDeploymentRequired':False}

class GuardError(ValueError):
    pass

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        return None

def need(ok, message):
    if not ok:
        raise GuardError(message)

def validate(c):
    need(isinstance(c,dict) and set(c)=={'schemaVersion','mode','exactMain','main','previous','qualification'}, 'unexpected candidate schema')
    need(type(c['schemaVersion']) is int and c['schemaVersion']==2 and c['mode']=={'windows':True} and type(c['mode']['windows']) is bool, 'Windows-only qualification required')
    need(isinstance(c['exactMain'],str) and re.fullmatch('[a-f0-9]{40}',c['exactMain']) and len(set(c['exactMain']))>1, 'unfilled exact merged main')
    m=c['main'];need(isinstance(m,dict) and set(m)==set(PREVIOUS)|{'runtimeVersion'}, 'unexpected main artifact fields')
    need(m['version']=='1.0.86' and m['commit']==c['exactMain'] and m['commit']!=PREVIOUS['commit'], 'new86 exact main required')
    for k in ('ciRun','ciWindowsJob','artifactId','bytes'):
        need(type(m[k]) is int and m[k]>0,'invalid official main coordinate')
    need(m['bytes']<16*1024**2 and m['files']==sorted(FILES),'invalid five-file bridge contract')
    for k in ('outerSha256','sha256','dllSha256'):
        need(isinstance(m[k],str) and re.fullmatch('[a-f0-9]{64}',m[k]) and len(set(m[k]))>1,'unfilled official digest')
    need(isinstance(m['runtimeVersion'],str) and re.fullmatch(r'10\.0\.\d+',m['runtimeVersion']),'explicit official runtime version required')
    need(c['previous']==PREVIOUS and all(type(c['previous'][k]) is int for k in ('ciRun','ciWindowsJob','artifactId','bytes')),'previous published85 identity changed')
    need(c['qualification']==QUALIFICATION and all(type(c['qualification'][k]) is bool for k in ['fullMainCiRequiredBeforePromotion','windowsOnlyMayStartWhileMainCiRuns','productionPublicationAllowed','serverDeploymentRequired']),'qualification cannot authorize publication/server changes')
    return c

def git(*args):
    return subprocess.check_output(['git','-C',str(ROOT),*args],text=True,encoding='utf-8').strip()

def source_guard(c):
    base=c['exactMain']
    subprocess.run(['git','-C',str(ROOT),'merge-base','--is-ancestor',base,'HEAD'],check=True)
    scope=json.loads((ROOT/'.github/qa/pr166-allowed-files.json').read_text())
    need(scope==list(ALLOWED),'private path allowlist changed')
    for path in git('diff','--name-only',base,'HEAD').splitlines():
        need(path in ALLOWED,'private QA contains product/CI changes')
    need(git('rev-parse',base+':.github/workflows/ci.yml')==git('rev-parse','HEAD:.github/workflows/ci.yml'),'mandatory CI changed')
    need('<Version>1.0.86</Version>' in git('show',base+':App/CustomerDisplay.csproj'),'main is not version86')

def api(path):
    token=os.environ.get('GH_TOKEN');need(bool(token),'read-only token missing')
    req=urllib.request.Request('https://api.github.com/repos/'+REPO+path,headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28'})
    with urllib.request.build_opener(NoRedirect()).open(req,timeout=30) as r: body=r.read(2*1024**2+1)
    need(len(body)<=2*1024**2,'oversized GitHub metadata')
    return json.loads(body)

def live_windows(c):
    need(api('/git/ref/heads/main')['object']['sha']==c['exactMain'],'current main moved')
    observations={}
    for role in ('main','previous'):
        m=c[role];run=api('/actions/runs/'+str(m['ciRun']))
        need(run['repository']['full_name']==REPO and run['head_repository']['full_name']==REPO and run['head_sha']==m['commit'] and run['head_branch']=='main' and run['event']=='push' and run['path']=='.github/workflows/ci.yml','not an exact-main push CI run')
        if role=='previous': need(run['status']=='completed' and run['conclusion']=='success','previous85 CI not successful')
        else: need((run['status']=='in_progress' and run['conclusion'] is None) or (run['status']=='completed' and run['conclusion']=='success'),'failed/cancelled main refused')
        job=api('/actions/jobs/'+str(m['ciWindowsJob']))
        need(job['run_id']==m['ciRun'] and job['head_sha']==m['commit'] and job['name']=='posm / Windows production build' and job['status']=='completed' and job['conclusion']=='success','exact official Windows job not successful')
        a=api('/actions/artifacts/'+str(m['artifactId']))
        need(a['workflow_run']['id']==m['ciRun'] and a['workflow_run']['head_sha']==m['commit'] and a['name']=='epharm-posm-'+m['commit'] and a['expired'] is False and a['digest']=='sha256:'+m['outerSha256'],'official artifact identity mismatch')
        observations[role]={'ciStatus':run['status'],'ciConclusion':run['conclusion'],'artifactId':m['artifactId'],'artifactDigest':a['digest']}
    need(api('/git/ref/heads/main')['object']['sha']==c['exactMain'],'main moved during qualification guard')
    return {'status':'PASS_FOR_PRIVATE_WINDOWS_QUALIFICATION_ONLY','observations':observations,'fullMainCiRequiredBeforePromotion':True,'productionPublicationAllowed':False,'serverDeploymentRequired':False}

def verify_local_bridge(item,archive):
    outer=Path(archive);need(hashlib.sha256(outer.read_bytes()).hexdigest()==item['outerSha256'],'outer digest mismatch')
    name='epharm-posm-'+item['version']+'-'+item['commit'][:12]+'-compat.zip'
    with zipfile.ZipFile(outer) as z:
        need(z.testzip() is None and len(z.namelist())==3 and set(z.namelist())=={name,name+'.sha256','provenance.json'},'outer allowlist/CRC mismatch')
        bridge=z.read(name);meta=json.loads(z.read('provenance.json'))
        need(meta['version']==item['version'] and meta['commit']==item['commit'] and meta['sha256']==item['sha256'] and meta['archive']==name and len(meta['files'])==5 and set(meta['files'])==FILES,'bridge source provenance mismatch')
        need(z.read(name+'.sha256').decode('ascii').strip()==item['sha256']+'  '+name,'bridge checksum sidecar mismatch')
    need(len(bridge)==item['bytes'] and hashlib.sha256(bridge).hexdigest()==item['sha256'],'bridge digest/size mismatch')
    with zipfile.ZipFile(io.BytesIO(bridge)) as z:
        need(z.testzip() is None and len(z.infolist())==5 and set(z.namelist())==FILES,'five-file bridge allowlist/CRC mismatch')
        need(hashlib.sha256(z.read('CustomerDisplay.dll')).hexdigest()==item['dllSha256'],'official DLL digest mismatch')
        runtime=json.loads(z.read('CustomerDisplay.runtimeconfig.json'))['runtimeOptions']
        frameworks=runtime['includedFrameworks']
        expected_runtime=item.get('runtimeVersion','10.0.15')
        need(runtime['tfm']=='net10.0' and len(frameworks)==2 and {f['name']:f['version'] for f in frameworks}=={'Microsoft.NETCore.App':expected_runtime,'Microsoft.WindowsDesktop.App':expected_runtime},'official runtime version mismatch')

def main():
    p=argparse.ArgumentParser();p.add_argument('mode',choices=['self-test','guard','live-windows','local-bridges','promotion-check']);p.add_argument('--main-archive');p.add_argument('--previous-archive');args=p.parse_args()
    try:
        c=validate(json.loads(CONFIG.read_text()))
        if args.mode=='self-test': return 0
        if args.mode=='local-bridges':
            source_guard(c);verify_local_bridge(c['main'],args.main_archive);verify_local_bridge(c['previous'],args.previous_archive)
        else:
            need(os.environ.get('GITHUB_REPOSITORY')==REPO and os.environ.get('GITHUB_REF')==BRANCH and os.environ.get('GITHUB_EVENT_NAME')=='workflow_dispatch','wrong private repo/ref/event')
            need(os.environ.get('EXACT_COMMIT')==c['exactMain'] and os.environ.get('CLIENT_VERSION')=='1.0.86','dispatch identity mismatch')
            source_guard(c);need(not git('status','--porcelain','--untracked-files=normal'),'private runtime checkout must be clean')
            if args.mode=='guard':
                with open(os.environ['GITHUB_OUTPUT'],'a') as out:out.write('windows=true\n')
            elif args.mode=='live-windows':
                proof=Path(os.environ['RUNNER_TEMP'])/'display-pilot-proof';proof.mkdir(exist_ok=False);(proof/'official-artifact-guard.json').write_text(json.dumps(live_windows(c),indent=2)+'\n')
            elif args.mode=='promotion-check':
                # This explicit final gate does not publish anything. Full main push CI/P0 remains mandatory.
                subprocess.run([sys.executable,str(ROOT/'tools/release/verify-release-source.py'),c['exactMain']],check=True)
        print('PR166_PRIVATE_GUARD_PASS:'+args.mode);return 0
    except Exception as error:
        print('PR166_PRIVATE_GUARD_FAIL:'+(str(error) if isinstance(error,GuardError) else 'private qualification unavailable'));return 1
if __name__=='__main__': raise SystemExit(main())
