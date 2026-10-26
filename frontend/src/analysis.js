import {escapeHtml as e, displayTime, safeLawLink} from './domain.js';

export const activeRun = run => ['QUEUED','RUNNING'].includes(run?.status) && run?.executionMode === 'LIVE';
export function pollDelay(attempt=0, hidden=false) { return hidden ? 10000 : Math.min(10000,2000 + Math.floor(attempt/5)*2000); }
export const stageLabel = stage => ({PREPARE:'입력 확인',HYDE:'검색 문맥 준비',SEARCH:'근거 검색',RERANK:'후보 정렬',VERIFY:'근거 검증',PERSIST:'결과 저장'}[stage] ?? '상태 확인');
export function failureMessage(code) {
  if (['MODEL_NOT_READY','VERIFIER_NOT_CONFIGURED','AI_CONFIGURATION_INVALID'].includes(code)) return '분석 서비스가 아직 준비되지 않았어요. 잠시 후 다시 확인해 주세요.';
  if (['DEADLINE_EXCEEDED','ATTEMPTS_EXHAUSTED','AI_UNAVAILABLE'].includes(code)) return '연결 또는 응답 시간 문제로 분석을 마치지 못했어요.';
  if (['ACCESS_DENIED','SOURCE_QUESTION_DELETED'].includes(code)) return '문제의 공개 상태나 접근 권한이 변경되어 처리를 중단했어요.';
  return '분석을 완료하지 못했어요. 저장한 문제를 확인한 뒤 다시 요청할 수 있어요.';
}

// Persist only an in-flight request key, never question text, tokens or a session identifier.
export class AnalysisRequests {
  constructor(api,{storage,uuid=()=>crypto.randomUUID()}={}) {
    this.api=api;this.uuid=uuid;this.inFlight=new Map();this.epoch=0;
    try { this.storage=storage ?? globalThis.sessionStorage; } catch { this.storage=null; }
    this.memory=new Map();
  }
  key(member,q) { return `lawground:analysis:${member}:${q.id}:${q.revision}`; }
  pending(member,q) {
    const key=this.key(member,q);let raw;
    try { raw=this.storage?.getItem(key) ?? this.memory.get(key); } catch { raw=this.memory.get(key); }
    try { const item=JSON.parse(raw);return /^[0-9a-f-]{36}$/i.test(item?.requestKey) ? item : null; } catch { return null; }
  }
  submit(member,q) {
    const key=this.key(member,q),epoch=this.epoch;if(this.inFlight.has(key))return this.inFlight.get(key);
    const item=this.pending(member,q) ?? {requestKey:this.uuid()};const raw=JSON.stringify(item);this.memory.set(key,raw);
    try { this.storage?.setItem(key,raw); } catch { /* Memory still prevents duplicate clicks. */ }
    const request=this.api.mutate(`/questions/${q.id}/trace-runs`,{body:{questionRevision:q.revision},headers:{'Idempotency-Key':item.requestKey}}).then(result=>{
      if(!/^[0-9a-f-]{36}$/i.test(result?.runId ?? '')){const error=new Error('UNCERTAIN_ACCEPTANCE');error.network=true;error.writing=true;throw error;}
      if(epoch===this.epoch && this.pending(member,q)?.requestKey===item.requestKey){this.memory.delete(key);try{this.storage?.removeItem(key);}catch{}}return result;
    });
    this.inFlight.set(key,request);const clear=()=>{if(this.inFlight.get(key)===request)this.inFlight.delete(key);};request.then(clear,clear);return request;
  }
  clear() {
    this.epoch++;this.memory.clear();this.inFlight.clear();
    try { for(let i=(this.storage?.length ?? 0)-1;i>=0;i--){const key=this.storage.key(i);if(key?.startsWith('lawground:analysis:'))this.storage.removeItem(key);} } catch {}
  }
}

