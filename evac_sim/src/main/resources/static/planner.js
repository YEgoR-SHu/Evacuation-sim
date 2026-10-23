'use strict';

const SVG_NS = 'http://www.w3.org/2000/svg';
const COLORS = ['#08786b', '#3159a6', '#9a5298', '#bf6d19', '#5b6f22', '#943f56'];
const byId = id => document.getElementById(id);
const state = { svg: '', metadata: null, result: null, busy: false, revision: 0 };
const occupancyInputs = new Map();
const connectionInputs = new Map();
const number = value => new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 1 }).format(value);

function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function svgEl(tag, attrs = {}) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
  return node;
}

function say(text, error = false) {
  const status = byId('status');
  status.textContent = text;
  status.classList.toggle('error', error);
}

function setBusy(busy) {
  state.busy = busy;
  byId('calculate').disabled = busy || !state.metadata;
  byId('load-example').disabled = busy;
  byId('file').disabled = busy;
  byId('fill-capacity').disabled = busy || !state.metadata;
  byId('clear-occupancy').disabled = busy || !state.metadata;
  byId('walking-speed').disabled = busy;
  occupancyInputs.forEach(input => { input.disabled = busy; });
  connectionInputs.forEach(input => { input.disabled = busy; });
  byId('calculate').textContent = busy ? 'Расчёт…' : 'Рассчитать эвакуацию';
}

async function analyze(occupancy, blockedConnections = []) {
  const speed = Number(byId('walking-speed').value);
  if (!Number.isFinite(speed) || speed < 0.1 || speed > 5) throw new Error('Укажите скорость ходьбы от 0,1 до 5 м/с.');
  const response = await fetch('/api/analyze', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ svg: state.svg, occupancy, blockedConnections, walkingSpeedMps: speed })
  });
  let result;
  try { result = await response.json(); }
  catch { throw new Error('Сервер вернул некорректный ответ. Проверьте, что приложение запущено через Java.'); }
  if (!response.ok) throw new Error(result.error || 'Не удалось выполнить расчёт.');
  return result;
}

