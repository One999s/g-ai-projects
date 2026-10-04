import json
from pathlib import Path
import tempfile
import unittest
import challenge_plans as p
ROOT=Path(__file__).resolve().parents[1]
C=ROOT/'content/candidates/world-foundations-r1.json'
R=ROOT/'content/reviews/world-foundations-r1.assistant.json'
T=ROOT/'content/reviews/world-foundations-r2.routes.json'
class PlanTests(unittest.TestCase):
 def test_twenty_classifications_and_two_routes_have_distinct_coverage(self):
  *_,v=p.validate(C,R,T);self.assertEqual(20,len(v['questions']));self.assertEqual(2,len(v['plans']));self.assertFalse(v['playerCalibrated'])
 def test_stale_missing_or_inflated_review_is_rejected(self):
  for mutate in [lambda v:v.update(candidateSha256='0'*64),lambda v:v.update(playerCalibrated=True),lambda v:v['questions'].pop(),lambda v:v['questions'][0].update(category='invented'),lambda v:v['questions'][0].update(difficulty=True),lambda v:v['plans'].append(v['plans'][0])]:
   v=json.loads(T.read_text());mutate(v)
   with tempfile.TemporaryDirectory() as d:
    x=Path(d)/'bad.json';x.write_text(json.dumps(v))
    with self.assertRaises(ValueError):p.validate(C,R,x)
 def test_insufficient_distinct_slots_and_decreasing_order_fail(self):
  for slots in [[dict(category='space',difficulty=3)]*5,[dict(category='space',difficulty=i) for i in [3,1,2,2,3]]]:
   v=json.loads(T.read_text());v['plans'][0]['slots']=slots
   with tempfile.TemporaryDirectory() as d:
    x=Path(d)/'bad.json';x.write_text(json.dumps(v))
    with self.assertRaises(ValueError):p.validate(C,R,x)
 def test_bilingual_export_preserves_identical_route_structure_and_answer_content(self):
  with tempfile.TemporaryDirectory() as d:
   out=Path(d)/'pack';receipt=p.export(C,R,T,out);self.assertFalse(receipt['databaseAccessed']);self.assertFalse(receipt['deploymentApproved']);self.assertFalse(receipt['audioListened'])
   docs=[json.loads((out/(locale+'.document.json')).read_text()) for locale in p.c.LOCALES]
   self.assertEqual([x['slots'] for x in docs[0]['plans']],[x['slots'] for x in docs[1]['plans']])
   for doc in docs:
    self.assertEqual(3,doc['schemaVersion']);self.assertEqual(20,len(doc['questions']));self.assertTrue(all(q['reviewedBy']=='assistant:source-and-bilingual-review' for q in doc['questions']))
   with self.assertRaises(ValueError):p.export(C,R,T,out)
