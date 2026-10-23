/* Общий модуль: хранилище, вход, SVG-планы, сетка стен и расчёт эвакуации. */
const NS = 'http://www.w3.org/2000/svg';
const COL = { stair: '#2d5fd0', arrive: '#7d9be0', exit: '#0e8f5b', fire: '#d6361f', spawn: '#b5730b' };
const LBL = { stair: 'Лестница ↓', arrive: 'Спуск', exit: 'Выход', fire: 'Очаг', spawn: 'Люди' };
const $ = s => document.querySelector(s);

/* Хранилище сценария. Сейчас localStorage; для Spring Boot замените load/save на fetch('/api/building'). */
const Store = {
  empty: () => ({ floors: [], stairs: [], exits: [], spawns: [], fire: null,
    params: { people: 40, speed: 1.3, fireRate: 0.4, scale: 20, stairTime: 12, exitFlow: 1.3, stairFlow: 1.0 } }),
  load() {
    const d = this.empty();
    try { const s = JSON.parse(localStorage.getItem('evac.building')) || {}; return { ...d, ...s, params: { ...d.params, ...s.params } }; }
    catch (e) { return d; }
  },
  save(b) { try { localStorage.setItem('evac.building', JSON.stringify(b)); return true; } catch (e) { return false; } }
};

/* Единственная роль — администратор. Проверка только в браузере: для настоящей защиты нужен серверный логин. */
const Auth = {
  isIn: () => sessionStorage.getItem('evac.auth') === '1',
  login: () => sessionStorage.setItem('evac.auth', '1'),
  logout: () => sessionStorage.removeItem('evac.auth'),
  guard() { if (!Auth.isIn()) location.replace('login.html'); },
  header(title) {
    const h = document.createElement('header');
    h.innerHTML = `<b>${title}</b><a href="index.html">Расчёт по SVG</a><a href="admin.html">Планы и сценарий</a><a href="viewer.html">Симуляция</a><a href="#" id="out">Выйти</a>`;
    document.body.prepend(h);
    $('#out').onclick = e => { e.preventDefault(); Auth.logout(); location.href = 'login.html'; };
  }
};

const sorted = b => [...b.floors].sort((a, c) => a.level - c.level);

/* Проверяет и очищает загруженный SVG (убирает скрипты и обработчики событий). */
function cleanSvg(text) {
  const doc = new DOMParser().parseFromString(text, 'image/svg+xml'), s = doc.documentElement;
  if (doc.querySelector('parsererror') || s.nodeName !== 'svg') throw new Error('Файл не похож на SVG.');
  s.querySelectorAll('script,foreignObject').forEach(n => n.remove());
  s.querySelectorAll('*').forEach(n => [...n.attributes].forEach(a => {
    if (/^on/i.test(a.name) || /^\s*javascript:/i.test(a.value)) n.removeAttribute(a.name);
  }));
  let vb = (s.getAttribute('viewBox') || '').trim().split(/[\s,]+/).map(Number);
  if (vb.length !== 4 || vb.some(isNaN)) vb = [0, 0, parseFloat(s.getAttribute('width')) || 1000, parseFloat(s.getAttribute('height')) || 700];
  s.setAttribute('viewBox', vb.join(' '));
  return { svg: new XMLSerializer().serializeToString(s), vb };
}

/* Рисует план этажа; поверх него слой g для отметок. */
function drawPlan(box, fl) {
  box.innerHTML = '';
  const svg = document.createElementNS(NS, 'svg'), g = document.createElementNS(NS, 'g');
  const bg = new DOMParser().parseFromString(fl.svg, 'image/svg+xml').documentElement;
  svg.setAttribute('viewBox', fl.vb.join(' '));
  ['x', 'y', 'width', 'height'].forEach((a, k) => bg.setAttribute(a, fl.vb[k]));
  svg.appendChild(document.importNode(bg, true));
  svg.appendChild(g);
  box.appendChild(svg);
  return { svg, g };
}

