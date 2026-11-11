"""Host-only synthetic signed update cases. Never publishes, uses only our fresh TEST key."""
import argparse,base64,hashlib,json,pathlib,re,shutil,subprocess,zipfile,os
r=pathlib.Path(os.environ.get('EPHARM_RECOVERY_FIXTURE_ROOT',str(pathlib.Path(__file__).resolve().parent)))
p=argparse.ArgumentParser();p.add_argument('--inputs',required=True,type=pathlib.Path);p.add_argument('--scope',choices=['main','lab'],required=True);a=p.parse_args()
x=json.loads(a.inputs.read_text(encoding="utf-8-sig"));main=x['main']; src=x[a.scope]
version='1.0.84' if a.scope=='main' else '9.99.1'
assert main['version']=='1.0.84' and src['version']==version
assert re.fullmatch('[0-9a-f]{40}',main['commit']) and src['commit']==main['commit']
assert re.fullmatch('[0-9a-f]{64}',src['sha256']) and re.fullmatch('[0-9a-f]{64}',src['dllSha256'])
assert re.fullmatch('[0-9]+',str(main['ciRun'])) and re.fullmatch('[0-9]+',str(main['artifactId']))
archive=pathlib.Path(src['zip']).resolve()
assert hashlib.sha256(archive.read_bytes()).hexdigest()==src['sha256']
meta=json.loads(pathlib.Path(src['provenance']).read_text(encoding='utf-8-sig'))
assert meta['commit']==src['commit'] and meta['version']==version and meta['sha256']==src['sha256']
if a.scope=='lab': assert meta['VersionOverrideOnly'] is True and meta['ProductionPublicationAllowed'] is False
allowed={'CustomerDisplay.exe','CustomerDisplay.dll','CustomerDisplay.deps.json','CustomerDisplay.runtimeconfig.json','QRCoder.dll'}
with zipfile.ZipFile(archive) as z:
 assert len(z.namelist())==5 and set(z.namelist())==allowed
 files={n:z.read(n) for n in z.namelist()}
assert files['CustomerDisplay.exe'][:2]==b'MZ'
assert hashlib.sha256(files['CustomerDisplay.dll']).hexdigest()==src['dllSha256']
assert (r/'fresh-test-signing-private.pem').is_file()
cases=[('main84',None)] if a.scope=='main' else [('lab-bad-exe','exe'),('lab-locked','ntfs-lock'),('lab-healthy',None),('lab-interrupted','helper-interruption')]
for name,fault in cases:
 c=r/'cases'/name;c.mkdir(parents=True,exist_ok=False);dest=c/'candidate.zip'
 if fault is None:shutil.copyfile(archive,dest)
 else:
  changed=dict(files)
  if fault=='exe':changed['CustomerDisplay.exe']=b'ZZ'+changed['CustomerDisplay.exe'][2:]
  with zipfile.ZipFile(dest,'w',compression=zipfile.ZIP_DEFLATED) as z:
   for n,data in changed.items():z.writestr(n,data)
   z.comment=('LAB ONLY '+name+'; never production publication').encode()
 sha=hashlib.sha256(dest.read_bytes()).hexdigest()
 m={'platform':'win-x64','version':version,'url':'http://127.0.0.1:18761/candidate.zip','sha256':sha,'mandatory':False,'current':True}
 canonical=('epharm-posm-update-v1\nwin-x64\n'+version+'\n'+m['url']+'\n'+sha+'\nfalse').encode()
 (c/'canonical').write_bytes(canonical)
 signature=subprocess.check_output([os.environ.get('EPHARM_PILOT_OPENSSL','openssl'),'dgst','-sha256','-sign',str(r/'fresh-test-signing-private.pem'),str(c/'canonical')])
 m['manifestSignature']=base64.b64encode(signature).decode();(c/'manifest.current.json').write_text(json.dumps(m,indent=2)+'\n')
 prov={'case':name,'sourceCommit':src['commit'],'sourceSha256':src['sha256'],'servedSha256':sha,'version':version,'faultInjection':fault,'sourceFileSha256':{n:hashlib.sha256(d).hexdigest() for n,d in files.items()},'productionPublicationAllowed':False,'scope':'private synthetic Windows pilot only'}
 (c/'provenance.json').write_text(json.dumps(prov,indent=2)+'\n')
 with zipfile.ZipFile(r/'cases'/('case-'+name+'.zip'),'w',compression=zipfile.ZIP_STORED) as z:
  for n in ['candidate.zip','manifest.current.json','provenance.json']:z.write(c/n,n)
 print(json.dumps({'case':name,'sha256':sha,'version':version,'sourceCommit':src['commit']}))
