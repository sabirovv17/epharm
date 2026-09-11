#!/usr/bin/env python3
"""Send only validated, published Standard N artifacts to a forced-command receiver."""
import argparse
import json
import os
import re
import stat
import subprocess
import tarfile
from datetime import datetime, timezone
from pathlib import Path
from pim_validation import durable_json, sha256

PIM_ROOT = Path(os.getenv('PIM_ROOT','/var/lib/pim-dashboard'))
ARTIFACT_ROOT = Path(os.getenv('PIM_ARTIFACT_ROOT',str(PIM_ROOT/'validated')))
CONFIG = Path(os.getenv('PIM_MEDUSA_CONFIG','/etc/pim-dashboard/medusa-push.json'))
FILES = ('catalog-manifest.json','products.ndjson','pharmacy-offers.ndjson','pharmacies.ndjson')
HOST_RE = re.compile(r'(?:[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\d{1,3}(?:\.\d{1,3}){3})\Z')
USER_RE = re.compile(r'[A-Za-z_][A-Za-z0-9_-]{0,31}\Z')


def protected_file(path, *, private):
    path=Path(path)
    if not path.is_absolute():raise RuntimeError('receiver file paths must be absolute')
    try:metadata=path.lstat()
    except FileNotFoundError as exc:raise RuntimeError(f'required receiver file is missing: {path}') from exc
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise RuntimeError(f'receiver file must be a regular non-symlink file: {path}')
    forbidden=0o077 if private else 0o022
    if stat.S_IMODE(metadata.st_mode)&forbidden:
        raise RuntimeError(f'unsafe permissions on receiver file: {path}')
    return path


def validate_receiver_config(config):
    if not isinstance(config,dict):raise RuntimeError('receiver config must be a JSON object')
    unknown=set(config)-{'host','user','port','known_hosts','identity_file'}
    if unknown:raise RuntimeError('receiver config contains unsupported fields')
    host=config.get('host','');user=config.get('user','');port=config.get('port',22)
    allowed_host=os.getenv('PIM_MEDUSA_ALLOWED_HOST','').strip()
    allowed_user=os.getenv('PIM_MEDUSA_ALLOWED_USER','standardn-ingest').strip()
    try:allowed_port=int(os.getenv('PIM_MEDUSA_ALLOWED_PORT','22'))
    except ValueError as exc:raise RuntimeError('PIM_MEDUSA_ALLOWED_PORT is invalid') from exc
    if not allowed_host:raise RuntimeError('PIM_MEDUSA_ALLOWED_HOST must explicitly allow the receiver')
    if not HOST_RE.fullmatch(host) or host!=allowed_host:
        raise RuntimeError('unexpected receiver host')
    if not USER_RE.fullmatch(user) or user!=allowed_user:
        raise RuntimeError('unexpected receiver user')
    if isinstance(port,bool) or not isinstance(port,int) or not 1<=port<=65535 or port!=allowed_port:
        raise RuntimeError('unexpected receiver port')
    known_hosts=protected_file(config.get('known_hosts',''),private=False)
    identity_file=protected_file(config.get('identity_file',''),private=True)
    return {'host':host,'user':user,'port':port,'known_hosts':str(known_hosts),'identity_file':str(identity_file)}


def load_config(path=CONFIG):
    config_path=protected_file(path,private=True)
    return validate_receiver_config(json.loads(config_path.read_text(encoding='utf-8')))


def validate_artifact(artifact):
    requested=Path(artifact)
    if not ARTIFACT_ROOT.is_absolute() or not requested.is_absolute():
        raise RuntimeError('artifact paths must be absolute')
    if requested.is_symlink():raise RuntimeError('artifact directory must not be a symlink')
    artifact=requested.resolve()
    artifact.relative_to(ARTIFACT_ROOT.resolve())
    if not artifact.is_dir():raise RuntimeError('artifact directory is missing')
    for name in ('publication.json','catalog-manifest.json'):
        path=artifact/name
        if not path.is_file() or path.is_symlink():raise RuntimeError('artifact metadata must be regular files')
    published=json.loads((artifact/'publication.json').read_text(encoding='utf-8'))
    manifest=json.loads((artifact/'catalog-manifest.json').read_text(encoding='utf-8'))
    if published.get('state')!='complete' or manifest.get('complete') is not True:
        raise RuntimeError('only complete published snapshots may be pushed')
    if not re.fullmatch('[a-f0-9]{64}',manifest.get('snapshot_id','')):
        raise RuntimeError('invalid immutable snapshot identifier')
    if manifest.get('source_date')!=published.get('snapshot_date'):
        raise RuntimeError('published date differs from catalog artifact')
    for group,name in (('products','products.ndjson'),('offers','pharmacy-offers.ndjson'),('pharmacies','pharmacies.ndjson')):
        path=artifact/name
        if not path.is_file() or path.is_symlink() or manifest[group]['file']!=name:
            raise RuntimeError('unexpected artifact member')
        if sha256(path)!=manifest[group]['sha256']:
            raise RuntimeError('artifact member hash differs from manifest')
    return manifest,sha256(artifact/'catalog-manifest.json')


