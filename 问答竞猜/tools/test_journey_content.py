import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import challenge_plans as p
ROOT=Path(__file__).resolve().parents[1]
C=ROOT/'content/candidates/world-foundations-r1.json';R=ROOT/'content/reviews/world-foundations-r1.assistant.json';J=ROOT/'content/reviews/world-foundations-r3.journey.json'
def rule_hash(doc):
 c=doc['campaign'];plans={x['id']:x for x in doc['plans']};text=c['id']+'\n'+c['version']+'\n'
 for l in c['levels']:
  text+=l['id']+'|'+l['planId']+'|'+str(l['requiredCorrect'])+'|'+''.join(s['category']+':'+str(s['difficulty'])+';' for s in plans[l['planId']]['slots'])+'\n'
 return hashlib.sha256(text.encode()).hexdigest()
class JourneyContentTests(unittest.TestCase):
 def test_three_chapters_increase_difficulty_with_explicit_thresholds(self):
  *_,v=p.validate(C,R,J);self.assertEqual([3,3,4],[l['requiredCorrect'] for l in v['campaign']['levels']]);self.assertEqual(3,len(v['campaign']['levels']))
 def test_bad_references_order_duplicates_and_thresholds_are_refused(self):
  for mutate in [lambda v:v['campaign']['levels'][0].update(id='free'),lambda v:v['campaign'].update(version='free'),lambda v:v['campaign']['levels'][1].update(planId='missing'),lambda v:v['campaign']['levels'][1].update(planId='first-light-route'),lambda v:v['campaign']['levels'][1].update(id='first-light'),lambda v:v['campaign']['levels'][2].update(requiredCorrect=2)]:
   v=json.loads(J.read_text());mutate(v)
   with tempfile.TemporaryDirectory() as d:
    q=Path(d)/'invalid.json';q.write_text(json.dumps(v))
    with self.assertRaises(ValueError):p.validate(C,R,q)
 def test_bilingual_rule_identity_is_identical_and_content_not_auto_approved(self):
  with tempfile.TemporaryDirectory() as d:
   out=Path(d)/'out';receipt=p.export(C,R,J,out);docs=[json.loads((out/(l+'.document.json')).read_text()) for l in p.c.LOCALES]
   self.assertEqual(4,receipt['schemaVersion']);self.assertFalse(receipt['deploymentApproved']);self.assertFalse(receipt['audioListened']);self.assertFalse(receipt['playerCalibrated']);self.assertEqual(rule_hash(docs[0]),rule_hash(docs[1]));self.assertNotEqual(docs[0]['campaign']['title'],docs[1]['campaign']['title'])
   changed=copy.deepcopy(docs[0]);changed['campaign']['levels'][2]['requiredCorrect']=5;self.assertNotEqual(rule_hash(docs[0]),rule_hash(changed))
