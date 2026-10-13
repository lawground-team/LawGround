import {ApiClient} from './api.js';
import {escapeHtml as e, koreaToday, displayTime, emptyQuestion, questionPayload, validateQuestion, statusInfo, shouldPoll, safeLawLink, filterQuestions, errorMessage} from './domain.js';

const root = document.querySelector('#app');
const state = {me:null, config:null, draft:null, editing:null, dirty:false, busy:false, items:[], cursor:null, query:'', filter:'all', errors:{}, epoch:0, authEpoch:0};
let controller = new AbortController(), pollTimer;
const api = new ApiClient({onUnauthorized:expireSession});

const icons = {
  arrow:'M5 12h14m-6-6 6 6-6 6', plus:'M12 5v14M5 12h14', document:'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M8 13h8 M8 17h6',
  book:'M12 7v14 M3 3h5a4 4 0 0 1 4 4 4 4 0 0 1 4-4h5v16h-5a4 4 0 0 0-4 2 4 4 0 0 0-4-2H3z',
  check:'M5 12l4 4L19 6', clock:'M12 8v5l3 2 M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  search:'M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0', info:'M12 11v6 M12 7h.01 M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  alert:'M12 8v5 M12 17h.01 M10 3 2 18a2 2 0 0 0 2 3h16a2 2 0 0 0 2-3L14 3a2 2 0 0 0-4 0', edit:'m16 3 5 5-12 12-6 1 1-6z M14 5l5 5',
  trash:'M3 6h18 M9 6V3h6v3 M5 6l1 15h12l1-15 M10 10v7 M14 10v7', lock:'M6 10h12v11H6z M8 10V6a4 4 0 0 1 8 0v4', logout:'M9 3H3v18h6 M12 12h10 M18 8l4 4-4 4', close:'m6 6 12 12 M6 18 18 6', loader:'M21 12a9 9 0 1 1-6-8.5'
};
function icon(name, className='') { return `<svg class="icon ${className}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${icons[name] ?? icons.document}"/></svg>`; }
function badge(run) { const s = statusInfo(run); return `<span class="badge ${s.tone}">${icon(s.icon)}${s.text}</span>`; }
function link(path, text, className='button secondary', glyph) { return `<a href="${e(path)}" data-nav class="${className}">${glyph ? icon(glyph) : ''}${text}</a>`; }
const analysisNotice = () => `<div class="notice subtle">${icon('info')}<div><strong>지금은 문제 저장과 라이브러리를 사용할 수 있어요.</strong><p>AI 근거 분석은 연결 준비 중이에요. 저장한 문제로 나중에 이어서 분석할 수 있어요.</p></div></div>`;
function header() {
  const active = location.pathname === '/' ? 'home' : location.pathname === '/library' ? 'library' : location.pathname === '/login' ? '' : 'new';
  return `<header class="site-header"><div class="header-inner"><a href="/" data-nav class="brand" aria-label="LawGround 홈">LawGround<span></span></a>${state.config?.localEnvironment?'<span class="environment">로컬</span>':''}<nav aria-label="주 메뉴">${[['/','홈','home'],['/questions/new','문제 등록','new'],['/library','내 라이브러리','library']].map(([path,label,id]) => `<a href="${path}" data-nav class="${id===active?'active':''}" ${id===active?'aria-current="page"':''}>${label}</a>`).join('')}</nav><div class="account">${state.me ? `<span class="avatar" aria-hidden="true">${e([...state.me.displayName][0] || 'L')}</span><span class="member-name">${e(state.me.displayName)}</span><button class="icon-button" data-action="logout" aria-label="로그아웃" title="로그아웃">${icon('logout')}</button>` : link('/login','로그인','button small')}</div></div></header>`;
}
function frame(content, title='LawGround', {focus=true}={}) {
  document.title = `${title} · LawGround`;
  root.innerHTML = `${header()}<main id="main" class="main-container" tabindex="-1">${content}</main><footer><span class="brand small-brand">LawGround</span><span>법령으로 이해하는 공부</span><span class="footer-right">내가 저장한 문제는 나에게만 공개돼요.</span></footer>`;
  if (focus) document.querySelector('#main').focus({preventScroll:true});
}
function loading(title='내용을 불러오고 있어요') { frame(`<div class="loading-state" aria-busy="true"><span class="spinner"></span><p>${title}</p></div>`); }
function notify(text) { const el=document.querySelector('#toast'); el.textContent=text; el.hidden=false; clearTimeout(notify.timer); notify.timer=setTimeout(()=>el.hidden=true,4500); }
function errorBlock(error) { return `<div class="notice error" role="alert">${icon('alert')}<div><strong>${e(errorMessage(error))}</strong>${error.requestId ? `<p>문의용 요청 번호: ${e(error.requestId)}</p>` : ''}</div></div>`; }
function stopReads() { controller.abort(); controller=new AbortController(); clearTimeout(pollTimer); state.epoch++; }
function clearPrivateState() { stopReads(); state.authEpoch++; Object.assign(state,{me:null,draft:null,editing:null,dirty:false,busy:false,items:[],cursor:null,query:'',filter:'all',errors:{}}); document.querySelector('#confirm-dialog').close(); }
function expireSession() { clearPrivateState(); go('/login?expired=1',true); }
function go(path, replace=false) { history[replace?'replaceState':'pushState']({},'',path); window.scrollTo(0,0); void route(); }
async function ensureUser() {
  const previous=state.me?.id;
  try {
    const member=await api.get('/me',{anonymous:true,signal:controller.signal});
    if (previous && previous!==member.id) { clearPrivateState(); state.me=member; notify('계정이 변경되어 이전 화면 내용을 지웠어요.'); go('/library',true); return false; }
    state.me=member;
    return true;
  } catch(error) {
    if (error.status===401) { clearPrivateState(); history.replaceState({},'',previous?'/login?expired=1':'/login'); loginView(Boolean(previous)); return false; }
    throw error;
  }
}

