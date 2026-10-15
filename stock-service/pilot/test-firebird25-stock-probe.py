#!/usr/bin/env python3
"""Validate the actual exported PowerShell probe SQL in offline Firebird fixtures."""
from pathlib import Path
import re
import sys
from importlib.util import module_from_spec, spec_from_file_location

spec = spec_from_file_location('metadata_test', Path(__file__).with_name('test-firebird25-metadata.py'))
metadata_test = module_from_spec(spec)
spec.loader.exec_module(metadata_test)

FIXTURE = """
SET BAIL ON;
SET AUTODDL OFF;
CREATE TABLE WAREBASE (
 PART_ID BIGINT NOT NULL, WARE_ID VARCHAR(16), BCODE_IZG VARCHAR(32), BARCODE VARCHAR(32), SNAME VARCHAR(80),
 QUANT DOUBLE PRECISION, REALQUANT DOUBLE PRECISION, BLOCK_QUANT DOUBLE PRECISION, BLOCK_COUNT INTEGER,
 GODENDO TIMESTAMP, ENDDT TIMESTAMP, EDIZM VARCHAR(16), PART_TYPE INTEGER, BASE_AGENT_ID BIGINT,
 DEP BIGINT, UPDATEDT TIMESTAMP, CONSTRAINT PK_WAREBASE PRIMARY KEY (PART_ID));
CREATE INDEX WAREBASE_IDX2 ON WAREBASE (BCODE_IZG);
CREATE DESCENDING INDEX WAREBASE_IDX5 ON WAREBASE (PART_ID);
COMMIT;
INSERT INTO WAREBASE (PART_ID, BCODE_IZG, SNAME, QUANT, REALQUANT, BLOCK_QUANT, BLOCK_COUNT)
 VALUES (123, '4601234567890', 'Synthetic fixture A', 2.5, 3.5, 1, 1);
INSERT INTO WAREBASE (PART_ID, BCODE_IZG, SNAME, QUANT, REALQUANT, BLOCK_QUANT, BLOCK_COUNT)
 VALUES (124, '4601234567890', 'Synthetic fixture B', 0, 0, NULL, NULL);
INSERT INTO WAREBASE (PART_ID, BCODE_IZG, SNAME, QUANT, REALQUANT, BLOCK_QUANT, BLOCK_COUNT)
 VALUES (125, '0000000000000', 'Unrelated synthetic fixture', 99, 99, 0, 0);
INSERT INTO WAREBASE (PART_ID, QUANT) VALUES (126, 10);
INSERT INTO WAREBASE (PART_ID, QUANT) VALUES (127, 20);
INSERT INTO WAREBASE (PART_ID, QUANT) VALUES (128, 30);
INSERT INTO WAREBASE (PART_ID, QUANT) VALUES (129, 40);
SET TERM ^;
EXECUTE BLOCK AS DECLARE N INTEGER;
BEGIN
 N = 1000;
 WHILE (N < 11000) DO BEGIN
   INSERT INTO WAREBASE (PART_ID, QUANT) VALUES (:N, 0);
   INSERT INTO WAREBASE (PART_ID, QUANT) VALUES (:N + 10000, 100);
   N = N + 1;
 END
END^
SET TERM ;^
COMMIT;
"""


def main() -> None:
    directory = Path(sys.argv[1])
    for kind in ('barcode', 'part', 'recent'):
        for phase in ('plan', 'sample'):
            sql = (directory / f'{kind}-{phase}.sql').read_text()
            code, output = metadata_test.run_sql(FIXTURE + sql)
            metadata_test.require(code == 0 and 'SQLSTATE' not in output,
                                  f'{kind}/{phase} SQL must run without errors', output)
            for expected in (
                r'(?m)^DIAGNOSTIC_READ_ONLY\s+TRUE\s*$',
                r'(?m)^PROBE_FIELD_COUNT\s+16\s*$',
                r'(?m)^PROBE_COMPUTED_COUNT\s+0\s*$',
                r'(?m)^PROBE_TABLE_TYPE\s+0\s*$',
                r'(?m)^PROBE_SCHEMA_GUARD\s+1\s*$',
                r'(?m)^DIAGNOSTIC_COMPLETE\s+EPHARM_STOCK_' + phase.upper() + r'_COMPLETE_V8\s*$',
            ):
                metadata_test.require(re.search(expected, output) is not None, 'Missing evidence: ' + expected, output)
            index = 'WAREBASE_IDX2' if kind == 'barcode' else 'WAREBASE_IDX5' if kind == 'recent' else 'PK_WAREBASE'
            plans = re.findall(r'(?m)^PLAN\s+([^\r\n]+)', output)
            expected_plan = f'(W ORDER {index})' if kind == 'recent' else f'(W INDEX ({index}))'
            metadata_test.require(len(plans) == 1 and plans[0].strip() == expected_plan,
                                  'Expected a single forced index plan', output)
            rows = re.findall(r'(?m)^SAMPLE_PART_ID\s+(\d+)\s*$', output)
            expected_rows = [] if phase == 'plan' else ['123', '124'] if kind == 'barcode' else ['20995', '20996', '20997', '20998', '20999'] if kind == 'recent' else ['123']
            metadata_test.require(sorted(rows) == expected_rows, 'Unexpected product rows; PLANONLY must read none', output)
            if phase == 'sample':
                metadata_test.require('Elapsed time' in output and 'Fetches' in output,
                                      'Sample must include isql statement statistics', output)
                if kind == 'recent':
                    fetches = re.search(r'(?im)^\s*Fetches\s*=\s*(\d+)', output)
                    metadata_test.require(fetches is not None and int(fetches.group(1)) < 500,
                                          'One ID page must not search through the 20,000 quantity values', output)
            (directory / f'{kind}-{phase}.output').write_text(output)
            print(f'PASS: Firebird 2.5.9 {kind}/{phase}; readonly; {index}; {len(rows)} synthetic product rows')
    changed_schema = """
ALTER TABLE WAREBASE DROP QUANT;
COMMIT;
ALTER TABLE WAREBASE ADD QUANT COMPUTED BY (REALQUANT);
COMMIT;
"""
    code, output = metadata_test.run_sql(FIXTURE + changed_schema + (directory / 'barcode-sample.sql').read_text())
    metadata_test.require(code != 0 and 'SQLSTATE' in output and 'SAMPLE_PART_ID' not in output,
                          'A changed computed source must abort before any product rows', output)
    print('PASS: schema changed to computed quantity; server guard aborts before product SELECT')


if __name__ == '__main__':
    main()
