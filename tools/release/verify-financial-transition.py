#!/usr/bin/env python3
"""Reject legacy financial writers, access control or admin sessions on a forward-migrated database.

Artifact markers guard known regressions; they do not approve a fallback release or
replace exact-source review, forward-schema rehearsal, or physical POSM acceptance.
"""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

RESOURCE = 'BOOT-INF/classes/finance-artifact-contract.properties'
CAPABILITY = {'epharm.finance.wallet': '1', 'epharm.finance.receipt-decisions': '1',
              'epharm.finance.source-bound-sales': '1'}
WALLET = 'kz/epharm/finance/service/PharmacistWalletService'
WRITERS = {
    f'{WALLET}.class': ('pharmacist_wallet_entries', 'FOR UPDATE', 'credit', 'adjustTo', 'reserveForPayout'),
    'kz/epharm/receipts/service/ReconcileService.class':
        (WALLET, 'credit', 'loadForDecision', 'getSourceSaleId', 'ingestVerifiedRecommendationSale'),
    'kz/epharm/training/service/TrainingService.class': (WALLET, 'credit'),
    'kz/epharm/pharmacists/service/PharmacistService.class': (WALLET, 'adjustTo'),
    'kz/epharm/finance/service/PayoutService.class': (WALLET, 'reserveForPayout', 'lockPayoutGeneration'),
    'kz/epharm/finance/merch/MerchRewardService.class': (WALLET, 'credit', 'redeem', 'reward'),
}
ACCESS_CLASSES = {
    'kz/epharm/auth/access/StoredGrant.class':
        ('PharmacyScope$MerchBranches', 'OwnerScope$Customers', 'Empty scope is never ALL',
         'getMerchBranchIds', 'getProducerIds'),
    'kz/epharm/auth/access/UnifiedAccessStore.class':
        ('MANAGED', 'unified_admin_profiles', 'grants_json', 'for no key update',
         'loadAccount', 'UnifiedAuthorityLock'),
    'kz/epharm/auth/access/AdministrativeScope.class':
        ('currentMerchBranchIds', 'currentProducerIds', 'lockCurrentActor',
         'requireTaskReviewPermission', 'epharm.verified-admin-scope'),
    'kz/epharm/auth/access/UnifiedAdminInterceptor.class':
        ('getManaged', 'getProfile', 'getExplicitAccess', 'NOT_READY', 'authorizeScope', 'setAttribute'),
    'kz/epharm/auth/access/UnifiedAdminCatalog.class':
        ('/unified-admin-routes.json', '/training-role-ceilings.json', 'availableRoles', 'NOT_READY', 'allScope'),
}
SESSION_CLASSES = {
    'kz/epharm/auth/service/JwtService.class':
        ('getSessionVersion', 'adminSessionVersionOf', 'hasValidSignedAdminVersion'),
    'kz/epharm/auth/security/JwtAuthenticationFilter.class':
        ('adminSessionVersionOf', 'kz/epharm/auth/security/AdminSessionLoader', 'load'),
    'kz/epharm/auth/security/AdminSessionLoader.class':
        ('getSessionVersion', 'getStatusRaw', 'kz/epharm/auth/security/AdminPrincipal'),
}
SCHEMA_PROBE = """SELECT CASE WHEN
  to_regclass('public.pharmacist_wallet_entries') IS NOT NULL OR
  to_regclass('public.pharmacist_wallet_checkpoints') IS NOT NULL OR
  to_regclass('public.merch_executor_otp_challenges') IS NOT NULL OR
  to_regclass('public.merch_executor_grants') IS NOT NULL OR
  to_regclass('public.merch_executor_bindings') IS NOT NULL OR
  to_regclass('public.merch_reward_events') IS NOT NULL OR
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public'
         AND table_name='pending_bonuses' AND column_name='source_sale_id')
THEN 'protected' ELSE 'legacy' END;\n"""
ACCESS_SCHEMA_PROBE = """SELECT CASE WHEN
  to_regclass('public.unified_persons') IS NOT NULL OR
  to_regclass('public.unified_admin_profiles') IS NOT NULL OR
  to_regclass('public.unified_admin_access_audit') IS NOT NULL OR
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public'
         AND table_name='admin_users' AND column_name='access_mode')
THEN 'protected' ELSE 'legacy' END;\n"""
SESSION_SCHEMA_PROBE = """SELECT CASE WHEN
  EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='public'
         AND table_name='admin_users' AND column_name='session_version')
THEN 'protected' ELSE 'legacy' END;\n"""