async function homeView(epoch) {
  let rows=[];
  if (state.me) { const page=await api.get('/questions?scope=mine&limit=4',{signal:controller.signal}); if(epoch!==state.epoch)return; rows=page.items; }
  frame(`<section class="home-hero"><div class="hero-copy"><span class="eyebrow">법령 근거로 이해하는 공인중개사 공부</span><h1>정답의 이유를,<br><span class="highlight">법령에서</span> 찾아보세요.</h1><p class="hero-description">문제와 제공된 정답을 정리하고,<br>나만의 라이브러리에서 이어서 공부하세요.</p><div class="hero-actions">${link('/questions/new','새 문제 등록','button large','plus')}${link('/library','내 라이브러리','button secondary large','book')}</div><div class="privacy-line">${icon('lock')}실제 계정에 저장 · 기본 비공개</div></div><div class="hero-workflow"><div class="mini-document" aria-hidden="true"><div class="mini-top"><span class="mini-mark">L</span><div class="mini-lines"><i></i><i></i></div><span class="mini-private">비공개</span></div><div class="mini-title">한 문제씩, 차곡차곡.</div><div class="mini-choices">${[1,2,3,4,5].map(n=>`<div><span>${n}</span><i></i></div>`).join('')}</div><div class="mini-bottom">${icon('book')}내 문제로 채워질 공간</div></div><div class="floating-tag">${icon('check')}다시 찾아볼 수 있는 공부</div></div></section><section class="workflow-strip" aria-label="이용 순서">${[['1','document','문제 입력','본문·5개 선지·제공된 정답'],['2','check','내용 확인 후 저장','법령 기준일을 한 번 더 확인'],['3','book','라이브러리에서 이어보기','내 계정에 저장한 문제 모아보기']].map(([n,ico,title,sub])=>`<div class="workflow-step"><span class="step-icon">${icon(ico)}</span><div><span class="step-caption">STEP ${n}</span><strong>${title}</strong><p>${sub}</p></div></div>`).join('')}</section><section class="recent-section"><div class="section-heading"><div><span class="eyebrow">MY STUDY</span><h2>이어서 확인하기</h2></div>${link('/library','라이브러리 전체 보기','text-link','arrow')}</div>${state.me ? rows.length ? `<div class="recent-grid">${rows.map(recentCard).join('')}</div>` : emptyLibrary(true) : `<div class="guest-callout">${icon('book')}<div><h3>내 문제를 한곳에 모아보세요.</h3><p>로그인하면 저장한 문제를 언제든 다시 확인할 수 있어요.</p></div>${link('/login','Google로 시작하기','button secondary','arrow')}</div>`}</section>${analysisNotice()}`,'홈');
}
function recentCard(item) { return `<article class="recent-card"><div class="card-meta"><span>공인중개사법령 및 중개실무</span>${badge(item.latestOwnRun)}</div><h3>${e(item.stemPreview)}</h3><p>법령 기준일 ${e(item.referenceDate)}</p><div class="card-bottom"><span>${e(displayTime(item.createdAt))} 저장</span>${link(`/questions/${item.id}`,'문제 보기','text-link','arrow')}</div></article>`; }
function loginView(expired=false) {
  const failed=new URLSearchParams(location.search).has('error');
  frame(`<div class="login-layout"><section class="login-story"><span class="eyebrow">YOUR OWN LAWGROUND</span><h1>공부의 근거가<br>쌓이는 곳.</h1><p>한 번 저장한 문제를 다시 꺼내보고,<br>정답의 이유를 차근차근 확인하세요.</p><div class="login-benefit">${icon('book')}내 계정에 보관하는 문제 라이브러리</div><div class="login-benefit">${icon('lock')}나에게만 보이는 비공개 문제</div><div class="login-benefit">${icon('document')}다시 로그인해도 이어지는 공부</div></section><section class="login-card"><span class="login-logo">L<span></span></span><h2>LawGround 시작하기</h2><p>Google 계정으로 간편하게 로그인하세요.</p>${expired?`<div class="notice warning" role="alert">${icon('clock')}로그인이 만료됐어요. 다시 로그인해 주세요.</div>`:''}${failed?`<div class="notice error" role="alert">${icon('alert')}로그인을 완료하지 못했어요. 계정과 연결 상태를 확인하고 다시 시도해 주세요.</div>`:''}${state.config?.googleLoginEnabled ? `<a class="google-button" href="/oauth2/authorization/google"><svg viewBox="0 0 24 24" aria-hidden="true"><path fill="#4285F4" d="M22 12.2c0-.7-.1-1.4-.2-2.1H12v4h5.6a4.8 4.8 0 0 1-2.1 3.2v2.6H19c2-1.8 3-4.4 3-7.7z"/><path fill="#34A853" d="M12 22c2.8 0 5.1-.9 6.8-2.5l-3.4-2.6c-.9.6-2 1-3.4 1-2.7 0-5-1.8-5.8-4.2H2.7v2.7A10.3 10.3 0 0 0 12 22z"/><path fill="#FBBC05" d="M6.2 13.7a6.2 6.2 0 0 1 0-3.4V7.6H2.7a10.3 10.3 0 0 0 0 8.8z"/><path fill="#EA4335" d="M12 6.1c1.5 0 2.8.5 3.8 1.5l2.9-2.9A9.9 9.9 0 0 0 12 2a10.3 10.3 0 0 0-9.3 5.6l3.5 2.7C7 7.9 9.3 6.1 12 6.1z"/></svg>Google 계정으로 로그인${icon('arrow')}</a>` : `<div class="notice warning">${icon('info')}Google 로그인 설정을 준비하고 있어요.</div><button class="button secondary full" data-action="refresh">연결 다시 확인</button>`}<div class="login-divider"></div><p class="login-note">별도 비밀번호를 만들지 않아요.<br>문제와 기록은 로그인한 계정으로 저장돼요.</p>${link('/','홈으로 돌아가기','text-link')}</section></div>`,'로그인');
}
function emptyLibrary(compact=false) { return `<div class="empty-state ${compact?'compact':''}"><span class="empty-illustration">${icon('book')}<span>+</span></span><h${compact?'3':'2'}>첫 문제부터 시작해 볼까요?</h${compact?'3':'2'}><p>아직 저장한 문제가 없어요.<br>문제를 등록하면 여기서 다시 확인할 수 있어요.</p>${link('/questions/new','첫 문제 등록하기','button','plus')}</div>`; }

