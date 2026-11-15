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
export const SUBJECTS = {BROKER_LAW:'공인중개사법령 및 중개실무', PUBLIC_LAW:'부동산공법'};
export const subjectLabel = subject => SUBJECTS[subject] ?? SUBJECTS.BROKER_LAW;
export const SOURCE_NOTICE = '본 기출문제의 저작권은 한국산업인력공단(Q-Net)에 있으며, 출처를 밝히고 사용하였습니다. 입력 편의를 위해 일부 재가공됨.';
export const emptyQuestion = () => ({subject:'BROKER_LAW', stem:'', questionType:'SELECT_CORRECT', choices:Array.from({length:5}, (_,i) => ({number:i+1,text:''})), providedAnswerNumber:null, referenceDate:koreaToday()});

export function questionPayload(draft) {
  return {subject:SUBJECTS[draft.subject] ? draft.subject : 'BROKER_LAW', stem:normalize(draft.stem), questionType:draft.questionType,
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

// 붙여넣기 → 지문·선지 5개 자동 분리 (docs/prototypes/paste_split.html 에서 검증한 규칙). 정답 번호·기준일은 추측하지 않는다.
const CIRCLED = '①②③④⑤';
const MARKER_STYLES = [
  n => new RegExp(CIRCLED[n - 1], 'g'),
  n => new RegExp(`[(（]\\s*${n}\\s*[)）]`, 'g'),
  n => new RegExp(`(?:^|(?<=\\s))${n}\\s*[)）]`, 'g'),
  n => new RegExp(`(?:^|(?<=\\s))${n}\\s*[.．](?=\\s)`, 'g'),
];
const squeeze = s => s.replace(/\s+/g, ' ').trim();
function findMarkers(text, style) {
  const out = []; let from = 0;
  for (let n = 1; n <= 5; n++) {
    const re = style(n); re.lastIndex = from;
    const m = re.exec(text); if (!m) return null;
    out.push({start:m.index, end:m.index + m[0].length}); from = m.index + m[0].length;
  }
  return out;
}
export function splitPastedQuestion(raw) {
  const text = String(raw ?? '').replace(/\r\n?/g, '\n').replace(/\u00a0/g, ' ').trim().replace(/^\s*(?:문\s*)?\d{1,3}\s*[.)번]\s*(?=\S)/, '');
  for (const style of MARKER_STYLES) {
    const m = findMarkers(text, style); if (!m) continue;
    const stem = text.slice(0, m[0].start).replace(/[ \t]+\n/g, '\n').replace(/\n{2,}/g, '\n').trim();
    return {stem, choices:m.map((x, i) => squeeze(text.slice(x.end, i < 4 ? m[i + 1].start : text.length)))};
  }
  return null;
}
export function detectQuestionType(stem) {
  const s = String(stem).replace(/\s+/g, '');
  if (/(옳지않은|틀린|잘못된|아닌것|않는것|없는것|않은것)/.test(s)) return 'SELECT_INCORRECT';
  if (/(옳은|맞는|바른|해당하는것|있는것)/.test(s)) return 'SELECT_CORRECT';
  return null;
}
const SUBJECT_WORDS = {
  PUBLIC_LAW:['국토의계획','국토계획','도시·군','도시군','용도지역','용도지구','개발행위','지구단위','기반시설','도시개발','환지','정비사업','재건축','재개발','정비구역','건축법','건축물','건폐율','용적률','주택법','주택조합','사업계획승인','분양가','농지','농업경영'],
  BROKER_LAW:['공인중개사','중개','중개보수','중개사무소','거래신고','토지거래허가','외국인','분묘','매수신청대리','확인·설명','확인설명','전속중개','주택임대차','포상금'],
};
export function detectSubject(text) {
  const s = String(text).replace(/\s+/g, '');
  const score = k => SUBJECT_WORDS[k].reduce((a, w) => a + s.split(w).length - 1, 0);
  const p = score('PUBLIC_LAW'), b = score('BROKER_LAW');
  return p > b ? 'PUBLIC_LAW' : b > p ? 'BROKER_LAW' : null;
}
