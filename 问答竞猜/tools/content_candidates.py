#!/usr/bin/env python3
"""Validate public bilingual drafts; export review-only SQL that always rolls back."""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import urlsplit

DOMAINS = {'science.nasa.gov', 'www.nasa.gov', 'oceanservice.noaa.gov',
           'oceanexplorer.noaa.gov', 'www.bipm.org', 'whc.unesco.org'}
LOCALES = ('zh-CN', 'en')


def fail(message='Invalid or non-draft candidate'):
    raise ValueError(message)


def exact(value, keys):
    if not isinstance(value, dict) or set(value) != set(keys.split()): fail()


def text(value, maximum):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum or any(ord(c) < 32 for c in value): fail()


def integer(value, low, high):
    if type(value) is not int or not low <= value <= high: fail()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result: fail('Duplicate JSON key')
        result[key] = value
    return result


def load(path):
    raw = Path(path).read_bytes()
    if len(raw) > 262144: fail('Candidate exceeds byte limit')
    value = json.loads(raw.decode('utf-8'), object_pairs_hook=unique_object,
                       parse_constant=lambda _: fail('Non-finite number'))
    validate(value)
    return value


def validate(pack):
    exact(pack, 'candidateSchemaVersion packVersion status visibility sourceCheckedOn approval questions')
    if type(pack['candidateSchemaVersion']) is not int or pack['candidateSchemaVersion'] != 1: fail()
    text(pack['packVersion'], 80)
    if not re.fullmatch(r'public-[a-z0-9-]+', pack['packVersion']): fail()
    if pack['status'] != 'draft' or pack['visibility'] != 'public-development-candidate': fail()
    dt.date.fromisoformat(pack['sourceCheckedOn'])
    exact(pack['approval'], 'factReview translationReview rightsReview audioReview approved')
    if pack['approval'] != {'factReview': 'pending-human', 'translationReview': 'pending-human',
                            'rightsReview': 'pending-human', 'audioReview': 'pending-listening', 'approved': False}: fail()
    if pack['approval']['approved'] is not False: fail()
    rows = pack['questions']
    if not isinstance(rows, list) or not 5 <= len(rows) <= 500: fail()
    seen = set()
    for row in rows:
        exact(row, 'id topic sourceUrls sourceFact rightsNote locales')
        text(row['id'], 64)
        if not re.fullmatch(r'[a-z][a-z0-9-]+', row['id']) or row['id'] in seen: fail()
        seen.add(row['id'])
        if row['topic'] not in {'space', 'ocean', 'measurement', 'heritage'}: fail()
        text(row['sourceFact'], 500); text(row['rightsNote'], 2000)
        urls = row['sourceUrls']
        if not isinstance(urls, list) or not 1 <= len(urls) <= 8 or len(set(urls)) != len(urls): fail()
        for source in urls:
            text(source, 2048); url = urlsplit(source)
            if url.scheme != 'https' or url.hostname not in DOMAINS or url.username or url.password or url.port: fail()
        exact(row['locales'], 'zh-CN en')
        answers = []
        for locale in LOCALES:
            q = row['locales'][locale]
            exact(q, 'text options answer explanation readingMillis')
            text(q['text'], 1000); text(q['explanation'], 2000)
            options = q['options']
            if not isinstance(options, list) or len(options) != 4: fail()
            for option in options: text(option, 400)
            if len(set(option.strip().casefold() for option in options)) != 4: fail()
            integer(q['answer'], 0, 3); integer(q['readingMillis'], 1000, 60000)
            answers.append(q['answer'])
        if answers[0] != answers[1]: fail('Bilingual answer index mismatch')
    return pack


def transcript(row, locale):
    q = row['locales'][locale]
    labels = [f'Option {letter}: {option}.' if locale == 'en' else f'选项{letter}：{option}。'
              for letter, option in zip('ABCD', q['options'])]
    return q['text'] + ' ' + ' '.join(labels)


def document(pack, locale):
    validate(pack)
    if locale not in LOCALES: fail()
    return {'schemaVersion': 2, 'questions': [
        {'question': {'id': row['id'], 'locale': locale, **row['locales'][locale]},
         'sources': row['sourceUrls'], 'rightsNote': row['rightsNote'],
         'reviewedBy': None, 'reviewedAtMillis': 0} for row in pack['questions']]}


def encode(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode('utf-8')


def hex_text(value):
    return 'CONVERT(0x' + value.encode('utf-8').hex() + ' USING utf8mb4)'


def draft_sql(pack, created_at_ms):
    integer(created_at_ms, 1, 9007199254740991)
    validate(pack)
    sql = ['-- REVIEW ONLY. Drafts are ineligible for gameplay; no approval or audit write.',
           '-- No database connection is made by this tool. Existing rows are never overwritten.',
           'START TRANSACTION;']
    for locale in LOCALES:
        raw = encode(document(pack, locale)).decode('utf-8')
        sha = hashlib.sha256(raw.encode('utf-8')).hexdigest()
        values = ','.join([hex_text(pack['packVersion']), hex_text(locale), "'draft'", hex_text(raw),
                           "'" + sha + "'", 'NULL', '0', 'NULL', 'NULL', str(created_at_ms)])
        sql.append('INSERT INTO quiz_question_packs (pack_version,locale,status,questions_json,content_sha256,reviewer,valid_from_ms,valid_until_ms,approved_at_ms,created_at_ms) VALUES (' + values + ');')
    sql += ['ROLLBACK;', '-- Intentionally no COMMIT, no approved state, no quiz_question_audit insertion.']
    return '\n'.join(sql) + '\n'


def export(path, output, created_at_ms):
    pack = load(path); output = Path(output)
    if output.exists(): fail('Output exists; draft previews are not overwritten')
    files = {f'{locale}.document.json': encode(document(pack, locale)) for locale in LOCALES}
    files['draft.review.sql'] = draft_sql(pack, created_at_ms).encode('utf-8')
    output.mkdir(parents=True)
    for name, raw in files.items(): (output / name).write_bytes(raw)
    receipt = {'candidateSha256': hashlib.sha256(Path(path).read_bytes()).hexdigest(),
               'status': 'draft', 'approved': False, 'databaseAccessed': False,
               'publicAnswersVisible': True, 'pairedQuestions': len(pack['questions']),
               'files': {name: hashlib.sha256(raw).hexdigest() for name, raw in files.items()}}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--created-at-ms', type=int)
    args = parser.parse_args()
    if args.output:
        if args.created_at_ms is None: parser.error('--output requires explicit --created-at-ms')
        print(json.dumps(export(args.candidate, args.output, args.created_at_ms)))
    else:
        pack = load(args.candidate)
        print(json.dumps({'validDraft': True, 'pairedQuestions': len(pack['questions']), 'approved': False}))