async function libraryView(epoch) {
  const page=await api.get('/questions?scope=mine&limit=20',{signal:controller.signal});
  if(epoch!==state.epoch)return;
  state.items=page.items; state.cursor=page.nextCursor;
  renderLibrary();
}
function renderLibrary() {
  frame(`<div class="page-heading"><div><span class="eyebrow">MY LIBRARY</span><h1>내가 저장한 문제</h1><p>한 번 저장한 문제를, 언제든 이어서 확인하세요.</p></div>${link('/questions/new','새 문제 등록','button','plus')}</div>${state.items.length ? `<section class="library-panel"><div class="library-tools"><div class="search-box">${icon('search')}<input type="search" id="library-search" aria-label="불러온 문제에서 본문 검색" placeholder="불러온 문제에서 본문 검색" value="${e(state.query)}"></div><button class="button ghost" data-action="refresh-library">새로고침</button></div><div class="filter-row" role="group" aria-label="문제 상태 필터">${[['all','전체'],['saved','저장 완료'],['active','분석 진행 중'],['done','분석 완료']].map(([value,label])=>`<button class="filter-chip ${state.filter===value?'selected':''}" data-filter="${value}" aria-pressed="${state.filter===value}">${label}</button>`).join('')}<span id="loaded-count" class="loaded-count"></span></div><div id="library-rows"></div><div id="library-paging"></div></section>` : `<section class="library-panel">${emptyLibrary()}</section>`}${analysisNotice()}`,'내 라이브러리');
  renderLibraryRows();
}
function renderLibraryRows() {
  const host=document.querySelector('#library-rows'); if(!host)return;
  const rows=filterQuestions(state.items,state.query,state.filter);
  document.querySelector('#loaded-count').textContent=`불러온 ${state.items.length}개 중 ${rows.length}개`;
  host.innerHTML=rows.length ? `<div class="library-table"><div class="table-heading"><span>문제</span><span>법령 기준일</span><span>상태</span><span>다음 행동</span></div>${rows.map(item=>`<article class="library-row"><div><a href="/questions/${item.id}" data-nav class="question-title">${e(item.stemPreview)}</a><p class="row-meta">${e(displayTime(item.createdAt))} 저장 · 비공개</p></div><span class="reference-date"><span class="mobile-label">기준일 </span>${e(item.referenceDate)}</span><div>${badge(item.latestOwnRun)}</div><div>${link(`/questions/${item.id}`,'문제 보기','button secondary small','arrow')}</div></article>`).join('')}</div>` : `<div class="no-results">조건에 맞는 문제가 없어요. 검색어나 필터를 바꿔보세요.</div>`;
  document.querySelector('#library-paging').innerHTML=state.cursor?`<div class="paging"><button class="button secondary" data-action="load-more">문제 더 불러오기 ${icon('arrow')}</button></div>`:'';
}

