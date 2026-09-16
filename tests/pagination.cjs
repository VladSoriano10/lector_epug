const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES ? process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES + '/playwright' : 'playwright');
const assets = path.join(__dirname, '../app/src/main/assets');
(async () => {
  const browser = await chromium.launch({headless:true});
  const page = await browser.newPage({viewport:{width:393,height:851}});
  const reports = [];
  await page.exposeFunction('reportPosition', (...args) => reports.push(args));
  await page.addInitScript(() => {
    window.AndroidReader={position:(...args)=>window.reportPosition(...args),manual(){},boundary(){},toggle(){}};
  });
  await page.route('https://epub.local/**', async route => {
    const name = new URL(route.request().url()).pathname.slice(1);
    if(['reader.html','reader.css','reader.js'].includes(name)) return route.fulfill({path:path.join(assets,name),contentType:name.endsWith('css')?'text/css':name.endsWith('js')?'text/javascript':'text/html'});
    if(name==='asset/illustration.svg') return route.fulfill({contentType:'image/svg+xml',body:'<svg xmlns="http://www.w3.org/2000/svg" width="900" height="1800"><rect width="900" height="1800" fill="#56818c"/><text x="70" y="240" font-size="75" fill="white">Ilustración</text></svg>'});
    return route.fulfill({status:404,body:''});
  });
  await page.goto('https://epub.local/reader.html');
  const markup = '<h1 data-anchor="inicio">Capítulo primero</h1>' + Array.from({length:24},(_,n)=>
    `<p><span id="c${n}" data-loc="${n*2}" data-chunk="${n}">${('La lectura conserva su lugar entre páginas. Un libro también cuenta su historia con ilustraciones. ').repeat(6)}</span></p>` + (n===5?'<img data-loc="11" src="https://epub.local/asset/illustration.svg" alt="Ilustración interior">':'')).join('');
  await page.evaluate(async html=>Reader.load(html,true,20,'',0,1),markup);
  await page.waitForFunction(()=>Reader.snapshot().count>5);
  let first=await page.evaluate(()=>Reader.snapshot());
  assert.equal(first.page,0);assert(first.count>5);
  const transition=await page.evaluate(()=>{
    Reader.turn(1);
    const sheet=document.querySelector('.turn-sheet');
    return {active:!!sheet,ids:sheet.querySelectorAll('[id]').length,
      width:sheet.clientWidth,originalWidth:document.getElementById('viewport').clientWidth,
      font:getComputedStyle(sheet.firstChild).fontSize,originalFont:getComputedStyle(document.getElementById('book')).fontSize};
  });
  assert(transition.active,'manual turn creates a visible sheet');
  assert.equal(transition.ids,0,'overlay does not duplicate reader IDs');
  assert.equal(transition.width,transition.originalWidth);
  assert.equal(transition.font,transition.originalFont);
  assert.equal((await page.evaluate(()=>Reader.snapshot())).page,1);
  await page.waitForTimeout(230);
  assert.equal(await page.locator('.turn-sheet').count(),0,'turn overlay is removed');
  await page.evaluate(()=>{Reader.turn(1);Reader.turn(-1);});
  assert.equal((await page.evaluate(()=>Reader.snapshot())).page,1,'rapid reverse preserves destination');
  await page.waitForTimeout(230);
  assert.equal(await page.locator('.turn-sheet').count(),0);
  await page.emulateMedia({reducedMotion:'reduce'});
  await page.evaluate(()=>Reader.turn(1));
  assert.equal(await page.locator('.turn-sheet').count(),0,'reduced motion skips animation');
  await page.emulateMedia({reducedMotion:'no-preference'});
  await page.evaluate(()=>Reader.page(5));
  const saved=await page.evaluate(()=>Reader.snapshot());assert.equal(saved.page,5);
  assert(saved.position.loc.startsWith('loc:'));
  await page.evaluate(()=>Reader.page(0));
  await page.evaluate(s=>Reader.locate(s.position.loc,s.position.offset),saved);
  assert.equal((await page.evaluate(()=>Reader.snapshot())).page,5,'restores same content at same size');
  await page.evaluate(()=>Reader.appearance(false,30));
  const large=await page.evaluate(()=>Reader.snapshot());assert(large.count>first.count,'larger font increases pages');
  assert.equal(await page.evaluate(()=>getComputedStyle(document.body).backgroundColor),'rgb(250, 246, 238)');
  await page.evaluate(()=>Reader.appearance(true,20));
  await page.evaluate(()=>Reader.speak(6));
  assert.equal(await page.locator('#c6').getAttribute('class'),'speaking');
  const image = await page.locator('img').boundingBox();
  assert(image.height<=first.height,'tall illustration fits page height');assert(image.width<=first.width,'illustration fits width');
  await page.evaluate(()=>Reader.page(3));
  await page.setViewportSize({width:851,height:393});
  await page.waitForTimeout(200);
  assert((await page.evaluate(()=>Reader.snapshot())).count>1);
  await page.setViewportSize({width:393,height:851});await page.waitForTimeout(200);
  await page.evaluate(()=>Reader.locate('loc:11'));
  const out=process.env.PAGING_SCREENSHOT;
  if(out)await page.screenshot({path:out});
  assert(reports.length>5,'page changes report persistent locations');
  console.log('Pagination checks passed: page turns, content restoration, font size, theme, illustration sizing, rotation and speech target.');
  await browser.close();
})().catch(error=>{console.error(error);process.exit(1);});
