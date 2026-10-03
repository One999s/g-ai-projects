import copy
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).parent))
import audio_candidates as audio

PROJECT = Path(__file__).resolve().parents[1]
CANDIDATE = PROJECT / 'content/candidates/world-foundations-r1.json'
AUDIO = PROJECT / 'content/audio-candidates/world-foundations-en-r1'
METRICS = ('audioSha256', 'bytes', 'durationMillis', 'sampleRate', 'channels', 'peak', 'rms', 'clippedSamples')


class AudioManifestTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        (self.root / 'en').mkdir()
        self.manifest = json.loads((AUDIO / 'manifest.json').read_text())
        self.metrics = {e['id']: {k: e[k] for k in METRICS} for e in self.manifest['entries']}
        for entry in self.manifest['entries']: shutil.copy2(AUDIO / entry['transcriptFile'], self.root / entry['transcriptFile'])
    def tearDown(self): self.tmp.cleanup()
    def verify(self):
        (self.root / 'manifest.json').write_text(json.dumps(self.manifest))
        with patch.object(audio, 'inspect', side_effect=lambda path: self.metrics[path.stem]):
            return audio.verify(CANDIDATE, self.root)
    def test_valid_manifest_is_still_unapproved_and_chinese_missing(self):
        result = self.verify(); self.assertEqual(20, result['validCandidates']); self.assertFalse(result['approved']); self.assertFalse(result['listened']); self.assertEqual(['zh-CN'], result['missingLocales'])
    def test_approval_and_listening_claims_are_refused(self):
        for key in ('approved', 'humanListeningCompleted'):
            self.manifest[key] = True
            with self.assertRaises(ValueError): self.verify()
            self.manifest[key] = False
    def test_source_hash_drift_is_refused(self):
        self.manifest['candidateSha256'] = '0' * 64
        with self.assertRaises(ValueError): self.verify()
    def test_path_traversal_is_refused(self):
        self.manifest['entries'][0]['file'] = '../../outside.flac'
        with self.assertRaises(ValueError): self.verify()
    def test_transcript_option_drift_is_refused(self):
        entry = self.manifest['entries'][0]; (self.root / entry['transcriptFile']).write_text('Different options')
        with self.assertRaises(ValueError): self.verify()
    def test_audio_hash_drift_is_refused(self):
        self.manifest['entries'][0]['audioSha256'] = '0' * 64
        with self.assertRaises(ValueError): self.verify()
    def test_duplicate_audio_identity_is_refused(self):
        self.manifest['entries'][1] = copy.deepcopy(self.manifest['entries'][0])
        with self.assertRaises(ValueError): self.verify()
    def test_audio_longer_than_reading_window_is_refused(self):
        entry = self.manifest['entries'][0]; entry['durationMillis'] = 15000; self.metrics[entry['id']]['durationMillis'] = 15000
        with self.assertRaises(ValueError): self.verify()
    def test_missing_chinese_cannot_be_silently_removed(self):
        self.manifest['missingLocales'] = {}
        with self.assertRaises(ValueError): self.verify()
    def test_unknown_approval_metadata_is_refused(self):
        self.manifest['reviewedBy'] = 'invented'
        with self.assertRaises(ValueError): self.verify()


if __name__ == '__main__': unittest.main()