export function resultMarkup(result) {
  const snapshot=result.inputSnapshot, choices=result.choiceAnalyses;
  const evidence=new Map((result.evidences ?? []).map(item=>[item.evidenceId,item]));
  const relation={SUPPORTS:['조문과 일치','green'],CONTRADICTS:['조문과 불일치','orange'],INSUFFICIENT:['근거 부족','neutral']};
  const analyses=new Map((choices ?? []).map(item=>[item.choiceNumber,item]));
  const cards=(snapshot.choices ?? []).map(choice=>{
    const c=analyses.get(choice.number), [label,tone]=relation[c?.relation] ?? ['설명 미제공','neutral'];
    const refs=(c?.evidenceIds ?? []).filter(id=>evidence.has(id));
    return `<article class="analysis-choice"><div class="choice-analysis-heading"><span class="choice-number">${choice.number}</span><span class="badge ${tone}">${label}</span>${choice.number===snapshot.providedAnswerNumber?'<span class="helper">제공 정답</span>':''}</div><p class="original-choice">${e(c?.originalText ?? choice.text)}</p>${c?`<p>${e(c.explanation)}</p>`:''}${c?.relation==='CONTRADICTS' && refs.length && c.correctedText?`<p class="corrected-choice"><strong>근거에 따른 정정</strong><br>${e(c.correctedText)}</p>`:''}${refs.length?`<div class="choice-references">${refs.map((id,i)=>`<a href="#quote-${e(id)}" data-quote-ref="${e(id)}">근거 ${i+1} 보기</a>`).join('')}</div>`:''}</article>`;
  }).join('');
  const quotes=(result.evidences ?? []).map(ev=>{
    const url=safeLawLink(ev.sourceUrl);
    return `<article id="quote-${e(ev.evidenceId)}" class="evidence-card" tabindex="-1"><h3>${e(ev.lawTitle)} · ${e(ev.articleLabel)}</h3><p class="helper">시행 ${e(ev.effectiveFrom)}${ev.effectiveTo?` ~ ${e(ev.effectiveTo)} 이전`:''}</p><blockquote>${e(ev.quote)}</blockquote><div class="choice-references"><a href="/evidence/${e(ev.articleId)}" data-nav>법령 원문 보기</a>${url?`<a href="${e(url)}" target="_blank" rel="noopener noreferrer">공식 출처 확인 ↗</a>`:''}</div></article>`;
  }).join('');
  return `<div class="notice ${result.verdict==='SUPPORTED'?'success':'warning'}" role="status"><div><strong>${result.verdict==='SUPPORTED'?'제공된 정답을 뒷받침하는 근거를 확인했어요.':result.verdict==='REJECTED'?'제공된 정답과 근거가 일치하지 않아요.':'현재 근거로 판단을 확정하기 어려워요.'}</strong><p>${e(result.summary)}</p></div></div><p class="helper">법령 기준일 ${e(result.referenceDate)} · 문제 revision ${result.questionRevision} · 실행 당시 입력을 보관한 결과예요.</p><div class="analysis-workspace"><section class="panel"><h2 class="stem">${e(snapshot.stem)}</h2><p class="helper">${snapshot.questionType==='SELECT_INCORRECT'?'틀린 설명 고르기':'옳은 설명 고르기'} · 제공 정답 ${snapshot.providedAnswerNumber}번</p>${choices==null?'<p class="notice subtle">이번 결과에는 선지별 설명이 제공되지 않았어요.</p>':''}<div class="analysis-choices">${cards}</div></section><aside class="panel evidence-panel"><h2>근거 조문</h2>${quotes || '<p class="helper">제시할 수 있는 인용 근거가 없어요.</p>'}</aside></div>`;
}

export function recommendationsMarkup(items,error) {
  if(error)return `<section class="panel related-panel"><h2>관련 문제</h2><p>${error.status===409?'문제가 수정되어 이 과거 결과로 추천할 수 없어요. 현재 문제에서 다시 분석해 주세요.':'관련 문제를 불러오지 못했어요.'}</p><button class="button secondary" data-action="refresh-run">다시 확인</button></section>`;
  return `<section class="panel related-panel"><h2>관련 문제</h2>${items.length?`<div class="related-grid">${items.map(item=>`<a class="related-card" data-nav href="/questions/${e(item.questionId)}"><strong>${e(item.stemPreview)}</strong><p>${e(item.reason)}</p><span class="helper">법령 기준일 ${e(item.referenceDate)} · 문제 보기 →</span></a>`).join('')}</div>`:'<p class="helper">접근 가능한 관련 문제가 아직 없어요. 추천을 만들기 위해 임의의 문제를 채우지 않아요.</p>'}</section>`;
}
