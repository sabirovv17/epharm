import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import warnings
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location('financial_transition',Path(__file__).resolve().parents[1] / 'verify-financial-transition.py')
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)


class FinancialTransitionTests(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory(prefix='financial-transition-test-')
        self.addCleanup(self.directory.cleanup)
        self.jar=Path(self.directory.name)/'app.jar'

    def artifact(self,resource=True,missing=None,obsolete=None,partial=False):
        with zipfile.ZipFile(self.jar,'w') as jar:
            if resource:
                jar.writestr(guard.RESOURCE,'\n'.join(f'{key}={value}' for key,value in guard.CAPABILITY.items()))
            if resource or partial:
                for name,markers in guard.WRITERS.items():
                    if name==missing: continue
                    jar.writestr('BOOT-INF/classes/'+name,' '.join(markers[1:] if name==obsolete else markers))
            else:
                jar.writestr('BOOT-INF/classes/db/migration/V061__posm_staff_auth.sql','legacy fixture')
        return self.jar

    def test_legacy_artifact_only_allowed_before_protected_schema(self):
        self.artifact(resource=False)
        self.assertFalse(guard.verify(self.jar,False))
        with self.assertRaises(ValueError): guard.verify(self.jar,True)

    def test_reviewed_artifact_allowed_on_both_schema_states(self):
        self.artifact()
        self.assertTrue(guard.verify(self.jar,False))
        self.assertTrue(guard.verify(self.jar,True))

    def test_descriptor_alone_cannot_hide_an_old_or_missing_writer(self):
        for name in guard.WRITERS:
            with self.subTest(writer=name):
                self.artifact(missing=name)
                with self.assertRaises(ValueError): guard.verify(self.jar,True)
                self.artifact(obsolete=name)
                with self.assertRaises(ValueError): guard.verify(self.jar,True)

    def test_new_financial_code_without_capability_rejected_before_migration_too(self):
        self.artifact(resource=False,partial=True)
        with self.assertRaises(ValueError): guard.verify(self.jar,False)

    def test_financial_migration_without_capability_rejected(self):
        with zipfile.ZipFile(self.jar,'w') as jar:
            jar.writestr('BOOT-INF/classes/db/migration/V064__recommendation_sale_bonus_source.sql','new')
        with self.assertRaises(ValueError): guard.verify(self.jar,False)

    def test_unknown_or_duplicate_capability_rejected(self):
        for body in ('epharm.finance.wallet=1\n','epharm.finance.wallet=2\n',
                     'epharm.finance.wallet=1\nepharm.finance.wallet=1\nepharm.finance.receipt-decisions=1\n'):
            with zipfile.ZipFile(self.jar,'w') as jar: jar.writestr(guard.RESOURCE,body)
            with self.assertRaises(ValueError): guard.verify(self.jar,True)

    def test_duplicate_zip_paths_rejected_instead_of_trusting_last_writer(self):
        self.artifact()
        with warnings.catch_warnings():
            warnings.simplefilter('ignore',UserWarning)
            with zipfile.ZipFile(self.jar,'a') as jar:
                jar.writestr(guard.RESOURCE,'epharm.finance.wallet=1\n')
        with self.assertRaises(ValueError): guard.verify(self.jar,True)

    def test_db_probe_uses_only_catalog_existence_not_racy_zero_counts_or_credentials(self):
        result=subprocess.CompletedProcess([],0,'protected\n','')
        with patch.object(guard.subprocess,'run',return_value=result) as run:
            self.assertTrue(guard.schema_is_protected())
        command=run.call_args.args[0]
        self.assertEqual(command[:4],['docker','exec','-i','epharm-postgres'])
        self.assertNotIn('password',' '.join(command).lower())
        sql=run.call_args.kwargs['input'].lower()
        self.assertIn('to_regclass',sql)
        self.assertNotIn('count(',sql)
        for mutation in ('insert ','update ','delete ','drop ','alter '): self.assertNotIn(mutation,sql)

    def test_unknown_schema_output_and_db_failure_fail_closed(self):
        for result in ('','0','protected\nlegacy','arbitrary private output'):
            with patch.object(guard.subprocess,'run',return_value=subprocess.CompletedProcess([],0,result,'')):
                with self.assertRaises(ValueError): guard.schema_is_protected()
        with patch.object(guard.subprocess,'run',side_effect=subprocess.CalledProcessError(1,['docker'],stderr='must-not-leak-password')):
            with patch('sys.argv',['guard','--image','sha256:'+'a'*64]):
                with self.assertRaises(SystemExit) as error: guard.main()
        self.assertNotIn('must-not-leak',str(error.exception))

    def test_image_inspection_never_starts_backend_and_cleans_container_after_denial(self):
        source=self.artifact(resource=False)
        image='sha256:'+'a'*64
        container='b'*64
        calls=[]
        def run(command,**kwargs):
            calls.append(command)
            if command[1]=='cp': shutil.copyfile(source,command[-1])
            return subprocess.CompletedProcess(command,0,'','')
        with patch.object(guard,'schema_is_protected',return_value=True), \
             patch.object(guard.subprocess,'check_output',return_value=container) as create, \
             patch.object(guard.subprocess,'run',side_effect=run):
            with self.assertRaises(ValueError): guard.verify_image(image)
        self.assertEqual(create.call_args.args[0],['docker','create','--network','none',image])
        self.assertEqual(calls[-1],['docker','rm',container])
        self.assertFalse(any(command[1] in {'start','run','compose'} for command in calls))

    def test_exact_image_required_and_live_schema_probe_cannot_be_overridden(self):
        for args in (['--image','epharm/backend:latest'],['--image','sha256:'+'a'*64,'--schema-protected']):
            with patch('sys.argv',['guard']+args),patch.object(guard,'verify_image') as inspect:
                with self.assertRaises(SystemExit): guard.main()
                inspect.assert_not_called()


if __name__=='__main__': unittest.main()