/* Отметки этажа: лестницы, места спуска сверху, выходы, точки людей, очаг. Возвращает радиус маркера. */
function drawMarks(g, b, fl) {
  const fs = sorted(b), i = fs.findIndex(f => f.id === fl.id), r = Math.max(fl.vb[2], fl.vb[3]) / 70;
  const put = (k, p) => g.insertAdjacentHTML('beforeend',
    `<circle cx="${p.x}" cy="${p.y}" r="${r}" fill="${COL[k]}" stroke="#fff" stroke-width="${r / 5}"/>` +
    `<text x="${p.x}" y="${p.y - r * 1.4}" font-size="${r * 1.6}" text-anchor="middle" fill="${COL[k]}" font-weight="700" stroke="#fff" stroke-width="${r / 6}" paint-order="stroke">${LBL[k]}${p.w && (k === 'stair' || k === 'exit') ? ' ' + p.w + ' м' : ''}</text>`);
  b.stairs.forEach(s => {
    if (s.floor === fl.id) put('stair', s);
    if (fs[i + 1] && s.floor === fs[i + 1].id) put('arrive', s);
  });
  b.exits.filter(e => e.floor === fl.id).forEach(e => put('exit', e));
  b.spawns.filter(e => e.floor === fl.id).forEach(e => put('spawn', e));
  if (b.fire && b.fire.floor === fl.id) put('fire', b.fire);
  return r;
}

/* ===== Сетка проходимости и расчёт эвакуации ===== */
const CELL_M = 0.4; // сторона клетки, м — стандартная ячейка клеточных моделей эвакуации (≈1 человек на клетку, до 6 чел/м²)

/* Растеризует план: клетка — стена, если внутри неё есть тёмный пиксель (порог яркости 150, чёрные линии на светлом фоне). */
function buildGrid(fl, scale) {
  return new Promise((ok, fail) => {
    const cu = CELL_M * scale, [vx, vy, w, h] = fl.vb, gw = Math.max(1, Math.ceil(w / cu)), gh = Math.max(1, Math.ceil(h / cu)), S = 3;
    if (gw * gh > 90000) return fail(new Error(`План «${fl.name}»: сетка получилась слишком мелкой. Проверьте масштаб (единиц SVG в 1 м).`));
    const el = new DOMParser().parseFromString(fl.svg, 'image/svg+xml').documentElement;
    el.setAttribute('width', w); el.setAttribute('height', h);
    const img = new Image();
    img.onload = () => {
      const cv = document.createElement('canvas'), W = cv.width = gw * S, H = cv.height = gh * S;
      const c = cv.getContext('2d', { willReadFrequently: true });
      c.fillStyle = '#fff'; c.fillRect(0, 0, W, H); c.drawImage(img, 0, 0, W, H);
      const d = c.getImageData(0, 0, W, H).data, wall = new Uint8Array(gw * gh);
      for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
        const i = (y * W + x) * 4;
        if (d[i] * .3 + d[i + 1] * .59 + d[i + 2] * .11 < 150) wall[((y / S) | 0) * gw + ((x / S) | 0)] = 1;
      }
      ok({ gw, gh, cu, vx, vy, wall });
    };
    img.onerror = () => fail(new Error(`Не удалось разобрать план «${fl.name}» как изображение.`));
    img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(new XMLSerializer().serializeToString(el));
  });
}

/* Ближайшая проходимая клетка к точке плана (координаты SVG), в радиусе поиска до 6 клеток, иначе -1. */
function snap(g, x, y) {
  const cx = Math.floor((x - g.vx) / g.cu), cy = Math.floor((y - g.vy) / g.cu);
  for (let r = 0; r <= 6; r++) for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) {
    if (Math.max(Math.abs(dx), Math.abs(dy)) !== r) continue;
    const X = cx + dx, Y = cy + dy;
    if (X >= 0 && Y >= 0 && X < g.gw && Y < g.gh && !g.wall[Y * g.gw + X]) return Y * g.gw + X;
  }
  return -1;
}

/* То же самое, но вдобавок клетка должна быть свободна от других людей (occ) — для расстановки при старте. */
function snapFree(g, occ, x, y, maxR = 25) {
  const cx = Math.floor((x - g.vx) / g.cu), cy = Math.floor((y - g.vy) / g.cu);
  for (let r = 0; r <= maxR; r++) for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) {
    if (Math.max(Math.abs(dx), Math.abs(dy)) !== r) continue;
    const X = cx + dx, Y = cy + dy;
    if (X < 0 || Y < 0 || X >= g.gw || Y >= g.gh) continue;
    const idx = Y * g.gw + X;
    if (!g.wall[idx] && !occ[idx]) return idx;
  }
  return -1;
}