function fieldError(name) { return `<span id="error-${name}" class="field-error" ${state.errors[name]?'':'hidden'}>${e(state.errors[name] ?? '')}</span>`; }
function invalid(name) { return state.errors[name] ? `aria-invalid="true" aria-describedby="error-${name}"` : ''; }
function editorView() {
  const d=state.draft;
  frame(`<div class="breadcrumb">문제 등록 <span>/</span> ${state.editing?'문제 수정':'문제 입력'}</div><div class="page-heading"><div><h1>${state.editing?'문제 내용을 수정해 주세요':'문제와 정답을 입력해 주세요'}</h1><p>입력한 내용은 다음 단계에서 확인하고 저장할 수 있어요.</p></div><span class="private-pill">${icon('lock')}비공개 저장</span></div>${steps(1)}<div class="editor-layout"><form id="question-form" class="panel question-form" novalidate><div class="field"><label for="question-type">문제 유형 <span class="required">*</span></label><select id="question-type" name="questionType" ${invalid('questionType')}><option value="SELECT_CORRECT" ${d.questionType==='SELECT_CORRECT'?'selected':''}>옳은 설명을 고르는 문제</option><option value="SELECT_INCORRECT" ${d.questionType==='SELECT_INCORRECT'?'selected':''}>틀린 설명을 고르는 문제</option></select>${fieldError('questionType')}</div><div class="field"><div class="label-row"><label for="question-stem">문제 본문 <span class="required">*</span></label><span class="field-limit">최대 10,000자</span></div><textarea id="question-stem" name="stem" rows="4" placeholder="문제의 질문을 입력하세요." ${invalid('stem')}>${e(d.stem)}</textarea>${fieldError('stem')}</div><fieldset class="choice-fields"><legend>선지 <span class="required">*</span></legend>${d.choices.map(({number,text})=>`<div class="choice-field"><label class="choice-number" for="choice-${number}">${number}<span class="sr-only">번 선지</span></label><div class="choice-input"><textarea id="choice-${number}" name="choice${number}" rows="2" placeholder="${number}번 선지 내용을 입력하세요." ${invalid(`choice${number}`)}>${e(text)}</textarea>${fieldError(`choice${number}`)}</div></div>`).join('')}</fieldset><div class="answer-date"><fieldset class="answer-field"><legend>제공된 정답 <span class="required">*</span></legend><div class="answer-options">${[1,2,3,4,5].map(n=>`<label class="answer-option"><input type="radio" name="providedAnswerNumber" value="${n}" ${d.providedAnswerNumber===n?'checked':''} ${invalid('providedAnswerNumber')}><span>${n}</span></label>`).join('')}</div>${fieldError('providedAnswerNumber')}<p class="helper">내 답이 아닌 시험지·교재의 정답을 선택하세요.</p></fieldset><div class="field date-field"><label for="reference-date">법령 기준일 <span class="required">*</span></label><input type="date" id="reference-date" name="referenceDate" value="${e(d.referenceDate)}" ${invalid('referenceDate')}>${fieldError('referenceDate')}<p class="helper">오늘 날짜를 미리 채웠어요. 과거 기출은 적용할 날짜를 확인해 주세요.</p></div></div><div id="form-error" role="alert">${Object.keys(state.errors).length?`<p class="validation-summary">빠진 항목을 확인해 주세요. 입력한 내용은 유지돼요.</p>`:''}</div><div class="form-actions">${link(state.editing?`/questions/${state.editing.id}`:'/','돌아가기','button secondary')}<button class="button" type="submit">입력 내용 확인 ${icon('arrow')}</button></div></form><aside class="input-guide"><h2>입력할 때 확인하세요</h2>${[['1','질문과 5개 선지를 입력하세요.','순서와 부정 표현을 그대로 입력해 주세요.'],['2','제공된 정답을 선택하세요.','내가 고른 답이 아닌, 시험지·교재에서 제공한 정답이에요.'],['3','법령 기준일을 확인하세요.','원하는 날짜를 직접 바꿀 수 있어요. 저장한 문제의 날짜는 자동으로 바뀌지 않아요.']].map(([n,t,p])=>`<div class="guide-item"><span>${n}</span><div><h3>${t}</h3><p>${p}</p></div></div>`).join('')}<div class="guide-bottom">${icon('info')}현재 과목은 공인중개사법령 및 중개실무예요.</div></aside></div>`,'문제 입력');
}
function steps(current) { return `<ol class="steps" aria-label="문제 저장 단계">${['문제 입력','입력 확인','저장 완료'].map((s,i)=>`<li class="${i+1===current?'current':i+1<current?'complete':''}" ${i+1===current?'aria-current="step"':''}><span>${i+1<current?icon('check'):i+1}</span>${s}</li>`).join('')}</ol>`; }
function questionBody(q) { return `<div class="question-type">공인중개사법령 및 중개실무 <span class="type-pill ${q.questionType==='SELECT_INCORRECT'?'incorrect':''}">${q.questionType==='SELECT_INCORRECT'?'틀린 설명 고르기':'옳은 설명 고르기'}</span></div><h2 class="stem">${e(q.stem)}</h2><ol class="choices-list">${q.choices.map(c=>`<li class="${c.number===q.providedAnswerNumber?'provided-answer':''}"><span class="choice-number">${c.number}</span><p>${e(c.text)}</p>${c.number===q.providedAnswerNumber?'<span class="answer-tag">제공된 정답</span>':''}</li>`).join('')}</ol><div class="question-footer"><span>제공된 정답 <strong>${q.providedAnswerNumber}번</strong></span><span>법령 기준일 <strong>${e(q.referenceDate)}</strong></span></div>`; }
function reviewView() {
  frame(`<div class="breadcrumb">문제 등록 <span>/</span> 입력 확인</div><div class="page-heading"><div><h1>저장하기 전에 확인해 주세요</h1><p>문제와 제공된 정답, 법령 기준일이 맞는지 한 번 더 확인하세요.</p></div></div>${steps(2)}<div class="review-layout"><section class="panel question-review">${questionBody(questionPayload(state.draft))}</section><aside class="save-panel"><span class="round-icon">${icon('lock')}</span><h2>${state.editing?'수정 내용을 저장할게요':'내 라이브러리에 저장할게요'}</h2><p>로그인한 계정에 비공개로 저장해요.<br>언제든 다시 열어보고 수정할 수 있어요.</p>${analysisNotice()}<div id="save-error" role="alert"></div><button class="button full" data-action="save" ${state.busy?'disabled':''}>${state.busy?'<span class="spinner white"></span>저장 중…':`${icon('check')}${state.editing?'수정 내용 저장':'문제 저장하기'}`}</button><button class="button secondary full" data-action="back-editor" ${state.busy?'disabled':''}>내용 다시 수정하기</button></aside></div>`,'입력 확인');
}

