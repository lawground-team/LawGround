import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
import {fileURLToPath,pathToFileURL} from 'node:url';
import path from 'node:path';
const {chromium}=process.env.LAWGROUND_E2E_PLAYWRIGHT_DIR
  ? await import(pathToFileURL(path.join(process.env.LAWGROUND_E2E_PLAYWRIGHT_DIR,'index.mjs')))
  : await import('playwright');
const base=process.env.LAWGROUND_E2E_BASE, output=process.env.LAWGROUND_E2E_OUTPUT;
assert(base && output,'Run through Gradle frontendE2E');
await mkdir(output,{recursive:true});
const browser=await chromium.launch({headless:true,...(process.env.LAWGROUND_E2E_CHROME?{executablePath:process.env.LAWGROUND_E2E_CHROME}:{})});
const errors=[],cases=[];
const control=async(command,values={})=>{const r=await fetch(process.env.LAWGROUND_E2E_CONTROL,{method:'POST',headers:{'Content-Type':'application/json','X-Probe-Token':process.env.LAWGROUND_E2E_TOKEN},body:JSON.stringify({command,...values})});assert.equal(r.status,200);};
const context=await browser.newContext({viewport:{width:1365,height:900}});
const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));
page.setDefaultTimeout(20000);
const screenshot=async(name)=>{await page.evaluate(()=>{document.activeElement?.blur();window.scrollTo(0,0);});await page.screenshot({path:path.join(output,name),fullPage:true,animations:'disabled'});};
const check=async(name,fn)=>{await fn();cases.push(name);console.log(`PASS browser ${name}`);};
const api=async(url)=>{const r=await context.request.get(base+'/api/v1'+url);assert.equal(r.status(),200,`${url}: ${await r.text()}`);return r.json();};
const login=async(subject)=>{await control('identity',{subject});await page.goto(base+'/login');await page.locator('.google-button').click();await page.waitForURL(url=>!url.pathname.includes('/login'));await page.locator('[data-action="logout"]').waitFor();};
const create=async(label)=>{
  await page.goto(base+'/questions/new');await page.locator('[name=stem]').fill(label);
  for(let i=1;i<=5;i++)await page.locator(`[name=choice${i}]`).fill(`alpha 합성 선지 ${i}`);
  await page.locator('[name=providedAnswerNumber][value="2"]').check();
  await page.locator('[name=questionType]').selectOption('SELECT_INCORRECT');
  await screenshot('00-input.png');
  await page.getByRole('button',{name:'입력 내용 확인'}).click();await page.locator('[data-action=save]').click();
  await page.waitForURL(/\/questions\/[0-9a-f-]{36}/);await page.locator('[data-action=analyze]').waitFor();return new URL(page.url()).pathname.split('/').at(-1);
};
const analyze=async()=>{await page.locator('[data-action=analyze]').click();await page.waitForURL(/\/runs\/[0-9a-f-]{36}/);return new URL(page.url()).pathname.split('/').at(-1);};
const completed=async()=>{await page.locator('.analysis-choice').first().waitFor({timeout:40000});assert.equal(await page.locator('.analysis-choice').count(),5);};
let own,run;
try{
  await check('signed OIDC login and real session',()=>login('browser-owner-a'));
  await check('photo OCR runs under the worker policy and saves the reviewed text', async () => {
    const image = fileURLToPath(new URL('./fixtures/ocr-question.png', import.meta.url));
    await page.goto(base + '/questions/new');
    const file = page.locator('[data-photo-file="gallery"]');
    const chooserPromise = page.waitForEvent('filechooser');
    await page.getByRole('button', { name: '사진 선택' }).click();
    const chooser = await chooserPromise;
    await chooser.setFiles(image);
    const dialog = page.getByRole('dialog', { name: '사진에서 문제 읽기' });
    const extract = dialog.getByRole('button', { name: '글자 추출', exact: true });
    await dialog.locator('canvas').waitFor();
    const external = [];
    const captureRequest = request => { if (!request.url().startsWith(base + '/')) external.push(request.url()); };
    context.on('request', captureRequest);
    try {
      await extract.click();
      await dialog.getByText('인식한 글자를 사진과 대조해 주세요.', { exact: true }).waitFor({ timeout: 50000 });
      const text = dialog.getByLabel('인식 텍스트');
      const raw = await text.inputValue();
      assert.match(raw.replace(/\s/g, ''), /공인중개사법/);
      assert.match(raw.replace(/\s/g, ''), /30일/);
      assert.deepEqual(external, []);
      const parsed = await page.evaluate(async value => {
        const { splitPhotoQuestion } = await import('/app/ocr.js');
        return splitPhotoQuestion(value);
      }, raw);
      assert.equal(parsed?.choices.length, 5, raw);
      await dialog.getByRole('button', { name: '입력란에 반영' }).click();
      assert.equal(await page.locator('[name=stem]').inputValue(), parsed.stem);
      assert.equal(await page.locator('[name=providedAnswerNumber]:checked').count(), 0);
      assert.equal(await page.locator('[name=choice1]').inputValue(), parsed.choices[0]);
      await page.locator('[name=providedAnswerNumber][value="3"]').check();
      await page.locator('[name=questionType]').selectOption('SELECT_INCORRECT');
      await page.getByRole('button', { name: '입력 내용 확인' }).click();
      await page.locator('[data-action=save]').click();
      await page.waitForURL(/\/questions\/[0-9a-f-]{36}/);
      const saved = await api('/questions/' + new URL(page.url()).pathname.split('/').at(-1));
      assert.equal(saved.stem, parsed.stem);
      assert.equal(saved.providedAnswerNumber, 3);
    } finally {
      context.off('request', captureRequest);
    }
    await page.goto(base + '/questions/new');
    await page.locator('[name=stem]').fill('이미 입력한 문제');
    await file.setInputFiles({ name: 'bad.png', mimeType: 'image/png', buffer: Buffer.from('not a photo') });
    await dialog.getByText('JPEG, PNG 또는 WebP 사진을 선택해 주세요.', { exact: true }).waitFor();
    await dialog.getByRole('button', { name: '닫기' }).click();
    assert.equal(await page.locator('[name=stem]').inputValue(), '이미 입력한 문제');
  });
  await check('approved public question by another owner can run',async()=>{
    await page.goto(base+'/questions/'+process.env.LAWGROUND_E2E_PUBLIC_QUESTION);await analyze();await completed();
  });
  await check('input review and private DB save',async()=>{own=await create('브라우저 검수 · 나의 합성 문제');await screenshot('01-input-saved.png');});
  await check('uncertain POST reuses key after reload without duplicate',async()=>{
    await control('mode',{mode:'wait'});
    const pattern='**/api/v1/questions/*/trace-runs';
    await page.route(pattern,async route=>{if(route.request().method()==='POST'){await route.fetch();await route.abort('connectionreset');}else await route.continue();});
    await page.locator('[data-action=analyze]').click();await page.locator('#analysis-error .notice').waitFor();
    await page.unroute(pattern);await page.reload();await page.getByRole('button',{name:'같은 분석 요청 확인'}).click();
    await page.waitForURL(/\/runs\//);run=new URL(page.url()).pathname.split('/').at(-1);
    assert.equal((await api(`/questions/${own}/trace-runs`)).items.length,1);
    await page.reload();await page.locator('[data-action=cancel-analysis]').waitFor();
    await screenshot('02-processing.png');
    await context.setOffline(true);await page.waitForTimeout(2300);await context.setOffline(false);
    await control('release');await completed();assert.equal((await api(`/questions/${own}/trace-runs`)).items.length,1);
  });
  await check('five choices, evidence anchor, recommendations and mobile layout',async()=>{
    await page.locator('[data-quote-ref]').first().click();await page.locator('.evidence-card:focus').waitFor();
    await page.locator('.related-card').first().waitFor();
    await screenshot('03-result.png');
    await page.setViewportSize({width:390,height:844});await screenshot('04-mobile-result.png');
    assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'mobile horizontal overflow');
    await page.setViewportSize({width:1365,height:900});
    await page.locator('.related-card').first().click();await page.waitForURL(/\/questions\/[0-9a-f-]{36}/);await page.locator('[data-action=analyze]').waitFor();
    await page.goto(base+'/runs/'+run);await completed();await page.locator('.evidence-card a[data-nav]').first().click();await page.waitForURL(/\/evidence\//);await page.getByText('alpha 합성 브라우저 검수 원문입니다. 실제 법령이 아닙니다.',{exact:true}).waitFor();
  });
  await check('HOLD and REJECTED remain distinct legal outcomes',async()=>{
    for(const mode of ['hold','rejected']){await control('mode',{mode});await page.goto(base+'/questions/'+own);await analyze();await completed();const r=await api(`/trace-runs/${new URL(page.url()).pathname.split('/').at(-1)}/result`);assert.equal(r.verdict,mode.toUpperCase());if(mode==='hold')assert.equal(await page.locator('[data-quote-ref]').count(),0);}
  });
  await check('infrastructure failure is not legal HOLD',async()=>{
    await control('mode',{mode:'fail'});await page.goto(base+'/questions/'+own);await analyze();await page.getByRole('heading',{name:'분석을 완료하지 못했어요'}).waitFor({timeout:40000});assert.equal(await page.locator('.analysis-choice').count(),0);
  });
  await check('cancel running work prevents result persistence',async()=>{
    await control('mode',{mode:'wait'});await page.goto(base+'/questions/'+own);const id=await analyze();await page.locator('[data-action=cancel-analysis]').click();await control('release');
    await page.waitForFunction(()=>document.body.innerText.includes('분석이 취소됐어요'));assert.equal((await api(`/trace-runs/${id}`)).status,'CANCELLED');
  });
  await check('library, logout and account isolation',async()=>{
    await page.goto(base+'/library');await page.getByText('브라우저 검수 · 나의 합성 문제',{exact:true}).waitFor();await screenshot('05-library.png');
    await page.locator('[data-action=logout]').click();await page.locator('.site-header .account a[href="/login"]').waitFor();
    assert.equal((await context.request.get(base+'/api/v1/me')).status(),401);
    assert.equal(await page.evaluate(()=>Object.keys(sessionStorage).filter(k=>k.startsWith('lawground:analysis:')).length),0);
    await login('browser-owner-b');assert.equal((await api('/questions?scope=mine')).items.length,0);
    for(const url of [`/questions/${own}`,`/trace-runs/${run}`,`/trace-runs/${run}/job`,`/trace-runs/${run}/result`])assert.equal((await context.request.get(base+'/api/v1'+url)).status(),404,url);
  });
  assert.deepEqual(errors,[]);
  await writeFile(path.join(output,'report.json'),JSON.stringify({cases,browser:browser.version(),real:['Spring','PostgreSQL/pgvector','RabbitMQ','browser','JDBC session'],synthetic:['signed test OIDC issuer','HTTP inference','law and exam fixtures'],errors},null,2));
}catch(error){await screenshot('failure.png');console.error(await page.locator('body').innerText());throw error;}
finally{await browser.close();}
