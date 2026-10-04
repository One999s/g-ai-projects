#!/usr/bin/env python3
"""Export hash-bound editorial route metadata alongside reviewed bilingual text; never publish to DB."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import content_candidates as c
import content_review as r


def validate(candidate, review, classification):
    pack, text_review, when = r.validate(candidate, review)
    raw = Path(classification).read_bytes()
    if len(raw) > 262144: c.fail('Classification too large')
    v = json.loads(raw, object_pairs_hook=c.unique_object)
    c.exact(v, 'schemaVersion candidateSha256 reviewSha256 reviewedBy difficultyBasis playerCalibrated questions plans' + (' campaign' if v.get('schemaVersion') == 2 else ''))
    if type(v['schemaVersion']) is not int or v['schemaVersion'] not in (1, 2): c.fail()
    if v['candidateSha256'] != hashlib.sha256(Path(candidate).read_bytes()).hexdigest() or v['reviewSha256'] != hashlib.sha256(Path(review).read_bytes()).hexdigest(): c.fail('Stale classification')
    if v['reviewedBy'] != 'assistant:editorial-route-review' or v['playerCalibrated'] is not False or v['difficultyBasis'] != 'editorial-estimate-1-foundation-2-familiar-3-specialist': c.fail('Editorial scope required')
    if not isinstance(v['questions'], list) or len(v['questions']) != len(pack['questions']): c.fail()
    for source, q in zip(pack['questions'], v['questions']):
        c.exact(q, 'id category difficulty rationale')
        if q['id'] != source['id'] or q['category'] != source['topic'] or type(q['difficulty']) is not int or not 1 <= q['difficulty'] <= 3: c.fail('Classification mismatch')
        c.text(q['rationale'], 500)
    if not isinstance(v['plans'], list) or not 1 <= len(v['plans']) <= 12: c.fail()
    ids = set()
    available = Counter((q['category'], q['difficulty']) for q in v['questions'])
    for p in v['plans']:
        c.exact(p, 'id titles slots')
        if not isinstance(p['id'], str) or not re.fullmatch('[a-z][a-z0-9-]{0,39}', p['id']) or p['id'] == 'free' or p['id'] in ids: c.fail()
        ids.add(p['id']); c.exact(p['titles'], 'en zh-CN')
        for title in p['titles'].values(): c.text(title, 100)
        if not isinstance(p['slots'], list) or len(p['slots']) != 5: c.fail()
        previous = 0; needed = Counter()
        for slot in p['slots']:
            c.exact(slot, 'category difficulty')
            if not isinstance(slot['category'], str) or not re.fullmatch('[a-z][a-z0-9-]{0,31}', slot['category']) or type(slot['difficulty']) is not int or not previous <= slot['difficulty'] <= 3 or slot['difficulty'] < 1: c.fail()
            previous = slot['difficulty']; needed[(slot['category'], previous)] += 1
        if needed - available: c.fail('Insufficient distinct questions for plan')
    if v['schemaVersion'] == 2:
        campaign=v['campaign']; c.exact(campaign, 'id version titles levels')
        for k in ('id','version'):
            if not isinstance(campaign[k],str) or not re.fullmatch('[a-z][a-z0-9-]{0,39}',campaign[k]) or campaign[k]=='free': c.fail()
        c.exact(campaign['titles'],'en zh-CN')
        for title in campaign['titles'].values(): c.text(title,100)
        if not isinstance(campaign['levels'],list) or len(campaign['levels']) != 3: c.fail()
        known={p['id']:p for p in v['plans']}; ids=set(); previous=0; threshold=0
        for level in campaign['levels']:
            c.exact(level,'id titles planId requiredCorrect')
            if not isinstance(level['id'],str) or not re.fullmatch('[a-z][a-z0-9-]{0,39}',level['id']) or level['id'] in ids or level['id']=='free' or level['planId'] not in known: c.fail()
            ids.add(level['id']); c.exact(level['titles'],'en zh-CN')
            for title in level['titles'].values(): c.text(title,100)
            difficulty=sum(x['difficulty'] for x in known[level['planId']]['slots'])
            if difficulty<=previous or type(level['requiredCorrect']) is not int or not max(1,threshold)<=level['requiredCorrect']<=5: c.fail()
            previous=difficulty;threshold=level['requiredCorrect']
    return pack, text_review, when, v


def export(candidate, review, classification, output):
    pack, text_review, when, v = validate(candidate, review, classification)
    output = Path(output)
    if output.exists(): c.fail('Output exists')
    files = {}
    for locale in c.LOCALES:
        document = c.document(pack, locale); document['schemaVersion'] = 4 if v['schemaVersion']==2 else 3
        for row, tag in zip(document['questions'], v['questions']):
            row.update(category=tag['category'], difficulty=tag['difficulty'], reviewedBy=text_review['reviewedBy'], reviewedAtMillis=when)
        document['plans'] = [dict(id=p['id'], title=p['titles'][locale], slots=p['slots']) for p in v['plans']]
        if v['schemaVersion']==2:
            campaign=v['campaign'];document['campaign']=dict(id=campaign['id'],version=campaign['version'],title=campaign['titles'][locale],levels=[dict(id=l['id'],title=l['titles'][locale],planId=l['planId'],requiredCorrect=l['requiredCorrect']) for l in campaign['levels']])
        files[locale + '.document.json'] = c.encode(document)
    receipt = dict(classificationSha256=hashlib.sha256(Path(classification).read_bytes()).hexdigest(),
                   candidateSha256=v['candidateSha256'], reviewSha256=v['reviewSha256'],
                   suggestedPackVersion='world-foundations-r3-journey' if v['schemaVersion']==2 else 'world-foundations-r2-routes', schemaVersion=4 if v['schemaVersion']==2 else 3,
                   difficultyBasis=v['difficultyBasis'], playerCalibrated=False, reviewedBy=v['reviewedBy'],
                   deploymentApproved=False, databaseAccessed=False, audioListened=False,
                   files={n:hashlib.sha256(b).hexdigest() for n,b in files.items()})
    output.mkdir(parents=True)
    for n,b in files.items(): (output/n).write_bytes(b)
    (output/'receipt.json').write_bytes(c.encode(receipt))
    return receipt


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('candidate');p.add_argument('review');p.add_argument('classification');p.add_argument('--output');a=p.parse_args()
    if a.output: print(json.dumps(export(a.candidate,a.review,a.classification,a.output)))
    else:
        *_,v=validate(a.candidate,a.review,a.classification)
        print(json.dumps(dict(classifiedPairs=len(v['questions']),plans=len(v['plans']),playerCalibrated=False,deploymentApproved=False)))
