(() => {
  'use strict';
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ---------- Liquid lens refraction map (Chromium only supports backdrop-filter:url()) ---------- */
  (function lensMap() {
    const w = 200, h = 60, c = document.createElement('canvas'); c.width = w; c.height = h;
    const g = c.getContext('2d'), img = g.createImageData(w, h), r = h / 2;
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      // distance to the pill edge, pushes pixels outward near the rim like a convex lens
      const cx = Math.min(Math.max(x, r), w - r), dx = x - cx, dy = y - r;
      const d = Math.hypot(dx, dy) || 1, edge = Math.max(0, 1 - (r - d) / (r * .55));
      const k = edge * edge, i = (y * w + x) * 4;
      img.data[i] = 128 - (dx / d) * 127 * k; img.data[i + 1] = 128 - (dy / d) * 127 * k; img.data[i + 2] = 128; img.data[i + 3] = 255;
    }
    g.putImageData(img, 0, 0);
    const fe = $('#lens feImage'); if (fe) fe.setAttribute('href', c.toDataURL());
    const chromium = !!window.chrome && CSS.supports('backdrop-filter', 'url(#lens)');
    if (chromium) document.documentElement.classList.add('refract');
  })();

  /* ---------- Tabs ---------- */
  const VIEWS = ['home', 'map', 'news', 'shots'];
  const lens = $('.tabs .lens');
  function placeLens(el, target, squish) {
    if (!el || !target) return;
    el.style.width = target.offsetWidth + 'px';
    el.style.transform = `translateX(${target.offsetLeft}px)`;
    if (squish && !reduced) { el.classList.remove('squish'); void el.offsetWidth; el.classList.add('squish'); }
  }
  let mapReady = false, refreshMap = () => {};
  function show(view, squish = true) {
    if (!VIEWS.includes(view)) view = 'home';
    $$('.view').forEach(v => { v.hidden = v.dataset.view !== view; });
    $$('.tabs a').forEach(a => { if (a.dataset.tab === view) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current'); });
    placeLens(lens, $(`.tabs a[data-tab="${view}"]`), squish);
    if (view === 'map') { if (!mapReady) { mapReady = true; initMap(); } else refreshMap(); }
    if (view === 'shots') placeLens($('.seg-lens'), $('.seg [aria-selected="true"]'), false);
  }
  const fromHash = () => (location.hash || '#home').slice(1);
  addEventListener('hashchange', () => { show(fromHash()); scrollTo({ top: 0, behavior: reduced ? 'auto' : 'smooth' }); });
  addEventListener('resize', () => { placeLens(lens, $('.tabs a[aria-current]'), false); placeLens($('.seg-lens'), $('.seg [aria-selected="true"]'), false); });

  /* ---------- News ---------- */
  const NEWS = window.AOC_NEWS || [];
  const fmt = iso => new Date(iso + 'T12:00:00Z').toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' });
  const list = $('#news-list'), art = $('#news-article');
  function openNews(i) {
    const n = NEWS[i]; if (!n) return;
    $$('button', list).forEach((b, j) => b.setAttribute('aria-current', String(i === j)));
    art.innerHTML = `<div class="nh"><span class="ver">${n.v}</span><time datetime="${n.date}">${fmt(n.date)}</time></div><h3 class="title"></h3><div class="news-body">${n.html}</div>`;
    $('.title', art).textContent = n.title;
    $$('table', art).forEach(t => { const w = document.createElement('div'); w.className = 'tbl'; t.replaceWith(w); w.append(t); });
  }
  NEWS.forEach((n, i) => {
    const li = document.createElement('li'), b = document.createElement('button');
    b.type = 'button'; b.innerHTML = `<span class="nv">${n.v} · ${fmt(n.date)}</span><span class="nt"></span>`;
    $('.nt', b).textContent = n.title; b.addEventListener('click', () => openNews(i));
    li.append(b); list.append(li);
  });
  openNews(0);
  if (NEWS[0]) $('#teaser-news .t-title').textContent = `${NEWS[0].v}: ${NEWS[0].title}`;

  /* ---------- Screenshots ---------- */
  const A = 'assets/shots/';
  const ALBUMS = {
    guild: { note: 'Гильдия искателей приключений стоит в Соре, к югу от центра. Внутри доска заданий, стойка и зал со столами.', shots: [
      ['guild-entrance', 'Главный вход со стороны Соры', 'wide'], ['guild-northwest', 'Гильдия за стеной внешнего кольца'],
      ['guild-hall', 'Зал и доска заданий'], ['guild-board', 'Доска заданий гильдии', 'half'], ['guild-gallery', 'Вид с галереи второго этажа', 'half'],
      ['guild-tables', 'Столы и книжные полки', 'half'], ['sora-aerial', 'Гильдия и кольцевая стена с высоты', 'half']] },
    sora: { note: 'Столица Одного Неба сверху: центральная площадь, пять дорог к кланам, клановые рынки и внешнее кольцо.', shots: [
      ['sora-top', 'Сора строго сверху', 'wide square'], ['sora-aerial', 'Внешнее кольцо и гильдия с птичьего полёта'], ['guild-northwest', 'Стена внешнего кольца']] },
    rift: { note: 'Врата Разлома стоят на восточной стороне внешнего кольца Соры. Портал в зале ведёт в подземелье Склеп Сакуры.', shots: [
      ['rift-outside', 'Врата Разлома снаружи', 'wide'], ['rift-crypt', 'Склеп Сакуры: первые залы'], ['rift-boss', 'Арена Вишнёвого сёгуна']] },
  };
  const gallery = $('#gallery'); let current = [], lbIndex = 0;
  function album(key) {
    const al = ALBUMS[key]; current = al.shots;
    $$('.seg button').forEach(b => b.setAttribute('aria-selected', String(b.dataset.album === key)));
    placeLens($('.seg-lens'), $(`.seg [data-album="${key}"]`), false);
    $('#album-note').textContent = al.note;
    gallery.replaceChildren(...al.shots.map(([f, cap, cls = ''], i) => {
      const b = document.createElement('button'); b.type = 'button'; b.className = 'shot glass ' + cls;
      b.innerHTML = `<img src="${A}${f}${cls.includes('wide') ? '' : '-s'}.webp" alt="" loading="lazy"><span class="cap"></span>`;
      $('img', b).alt = cap; $('.cap', b).textContent = cap;
      b.addEventListener('click', () => openLb(i)); return b;
    }));
  }
  $$('.seg button').forEach(b => b.addEventListener('click', () => album(b.dataset.album)));
  album('guild');

  const lb = $('#lightbox');
  function openLb(i) { lbIndex = (i + current.length) % current.length; const [f, cap] = current[lbIndex]; $('#lb-img').src = A + f + '.webp'; $('#lb-img').alt = cap; $('#lb-cap').textContent = cap; lb.hidden = false; $('#lb-close').focus(); }
  const closeLb = () => { lb.hidden = true; };
  $('#lb-close').addEventListener('click', closeLb);
  $('#lb-prev').addEventListener('click', () => openLb(lbIndex - 1));
  $('#lb-next').addEventListener('click', () => openLb(lbIndex + 1));
  lb.addEventListener('click', e => { if (e.target === lb) closeLb(); });
  addEventListener('keydown', e => { if (lb.hidden) return; if (e.key === 'Escape') closeLb(); if (e.key === 'ArrowLeft') openLb(lbIndex - 1); if (e.key === 'ArrowRight') openLb(lbIndex + 1); });

  /* ---------- Map ---------- */
  function initMap() {
    const W = window.AOC_WORLD; if (!W) return;
    const R = W.R, NS = 'http://www.w3.org/2000/svg', svg = $('#map-svg'), frame = $('#map-frame'), card = $('#map-card');
    const el = (n, a = {}) => { const e = document.createElementNS(NS, n); for (const k in a) e.setAttribute(k, a[k]); return e; };
    const COLORS = { sora: '#ffffff', res: '#ffd36b', place: '#c9d2e3', event: '#c06bff' };
    const root = el('g'); svg.append(root);
    const defs = el('defs'); const cp = el('clipPath', { id: 'world-clip' }); cp.append(el('circle', { cx: 0, cy: 0, r: R * .995 })); defs.append(cp); svg.append(defs);
    root.append(el('image', { href: 'assets/relief.webp', x: -R, y: -R, width: 2 * R, height: 2 * R, 'clip-path': 'url(#world-clip)', preserveAspectRatio: 'none' }));
    root.append(el('circle', { cx: 0, cy: 0, r: R, fill: 'none', stroke: 'rgba(255,255,255,.35)', 'stroke-width': 2, 'vector-effect': 'non-scaling-stroke' }));
    const shade = el('circle', { cx: 0, cy: 0, r: R, fill: 'rgba(22,12,43,.38)' }); root.append(shade);
    const roads = el('g', { 'data-layer': 'roads' }); root.append(roads);
    W.roads.forEach(rd => roads.append(el('polyline', { points: rd.p.map(p => p.join(',')).join(' '), fill: 'none', stroke: rd.s === 'main' ? 'rgba(255,240,225,.85)' : 'rgba(255,240,225,.5)', 'stroke-width': rd.s === 'main' ? 2.4 : 1.4, 'stroke-dasharray': rd.s === 'cross' ? '4 4' : '', 'stroke-linecap': 'round', 'vector-effect': 'non-scaling-stroke' })));
    const layers = {}; ['res', 'place', 'event', 'clan', 'sora'].forEach(k => { layers[k] = el('g', { 'data-layer': k }); root.append(layers[k]); });
    const pois = W.points.map(p => {
      const col = p.k ? W.clans[p.k] : COLORS[p.c];
      const big = p.t.startsWith('Столица') || p.c === 'sora' && p.x === 0 && p.z === 0;
      const g = el('g', { class: 'poi', tabindex: 0, role: 'button', 'aria-label': `${p.n}, ${p.t}` });
      const halo = el('circle', { fill: col, opacity: .28 }), dot = el('circle', { fill: col, stroke: '#140a26', 'vector-effect': 'non-scaling-stroke', 'stroke-width': 2 });
      g.append(halo, dot);
      let label = null;
      if (big) { label = el('text', { class: 'poi-label', 'text-anchor': 'middle' }); label.textContent = p.n.replace('Сора — столица Одного Неба', 'Сора').replace('Столица ', ''); g.append(label); }
      layers[p.c].append(g);
      const pick = () => { card.hidden = false; $('.mc-type', card).textContent = p.t; $('.mc-name', card).textContent = p.n; $('.mc-xz', card).textContent = `X ${p.x}, Z ${p.z}`; };
      g.addEventListener('pointerenter', pick); g.addEventListener('focus', pick); g.addEventListener('click', e => { e.stopPropagation(); pick(); });
      return { p, g, halo, dot, label, big };
    });
    // Sora is drawn on top of its own ring; nudge the guild and the gate apart a little in screen space when zoomed out.
    const legend = $('#map-legend');
    legend.innerHTML = Object.entries(W.clans).map(([k, c]) => `<span><i class="dot" style="background:${c}"></i>${k}</span>`).join('');

    let view = { x: -R, y: -R, w: 2 * R }, home;
    function fit() { const b = frame.getBoundingClientRect(); if (!b.width) return; const aspect = b.height / b.width, w = 2 * R * 1.04 * Math.max(1, 1 / aspect); home = { x: -w / 2, y: -w * aspect / 2, w }; }
    function apply() {
      const b0 = frame.getBoundingClientRect(); if (!b0.width || !home) return;
      const b = b0, aspect = b.height / b.width, h = view.w * aspect;
      svg.setAttribute('viewBox', `${view.x} ${view.y} ${view.w} ${h}`);
      const u = view.w / b.width; // world units per screen pixel
      pois.forEach(o => {
        const s = o.big ? 7 : 4.5; o.dot.setAttribute('cx', o.p.x); o.dot.setAttribute('cy', o.p.z); o.dot.setAttribute('r', s * u);
        o.halo.setAttribute('cx', o.p.x); o.halo.setAttribute('cy', o.p.z); o.halo.setAttribute('r', s * 2.1 * u);
        if (o.label) { o.label.setAttribute('x', o.p.x); o.label.setAttribute('y', o.p.z - 13 * u); o.label.setAttribute('font-size', 13 * u); o.label.style.strokeWidth = 4 * u; }
      });
      // show the guild and the gate labels only when Sora is close enough to tell them apart
      const close = u < 3;
      pois.forEach(o => { if (!o.label && close && !o.lab2 && o.p.c === 'sora') { o.lab2 = el('text', { class: 'poi-label', 'text-anchor': 'middle' }); o.lab2.textContent = o.p.n; o.g.append(o.lab2); }
        if (o.lab2) { o.lab2.style.display = close ? '' : 'none'; o.lab2.setAttribute('x', o.p.x); o.lab2.setAttribute('y', o.p.z + 22 * u); o.lab2.setAttribute('font-size', 12 * u); o.lab2.style.strokeWidth = 4 * u; } });
    }
    function zoom(f, cx, cy) {
      const b = frame.getBoundingClientRect(); cx ??= b.width / 2; cy ??= b.height / 2;
      const u = view.w / b.width, wx = view.x + cx * u, wy = view.y + cy * u;
      const nw = Math.min(Math.max(view.w * f, 260), home.w * 1.2);
      const nu = nw / b.width; view = { x: wx - cx * nu, y: wy - cy * nu, w: nw }; apply();
    }
    fit(); if (home) view = { ...home }; apply();
    addEventListener('resize', () => { fit(); apply(); });
    refreshMap = () => { fit(); if (!view.w || view.w > home.w * 1.2) view = { ...home }; apply(); };
    frame.addEventListener('wheel', e => { e.preventDefault(); const b = frame.getBoundingClientRect(); zoom(Math.exp(e.deltaY * .0015), e.clientX - b.left, e.clientY - b.top); }, { passive: false });
    const ptrs = new Map(); let pinch = 0;
    frame.addEventListener('pointerdown', e => { if (e.target.closest('.map-zoom')) return; frame.setPointerCapture(e.pointerId); ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY }); frame.classList.add('drag'); });
    frame.addEventListener('pointermove', e => {
      if (!ptrs.has(e.pointerId)) return; const prev = ptrs.get(e.pointerId); ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY });
      const b = frame.getBoundingClientRect(), u = view.w / b.width;
      if (ptrs.size === 1) { view.x -= (e.clientX - prev.x) * u; view.y -= (e.clientY - prev.y) * u; apply(); }
      else if (ptrs.size === 2) { const [a, c] = [...ptrs.values()], d = Math.hypot(a.x - c.x, a.y - c.y); if (pinch) zoom(pinch / d, (a.x + c.x) / 2 - b.left, (a.y + c.y) / 2 - b.top); pinch = d; }
    });
    const up = e => { ptrs.delete(e.pointerId); if (ptrs.size < 2) pinch = 0; if (!ptrs.size) frame.classList.remove('drag'); };
    frame.addEventListener('pointerup', up); frame.addEventListener('pointercancel', up);
    $('#zoom-in').addEventListener('click', () => zoom(.6)); $('#zoom-out').addEventListener('click', () => zoom(1 / .6));
    $('#zoom-home').addEventListener('click', () => { view = { ...home }; apply(); });
    $$('.map-filters .pill').forEach(b => b.addEventListener('click', () => {
      const on = !b.classList.contains('on'); b.classList.toggle('on', on); b.setAttribute('aria-pressed', String(on));
      const g = $(`[data-layer="${b.dataset.layer}"]`, svg); if (g) g.style.display = on ? '' : 'none';
    }));
    // start with Sora's neighbourhood readable: open on the whole world but show Sora's card
    const sora = pois.find(o => o.p.c === 'sora'); if (sora) { card.hidden = false; $('.mc-type', card).textContent = sora.p.t; $('.mc-name', card).textContent = sora.p.n; $('.mc-xz', card).textContent = 'X 0, Z 0'; }
  }

  show(fromHash(), false);
  document.fonts?.ready.then(() => { placeLens(lens, $('.tabs a[aria-current]'), false); placeLens($('.seg-lens'), $('.seg [aria-selected="true"]'), false); });
})();
