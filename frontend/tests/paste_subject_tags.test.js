import test from 'node:test';
import assert from 'node:assert/strict';
import {questionPayload,subjectLabel,splitPastedQuestion,detectQuestionType,detectSubject,SOURCE_NOTICE} from '../src/domain.js';
import {tagChips} from '../src/analysis.js';

test('pasted circled, numbered, parenthesised and flattened choices split into stem + five',()=>{
  let r=splitPastedQuestion('3. 다음 중 옳은 것은?\n① 가나\n다라\n② 마바 ③ 사아\n④ 자차\n⑤ 카타 1.5배');
  assert.deepEqual(r,{stem:'다음 중 옳은 것은?',choices:['가나 다라','마바','사아','자차','카타 1.5배']});
  r=splitPastedQuestion('옳지 않은 것은?\n1. 3개월 이내\n2. 제2조 1.5배\n3. 셋\n4. 넷\n5. 다섯');
  assert.deepEqual(r.choices,['3개월 이내','제2조 1.5배','셋','넷','다섯']);
  assert.deepEqual(splitPastedQuestion('옳은 것은? (1) 하나 (2) 둘 (3) 셋 (4) 넷 (5) 다섯').choices,['하나','둘','셋','넷','다섯']);
  assert.deepEqual(splitPastedQuestion('옳은 것은? 1) 제1조) 하나 2) 둘 3) 셋 4) 넷 5) 다섯').choices,['제1조) 하나','둘','셋','넷','다섯']);
  assert.equal(splitPastedQuestion('선지가 없는 문장'),null);
  assert.equal(splitPastedQuestion('① 하나 ② 둘 ③ 셋 ④ 넷'),null);
});

test('question type and subject are guessed only when the text says so',()=>{
  assert.equal(detectQuestionType('옳지 않은 것은?'),'SELECT_INCORRECT');
  assert.equal(detectQuestionType('해당하지 않는 것은?'),'SELECT_INCORRECT');
  assert.equal(detectQuestionType('옳은 것은?'),'SELECT_CORRECT');
  assert.equal(detectQuestionType('무엇인가'),null);
  assert.equal(detectSubject('국토의 계획 및 이용에 관한 법령상 용도지역'),'PUBLIC_LAW');
  assert.equal(detectSubject('공인중개사법령상 중개보수'),'BROKER_LAW');
  assert.equal(detectSubject('무관한 글'),null);
});

test('public law is a valid subject and unknown subjects fall back to the broker law',()=>{
  assert.equal(questionPayload({subject:'PUBLIC_LAW',choices:[]}).subject,'PUBLIC_LAW');
  assert.equal(questionPayload({subject:'CIVIL_LAW',choices:[]}).subject,'BROKER_LAW');
  assert.equal(subjectLabel('PUBLIC_LAW'),'부동산공법');
  assert.ok(SOURCE_NOTICE.includes('한국산업인력공단(Q-Net)') && SOURCE_NOTICE.includes('일부 재가공됨'));
});

test('only agreed tag codes are shown, with Korean labels',()=>{
  const html=tagChips([{code:'TIMING'},{code:'ACTOR'},{code:'<script>'},null]);
  assert.ok(html.includes('시점·기한') && html.includes('주체·권한'));
  assert.ok(!html.includes('script'));
  assert.equal(tagChips(undefined),'');
});