/* Двоичная куча (мин-куча) для алгоритма Дейкстры: элементы — пары [стоимость, ключ]. */
function heapPush(h, c, k) {
  h.push([c, k]);
  for (let i = h.length - 1; i > 0;) { const p = (i - 1) >> 1; if (h[p][0] <= h[i][0]) break; [h[p], h[i]] = [h[i], h[p]]; i = p; }
}
function heapPop(h) {
  const top = h[0], l = h.pop();
  if (h.length) {
    h[0] = l;
    for (let i = 0;;) {
      const a = 2 * i + 1, c = a + 1; let m = i;
      if (a < h.length && h[a][0] < h[m][0]) m = a;
      if (c < h.length && h[c][0] < h[m][0]) m = c;
      if (m === i) break;
      [h[m], h[i]] = [h[i], h[m]]; i = m;
    }
  }
  return top;
}

/* Многоисточниковый Дейкстра по сетке: возвращает время (с) от каждой клетки до ближайшего источника
   при свободном движении со скоростью speed (диагонали запрещены «срезать» углы стен). */
function fieldFrom(g, sources, speed) {
  const n = g.gw * g.gh, dist = new Float32Array(n).fill(Infinity), seen = new Uint8Array(n), heap = [];
  const diag = g.cu * Math.SQRT2;
  sources.forEach(s => { if (s.idx >= 0 && s.extra < dist[s.idx]) { dist[s.idx] = s.extra; heapPush(heap, s.extra, s.idx); } });
  while (heap.length) {
    const [d, idx] = heapPop(heap);
    if (seen[idx]) continue;
    seen[idx] = 1;
    const x = idx % g.gw, y = (idx / g.gw) | 0;
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      if (!dx && !dy) continue;
      const nx = x + dx, ny = y + dy;
      if (nx < 0 || ny < 0 || nx >= g.gw || ny >= g.gh) continue;
      const nidx = ny * g.gw + nx;
      if (g.wall[nidx]) continue;
      if (dx && dy && (g.wall[y * g.gw + nx] || g.wall[ny * g.gw + x])) continue; // не срезать углы стен
      const nd = d + (dx && dy ? diag : g.cu) / speed;
      if (nd < dist[nidx]) { dist[nidx] = nd; heapPush(heap, nd, nidx); }
    }
  }
  return dist;
}

/* Строит сетки стен и поля времени-до-безопасности для всех этажей, снизу вверх.
   portals[floorId]: Map(индекс клетки → {kind:'exit'|'stair', gap, toFloor, toIdx, stairTime}).
   gap — минимальный интервал между людьми, проходящими через эту дверь/лестницу (по ширине и пропускной способности). */
async function buildEngine(b) {
  const fs = sorted(b), P = b.params, grids = {}, fields = {}, portals = {};
  for (const fl of fs) grids[fl.id] = await buildGrid(fl, P.scale);
  for (let i = 0; i < fs.length; i++) {
    const fl = fs[i], g = grids[fl.id], pm = new Map(), sources = [];
    b.exits.filter(e => e.floor === fl.id).forEach(e => {
      const idx = snap(g, e.x, e.y);
      if (idx < 0) return;
      pm.set(idx, { kind: 'exit', gap: 1 / Math.max(.05, (e.w || 1) * P.exitFlow) });
      sources.push({ idx, extra: 0 });
    });
    if (i > 0) {
      const below = fs[i - 1], gb = grids[below.id], fb = fields[below.id];
      b.stairs.filter(s => s.floor === fl.id).forEach(s => {
        const idxB = snap(gb, s.x, s.y), idx = snap(g, s.x, s.y);
        if (idx < 0 || idxB < 0 || !isFinite(fb[idxB])) return;
        pm.set(idx, { kind: 'stair', gap: 1 / Math.max(.05, (s.w || 1) * P.stairFlow), toFloor: below.id, toIdx: idxB, stairTime: P.stairTime });
        sources.push({ idx, extra: fb[idxB] + P.stairTime });
      });
    }
    portals[fl.id] = pm;
    fields[fl.id] = sources.length ? fieldFrom(g, sources, P.speed) : new Float32Array(g.gw * g.gh).fill(Infinity);
  }
  return { grids, fields, portals };
}
