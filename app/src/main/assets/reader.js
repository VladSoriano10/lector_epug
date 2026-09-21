/* Trusted app code. EPUB scripts, styles, navigation and event handlers are removed before insertion. */
(() => {
  'use strict';
  const viewport = document.getElementById('viewport'), book = document.getElementById('book');
  let epoch = 0, page = 0, count = 1, stride = 1, ready = false, current = {loc:'', offset:0};
  let touch = null, suppressClick = false, resizeTimer;
  let turnSheet = null, turnAnimation = null;
  let modalOpen=false, ignoreInputUntil=0;
  function cancelTurn() {
    if(turnAnimation)turnAnimation.cancel();
    if(turnSheet)turnSheet.remove();
    turnAnimation=null;turnSheet=null;
  }
  function animateTurn(delta) {
    cancelTurn();
    if(matchMedia('(prefers-reduced-motion: reduce)').matches)return;
    const rect=viewport.getBoundingClientRect(), sheet=document.createElement('div');
    sheet.className='turn-sheet';sheet.setAttribute('aria-hidden','true');
    Object.assign(sheet.style,{left:rect.left+'px',top:rect.top+'px',width:rect.width+'px',height:rect.height+'px',
      transformOrigin:delta>0?'left center':'right center'});
    const copy=book.cloneNode(true);copy.removeAttribute('id');copy.classList.add('turn-book');
    copy.querySelectorAll('[id]').forEach(el=>el.removeAttribute('id'));
    sheet.appendChild(copy);document.body.appendChild(sheet);
    turnSheet=sheet;
    turnAnimation=sheet.animate([
      {transform:'perspective(1000px) rotateY(0deg)',opacity:1},
      {transform:'perspective(1000px) rotateY('+(delta>0?-75:75)+'deg)',opacity:0}
    ],{duration:180,easing:'ease-out'});
    turnAnimation.onfinish=()=>{if(turnSheet===sheet)cancelTurn();};
  }
  const bridge = (name, ...args) => { if (window.AndroidReader && typeof AndroidReader[name] === 'function') AndroidReader[name](epoch,...args); };
  const INSET=8, GAP=48;
  function measurePage() {
    // CSS percentages can be fractional on Android. Use the SAME integer width for
    // the actual column and its translation; never mix clientWidth with calc().
    const width=Math.max(32,Math.floor(viewport.getBoundingClientRect().width-2*INSET));
    book.style.width=width+'px';book.style.columnWidth=width+'px';stride=width+GAP;
    book.style.setProperty('--page-height',Math.max(40,viewport.clientHeight-28)+'px');
  }
  function pageAtRect(rect) { return Math.max(0, Math.floor((rect.left - viewport.getBoundingClientRect().left + page*stride - INSET + GAP/2) / stride)); }
  function visibleOffset(el) {
    const node=el.firstChild;
    if(!node || node.nodeType!==3)return -1;
    const bounds=viewport.getBoundingClientRect(), range=document.createRange();
    for(let offset=0;offset<node.length;offset++) {
      if(/\s/.test(node.data[offset]))continue;
      range.setStart(node,offset);range.setEnd(node,offset+1);
      if([...range.getClientRects()].some(r=>r.width>0 && r.right>bounds.left+INSET && r.left<bounds.right-INSET &&
          r.bottom>bounds.top && r.top<bounds.bottom))return offset;
    }
    return -1;
  }
  function visibleSpeech() {
    for(const el of book.querySelectorAll('[data-loc]')) {
      if(![...el.getClientRects()].some(r=>pageAtRect(r)===page))continue;
      if(el.tagName==='IMG')return {loc:'loc:'+el.dataset.loc,offset:0,chunk:0};
      if(el.dataset.chunk===undefined)continue;
      const offset=visibleOffset(el);
      if(offset>=0)return {loc:'loc:'+el.dataset.loc,offset,chunk:Number(el.dataset.chunk)};
    }
    // Illustration-only page: use the following text, never a previous paragraph.
    const next=[...book.querySelectorAll('[data-chunk]')].find(el=>charPage(el,0)>page);
    return next ? {loc:'loc:'+next.dataset.loc,offset:0,chunk:Number(next.dataset.chunk)} : null;
  }
  function charPage(element, offset) {
    if (!element.firstChild || element.firstChild.nodeType !== 3) return pageAtRect(element.getBoundingClientRect());
    const length=element.firstChild.length, range=document.createRange();
    const start=Math.min(Math.max(0,offset),Math.max(0,length-1));
    range.setStart(element.firstChild,start); range.setEnd(element.firstChild,Math.min(length,start+1));
    return pageAtRect(range.getBoundingClientRect());
  }
  function elementFor(loc) {
    if(loc.startsWith('chunk:')) return document.getElementById('c'+Number(loc.slice(6)));
    if(loc.startsWith('loc:')) return book.querySelector('[data-loc="'+Number(loc.slice(4))+'"]');
    if(loc.startsWith('anchor:')) return [...book.querySelectorAll('[data-anchor]')].find(e=>e.dataset.anchor===loc.slice(7));
    return null;
  }
  function firstVisible() {
    let fallback=null;
    const elements=[...book.querySelectorAll('[data-loc]')];
    for(const el of elements) {
      const rects=[...el.getClientRects()];
      if (!rects.some(r=>pageAtRect(r)===page)) continue;
      let offset=0;
      if(el.dataset.chunk!==undefined && el.firstChild && el.firstChild.nodeType===3) {
        offset=visibleOffset(el);
        if(offset<0)continue;
      }
      let chunk=Number(el.dataset.chunk ?? -1);
      if(chunk<0) {
        const next=elements.find(e=>Number(e.dataset.loc)>Number(el.dataset.loc) && e.dataset.chunk!==undefined);
        const prev=[...elements].reverse().find(e=>Number(e.dataset.loc)<Number(el.dataset.loc) && e.dataset.chunk!==undefined);
        chunk=Number((next || prev)?.dataset.chunk ?? 0);
      }
      fallback={loc:'loc:'+el.dataset.loc,offset,chunk}; break;
    }
    return fallback || {loc:current.loc,offset:current.offset,chunk:0};
  }
  function report() {
    if(!ready) return;
    current=firstVisible();
    document.getElementById('counter').textContent=(page+1)+' / '+count;
    bridge('position', page, count, current.loc, current.offset, current.chunk);
  }
  function go(target, notify=true) {
    page=Math.max(0,Math.min(count-1,Math.trunc(Number(target)||0)));
    book.style.transform='translateX('+(-page*stride)+'px)';
    if(notify) report();
  }
  function restore(loc, offset) {
    if(loc==='end') { go(count-1); return; }
    const element=elementFor(loc || ''); go(element ? charPage(element,offset) : 0);
  }
  function layout(loc,offset) {
    cancelTurn();
    // Reset geometry before measuring after a font/viewport change.
    page=0;book.style.transform='translateX(0px)';
    measurePage();
    count=Math.max(1,Math.round((book.scrollWidth+GAP)/stride));
    ready=true; restore(loc,offset);
  }
  window.Reader={
    async load(html,dark,font,loc,offset,token) {
      cancelTurn();
      epoch=token; const ownEpoch=token; ready=false; viewport.scrollLeft=0; page=0;
      book.style.transform='translateX(0px)';
      document.documentElement.classList.toggle('dark',dark); book.style.fontSize=font+'px';
      book.innerHTML=html; current={loc,offset};
      measurePage();
      await Promise.all([...book.querySelectorAll('img')].map(img=>img.complete?Promise.resolve():new Promise(resolve=>{
        img.onload=resolve; img.onerror=()=>{img.alt=img.alt||'Ilustración no disponible';resolve();};
      })));
      if(ownEpoch!==epoch)return;
      requestAnimationFrame(()=>{if(ownEpoch===epoch)layout(loc,offset);});
    },
    page(target) { cancelTurn();go(target); },
    modal(open) {
      modalOpen=open;touch=null;suppressClick=false;cancelTurn();
      if(!open)ignoreInputUntil=Date.now()+350;
    },
    startSpeech() {
      if(!ready)return;
      cancelTurn();
      const position=visibleSpeech();
      if(position)bridge('playVisible',position.loc,position.offset,position.chunk);
      else bridge('boundarySpeech');
    },
    turn(delta) {
      if(!ready)return;
      if(page+delta<0 || page+delta>=count) bridge('boundary',delta);
      else { bridge('manual'); animateTurn(delta);go(page+delta); }
    },
    locate(loc,offset=0) { cancelTurn();restore(loc,offset); },
    speak(chunk,offset=0) {
      cancelTurn();
      book.querySelectorAll('.speaking').forEach(e=>e.classList.remove('speaking'));
      const el=document.getElementById('c'+chunk);
      if(el) { el.classList.add('speaking'); go(charPage(el,offset)); }
    },
    appearance(dark,font) {
      const saved=firstVisible(); document.documentElement.classList.toggle('dark',dark);book.style.fontSize=font+'px';
      layout(saved.loc,saved.offset);
    },
    clearSpeech() { book.querySelectorAll('.speaking').forEach(e=>e.classList.remove('speaking')); },
    snapshot() {return {ready,epoch,page,count,stride,position:firstVisible(),height:viewport.clientHeight,width:viewport.clientWidth};}
  };
  window.addEventListener('resize',()=>{clearTimeout(resizeTimer);const saved={...current};resizeTimer=setTimeout(()=>{if(ready)layout(saved.loc,saved.offset);},100);});
  document.addEventListener('touchstart',e=>{touch=null;if(!modalOpen && Date.now()>=ignoreInputUntil && e.touches.length===1)touch={x:e.touches[0].clientX,y:e.touches[0].clientY,time:Date.now()};},{passive:true});
  document.addEventListener('touchcancel',()=>{touch=null;},{passive:true});
  document.addEventListener('touchend',e=>{
    if(modalOpen || Date.now()<ignoreInputUntil || !touch){touch=null;return;}const t=e.changedTouches[0],dx=t.clientX-touch.x,dy=t.clientY-touch.y;
    if(Math.abs(dx)>45 && Math.abs(dx)>Math.abs(dy)*1.4 && Date.now()-touch.time<800) {
      suppressClick=true;Reader.turn(dx<0?1:-1);setTimeout(()=>suppressClick=false,400);
    }
    touch=null;
  },{passive:true});
  document.addEventListener('click',()=>{if(!modalOpen && Date.now()>=ignoreInputUntil && !suppressClick && !String(window.getSelection()))bridge('toggle');});
})();
