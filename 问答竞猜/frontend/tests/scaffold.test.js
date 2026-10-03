import test from 'node:test';import assert from 'node:assert/strict';import {readFileSync} from 'node:fs';
test('frontend pins local entry and marks demo boundary',()=>{assert.match(readFileSync('src/App.vue','utf8'),/不上传成绩/);assert.match(readFileSync('index.html','utf8'),/src\/main.js/)});
