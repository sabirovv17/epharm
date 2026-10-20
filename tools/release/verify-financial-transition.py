#!/usr/bin/env python3
"""Fail closed before an image transition could restore a pre-wallet financial writer."""
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


def verify(jar_path: Path, schema_protected: bool) -> bool:
    with zipfile.ZipFile(jar_path) as jar:
        entries = jar.namelist()
        names = set(entries)
        if len(entries) != len(names):
            raise ValueError('Ambiguous duplicate artifact entries')
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


def schema_is_protected() -> bool:
    # The container already owns the DB credentials. Never read/print dotenv or app responses.
    result = subprocess.run(['docker', 'exec', '-i', 'epharm-postgres', 'sh', '-c',
        'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        input=SCHEMA_PROBE, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15, check=True)
    state = result.stdout.strip()
    if state not in {'legacy', 'protected'}:
        raise ValueError('Cannot establish financial schema state')
    return state == 'protected'


def verify_image(image: str) -> bool:
    protected = schema_is_protected()
    container = subprocess.check_output(['docker', 'create', '--network', 'none', image],
        text=True, stderr=subprocess.DEVNULL, timeout=15).strip()
    if not re.fullmatch(r'[a-f0-9]{64}', container):
        raise ValueError('Cannot inspect an exact financial image')
    try:
        with tempfile.TemporaryDirectory(prefix='epharm-financial-transition-') as directory:
            jar = Path(directory) / 'app.jar'
            subprocess.run(['docker', 'cp', f'{container}:/app/app.jar', str(jar)],
                check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
            return verify(jar, protected)
    finally:
        subprocess.run(['docker', 'rm', container], check=True, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, timeout=15)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--image', help='Exact backend sha256 image ID; probes the existing DB read-only')
    source.add_argument('--jar', type=Path, help='Offline artifact check only')
    parser.add_argument('--schema-protected', action='store_true', help='Offline --jar fixture represents migrated DB')
    args = parser.parse_args()
    if args.image and (args.schema_protected or not re.fullmatch(r'sha256:[a-f0-9]{64}', args.image)):
        parser.error('Production checks require an exact image ID and live read-only DB probe')
    try:
        capable = verify_image(args.image) if args.image else verify(args.jar, args.schema_protected)
    except (OSError, ValueError, KeyError, UnicodeError, RuntimeError, zipfile.BadZipFile, subprocess.SubprocessError):
        raise SystemExit('ERROR: financial transition denied or state unknown; keep current pins and use a reviewed wallet-capable forward image') from None
    print('Financial transition contract OK: ' + ('wallet-capable artifact' if capable else 'legacy schema/artifact'))


if __name__ == '__main__':
    main()
