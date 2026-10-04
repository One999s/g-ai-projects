import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
import unittest
import render_answer

HERE=Path(__file__).resolve().parent
FIXTURES=HERE.parent/'speech-worker/fixtures'

class AnswerFixtureTests(unittest.TestCase):
 key='third-option'
 @property
 def fixture(self):return FIXTURES/('synthetic-zh-'+self.key+'-v1')
 def test_fixed_text_renderer_and_shared_guard_provenance(self):
  receipt=json.loads((self.fixture/'receipt.json').read_text())
  self.assertEqual(render_answer.TEXTS[self.key]+'\n',(self.fixture/'transcript.txt').read_text())
  self.assertEqual(receipt['textSha256'],hashlib.sha256(render_answer.TEXTS[self.key].encode()).hexdigest())
  for field,name in [('rendererSha256','render_answer.py'),('sharedGuardSha256','render_one.py'),('modelManifestSha256','model-source.json')]:
   self.assertEqual(receipt[field],hashlib.sha256((HERE/name).read_bytes()).hexdigest())
 def test_full_untruncated_pcm_is_valid_for_answer_upload(self):
  receipt=json.loads((self.fixture/'receipt.json').read_text());data=(self.fixture/'candidate.wav').read_bytes()
  self.assertEqual(receipt['audioSha256'],hashlib.sha256(data).hexdigest())
  self.assertEqual(data[:4],b'RIFF');self.assertEqual(data[8:16],b'WAVEfmt ');self.assertEqual(data[36:40],b'data')
  self.assertEqual(struct.unpack_from('<IHHIIHH',data,16),(16,1,1,16000,32000,2,16))
  self.assertEqual(struct.unpack_from('<I',data,40)[0],len(data)-44)
  self.assertTrue(3244<=len(data)<=192044)
  self.assertEqual(receipt['durationMillis'],((len(data)-44)*1000+31999)//32000)
  self.assertGreater(receipt['rms'],.002)
 def test_no_listening_device_or_network_acceptance_claim(self):
  receipt=json.loads((self.fixture/'receipt.json').read_text())
  for key in ['approved','listened','realUserAudio','nativeNetworkObservation']:self.assertIs(receipt[key],False)
  self.assertEqual(receipt['expectedChoice'],2);self.assertLess(receipt['peakRssKiB'],3*1024*1024)
 def test_help_needs_no_model_and_cannot_accept_arbitrary_speech_or_recording(self):
  result=subprocess.run([sys.executable,str(HERE/'render_answer.py'),'--help'],capture_output=True,text=True,timeout=5)
  self.assertEqual(result.returncode,0,result.stderr)
  for forbidden in ['--text','--audio','--speaker','--url','--candidate']:self.assertNotIn(forbidden,result.stdout)
  source=(HERE/'render_answer.py').read_text()
  self.assertLess(source.index('guard_runtime()'),source.index('    import torch'))
  for required in ['verified_bundle(','weights_only=True','local_files_only=True,use_safetensors=True','torch.set_num_threads(1)']:self.assertIn(required,source)

class AnswerThreeFixtureTests(AnswerFixtureTests):
 key='answer-three'

if __name__=='__main__':unittest.main()
