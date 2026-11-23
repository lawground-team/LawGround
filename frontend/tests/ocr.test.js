import assert from 'node:assert/strict';
import test from 'node:test';
import { isSupportedPhoto, missingPhotoChoice, splitPhotoQuestion } from '../src/ocr.js';

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

test('offers to repair an isolated broken choice marker without changing the choice text', () => {
  const broken = example.replace('(3) 3일 이하이다.', 'C$\n3일 이하이다.');
  const result = missingPhotoChoice(broken);
  assert.equal(result.number, 3);
  assert.equal(result.next, '3일 이하이다.');
  assert.equal(splitPhotoQuestion(broken), null);
  assert.deepEqual(splitPhotoQuestion(result.repaired), splitPhotoQuestion(example));
});

test('missing choice review leaves ambiguous and incomplete text for manual editing', () => {
  assert.deepEqual(missingPhotoChoice(example.replace('(3) 3일 이하이다.', '3일 이하이다.')), { number: 3 });
  assert.deepEqual(missingPhotoChoice(example.replace('(3) 3일 이하이다.', '$\n3일 이하이다.\n*\n이어서 설명한다.')), { number: 3 });
  assert.equal(missingPhotoChoice(example.replace('(3) 3일 이하이다.', '').replace('④', '②')), null);
  assert.equal(missingPhotoChoice(example.replace('(3) 3일 이하이다.', '') + '\n12. 다른 문제인가?'), null);
});

test('only supported image signatures pass', () => {
  assert.equal(isSupportedPhoto(Uint8Array.of(0xff, 0xd8, 0xff)), true);
  assert.equal(isSupportedPhoto(Uint8Array.of(0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10)), true);
  assert.equal(isSupportedPhoto(new TextEncoder().encode('RIFF0000WEBP')), true);
  assert.equal(isSupportedPhoto(new TextEncoder().encode('<svg/>')), false);
});
