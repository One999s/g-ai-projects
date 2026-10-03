import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess
import sys
import unittest
import wave

HERE=Path(__file__).resolve().parent
PROJECT=HERE.parent
PILOT=PROJECT/'content/audio-candidates/world-foundations-zh-pilot'
sys.path.insert(0,str(PROJECT/'tools'))
import content_candidates as c

class PilotTests(unittest.TestCase):
 def test_pilot_matches_current_original_text_and_not_answer_explanation(self):
  pack=c.load(PROJECT/'content/candidates/world-foundations-r1.json');row=next(q for q in pack['questions'] if q['id']=='world-001')
  text=c.transcript(row,'zh-CN');self.assertEqual(text+'\n',(PILOT/'transcript.txt').read_text())
  r=json.loads((PILOT/'receipt.json').read_text());self.assertEqual(hashlib.sha256(text.encode()).hexdigest(),r['textSha256'])
  self.assertEqual(hashlib.sha256((PROJECT/'content/candidates/world-foundations-r1.json').read_bytes()).hexdigest(),r['sourceCandidateSha256'])
 def test_actual_pcm_bytes_hash_and_reading_margin(self):
  r=json.loads((PILOT/'receipt.json').read_text());b=(PILOT/'candidate.wav').read_bytes()
  self.assertEqual(hashlib.sha256(b).hexdigest(),r['audioSha256']);self.assertEqual(b[:4],b'RIFF');self.assertEqual(b[36:40],b'data')
  self.assertEqual(struct.unpack_from('<I',b,40)[0]+44,len(b))
  with wave.open(str(PILOT/'candidate.wav'),'rb') as f:
   self.assertEqual((1,2,16000),(f.getnchannels(),f.getsampwidth(),f.getframerate()));duration=math.ceil(f.getnframes()*1000/16000)
   samples=struct.unpack('<'+'h'*f.getnframes(),f.readframes(f.getnframes()));self.assertGreater(max(abs(s) for s in samples),100)
  self.assertEqual(duration,r['durationMillis']);self.assertLessEqual(duration+1000,r['readingMillis'])
 def test_no_listening_approval_network_or_user_audio_claim(self):
  r=json.loads((PILOT/'receipt.json').read_text())
  for k in ['listened','approved','realUserAudio','nativeNetworkObservation']:self.assertIs(r[k],False)
  self.assertLess(r['peakRssKiB'],3*1024*1024)
 def test_real_run_receipt_binds_exact_renderer_and_model_inventory(self):
  r=json.loads((PILOT/'receipt.json').read_text())
  self.assertEqual(hashlib.sha256((HERE/'render_one.py').read_bytes()).hexdigest(),r['rendererSha256'])
  self.assertEqual(hashlib.sha256((HERE/'model-source.json').read_bytes()).hexdigest(),r['modelManifestSha256'])
 def test_process_audit_guard_rejects_network_and_subprocess_events_without_executing_them(self):
  # Synthetic audit events only: no network operation is performed by this test.
  code="""import sys
sys.path.insert(0,sys.argv[1])
from render_one import guard_runtime
guard_runtime()
for name in ['socket.connect','socket.getaddrinfo','socket.bind','subprocess.Popen','os.system']:
 try:sys.audit(name)
 except RuntimeError:pass
 else:raise AssertionError(name)
print('guarded')
"""
  result=subprocess.run([sys.executable,'-c',code,str(HERE)],capture_output=True,text=True,timeout=5)
  self.assertEqual(0,result.returncode,result.stderr);self.assertIn('guarded',result.stdout)

if __name__=='__main__':unittest.main()