def make_archive(artifact):
    archive=artifact/'medusa-push.tar.gz'
    partial=archive.with_name(archive.name+'.partial')
    for path in (archive,partial):
        if path.is_symlink() or (path.exists() and not path.is_file()):
            raise RuntimeError('unsafe archive output path')
    with tarfile.open(partial,'w:gz',format=tarfile.USTAR_FORMAT,compresslevel=6) as stream:
        for name in FILES:
            path=artifact/name
            if not path.is_file() or path.is_symlink():raise RuntimeError('non-regular artifact member')
            stream.add(path,arcname=name,recursive=False)
    partial.replace(archive)
    return archive


def deliver(artifact, config):
    artifact=Path(artifact)
    manifest,manifest_hash=validate_artifact(artifact)
    config=validate_receiver_config(config)
    delivery=artifact/'delivery.json'
    if delivery.exists():
        if delivery.is_symlink() or not delivery.is_file():raise RuntimeError('unsafe delivery record')
        previous=json.loads(delivery.read_text())
        if previous.get('accepted') is True and previous.get('manifest_sha256')==manifest_hash:
            return {**previous,'already_delivered':True}
    archive=make_archive(artifact)
    command=['ssh','-T','-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes',
             '-o','ConnectTimeout=15','-o','ConnectionAttempts=1','-o','ServerAliveInterval=15',
             '-o','ServerAliveCountMax=3','-o','UserKnownHostsFile='+config['known_hosts'],
             '-i',config['identity_file'],'-p',str(config['port']),config['user']+'@'+config['host']]
    with archive.open('rb') as source:
        result=subprocess.run(command,stdin=source,capture_output=True,text=False,timeout=240)
    if result.returncode:
        raise RuntimeError(f'artifact receiver failed ({result.returncode}): '+result.stderr.decode(errors='replace')[:500])
    ack=json.loads(result.stdout.decode())
    if ack.get('accepted') is not True or ack.get('snapshot_id')!=manifest['snapshot_id'] or ack.get('manifest_sha256')!=manifest_hash:
        raise RuntimeError('receiver acknowledgement does not match source manifest')
    record={'accepted':True,'snapshot_id':manifest['snapshot_id'],'manifest_sha256':manifest_hash,
            'delivered_at':datetime.now(timezone.utc).isoformat(),'archive_sha256':sha256(archive)}
    durable_json(delivery,record)
    return record


def latest_published():
    if not ARTIFACT_ROOT.is_absolute():raise RuntimeError('PIM artifact root must be absolute')
    candidates=[]
    for path in ARTIFACT_ROOT.glob('*/publication.json'):
        if path.is_symlink() or not path.is_file() or path.parent.is_symlink():continue
        document=json.loads(path.read_text())
        if document.get('state')=='complete':candidates.append((document['snapshot_date'],document.get('published_at',''),path.parent))
    if not candidates:raise RuntimeError('no complete published snapshot for Medusa')
    return max(candidates,key=lambda pair:(pair[0],pair[1]))[2]


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--artifact',type=Path)
    args=parser.parse_args()
    config=load_config()
    artifact=args.artifact or latest_published()
    try:
        result=deliver(artifact,config)
        print(json.dumps(result))
    except Exception as exc:
        durable_json(artifact/'delivery-error.json',{'at':datetime.now(timezone.utc).isoformat(),'error_type':type(exc).__name__,'error':str(exc)[:1000]})
        raise