async function detailView(id, epoch) {
  const [q,runs]=await Promise.all([api.get(`/questions/${id}`,{signal:controller.signal}),api.get(`/questions/${id}/trace-runs?limit=20`,{signal:controller.signal})]);
  if(epoch!==state.epoch)return;
  const saved=new URLSearchParams(location.search).has('saved');
  frame(`<div class="breadcrumb">${link('/library','내 라이브러리','plain-link')}<span>/</span> 문제 상세</div>${saved?`<div class="notice success" role="status">${icon('check')}<div><strong>문제를 저장했어요.</strong><p>새로고침하거나 다시 로그인해도 라이브러리에서 확인할 수 있어요.</p></div></div>`:''}<div class="page-heading"><div><h1>저장한 문제</h1><p>${e(displayTime(q.createdAt))} 저장 · 나에게만 공개</p></div><div class="heading-actions"><button class="button secondary" data-edit="${q.id}">${icon('edit')}수정</button><button class="icon-button danger" data-delete="${q.id}" data-revision="${q.revision}" aria-label="문제 삭제" title="문제 삭제">${icon('trash')}</button></div></div><div class="detail-layout"><section class="panel question-review">${questionBody(q)}</section><aside><div class="panel detail-summary"><span class="round-icon">${icon('book')}</span><h2>내 라이브러리에 보관 중</h2><p>문제와 제공된 정답이 저장됐어요.</p><dl><div><dt>법령 기준일</dt><dd>${e(q.referenceDate)}</dd></div><div><dt>마지막 수정</dt><dd>${e(displayTime(q.updatedAt))}</dd></div></dl><button class="button full" disabled>AI 분석 연결 준비 중</button><p class="helper">실제 분석 서비스가 연결되면 저장한 문제로 분석을 요청할 수 있어요.</p>${link('/library','내 라이브러리로','button secondary full','book')}</div></aside></div><section class="history-section"><div class="section-heading"><h2>이 문제의 실행 이력</h2><span class="helper">각 실행은 당시 문제 내용을 보관해요.</span></div>${runs.items.length?`<div class="history-list">${runs.items.map(run=>`<article><div>${badge(run)}<span>${e(displayTime(run.createdAt))}</span></div>${link(`/runs/${run.id}`,'실행 상세','text-link','arrow')}</article>`).join('')}</div>`:`<div class="empty-history">${icon('clock')}아직 분석 실행 이력이 없어요. 문제 저장은 정상적으로 완료됐어요.</div>`}</section>`,'문제 상세');
}
async function runView(id, epoch) {
  const run=await api.get(`/trace-runs/${id}`,{signal:controller.signal});
  let result=null;
  if(run.status==='COMPLETED') result=await api.get(`/trace-runs/${id}/result`,{signal:controller.signal});
  if(epoch!==state.epoch)return;
  const mock=run.executionMode==='MOCK';
  frame(`<div class="breadcrumb">${link('/library','내 라이브러리','plain-link')}<span>/</span> 실행 상세</div><div class="page-heading"><div><h1>${mock?'개발용 테스트 실행':'분석 실행 상세'}</h1><p>${e(displayTime(run.createdAt))} 접수 · 기준일 ${e(run.referenceDate)}</p></div>${badge(run)}</div>${mock?`<div class="notice warning">${icon('info')}이 기록은 개발용 테스트 실행이에요. 실제 법령 근거 확인 결과가 아니에요.</div>`:''}<section class="panel run-panel">${result?`<h2>${mock?'실제 분석 결과가 없는 기록이에요':e(statusInfo(run).text)}</h2><p>${mock?'이 테스트에서 만들어진 내용을 법령의 근거나 정답 검증으로 표시하지 않아요.':e(result.summary)}</p>${!mock && result.evidences.length?result.evidences.map(ev=>`<article class="evidence-card"><h3>${e(ev.lawTitle)} · ${e(ev.articleLabel)}</h3><blockquote>${e(ev.quote)}</blockquote>${link(`/evidence/${ev.articleId}`,'법령 원문 보기','text-link','arrow')}</article>`).join(''):''}`:`<span class="round-icon">${icon(shouldPoll(run)?'loader':'clock',shouldPoll(run)?'spinning':'')}</span><h2>${e(statusInfo(run).text)}</h2><p>${shouldPoll(run)?'서버에 저장된 실행 상태를 확인하고 있어요. 라이브러리로 이동해도 작업은 유지돼요.':'현재 저장된 실행 상태예요.'}</p>`}<div class="form-actions">${link(`/questions/${run.questionId}`,'문제로 돌아가기','button secondary')}${link('/library','내 라이브러리','button','book')}</div></section>`,'실행 상세');
  if(shouldPoll(run)) pollTimer=setTimeout(()=>{if(epoch===state.epoch)void runView(id,epoch).catch(showRouteError);},document.hidden?10000:2500);
}
async function evidenceView(id, epoch) {
  const law=await api.get(`/law-articles/${id}`,{signal:controller.signal}); if(epoch!==state.epoch)return;
  const url=safeLawLink(law.sourceUrl);
  frame(`<div class="breadcrumb">법령 원문</div><div class="page-heading"><div><h1>${e(law.lawTitle)}</h1><p>${e(law.articleLabel)} · 시행일 ${e(law.effectiveFrom)}</p></div></div><section class="panel law-panel"><h2>${e(law.articleLabel)}</h2><div class="law-text">${e(law.sourceText)}</div>${url?`<a class="button secondary" href="${e(url)}" target="_blank" rel="noopener noreferrer">공식 출처에서 원문 확인 ${icon('arrow')}</a>`:''}</section>`,'법령 원문');
}

