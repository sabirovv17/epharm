import csv
import io
import json
import stat
import tempfile
import unittest
from collections import Counter
from pathlib import Path
from pim_validation import HEADER, ValidationError, normalize_row, normalize_records, validate_file


def row(**changes):
    values = ['123', '000088C7-9968-48E3-AD13-C343B4FFC9D1', 'Product', '233', 'Pharmacy',
              '0', '0', '0', '0', '0', '0', '105.50', '70', '1', '2', 'Supplier', 'LOT-1',
              '12/31/2027', '1', '']
    for key, value in changes.items(): values[HEADER.index(key)] = value
    return values


class ValidationTests(unittest.TestCase):
    def normalize(self, values):
        self.quality=Counter();self.audit=[]
        return normalize_row(values,1,self.quality,self.audit.append)

    def test_uuid_case_and_expiry(self):
        r=self.normalize(row());self.assertEqual(r[1],r[1].lower());self.assertEqual(r[17],'2027-12-31 00:00:00')
    def test_us_datetime(self):
        self.assertEqual(self.normalize(row(EXPIRATION_DATE='6/2/2027 9:35:00 PM'))[17],'2027-06-02 21:35:00')
    def test_undefined_byte_text_audited(self):
        r=self.normalize(row(SERIES='x\udc98y'));self.assertEqual(r[16],'x\ufffdy');self.assertEqual(self.audit[0]['original_bytes_hex'],'789879')
    def test_undefined_byte_numeric_rejected(self):
        with self.assertRaises(ValidationError):self.normalize(row(RETAIL_PRICE='1\udc98'))
    def test_invalid_uuid_preserved_and_quarantined(self):
        r=self.normalize(row(WARE_ID='9D1A;54F-5B70-42AE-8DD5-35E964A2BF5D',SNAME=''))
        self.assertEqual(r[1],'9D1A;54F-5B70-42AE-8DD5-35E964A2BF5D');self.assertEqual(self.quality['invalid_ware_rows'],1)
    def test_blank_uuid_preserved(self):
        self.normalize(row(WARE_ID='',SNAME=''));self.assertEqual(self.quality['blank_ware_rows'],1)
    def test_optional_empty_numbers_preserved(self):
        r=self.normalize(row(RETAIL_PRICE='',COST_PRICE=''));self.assertEqual(r[11:13],['','']);self.assertEqual(self.quality['missing_revenue'],1)
    def test_required_empty_numeric_rejected(self):
        with self.assertRaises(ValidationError):self.normalize(row(STOCK_AT_THE_END=''))
    def test_negative_and_fractional_stock_preserved(self):
        self.assertEqual(self.normalize(row(STOCK_AT_THE_END='-0.6'))[14],'-0.6')
    def test_nan_rejected(self):
        with self.assertRaises(ValidationError):self.normalize(row(STOCK_AT_THE_END='NaN'))
    def test_infinity_rejected(self):
        with self.assertRaises(ValidationError):self.normalize(row(STOCK_AT_THE_END='1e9999'))
    def test_invalid_integer_rejected(self):
        with self.assertRaises(ValidationError):self.normalize(row(PART_ID='2.5'))
    def test_invalid_date_rejected(self):
        with self.assertRaises(ValidationError):self.normalize(row(EXPIRATION_DATE='13/31/2027'))
    def test_malformed_row_rejected(self):
        with self.assertRaises(ValidationError):self.normalize(row()[:-1])
    def test_header_rejected(self):
        with self.assertRaises(ValidationError):normalize_records(iter([HEADER[:-1],row()]),io.StringIO(),Counter(),lambda x:None)
    def test_duplicate_batch_rejected(self):
        with self.assertRaises(ValidationError):normalize_records(iter([HEADER,row(),row()]),io.StringIO(),Counter(),lambda x:None)
    def test_same_batch_other_pharmacy_is_valid(self):
        other=row();other[3]='234';r=normalize_records(iter([HEADER,row(),other]),io.StringIO(),Counter(),lambda x:None)
        self.assertEqual(r['rows'],2)
    def test_no_partial_artifact_on_interruption(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'2026-09-06.csv'
            with p.open('w',encoding='cp1251',newline='') as f:
                writer=csv.writer(f,delimiter='\t');writer.writerows([HEADER,row(),row(PART_ID='124')])
            with self.assertRaises(InterruptedError):validate_file(p,Path(temp)/'artifacts',interrupt_after=1)
            self.assertFalse(list((Path(temp)/'artifacts').rglob('manifest.json')))
            self.assertFalse(list((Path(temp)/'artifacts').rglob('normalized.tsv')))
            self.assertEqual(len(list((Path(temp)/'artifacts').rglob('failure.json'))),1)
    def test_complete_file_hash_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'2026-09-06.csv'
            with p.open('w',encoding='cp1251',newline='') as f:csv.writer(f,delimiter='\t').writerows([HEADER,row()])
            m,artifact=validate_file(p,Path(temp)/'artifacts',p.stat().st_size)
            self.assertEqual(m['rows'],1);self.assertEqual(m['discarded_rows'],0);self.assertTrue((artifact/'normalized.tsv').exists())
            self.assertEqual(stat.S_IMODE(artifact.stat().st_mode)&0o077,0)
    def test_truncated_download_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'2026-09-06.csv';p.write_bytes(b'not complete')
            with self.assertRaises(ValidationError):validate_file(p,Path(temp)/'artifacts',100)


if __name__=='__main__':unittest.main()
