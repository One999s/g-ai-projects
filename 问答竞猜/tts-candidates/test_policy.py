import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import local_policy as p

class PolicyTests(unittest.TestCase):
 def test_onnx_presence_rejected_without_import(self):
  with patch.object(p.importlib.util,'find_spec',return_value=object()):
   with self.assertRaisesRegex(RuntimeError,'ONNX'):p.prepare_environment()
 def test_offline_and_thread_limits_precede_native_import(self):
  with patch.object(p.importlib.util,'find_spec',return_value=None),patch.dict(os.environ,{},clear=True):
   p.prepare_environment();self.assertEqual('1',os.environ['HF_HUB_OFFLINE']);self.assertEqual('1',os.environ['HF_HUB_DISABLE_TELEMETRY']);self.assertEqual('2',os.environ['OMP_NUM_THREADS'])
 def make(self,root):
  names=['melo/config.json','melo/checkpoint.pth','bert/config.json','bert/model.safetensors','bert/tokenizer.json','bert/tokenizer_config.json','bert/vocab.txt']
  d={'schemaVersion':1,'sources':[],'files':{}}
  for n in names:
   q=root/n;q.parent.mkdir(exist_ok=True);q.write_bytes(b'test fixture')
   d['files'][n]={'size':12,'sha256':hashlib.sha256(q.read_bytes()).hexdigest()}
  m=root/'manifest.json';m.write_text(json.dumps(d));return m,d
 def test_complete_hash_bound_local_inventory(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp).resolve();m,d=self.make(root);self.assertEqual(root,p.verified_bundle(root,m))
 def test_tampered_missing_and_symlink_model_rejected(self):
  for mode in ['tamper','missing','symlink']:
   with tempfile.TemporaryDirectory() as tmp:
    root=Path(tmp).resolve();m,d=self.make(root);q=root/'melo/config.json';q.unlink()
    if mode=='tamper':q.write_bytes(b'wrong length')
    if mode=='symlink':q.symlink_to(root/'bert/config.json')
    with self.assertRaises(ValueError):p.verified_bundle(root,m)
 def test_extra_remote_or_path_traversal_inventory_rejected(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp).resolve();m,d=self.make(root);d['files']['../other']={'size':1,'sha256':'0'*64};m.write_text(json.dumps(d))
   with self.assertRaises(ValueError):p.verified_bundle(root,m)
 def test_vendor_hashes_match_and_no_unbounded_loader_is_vendored(self):
  here=Path(__file__).parent;d=json.loads((here/'vendor-provenance.json').read_text())
  for e in d['files']:
   b=(here/'vendor'/e['path']).read_bytes();self.assertEqual(e['adaptedSha256'],hashlib.sha256(b).hexdigest())
   if e['path'].endswith('.py'):
    for bad in [b'pickle.load(',b'from_pretrained(',b'hf_hub_download',b'import onnxruntime']:
     self.assertNotIn(bad,b)
 def test_renderer_static_local_loading_contract(self):
  source=(Path(__file__).parent/'render_one.py').read_text()
  for required in ['weights_only=True','local_files_only=True,use_safetensors=True','torch.set_num_threads(2)','socket.connect','LIMIT=3*1024**3']:
   self.assertIn(required,source)
  self.assertNotIn('weights_only=False',source)

if __name__=='__main__':unittest.main()
