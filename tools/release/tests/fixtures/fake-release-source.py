"""Isolated promotion gate; real GitHub evidence has separate unit tests."""
import os
import re
import sys

if len(sys.argv) != 2 or not re.fullmatch(r'[a-f0-9]{40}', sys.argv[1]):
    raise SystemExit('Invalid release source fixture identity')
if os.environ.get('TEST_BAD_RELEASE_SOURCE') == 'true':
    raise SystemExit('Unverified release source fixture')
