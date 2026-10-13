export function escapeHtml(value = '') {
  return String(value).replace(/[&<>"']/g, c => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c]));
}

export function koreaToday(now = new Date()) {
  const parts = new Intl.DateTimeFormat('en-CA', {timeZone:'Asia/Seoul', year:'numeric', month:'2-digit', day:'2-digit'}).formatToParts(now);
  return ['year','month','day'].map(key => parts.find(p => p.type === key).value).join('-');
}

export function displayTime(value) {
  if (!value) return '—';
  return new Intl.DateTimeFormat('ko-KR', {timeZone:'Asia/Seoul', year:'numeric', month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit', hour12:false}).format(new Date(value));
}

const normalize = text => String(text ?? '').replace(/\r\n?/g, '\n').trim();
export const emptyQuestion = () => ({subject:'BROKER_LAW', stem:'', questionType:'SELECT_CORRECT', choices:Array.from({length:5}, (_,i) => ({number:i+1,text:''})), providedAnswerNumber:null, referenceDate:koreaToday()});

export function questionPayload(draft) {
  return {subject:'BROKER_LAW', stem:normalize(draft.stem), questionType:draft.questionType,
    choices:Array.from({length:5},(_,i) => ({number:i+1,text:normalize(draft.choices?.find(c => c.number === i+1)?.text)})),
    providedAnswerNumber:draft.providedAnswerNumber == null ? null : Number(draft.providedAnswerNumber), referenceDate:draft.referenceDate};
}

export function validateQuestion(draft) {
  const data = questionPayload(draft), errors = {};
  if (!data.stem) errors.stem = '문제 본문을 입력해 주세요.';
  else if ([...data.stem].length > 10000) errors.stem = '본문은 10,000자까지 입력할 수 있어요.';
  if (!['SELECT_CORRECT','SELECT_INCORRECT'].includes(data.questionType)) errors.questionType = '문제 유형을 선택해 주세요.';
  data.choices.forEach(({number,text}) => {
    if (!text) errors[`choice${number}`] = `${number}번 선지를 입력해 주세요.`;
    else if ([...text].length > 2000) errors[`choice${number}`] = '선지는 2,000자까지 입력할 수 있어요.';
  });
  if (!Number.isInteger(data.providedAnswerNumber) || data.providedAnswerNumber < 1 || data.providedAnswerNumber > 5) errors.providedAnswerNumber = '시험지·교재에서 제공한 정답을 선택해 주세요.';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(data.referenceDate ?? '') || data.referenceDate.startsWith('0000') ||
      Number.isNaN(Date.parse(data.referenceDate)) || new Date(data.referenceDate).toISOString().slice(0,10) !== data.referenceDate) errors.referenceDate = '유효한 법령 기준일을 선택해 주세요.';
  return errors;
}

export function statusInfo(run) {
  if (!run) return {text:'저장 완료', tone:'neutral', icon:'document'};
  if (run.executionMode === 'MOCK') return {text:'테스트 실행', tone:'neutral', icon:'info'};
  if (['QUEUED','PENDING'].includes(run.status)) return {text:'분석 대기', tone:'neutral', icon:'clock'};
  if (['RUNNING','RETRY_WAIT'].includes(run.status)) return {text:'분석 중', tone:'blue', icon:'loader'};
  if (run.status === 'FAILED') return {text:'처리 실패', tone:'red', icon:'alert'};
  if (run.status === 'CANCELLED') return {text:'분석 취소', tone:'neutral', icon:'clock'};
  if (run.status === 'COMPLETED' && run.verdict === 'SUPPORTED') return {text:'근거 확인', tone:'green', icon:'check'};
  if (run.status === 'COMPLETED' && run.verdict === 'REJECTED') return {text:'정답과 근거 불일치', tone:'orange', icon:'alert'};
  if (run.status === 'COMPLETED' && run.verdict === 'HOLD') return {text:'판단 보류', tone:'orange', icon:'info'};
  return {text:'상태 확인 필요',tone:'neutral',icon:'info'};
}

export function shouldPoll(run) {
  return run?.executionMode !== 'MOCK' && ['QUEUED','RUNNING'].includes(run?.status);
}

export function safeLawLink(value) {
  try { const url = new URL(value); return url.protocol === 'https:' && ['law.go.kr','www.law.go.kr'].includes(url.hostname) && !url.username && !url.password ? url.href : null; }
  catch { return null; }
}

export function filterQuestions(items, query, status) {
  const term = query.trim().toLocaleLowerCase('ko-KR');
  return items.filter(item => (!term || item.stemPreview.toLocaleLowerCase('ko-KR').includes(term)) &&
    (status === 'all' || (status === 'saved' && !item.latestOwnRun) ||
    (status === 'active' && shouldPoll(item.latestOwnRun)) || (status === 'done' && item.latestOwnRun?.executionMode === 'LIVE' && item.latestOwnRun?.status === 'COMPLETED')));
}

export function errorMessage(error) {
  if (error.status === 401) return '로그인이 만료됐어요. 다시 로그인해 주세요.';
  if (error.status === 403) return '요청을 허용할 수 없어요. 로그인 상태를 새로 확인해 주세요.';
  if (error.code === 'DUPLICATE_QUESTION') return '이미 저장한 문제예요. 내 라이브러리에서 확인해 주세요.';
  if (error.code === 'VERSION_CONFLICT') return '다른 화면에서 문제가 변경됐어요. 최신 내용을 확인한 뒤 다시 수정해 주세요.';
  if (error.status === 404) return '문제를 찾을 수 없거나 접근 권한이 없어요.';
  if (error.status === 428) return '문제의 최신 버전을 다시 불러와 주세요.';
  if (error.network) return error.writing ? '저장 결과를 확인하지 못했어요. 내 라이브러리에서 먼저 확인해 주세요.' : '서버에 연결하지 못했어요. 잠시 후 다시 확인해 주세요.';
  return '요청을 완료하지 못했어요. 입력 내용과 연결 상태를 확인해 주세요.';
}