/* Для просмотра создаётся новая SVG-структура с разрешёнными тегами и атрибутами. */
function safeSvg(source) {
  const doc = new DOMParser().parseFromString(source, 'image/svg+xml');
  if (doc.querySelector('parsererror') || doc.documentElement.localName !== 'svg') throw new Error('Файл не является корректным SVG.');
  const allowedTags = new Set(['svg', 'g', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon', 'path', 'text', 'tspan', 'title', 'desc']);
  const allowedAttrs = new Set(['viewBox', 'x', 'y', 'width', 'height', 'cx', 'cy', 'r', 'rx', 'ry', 'x1', 'x2', 'y1', 'y2', 'points', 'd', 'transform', 'fill', 'fill-opacity', 'fill-rule', 'stroke', 'stroke-width', 'stroke-opacity', 'stroke-linecap', 'stroke-linejoin', 'stroke-dasharray', 'opacity', 'font-size', 'font-family', 'font-weight', 'text-anchor', 'dominant-baseline', 'dx', 'dy', 'preserveAspectRatio']);
  function copy(node) {
    if (node.nodeType === Node.TEXT_NODE) return document.createTextNode(node.textContent);
    if (node.nodeType !== Node.ELEMENT_NODE || !allowedTags.has(node.localName)) return null;
    const clone = svgEl(node.localName);
    for (const attr of node.attributes) {
      if (allowedAttrs.has(attr.name) && !/url\s*\(|javascript:|@import|expression\s*\(/i.test(attr.value)) clone.setAttribute(attr.name, attr.value);
    }
    for (const child of node.childNodes) { const item = copy(child); if (item) clone.append(item); }
    return clone;
  }
  const svg = copy(doc.documentElement);
  let viewBox = (svg.getAttribute('viewBox') || '').trim().split(/[\s,]+/).map(Number);
  if (viewBox.length !== 4 || viewBox.some(x => !Number.isFinite(x)) || viewBox[2] <= 0 || viewBox[3] <= 0) {
    const width = parseFloat(svg.getAttribute('width')), height = parseFloat(svg.getAttribute('height'));
    viewBox = [0, 0, width > 0 && Number.isFinite(width) ? width : 1200, height > 0 && Number.isFinite(height) ? height : 800];
  }
  svg.setAttribute('viewBox', viewBox.join(' '));
  svg.removeAttribute('width'); svg.removeAttribute('height');
  // Координаты маршрутов уже учитывают root transform на сервере.
  const sourceLayer = svgEl('g');
  const sourceAttributes = ['transform', 'fill', 'fill-opacity', 'fill-rule', 'stroke', 'stroke-width', 'stroke-opacity', 'stroke-linecap', 'stroke-linejoin', 'stroke-dasharray', 'opacity', 'font-size', 'font-family', 'font-weight', 'text-anchor', 'dominant-baseline'];
  for (const name of sourceAttributes) {
    if (svg.hasAttribute(name)) { sourceLayer.setAttribute(name, svg.getAttribute(name)); svg.removeAttribute(name); }
  }
  while (svg.firstChild) sourceLayer.append(svg.firstChild);
  svg.append(sourceLayer);
  svg.setAttribute('role', 'img'); svg.setAttribute('aria-label', 'План этажа и использованные маршруты эвакуации');
  return { svg, viewBox };
}

function clearResult() {
  state.result = null;
  ['total', 'evacuated', 'unreachable', 'duration'].forEach(id => { byId(id).textContent = '—'; });
  const row = el('tr'); const cell = el('td', 'Выполните расчёт.'); cell.colSpan = 4; row.append(cell);
  byId('bottlenecks').replaceChildren(row);
  byId('routes').replaceChildren(el('li', 'Выполните расчёт.'));
  byId('warnings').replaceChildren();
  byId('route-filter').replaceChildren(new Option('всех помещений', ''));
  byId('route-filter').disabled = true;
  drawPlan();
}

function invalidate() {
  state.revision++;
  clearResult();
  say('Параметры изменены. Выполните расчёт, чтобы обновить маршруты.');
}

function renderInputs() {
  occupancyInputs.clear(); connectionInputs.clear();
  const occupancyBox = byId('occupancy'); occupancyBox.replaceChildren();
  for (const node of state.metadata.nodes.filter(n => n.kind !== 'outside')) {
    const row = el('div', undefined, 'occupancy-row');
    const label = el('label', node.name && node.name !== node.id ? node.name : node.id);
    const input = el('input'); input.id = 'people-' + occupancyInputs.size;
    input.type = 'number'; input.min = '0'; input.max = String(node.capacity); input.step = '1'; input.value = '0';
    label.htmlFor = input.id;
    label.append(el('small', `${node.id} · вместимость ${node.capacity} чел.`));
    input.addEventListener('input', invalidate);
    occupancyInputs.set(node.id, input); row.append(label, input); occupancyBox.append(row);
  }
  const connectionsBox = byId('connections'); connectionsBox.replaceChildren();
  for (const connection of state.metadata.connections) {
    const label = el('label', undefined, 'connection-row'), input = el('input');
    input.type = 'checkbox'; input.addEventListener('change', invalidate);
    label.append(input, document.createTextNode(`${connection.id} · ${number(connection.throughput)} чел/с`));
    connectionsBox.append(label); connectionInputs.set(connection.id, input);
  }
  const scaleLabel = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 6 }).format(state.metadata.scaleMetersPerUnit);
  byId('plan-info').textContent = `Этаж ${state.metadata.floorNumber} · ${scaleLabel} м/ед. SVG · ${occupancyInputs.size} помещений`;
}

function fillCapacity() {
  for (const node of state.metadata.nodes) {
    const input = occupancyInputs.get(node.id);
    if (input) input.value = node.kind === 'room' ? String(node.capacity) : '0';
  }
}

async function load(source, name, fullRooms = false) {
  if (new TextEncoder().encode(source).length > 2 * 1024 * 1024) throw new Error('SVG превышает 2 МБ. Уменьшите размер плана.');
  safeSvg(source);
  setBusy(true); say(`Проверка плана «${name}»…`);
  const previousSvg = state.svg;
  state.svg = source;
  try {
    const metadata = await analyze({});
    state.metadata = metadata;
    state.revision++;
    renderInputs();
    if (fullRooms) fillCapacity();
    clearResult();
    say(fullRooms
      ? 'Пример открыт: в каждой комнате задано число людей, равное её вместимости; в коридорах — 0. Можно изменить значения и выполнить расчёт.'
      : 'План загружен. Задайте число людей в помещениях или нажмите «По вместимости комнат».');
    renderWarnings(metadata.warnings);
  } catch (error) {
    state.svg = previousSvg;
    throw error;
  } finally { setBusy(false); }
}

function renderWarnings(warnings) {
  byId('warnings').replaceChildren(...(warnings || []).map(warning => el('li', warning)));
}

function drawPlan() {
  if (!state.svg || !state.metadata) return;
  const { svg, viewBox } = safeSvg(state.svg);
  const result = state.result, metadata = result || state.metadata;
  const scale = Math.max(viewBox[2], viewBox[3]) / 240;
  const defs = svgEl('defs'); svg.append(defs);
  COLORS.forEach((color, i) => {
    const marker = svgEl('marker', { id: `route-arrow-${i}`, viewBox: '0 0 10 10', refX: 9, refY: 5, markerWidth: 5, markerHeight: 5, orient: 'auto-start-reverse' });
    marker.append(svgEl('path', { d: 'M 0 0 L 10 5 L 0 10 Z', fill: color })); defs.append(marker);
  });
  const nodes = new Map(metadata.nodes.map(node => [node.id, node]));
  const connections = new Map(metadata.connections.map(connection => [connection.id, connection]));
  const filter = byId('route-filter').value;
  if (result) result.routes.forEach((route, i) => {
    if (filter && route.sourceNodeId !== filter) return;
    const points = [];
    route.nodeIds.forEach((id, index) => {
      const node = nodes.get(id);
      if (node && node.kind !== 'outside') points.push([node.x, node.y]);
      const connection = connections.get(route.connectionIds[index]);
      if (connection) points.push([connection.x, connection.y]);
    });
    if (points.length < 2 || points.some(point => point.some(n => !Number.isFinite(n)))) return;
    const colorIndex = i % COLORS.length;
    const path = svgEl('polyline', { points: points.map(point => point.join(',')).join(' '), fill: 'none', stroke: COLORS[colorIndex], 'stroke-width': scale * 0.8, 'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'stroke-opacity': 0.85, 'marker-end': `url(#route-arrow-${colorIndex})` });
    const title = svgEl('title'); title.textContent = `${route.sourceNodeId}: ${route.people} чел. · ${route.connectionIds.join(' → ')}`;
    path.append(title); svg.append(path);
  });
  for (const connection of metadata.connections) {
    if (!connectionInputs.get(connection.id)?.checked) continue;
    const size = scale * 2;
    const cross = svgEl('path', { d: `M ${connection.x-size} ${connection.y-size} L ${connection.x+size} ${connection.y+size} M ${connection.x-size} ${connection.y+size} L ${connection.x+size} ${connection.y-size}`, stroke: '#b52f35', 'stroke-width': scale, fill: 'none' });
    const title = svgEl('title'); title.textContent = `Закрыто: ${connection.id}`; cross.append(title); svg.append(cross);
  }
  byId('plan').replaceChildren(svg);
}

function renderResult(result) {
  state.result = result;
  byId('total').textContent = number(result.totalPeople);
  byId('evacuated').textContent = number(result.evacuatedPeople);
  byId('unreachable').textContent = number(result.unreachablePeople);
  byId('duration').textContent = result.unreachablePeople ? `${number(result.evacuationTimeSeconds)} с*` : `${number(result.evacuationTimeSeconds)} с`;
  byId('route-filter').replaceChildren(new Option('всех помещений', ''));
  const sources = [...new Set(result.routes.map(route => route.sourceNodeId))];
  sources.forEach(id => byId('route-filter').append(new Option(id, id)));
  byId('route-filter').disabled = !sources.length;
  const rows = [...result.bottlenecks].sort((a, b) => b.maxWaitSeconds - a.maxWaitSeconds).map(item => {
    const row = el('tr');
    [item.connectionId, number(item.people), number(item.maxWaitSeconds), number(item.busySeconds)].forEach(value => row.append(el('td', value)));
    return row;
  });
  if (!rows.length) { const row = el('tr'), cell = el('td', 'Проходы не использовались.'); cell.colSpan = 4; row.append(cell); rows.push(row); }
  byId('bottlenecks').replaceChildren(...rows);
  const routes = result.routes.map((route, i) => {
    const item = el('li'), dot = el('span', undefined, 'route-dot'); dot.style.backgroundColor = COLORS[i % COLORS.length];
    item.append(dot, document.createTextNode(`${route.sourceNodeId} · ${route.people} чел. · ${route.nodeIds.join(' → ')}`));
    item.append(el('small', `Проходы: ${route.connectionIds.join(' → ')}. Прибытие: ${number(route.firstArrivalSeconds)}–${number(route.lastArrivalSeconds)} с.`));
    return item;
  });
  byId('routes').replaceChildren(...(routes.length ? routes : [el('li', 'Завершённых маршрутов нет.')]));
  const warnings = [...(result.warnings || [])];
  if (result.unreachablePeople) warnings.push('* Показано время выхода только тех людей, для которых есть путь. Полная эвакуация здания не завершена.');
  renderWarnings(warnings);
  drawPlan();
  say(result.unreachablePeople
    ? `Расчёт завершён: ${result.unreachablePeople} чел. не имеют доступного пути к выходу. Проверьте закрытые проходы и связи плана.`
    : `Расчёт завершён: эвакуированы ${result.evacuatedPeople} из ${result.totalPeople} чел.`, !!result.unreachablePeople);
}

byId('calculate').addEventListener('click', async () => {
  if (state.busy || !state.metadata) return;
  try {
    const occupancy = {};
    for (const [id, input] of occupancyInputs) {
      if (input.value.trim() === '' || !input.checkValidity()) throw new Error(`Укажите целое число людей от 0 до ${input.max} для помещения ${id}.`);
      occupancy[id] = Number(input.value);
    }
    const blocked = [...connectionInputs].filter(([, input]) => input.checked).map(([id]) => id);
    const revision = state.revision;
    setBusy(true); say('Расчёт маршрутов и очередей…');
    const result = await analyze(occupancy, blocked);
    if (revision === state.revision) renderResult(result);
    else say('Параметры изменились во время расчёта. Выполните расчёт повторно.');
  } catch (error) { say(error.message, true); }
  finally { setBusy(false); }
});

byId('load-example').addEventListener('click', async () => {
  try {
    const name = byId('example').value;
    setBusy(true);
    const response = await fetch('/examples/' + encodeURIComponent(name));
    if (!response.ok) throw new Error('Не удалось загрузить встроенный пример.');
    await load(await response.text(), name, true);
  } catch (error) { say(error.message, true); }
  finally { setBusy(false); }
});

byId('file').addEventListener('change', async event => {
  const file = event.target.files[0];
  if (!file) return;
  try {
    if (file.size > 2 * 1024 * 1024) throw new Error('SVG превышает 2 МБ. Уменьшите размер плана.');
    await load(await file.text(), file.name);
  } catch (error) { say(error.message, true); }
  finally { event.target.value = ''; }
});

byId('fill-capacity').addEventListener('click', () => { fillCapacity(); invalidate(); say('Комнаты заполнены по вместимости; в коридорах — 0. Можно выполнить расчёт.'); });
byId('clear-occupancy').addEventListener('click', () => { occupancyInputs.forEach(input => { input.value = '0'; }); invalidate(); });
byId('walking-speed').addEventListener('input', invalidate);
byId('route-filter').addEventListener('change', drawPlan);
byId('load-example').click();
