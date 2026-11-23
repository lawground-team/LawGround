import assert from 'node:assert/strict';
import test from 'node:test';
import { isSupportedPhoto, splitPhotoQuestion } from '../src/ocr.js';

const example = [
  '1. 제33조의2에 관한 설명으로 옳지 않은 것은?',
  '① 30일 이내에 신고한다.',
  '2) 20일 이상 경과했다.',
  '(3) 3일 이하이다.',
  '④ 제12조에 따른다.',
  '5. 신청하지 않는다.',
].join('\n');

test('photo split preserves the text and accepts mixed line-start markers', () => {
  assert.deepEqual(splitPhotoQuestion(example), {
    stem: '제33조의2에 관한 설명으로 옳지 않은 것은?',
    choices: ['30일 이내에 신고한다.', '20일 이상 경과했다.', '3일 이하이다.', '제12조에 따른다.', '신청하지 않는다.'],
  });
});

test('unclear OCR text requires review before applying', () => {
  assert.equal(splitPhotoQuestion(example.replace('2) 20일 이상 경과했다.', '')), null);
  assert.equal(splitPhotoQuestion(example + '\n① 또 다른 선지'), null);
  assert.equal(splitPhotoQuestion(example + '\n12. 다음 중 옳은 것은?'), null);
  assert.equal(splitPhotoQuestion(example.replace('5. 신청하지 않는다.', '⑤')), null);
  assert.equal(splitPhotoQuestion(example.replace('제12조에 따른다.', '①을 참고한다.')), null);
});

test('only supported image signatures pass', () => {
  assert.equal(isSupportedPhoto(Uint8Array.of(0xff, 0xd8, 0xff)), true);
  assert.equal(isSupportedPhoto(Uint8Array.of(0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10)), true);
  assert.equal(isSupportedPhoto(new TextEncoder().encode('RIFF0000WEBP')), true);
  assert.equal(isSupportedPhoto(new TextEncoder().encode('<svg/>')), false);
});