def verify(jar_path: Path, schema_protected: bool, access_protected: bool = False,
           session_protected: bool = False) -> bool:
    with zipfile.ZipFile(jar_path) as jar:
        entries = jar.namelist()
        names = set(entries)
        if len(entries) != len(names):
            raise ValueError('Ambiguous duplicate artifact entries')
        access_code = any('BOOT-INF/classes/' + name in names for name in ACCESS_CLASSES) or any(
            re.fullmatch(r'BOOT-INF/classes/db/migration/V0*66__[^/]+\.sql', name) for name in names)
        # Presence is conservative even before any account becomes MANAGED. A failed
        # candidate can migrate the schema before its automatic rollback is attempted.
        if access_protected or access_code:
            for name, markers in ACCESS_CLASSES.items():
                path = 'BOOT-INF/classes/' + name
                if path not in names or jar.getinfo(path).file_size > 2 * 1024 * 1024:
                    raise ValueError('Target lacks the reviewed managed-access implementation')
                body = jar.read(path)
                if any(marker.encode('ascii') not in body for marker in markers):
                    raise ValueError('Target contains obsolete managed-access scope handling')
        session_code = any(re.fullmatch(r'BOOT-INF/classes/db/migration/V0*93__[^/]+\.sql', name)
                           for name in names)
        # Schema presence is durable; a zero-version check could race a password change before rollback.
        if session_protected or session_code:
            for name, markers in SESSION_CLASSES.items():
                path = 'BOOT-INF/classes/' + name
                if path not in names or jar.getinfo(path).file_size > 2 * 1024 * 1024:
                    raise ValueError('Target lacks the reviewed admin session-version implementation')
                body = jar.read(path)
                if any(marker.encode('ascii') not in body for marker in markers):
                    raise ValueError('Target contains obsolete admin session-version handling')
        if RESOURCE not in names:
            financial_code = f'BOOT-INF/classes/{WALLET}.class' in names or any(
                re.fullmatch(r'BOOT-INF/classes/db/migration/V0*(62|63|64)__[^/]+\.sql', name)
                for name in names)
            if schema_protected or financial_code:
                raise ValueError('Target lacks the reviewed wallet capability; legacy transition denied')
            return False
        if jar.getinfo(RESOURCE).file_size > 8192:
            raise ValueError('Invalid financial capability resource')
        rows = [line.split('=', 1) for line in jar.read(RESOURCE).decode('ascii').splitlines()
                if line and not line.startswith('#')]
        if len(rows) != len(CAPABILITY) or any(len(row) != 2 for row in rows) or dict(rows) != CAPABILITY:
            raise ValueError('Unknown or incomplete financial capability')
        for name, markers in WRITERS.items():
            path = 'BOOT-INF/classes/' + name
            if path not in names or jar.getinfo(path).file_size > 2 * 1024 * 1024:
                raise ValueError('Target lacks a required financial writer')
            body = jar.read(path)
            if any(marker.encode('ascii') not in body for marker in markers):
                raise ValueError('Target contains an obsolete financial writer')
    return True


def _schema_is_protected(probe: str, kind: str) -> bool:
    # The container already owns the DB credentials. Never read/print dotenv or app responses.
    result = subprocess.run(['docker', 'exec', '-i', 'epharm-postgres', 'sh', '-c',
        'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        input=probe, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15, check=True)
    state = result.stdout.strip()
    if state not in {'legacy', 'protected'}:
        raise ValueError(f'Cannot establish {kind} schema state')
    return state == 'protected'


def schema_is_protected() -> bool:
    return _schema_is_protected(SCHEMA_PROBE, 'financial')


def access_schema_is_protected() -> bool:
    return _schema_is_protected(ACCESS_SCHEMA_PROBE, 'managed-access')


def session_schema_is_protected() -> bool:
    return _schema_is_protected(SESSION_SCHEMA_PROBE, 'admin-session')


def verify_image(image: str) -> bool:
    protected = schema_is_protected()
    access_protected = access_schema_is_protected()
    session_protected = session_schema_is_protected()
    container = subprocess.check_output(['docker', 'create', '--network', 'none', image],
        text=True, stderr=subprocess.DEVNULL, timeout=15).strip()
    if not re.fullmatch(r'[a-f0-9]{64}', container):
        raise ValueError('Cannot inspect an exact financial image')
    try:
        with tempfile.TemporaryDirectory(prefix='epharm-financial-transition-') as directory:
            jar = Path(directory) / 'app.jar'
            subprocess.run(['docker', 'cp', f'{container}:/app/app.jar', str(jar)],
                check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
            return verify(jar, protected, access_protected, session_protected)
    finally:
        subprocess.run(['docker', 'rm', container], check=True, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=15)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--image', help='Exact backend sha256 image ID; probes the existing DB read-only')
    source.add_argument('--jar', type=Path, help='Offline artifact check only')
    parser.add_argument('--schema-protected', action='store_true', help='Offline --jar fixture represents migrated DB')
    parser.add_argument('--access-protected', action='store_true', help='Offline --jar fixture represents the V066 access schema')
    parser.add_argument('--session-protected', action='store_true', help='Offline --jar fixture represents the V093 admin-session schema')
    args = parser.parse_args()
    if args.image and (args.schema_protected or args.access_protected or args.session_protected or
                       not re.fullmatch(r'sha256:[a-f0-9]{64}', args.image)):
        parser.error('Production checks require an exact image ID and live read-only DB probe')
    try:
        capable = verify_image(args.image) if args.image else verify(
            args.jar, args.schema_protected, args.access_protected, args.session_protected)
    except (OSError, ValueError, KeyError, UnicodeError, RuntimeError, zipfile.BadZipFile, subprocess.SubprocessError):
        raise SystemExit('ERROR: financial/access/session transition denied or state unknown; keep current pins and use a reviewed wallet/RBAC/session-capable forward image') from None
    print('Financial transition contract OK: ' + ('wallet-capable artifact' if capable else 'legacy schema/artifact') + '; managed-access and admin-session guards checked')


if __name__ == '__main__':
    main()
