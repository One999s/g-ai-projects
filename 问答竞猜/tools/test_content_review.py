import copy
import json
from pathlib import Path
import tempfile
import unittest
import content_review as r
ROOT=Path(__file__).resolve().parents[1]
C=ROOT/'content/candidates/world-foundations-r1.json'
R=ROOT/'content/reviews/world-foundations-r1.assistant.json'
class ReviewTests(unittest.TestCase):
 def test_twenty_source_bound_bilingual_reviews(self):
  p,v,t=r.validate(C,R);self.assertEqual(20,len(v['questions']));self.assertEqual('assistant',v['reviewKind']);self.assertGreater(t,0)
 def test_review_rejects_changed_question_stale_hash_claim_or_missing_row(self):
  for change in [lambda v:v.update(candidateSha256='0'*64),lambda v:v.update(audioListened=True),lambda v:v.update(deploymentApproved=True),lambda v:v.update(reviewKind='human'),lambda v:v['questions'].pop(),lambda v:v['questions'][0].update(questionSha256='0'*64),lambda v:v.update(reviewedAt='2026-10-03T14:11:00')]:
   v=json.loads(R.read_text());change(v)
   with tempfile.TemporaryDirectory() as d:
    x=Path(d)/'review.json';x.write_text(json.dumps(v))
    with self.assertRaises(ValueError):r.validate(C,x)
 def test_export_sets_real_assistant_attribution_without_database_approval(self):
  with tempfile.TemporaryDirectory() as d:
   out=Path(d)/'reviewed';receipt=r.export(C,R,out)
   self.assertFalse(receipt['databaseAccessed']);self.assertFalse(receipt['deploymentApproved'])
   for locale in r.c.LOCALES:
    doc=json.loads((out/(locale+'.document.json')).read_text())
    self.assertTrue(all(q['reviewedBy']=='assistant:source-and-bilingual-review' for q in doc['questions']))
   with self.assertRaises(ValueError):r.export(C,R,out)
