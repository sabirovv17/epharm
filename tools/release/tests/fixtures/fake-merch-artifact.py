"""Mock only image extraction; the real ZIP contract has separate corruption tests."""
import os

if os.environ.get('TEST_BAD_MERCH_ARTIFACT') == 'true':
    raise SystemExit('Obsolete merchandising artifact fixture')
if os.environ.get('TEST_EVENTS'):
    with open(os.environ['TEST_EVENTS'], 'a') as events:
        events.write('artifact\n')
