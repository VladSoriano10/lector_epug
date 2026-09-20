const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES ? process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES + '/playwright' : 'playwright');
const assets = path.join(__dirname, '../app/src/main/assets');
(async () => {
  const browser = await chromium.launch({headless:true});
  const page = await browser.newPage({viewport:{width:393,height:851},deviceScaleFactor:2.75,isMobile:true,hasTouch:true});
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
  // Independent oracle: inspect actual character rectangles, not Reader's page arithmetic.
  await page.evaluate(()=>{
    window.AndroidReader.playVisible=(epoch,loc,offset,chunk)=>{window.speechRequest={epoch,loc,offset,chunk};};
    window.firstPaintedCharacter=()=>{
      const bounds=document.getElementById('viewport').getBoundingClientRect();
      for(const el of document.querySelectorAll('#book [data-chunk]')) {
        const node=el.firstChild, range=document.createRange();
        for(let i=0;i<node.length;i++) {
          if(!node.data[i].trim())continue;
          range.setStart(node,i);range.setEnd(node,i+1);
          if([...range.getClientRects()].some(r=>r.width>0 && r.right>bounds.left && r.left<bounds.right && r.bottom>bounds.top && r.top<bounds.bottom))
            return {loc:'loc:'+el.dataset.loc,offset:i,chunk:Number(el.dataset.chunk)};
        }
      }
      return null;
    };
  });
  for(const font of [16,20,34]) {
    await page.evaluate(font=>Reader.appearance(true,font),font);
    const total=(await page.evaluate(()=>Reader.snapshot())).count;
    for(let n=0;n<total;n++) {
      const result=await page.evaluate(n=>{
        Reader.page(n);
        const expected=firstPaintedCharacter();
        window.speechRequest=null;Reader.startSpeech();
        const request=window.speechRequest;
        const v=document.getElementById('viewport');
        // A clipped (non-scrollable) viewport must reject native scrolling attempts.
        v.scrollLeft=37;
        const drift=Math.abs(v.scrollLeft);
        const bounds=v.getBoundingClientRect();
        const visibleRects=[...document.querySelectorAll('#book [data-chunk]')].flatMap(el=>[...el.getClientRects()])
          .filter(r=>r.right>bounds.left && r.left<bounds.right && r.bottom>bounds.top && r.top<bounds.bottom);
        const leftMargin=visibleRects.length?Math.min(...visibleRects.map(r=>r.left-bounds.left)):8;
        if(expected && request)Reader.speak(request.chunk,request.offset);
        return {expected,request,drift,leftMargin,after:Reader.snapshot().page};
      },n);
      assert(result.drift<=1,`page ${n} must not drift or clip its left edge: ${result.drift}`);
      assert(result.leftMargin>=7,`page ${n}, font ${font}: content enters left clipping edge (${result.leftMargin}px)`);
      if(result.expected) {
        assert(result.request,`missing speech target on page ${n}`);
        const {epoch,...actual}=result.request;
        assert.deepEqual(actual,result.expected,`speech starts at first visible character, page ${n}, font ${font}`);
        assert.equal(result.after,n,'starting speech must not jump to another page');
      }
    }
  }
  // A long italic paragraph crosses columns, keeping a gutter around the glyphs.
  await page.evaluate(async()=>Reader.load('<p><em><span id="c0" data-chunk="0" data-loc="0">'+('fijación Ágil y lectura en español. ').repeat(180)+'</span></em></p>',true,24,'',0,2));
  await page.waitForFunction(()=>Reader.snapshot().ready && Reader.snapshot().epoch===2 && Reader.snapshot().count>2);
  await page.evaluate(()=>Reader.page(1));
  assert.equal((await page.evaluate(()=>Reader.snapshot())).page,1,'italic test measures the second page, not a pending load');
  const inset=await page.evaluate(()=>{
    const bounds=document.getElementById('viewport').getBoundingClientRect();
    return Math.min(...[...document.getElementById('c0').getClientRects()].filter(r=>r.left>=bounds.left && r.left<bounds.right).map(r=>r.left-bounds.left));
  });
  assert(inset>=5,'italic text has an inner left gutter');
  if(out)await page.screenshot({path:out});
  // Fractional CSS viewport: a rounded stride and a fractional column used to drift.
  await page.evaluate(async()=>{
    const viewport=document.getElementById('viewport');viewport.style.left='24.2px';viewport.style.right='24.3px';
    await Reader.load(Array.from({length:180},(_,i)=>'<p><span id="c'+i+'" data-chunk="'+i+'" data-loc="'+i+'">'+('fijación Ágil y lectura en español. ').repeat(18)+'</span></p>').join(''),true,24,'',0,3);
  });
  await page.waitForFunction(()=>Reader.snapshot().ready && Reader.snapshot().epoch===3);
  const fractionalCount=(await page.evaluate(()=>Reader.snapshot())).count;
  assert(fractionalCount>50,'long section covers accumulated rounding errors');
  for(const n of [0,1,20,50,fractionalCount-1]) {
    const margin=await page.evaluate(n=>{
      Reader.page(n);const bounds=document.getElementById('viewport').getBoundingClientRect();
      const rects=[...document.querySelectorAll('#book [data-chunk]')].flatMap(e=>[...e.getClientRects()]).filter(r=>r.right>bounds.left && r.left<bounds.right);
      return Math.min(...rects.map(r=>r.left-bounds.left));
    },n);
    assert(margin>=7 && margin<=9,`fractional width, page ${n}: stable left gutter, got ${margin}`);
  }
  // A gesture begun before a panel must not finish as a page turn after dismissal.
  const modalTest=await page.evaluate(()=>{
    Reader.page(2);const before=Reader.snapshot();
    const touch=(type,x)=>document.dispatchEvent(new TouchEvent(type,{touches:type==='touchstart'?[new Touch({identifier:1,target:document.body,clientX:x,clientY:200})]:[],changedTouches:[new Touch({identifier:1,target:document.body,clientX:x,clientY:200})]}));
    touch('touchstart',300);Reader.modal(true);Reader.modal(false);touch('touchend',40);
    return {before:before.page,after:Reader.snapshot().page,height:Reader.snapshot().height,oldHeight:before.height};
  });
  assert.equal(modalTest.before,modalTest.after,'panel dismissal cancels stale swipe');
  assert.equal(modalTest.height,modalTest.oldHeight);
  console.log('Pagination checks passed: page turns, content restoration, font size, theme, illustration sizing, rotation and speech target.');
  await browser.close();
})().catch(error=>{console.error(error);process.exit(1);});
