"""Fake admin image verifier for shell release contracts only."""
import os

if os.environ.get('TEST_BAD_ADMIN_ARTIFACT') == 'true':
    raise SystemExit('Obsolete admin artifact fixture')