async function route() {
  stopReads(); const epoch=state.epoch;
  const path=location.pathname;
  if(path==='/login') { if(state.me)go('/library',true);else loginView(new URLSearchParams(location.search).has('expired')); return; }
  loading();
  try {
    if(path==='/') {await homeView(epoch);return;}
    if(!await ensureUser() || epoch!==state.epoch)return;
    if(path==='/library') {await libraryView(epoch);return;}
    if(path==='/questions/new') {
      const editing=new URLSearchParams(location.search).get('edit');
      if(editing && state.editing?.id!==editing) {
        const q=await api.get(`/questions/${encodeURIComponent(editing)}`,{signal:controller.signal}); if(epoch!==state.epoch)return;
        state.editing={id:q.id,revision:q.revision};state.draft=questionPayload(q);state.dirty=false;
      } else if(!editing && state.editing) {state.editing=null;state.draft=null;}
      state.draft ??= emptyQuestion(); editorView();return;
    }
    const match=path.match(/^\/(questions|runs|evidence)\/([0-9a-f-]{36})$/i);
    if(match) {await ({questions:detailView,runs:runView,evidence:evidenceView}[match[1]])(match[2],epoch);return;}
    frame(`<div class="empty-state"><h1>찾을 수 없는 페이지예요.</h1>${link('/','홈으로','button')}</div>`,'페이지 없음');
  } catch(error) { if(error.name!=='AbortError' && epoch===state.epoch)showRouteError(error); }
}
function showRouteError(error) { frame(`<div class="route-error">${errorBlock(error)}<div class="hero-actions"><button class="button" data-action="refresh">다시 확인하기</button>${link('/library','내 라이브러리','button secondary')}</div></div>`,'연결 확인'); }

