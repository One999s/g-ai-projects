#!/usr/bin/env python3
"""Validate a hash-bound assistant text review; emit reviewed text, never DB approval."""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import content_candidates as c


def validate(candidate_path, review_path):
    pack = c.load(candidate_path)
    raw = Path(review_path).read_bytes()
    if len(raw) > 262144: c.fail('Review too large')
    review = json.loads(raw.decode('utf-8'), object_pairs_hook=c.unique_object,
                        parse_constant=lambda _: c.fail('Non-finite review'))
    c.exact(review, 'reviewSchemaVersion candidateSha256 reviewedBy reviewedAt reviewKind factReview translationReview wordingReview audioListened deploymentApproved publicAnswersVisible questions')
    if type(review['reviewSchemaVersion']) is not int or review['reviewSchemaVersion'] != 1: c.fail()
    if review['candidateSha256'] != hashlib.sha256(Path(candidate_path).read_bytes()).hexdigest(): c.fail('Stale candidate review')
    if review['reviewKind'] != 'assistant' or review['reviewedBy'] != 'assistant:source-and-bilingual-review': c.fail('Reviewer attribution mismatch')
    if review['factReview'] != 'passed' or review['translationReview'] != 'passed' or review['wordingReview'] != 'original-factual-prompts-no-source-media': c.fail()
    if review['audioListened'] is not False or review['deploymentApproved'] is not False or review['publicAnswersVisible'] is not True: c.fail('Text review cannot approve listening or deployment')
    instant = dt.datetime.fromisoformat(review['reviewedAt'].replace('Z', '+00:00'))
    if instant.tzinfo is None or instant.timestamp() <= 0: c.fail('Timezone required')
    if not isinstance(review['questions'], list) or len(review['questions']) != len(pack['questions']): c.fail('Incomplete review')
    for q, item in zip(pack['questions'], review['questions']):
        c.exact(item, 'id questionSha256 sourceUrls sourceCheckedOn factFinding bilingualFinding result')
        if item['id'] != q['id'] or item['questionSha256'] != hashlib.sha256(c.encode(q)).hexdigest(): c.fail('Stale question review')
        if item['sourceUrls'] != q['sourceUrls'] or item['result'] != 'passed' or item['sourceCheckedOn'] != instant.date().isoformat(): c.fail()
        c.text(item['factFinding'], 500); c.text(item['bilingualFinding'], 1000)
        if item['factFinding'] != q['sourceFact']: c.fail('Fact finding mismatch')
    return pack, review, int(instant.timestamp() * 1000)


def export(candidate_path, review_path, output):
    pack, review, when = validate(candidate_path, review_path)
    output = Path(output)
    if output.exists(): c.fail('Output exists')
    files = {}
    for locale in c.LOCALES:
        document = c.document(pack, locale)
        for row in document['questions']:
            row.update(reviewedBy=review['reviewedBy'], reviewedAtMillis=when,
                       rightsNote='Original factual question and options; sources identify supporting facts. Assistant wording review found no source media or extended prose reproduced. No source endorsement or legal opinion is claimed.')
        files[locale + '.document.json'] = c.encode(document)
    receipt = {'candidateSha256': review['candidateSha256'], 'reviewSha256': hashlib.sha256(Path(review_path).read_bytes()).hexdigest(),
               'reviewedBy': review['reviewedBy'], 'textReviewed': True, 'audioListened': False,
               'databaseAccessed': False, 'deploymentApproved': False, 'publicAnswersVisible': True,
               'files': {n: hashlib.sha256(b).hexdigest() for n, b in files.items()}}
    output.mkdir(parents=True)
    for n, b in files.items(): (output / n).write_bytes(b)
    (output / 'receipt.json').write_bytes(c.encode(receipt))
    return receipt


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('candidate', type=Path); p.add_argument('review', type=Path); p.add_argument('--output', type=Path)
    a = p.parse_args()
    if a.output: print(json.dumps(export(a.candidate, a.review, a.output)))
    else:
        pack, review, when = validate(a.candidate, a.review)
        print(json.dumps({'reviewedPairs': len(pack['questions']), 'reviewKind': review['reviewKind'], 'audioListened': False, 'deploymentApproved': False}))
