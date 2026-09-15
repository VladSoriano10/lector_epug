/* Trusted app code. EPUB scripts, styles, navigation and event handlers are removed before insertion. */
(() => {
  'use strict';
  const viewport = document.getElementById('viewport'), book = document.getElementById('book');
  let epoch = 0, page = 0, count = 1, stride = 1, ready = false, current = {loc:'', offset:0};
  let touch = null, suppressClick = false, resizeTimer;
  const bridge = (name, ...args) => { if (window.AndroidReader && typeof AndroidReader[name] === 'function') AndroidReader[name](epoch,...args); };
  function pageAtRect(rect) { return Math.max(0, Math.floor((rect.left - viewport.getBoundingClientRect().left + viewport.scrollLeft + 1) / stride)); }
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
        let low=0,high=el.firstChild.length;
        while(low<high) { const middle=(low+high)>>1; if(charPage(el,middle)<page) low=middle+1; else high=middle; }
        offset=low;
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
    page=Math.max(0,Math.min(count-1,Number(target)||0)); viewport.scrollLeft=page*stride;
    if(notify) report();
  }
  function restore(loc, offset) {
    if(loc==='end') { go(count-1); return; }
    const element=elementFor(loc || ''); go(element ? charPage(element,offset) : 0);
  }
  function layout(loc,offset) {
    book.style.columnWidth=viewport.clientWidth+'px'; stride=viewport.clientWidth+48;
    book.style.setProperty('--page-height',Math.max(40,viewport.clientHeight-28)+'px');
    count=Math.max(1,Math.round((book.scrollWidth+48)/stride)); ready=true; restore(loc,offset);
  }
  window.Reader={
    async load(html,dark,font,loc,offset,token) {
      epoch=token; const ownEpoch=token; ready=false; viewport.scrollLeft=0; page=0;
      document.documentElement.classList.toggle('dark',dark); book.style.fontSize=font+'px';
      book.innerHTML=html; current={loc,offset};
      book.style.columnWidth=viewport.clientWidth+'px';
      book.style.setProperty('--page-height',Math.max(40,viewport.clientHeight-28)+'px');
      await Promise.all([...book.querySelectorAll('img')].map(img=>img.complete?Promise.resolve():new Promise(resolve=>{
        img.onload=resolve; img.onerror=()=>{img.alt=img.alt||'Ilustración no disponible';resolve();};
      })));
      if(ownEpoch!==epoch)return;
      requestAnimationFrame(()=>{if(ownEpoch===epoch)layout(loc,offset);});
    },
    page(target) { go(target); },
    turn(delta) {
      if(!ready)return;
      if(page+delta<0 || page+delta>=count) bridge('boundary',delta);
      else { bridge('manual'); go(page+delta); }
    },
    locate(loc,offset=0) { restore(loc,offset); },
    speak(chunk,offset=0) {
      book.querySelectorAll('.speaking').forEach(e=>e.classList.remove('speaking'));
      const el=document.getElementById('c'+chunk);
      if(el) { el.classList.add('speaking'); go(charPage(el,offset)); }
    },
    appearance(dark,font) {
      const saved=firstVisible(); document.documentElement.classList.toggle('dark',dark);book.style.fontSize=font+'px';
      layout(saved.loc,saved.offset);
    },
    clearSpeech() { book.querySelectorAll('.speaking').forEach(e=>e.classList.remove('speaking')); },
    snapshot() {return {page,count,stride,position:firstVisible(),height:viewport.clientHeight,width:viewport.clientWidth};}
  };
  window.addEventListener('resize',()=>{clearTimeout(resizeTimer);const saved={...current};resizeTimer=setTimeout(()=>{if(ready)layout(saved.loc,saved.offset);},100);});
  document.addEventListener('touchstart',e=>{if(e.touches.length===1)touch={x:e.touches[0].clientX,y:e.touches[0].clientY,time:Date.now()};},{passive:true});
  document.addEventListener('touchend',e=>{
    if(!touch)return;const t=e.changedTouches[0],dx=t.clientX-touch.x,dy=t.clientY-touch.y;
    if(Math.abs(dx)>45 && Math.abs(dx)>Math.abs(dy)*1.4 && Date.now()-touch.time<800) {
      suppressClick=true;Reader.turn(dx<0?1:-1);setTimeout(()=>suppressClick=false,400);
    }
    touch=null;
  },{passive:true});
  document.addEventListener('click',()=>{if(!suppressClick && !String(window.getSelection()))bridge('toggle');});
})();