async function saveQuestion() {
  if(state.busy)return;state.busy=true;reviewView();
  const authEpoch=state.authEpoch, edit=state.editing;
  try {
    const result=await api.mutate(edit?`/questions/${edit.id}`:'/questions',{method:edit?'PUT':'POST',body:questionPayload(state.draft),headers:edit?{'If-Match':`"${edit.revision}"`}:{}});
    if(authEpoch!==state.authEpoch)return;
    state.dirty=false;state.draft=null;state.editing=null;
    go(`/questions/${result.id}?saved=1`);
  } catch(error) {
    const host=document.querySelector('#save-error'); if(host)host.innerHTML=errorBlock(error);
    if(error.status===400 && error.fieldErrors.length)notify('입력 내용을 다시 확인해 주세요.');
  } finally {state.busy=false;const b=document.querySelector('[data-action="save"]');if(b){b.disabled=false;b.innerHTML=`${icon('check')}${edit?'수정 내용 저장':'문제 저장하기'}`;}const back=document.querySelector('[data-action="back-editor"]');if(back)back.disabled=false;}
}
async function logout() {
  if(state.busy)return;state.busy=true;
  try { await api.mutate('/auth/logout'); clearPrivateState(); notify('로그아웃했어요.');go('/',true); }
  catch(error) {notify(errorMessage(error));}
  finally {state.busy=false;}
}
function deleteQuestion(id,revision) {
  const dialog=document.querySelector('#confirm-dialog');
  dialog.innerHTML=`<span class="round-icon destructive">${icon('trash')}</span><h2 id="dialog-title">이 문제를 삭제할까요?</h2><p>라이브러리에서 문제가 사라지고<br>연결된 실행 결과도 볼 수 없게 돼요.</p><div id="delete-error" role="alert"></div><div class="dialog-actions"><button class="button secondary" id="delete-cancel">취소</button><button class="button danger-button" id="delete-confirm">삭제하기</button></div>`;
  dialog.showModal();document.querySelector('#delete-cancel').onclick=()=>dialog.close();
  document.querySelector('#delete-confirm').onclick=async event=>{
    event.target.disabled=true;
    try {await api.mutate(`/questions/${id}`,{method:'DELETE',headers:{'If-Match':`"${revision}"`}});dialog.close();notify('문제를 삭제했어요.');go('/library');}
    catch(error){const el=document.querySelector('#delete-error');if(el)el.innerHTML=errorBlock(error);event.target.disabled=false;}
  };
}

