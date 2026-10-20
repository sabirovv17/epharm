#!/usr/bin/env python3
"""Shell wiring fixture only; actual artifact/schema logic has separate Python tests."""
import argparse
import os

parser = argparse.ArgumentParser()
parser.add_argument('--image', required=True)
args = parser.parse_args()
if args.image == os.environ.get('TEST_FINANCE_DENY_IMAGE'):
    raise SystemExit('ERROR: Financial transition denied fixture')
