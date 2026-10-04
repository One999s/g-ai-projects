import http.client
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import threading
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('worker',Path(__file__).with_name('private_asr.py'))
worker=importlib.util.module_from_spec(spec);spec.loader.exec_module(worker)


def wave():
    data=b'\x00\x00'*16000
    return b'RIFF'+struct.pack('<I',len(data)+36)+b'WAVEfmt '+struct.pack('<IHHIIHH',16,1,1,16000,32000,2,16)+b'data'+struct.pack('<I',len(data))+data


class WorkerContract(unittest.TestCase):
    def setUp(self):
        self.calls=[];self.result='Option B'
        def process(data,locale):self.calls.append((data,locale));return self.result
        self.server=worker.make_server(process,0);self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def tearDown(self):self.server.shutdown();self.server.server_close();self.thread.join(2)
    def request(self,body=None,headers=None,path='/internal/quiz/asr',method='POST'):
        con=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=3)
        con.request(method,path,body,headers or {'Content-Type':'audio/wav','X-Quiz-Language':'en'})
        response=con.getresponse();value=json.loads(response.read());status=response.status;con.close();return status,value
    def test_actual_loopback_contract_without_claiming_real_recognition(self):
        status,body=self.request(wave());self.assertEqual((200,{'text':'Option B'}),(status,body));self.assertEqual([(wave(),'en')],self.calls)
    def test_invalid_pcm_never_reaches_processor(self):
        self.assertEqual(400,self.request(b'x'*32044)[0]);self.assertEqual([],self.calls)
    def test_wrong_type_and_large_body_are_rejected(self):
        self.assertEqual(415,self.request(wave(),{'Content-Type':'audio/webm','X-Quiz-Language':'en'})[0]);self.assertEqual(413,self.request(b'x'*192045)[0]);self.assertEqual([],self.calls)
    def test_unknown_locale_route_and_encoding_are_rejected(self):
        self.assertEqual(400,self.request(wave(),{'Content-Type':'audio/wav','X-Quiz-Language':'fr'})[0]);self.assertEqual(404,self.request(wave(),path='/internal/quiz/asr?url=elsewhere')[0]);self.assertEqual(415,self.request(wave(),{'Content-Type':'audio/wav','X-Quiz-Language':'en','Content-Encoding':'gzip'})[0])
    def test_overlong_or_nontext_model_response_is_sanitized(self):
        for text in ['x'*161,None,'B\nsecret']:
            self.result=text;self.assertEqual((503,{'error':'RECOGNITION_UNAVAILABLE'}),self.request(wave()))
    def test_health_does_not_expose_model_path_or_identity(self):
        self.assertEqual((200,{'ready':True,'audioPersisted':False}),self.request(path='/health',method='GET'))
    def test_canonical_header_fields_are_not_ignored(self):
        for offset in [0,4,8,12,16,20,22,24,28,32,34,36,40]:
            data=bytearray(wave());data[offset]^=1
            with self.assertRaises(ValueError):worker.pcm_bytes(data)
    def test_model_requires_local_hash_manifest_before_import(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):worker.verified_model(directory)
            p=Path(directory);(p/'model-manifest.json').write_text(json.dumps({'files':{'model.bin':'0'*64,'config.json':'0'*64,'tokenizer.json':'0'*64}}))
            with self.assertRaises(ValueError):worker.verified_model(directory)

    def test_onnx_presence_stops_before_any_native_model_import(self):
        with patch.object(worker, 'verified_model', return_value=Path('/synthetic/model')):
            with patch.object(worker.importlib.util, 'find_spec', return_value=object()):
                with self.assertRaisesRegex(RuntimeError, 'ONNX_RUNTIME_MUST_BE_ABSENT'):
                    worker.LocalWhisper('/synthetic/model')


class ModelProfileContract(unittest.TestCase):
    def test_profile_selection_is_closed_and_default_remains_tiny(self):
        self.assertEqual('Systran/faster-whisper-tiny', worker.source_manifest()['repository'])
        for name in ['', 'small', '../small', 'https://example.invalid/model']:
            with self.assertRaises(ValueError): worker.source_manifest(name)
        self.assertEqual(100*1024*1024, worker.DEFAULT_MODEL_FILE_LIMIT)

    def fixture(self, root, profile):
        manifest=worker.source_manifest(profile)
        (root/'model-manifest.json').write_text(json.dumps(manifest))
        for name in manifest['files']:
            with (root/name).open('wb') as target: target.truncate(manifest.get('sizes',{}).get(name,1))
        return manifest

    def test_exact_small_sizes_and_hashes_allow_only_selected_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);manifest=self.fixture(root,'small-2ec96c54')
            with patch.object(worker,'file_hash',side_effect=lambda p:manifest['files'][p.name]):
                self.assertEqual(root.resolve(),worker.verified_model(root,'small-2ec96c54'))
                with self.assertRaises(ValueError):worker.verified_model(root)
                with (root/'model.bin').open('wb') as target:target.truncate(worker.SMALL_MODEL_BYTES-1)
                with self.assertRaisesRegex(ValueError,'size'):worker.verified_model(root,'small-2ec96c54')

    def test_large_exception_does_not_apply_to_tiny_or_tokenizer(self):
        for profile,name in [('tiny','model.bin'),('small-2ec96c54','tokenizer.json')]:
            with tempfile.TemporaryDirectory() as directory:
                root=Path(directory);manifest=self.fixture(root,profile)
                with (root/name).open('wb') as target:target.truncate(worker.DEFAULT_MODEL_FILE_LIMIT+1)
                with patch.object(worker,'file_hash',side_effect=lambda p:manifest['files'][p.name]):
                    with self.assertRaisesRegex(ValueError,'size'):worker.verified_model(root,profile)

    def test_small_tampering_and_manifest_edit_cannot_expand_allowance(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);manifest=self.fixture(root,'small-2ec96c54')
            with patch.object(worker,'file_hash',return_value='0'*64):
                with self.assertRaisesRegex(ValueError,'hash'):worker.verified_model(root,'small-2ec96c54')
            manifest['sizes']['model.bin']+=1
            (root/'model-manifest.json').write_text(json.dumps(manifest))
            with self.assertRaisesRegex(ValueError,'Unreviewed'):worker.verified_model(root,'small-2ec96c54')

    def test_small_missing_or_symlink_file_fails_before_import(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);manifest=self.fixture(root,'small-2ec96c54')
            (root/'model.bin').unlink()
            with patch.object(worker,'file_hash',side_effect=lambda p:manifest['files'][p.name]):
                with self.assertRaises(ValueError):worker.verified_model(root,'small-2ec96c54')
                (root/'model.bin').symlink_to(root/'tokenizer.json')
                with self.assertRaises(ValueError):worker.verified_model(root,'small-2ec96c54')

if __name__=='__main__':unittest.main()
