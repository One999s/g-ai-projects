import importlib.util
import pathlib
import sys
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('release', pathlib.Path(__file__).with_name('build_release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseReceiptTest(unittest.TestCase):
    def test_digest_streams_exact_bytes(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'file'
            path.write_bytes(b'abc')
            self.assertEqual('ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad', release.digest(path))

    def test_test_counts_preserve_skips(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp)
            (path / 'TEST-one.xml').write_text('<testsuite tests="10" failures="0" errors="0" skipped="3"/>')
            self.assertEqual(7, release.report_counts(path)['passed'])
            self.assertEqual(3, release.report_counts(path)['skipped'])

    def test_absent_or_failed_reports_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp)
            with self.assertRaises(RuntimeError): release.report_counts(path)
            (path / 'TEST-one.xml').write_text('<testsuite tests="1" failures="1" errors="0" skipped="0"/>')
            with self.assertRaises(RuntimeError): release.report_counts(path)

    def test_jar_without_runtime_guard_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'app.jar'
            with zipfile.ZipFile(path, 'w') as jar: jar.writestr('BOOT-INF/classes/Fake.class', b'')
            with self.assertRaises(RuntimeError): release.inspect_jar(path)

    def test_test_identity_is_rejected_even_when_required_runtime_exists(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'app.jar'
            with zipfile.ZipFile(path, 'w') as jar:
                for name in ['QuizApplication','api/IdentityAdmission','quota/RedisRequestQuota','api/GameHttpFlowTest$TestIdentity']:
                    jar.writestr('BOOT-INF/classes/com/gaiprojects/quiz/' + name + '.class', b'')
            with self.assertRaises(RuntimeError): release.inspect_jar(path)

    def test_required_runtime_with_no_fixture_marker_is_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / 'app.jar'
            with zipfile.ZipFile(path, 'w') as jar:
                for name in ['QuizApplication', 'api/IdentityAdmission', 'quota/RedisRequestQuota', 'speech/VoiceService', 'narration/NarrationBank', 'api/NarrationController']:
                    jar.writestr('BOOT-INF/classes/com/gaiprojects/quiz/' + name + '.class', b'fixture-free')
            self.assertTrue(release.inspect_jar(path)['testFixturesAbsent'])

    def test_ignored_local_secret_and_model_files_are_not_copied(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp); project = root / 'quiz'; output = root / 'out'
            (project / 'deploy').mkdir(parents=True)
            (project / 'deploy/RUNBOOK.md').write_text('Reviewed documentation')
            (project / 'deploy/.env').write_text('PRIVATE_LOCAL_ONLY')
            (project / 'deploy/model.bin').write_bytes(b'UNTRACKED')
            release.copy_tracked_project_inputs(root, project, output, {'quiz/deploy/RUNBOOK.md': 'hash'})
            self.assertTrue((output / 'deploy/RUNBOOK.md').exists())
            self.assertFalse((output / 'deploy/.env').exists())
            self.assertFalse((output / 'deploy/model.bin').exists())

    def test_hidden_frontend_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp); dist = root / 'dist'; dist.mkdir()
            (dist / '.env').write_text('PRIVATE_LOCAL_ONLY')
            with self.assertRaises(RuntimeError): release.copy_frontend(dist, root / 'out')


if __name__ == '__main__': unittest.main()
