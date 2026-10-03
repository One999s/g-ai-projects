import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).parent))
import content_candidates as c

PATH = Path(__file__).resolve().parents[1] / 'content/candidates/world-foundations-r1.json'


class CandidateTests(unittest.TestCase):
    def setUp(self): self.pack = c.load(PATH)
    def test_twenty_pairs_and_answer_balance(self):
        self.assertEqual(20, len(self.pack['questions']))
        self.assertEqual([5, 5, 5, 5], [sum(q['locales']['en']['answer'] == i for q in self.pack['questions']) for i in range(4)])
    def test_approved_status_is_rejected(self):
        self.pack['status'] = 'approved'
        with self.assertRaises(ValueError): c.validate(self.pack)
    def test_approval_claim_is_rejected(self):
        self.pack['approval']['approved'] = True
        with self.assertRaises(ValueError): c.validate(self.pack)
    def test_unknown_fields_are_rejected(self):
        self.pack['fakeReviewer'] = 'someone'
        with self.assertRaises(ValueError): c.validate(self.pack)
    def test_duplicate_ids_are_rejected(self):
        self.pack['questions'][1]['id'] = self.pack['questions'][0]['id']
        with self.assertRaises(ValueError): c.validate(self.pack)
    def test_translation_answer_drift_is_rejected(self):
        self.pack['questions'][0]['locales']['en']['answer'] = 0
        with self.assertRaises(ValueError): c.validate(self.pack)
    def test_bool_float_and_string_answers_are_rejected(self):
        for value in [True, 1.0, '1', -1, 4]:
            pack = copy.deepcopy(self.pack)
            pack['questions'][0]['locales']['en']['answer'] = value
            with self.assertRaises(ValueError): c.validate(pack)
    def test_duplicate_options_are_rejected(self):
        self.pack['questions'][0]['locales']['en']['options'] = ['Mars', ' mars ', 'Earth', 'Sun']
        with self.assertRaises(ValueError): c.validate(self.pack)
    def test_unsafe_unverified_source_hosts_are_rejected(self):
        for url in ['http://science.nasa.gov/x', 'https://user:secret@science.nasa.gov/x', 'https://127.0.0.1/x', 'https://science.nasa.gov.evil.example/x']:
            pack = copy.deepcopy(self.pack); pack['questions'][0]['sourceUrls'] = [url]
            with self.assertRaises(ValueError): c.validate(pack)
    def test_reading_bounds_are_strict(self):
        for value in [0, 60001, True]:
            pack = copy.deepcopy(self.pack); pack['questions'][0]['locales']['en']['readingMillis'] = value
            with self.assertRaises(ValueError): c.validate(pack)
    def test_missing_locale_is_rejected(self):
        del self.pack['questions'][0]['locales']['zh-CN']
        with self.assertRaises(ValueError): c.validate(self.pack)
    def test_runtime_document_cannot_claim_review(self):
        doc = c.document(self.pack, 'en')
        self.assertEqual(2, doc['schemaVersion'])
        self.assertTrue(all(row['reviewedBy'] is None and row['reviewedAtMillis'] == 0 for row in doc['questions']))
    def test_sql_rolls_back_and_never_updates_or_approves(self):
        sql = c.draft_sql(self.pack, 1791030000000)
        self.assertEqual(2, sql.count('INSERT INTO quiz_question_packs'))
        self.assertIn('\nROLLBACK;\n', sql)
        self.assertNotIn('\nCOMMIT;', sql)
        self.assertNotIn('UPDATE ', sql)
        self.assertNotIn('INSERT INTO quiz_question_audit', sql)
        self.assertNotIn("'approved'", sql)
    def test_unicode_and_sql_punctuation_are_hex_encoded(self):
        q = self.pack['questions'][0]['locales']['en']; q['text'] = "Where?'); DROP TABLE users;--"
        sql = c.draft_sql(self.pack, 1791030000000)
        self.assertNotIn('DROP TABLE', sql)
        self.assertIn(c.encode(c.document(self.pack, 'en')).hex(), sql)
    def test_narration_contains_each_option_but_not_explanation(self):
        row = self.pack['questions'][0]; audio_text = c.transcript(row, 'en')
        self.assertTrue(audio_text.startswith(row['locales']['en']['text']))
        for letter, option in zip('ABCD', row['locales']['en']['options']): self.assertIn(f'Option {letter}: {option}.', audio_text)
        self.assertNotIn(row['locales']['en']['explanation'], audio_text)
    def test_duplicate_json_keys_and_oversize_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / 'candidate.json'; p.write_text('{"x":1,"x":2}')
            with self.assertRaises(ValueError): c.load(p)
            p.write_bytes(b' ' * 262145)
            with self.assertRaises(ValueError): c.load(p)
    def test_export_is_deterministic_but_never_overwrites(self):
        with tempfile.TemporaryDirectory() as tmp:
            a = c.export(PATH, Path(tmp) / 'a', 1791030000000)
            b = c.export(PATH, Path(tmp) / 'b', 1791030000000)
            self.assertEqual(a, b)
            self.assertFalse(a['databaseAccessed']); self.assertFalse(a['approved'])
            with self.assertRaises(ValueError): c.export(PATH, Path(tmp) / 'a', 1791030000000)


if __name__ == '__main__': unittest.main()