document.addEventListener('click',event=>{
  const nav=event.target.closest('a[data-nav]');
  if(nav && !event.ctrlKey && !event.metaKey && !event.shiftKey && event.button===0){event.preventDefault();if(state.busy)return;go(nav.getAttribute('href'));return;}
  const button=event.target.closest('button');if(!button || button.disabled)return;
  if(button.dataset.edit){go(`/questions/new?edit=${button.dataset.edit}`);return;}
  if(button.dataset.delete){deleteQuestion(button.dataset.delete,button.dataset.revision);return;}
  if(button.dataset.filter){state.filter=button.dataset.filter;document.querySelectorAll('[data-filter]').forEach(b=>{b.classList.toggle('selected',b.dataset.filter===state.filter);b.setAttribute('aria-pressed',b.dataset.filter===state.filter);});renderLibraryRows();return;}
  const action=button.dataset.action;
  if(action==='save')void saveQuestion();
  if(action==='back-editor')editorView();
  if(action==='logout')void logout();
  if(action==='refresh')void bootstrap();
  if(action==='refresh-library')void route();
  if(action==='load-more')void (async()=>{button.disabled=true;try{const epoch=state.epoch;const page=await api.get(`/questions?scope=mine&limit=20&cursor=${encodeURIComponent(state.cursor)}`,{signal:controller.signal});if(epoch!==state.epoch)return;state.items=[...new Map([...state.items,...page.items].map(item=>[item.id,item])).values()];state.cursor=page.nextCursor;renderLibraryRows();}catch(error){if(error.name!=='AbortError')notify(errorMessage(error));button.disabled=false;}})();
});
document.addEventListener('input',event=>{
  const input=event.target;
  if(input.id==='library-search'){state.query=input.value;renderLibraryRows();return;}
  if(!input.closest('#question-form'))return;
  const name=input.name;state.dirty=true;
  if(name.startsWith('choice'))state.draft.choices[Number(name.replace('choice',''))-1].text=input.value;
  else state.draft[name]=name==='providedAnswerNumber'?Number(input.value):input.value;
  delete state.errors[name];input.removeAttribute('aria-invalid');const error=document.querySelector(`#error-${name}`);if(error)error.hidden=true;
});
document.addEventListener('submit',event=>{
  if(event.target.id!=='question-form')return;event.preventDefault();
  state.errors=validateQuestion(state.draft);
  if(Object.keys(state.errors).length){editorView();const first=document.querySelector('[aria-invalid="true"]');first?.focus();first?.scrollIntoView({block:'center',behavior:'smooth'});return;}
  reviewView();window.scrollTo(0,0);
});
window.addEventListener('popstate',()=>void route());
window.addEventListener('beforeunload',event=>{if(state.dirty){event.preventDefault();event.returnValue='';}});
document.addEventListener('visibilitychange',()=>{
  if(document.hidden || !state.me || state.busy)return;
  void api.get('/me',{anonymous:true}).then(member=>{if(member.id!==state.me?.id){clearPrivateState();state.me=member;notify('로그인 계정이 변경됐어요.');go('/library',true);}}).catch(error=>{if(error.status===401)expireSession();});
});
async function bootstrap() {
  stopReads();loading('로그인과 연결 상태를 확인하고 있어요');
  try {
    const [config,member]=await Promise.all([api.get('/app/config'),api.get('/me',{anonymous:true}).catch(error=>{if(error.status===401)return null;throw error;})]);
    if(state.me && state.me.id!==member?.id)clearPrivateState();
    state.config=config;state.me=member;
    await route();
  } catch(error){showRouteError(error);}
}
void bootstrap();
