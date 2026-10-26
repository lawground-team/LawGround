import test from 'node:test';
import assert from 'node:assert/strict';
import {AnalysisRequests,pollDelay,resultMarkup,recommendationsMarkup} from '../src/analysis.js';
const uuid=n=>`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`;
const question={id:uuid(1),revision:1};
const storage=()=>{const m=new Map();return {getItem:k=>m.get(k)??null,setItem:(k,v)=>m.set(k,v),removeItem:k=>m.delete(k),key:i=>[...m.keys()][i],get length(){return m.size;}};};
test('pending acceptance reuses a key across reloads without storing question text',async()=>{
  const s=storage(),keys=[];const api={mutate:async(_,o)=>{keys.push(o.headers['Idempotency-Key']);if(keys.length===1)throw new TypeError('uncertain');return {runId:uuid(3)};}};
  const first=new AnalysisRequests(api,{storage:s,uuid:()=>uuid(2)});await assert.rejects(first.submit('member',question));
  assert.equal(s.length,1);assert.equal(s.getItem(s.key(0)),JSON.stringify({requestKey:uuid(2)}));
  const second=new AnalysisRequests(api,{storage:s,uuid:()=>uuid(9)});await second.submit('member',question);assert.deepEqual(keys,[uuid(2),uuid(2)]);assert.equal(s.length,0);
});
test('double clicks share one in-flight request and logout isolates old completions',async()=>{
  const s=storage(),resolve=[];let calls=0;
  const requests=new AnalysisRequests({mutate:()=>{calls++;return new Promise(r=>resolve.push(r));}},{storage:s,uuid:()=>uuid(calls+3)});
  const first=requests.submit('same',question);assert.equal(first,requests.submit('same',question));assert.equal(calls,1);
  requests.clear();const second=requests.submit('same',question);const key=requests.pending('same',question).requestKey;
  resolve[0]({runId:uuid(20)});await first;assert.equal(requests.pending('same',question).requestKey,key);assert.equal(second,requests.submit('same',question));
  resolve[1]({runId:uuid(21)});await second;assert.equal(s.length,0);
});
test('polling is bounded and hidden tabs stay at ten seconds',()=>{assert.equal(pollDelay(0),2000);assert.equal(pollDelay(50),10000);assert.equal(pollDelay(0,true),10000);});
test('result uses frozen originals and escaped text without fabricated correction or statistics',()=>{
  const q={stem:'<script>bad</script>',choices:[{number:1,text:'snapshot original'}],providedAnswerNumber:1};
  const html=resultMarkup({inputSnapshot:q,questionRevision:1,referenceDate:'2025-01-01',verdict:'HOLD',summary:'<img onerror=x>',evidences:[],choiceAnalyses:[{choiceNumber:1,originalText:'old 😀',relation:'INSUFFICIENT',explanation:'cannot verify',correctedText:'must not display',evidenceIds:[]}]});
  assert.ok(html.includes('old 😀'));assert.ok(html.includes('&lt;script&gt;'));assert.ok(!html.includes('<script>'));assert.ok(!html.includes('must not display'));assert.ok(!html.includes('선택률'));assert.ok(!html.includes('오답률'));
  assert.ok(recommendationsMarkup([],null).includes('관련 문제가 아직 없어요'));
});
