import { Auth } from '../auth';
import { api } from '../api';
import { $, safeSvgElement } from '../shared';
import { personAt, playbackCounts } from '../playback.mjs';
import type { Building, Floor, Mark, NetworkNode, SimulationRun, SimulationSettings, ScenarioFull, Topology, Position } from '../types';

const query = new URLSearchParams(location.search);
let bid = Number(query.get('bid')) || 0;
let sid = Number(query.get('sid')) || 0;
let building: Building | null = null;
let scenario: ScenarioFull | null = null;
let topology: Topology | null = null;
let settings: SimulationSettings = { occupancy: {}, blockedConnections: [], stairLinks: [] };
let selectedRun: SimulationRun | null = null;
let floors: Floor[] = [];
let currentFloor = '';
let time = 0;
let running = false;
let lastFrame = 0;
let pending = false;
let svg: SVGSVGElement | null = null;
let dots: SVGGElement | null = null;
const ns = 'http://www.w3.org/2000/svg';
const occupancyInputs = new Map<string, HTMLInputElement>();
const blockedInputs = new Map<string, HTMLInputElement>();
const buildSelect = $<HTMLSelectElement>('#building-select');
const scenarioSelect = $<HTMLSelectElement>('#scenario-select');
const runSelect = $<HTMLSelectElement>('#run-select');
const msg = $<HTMLParagraphElement>('#msg');
const timeline = $<HTMLInputElement>('#timeline');
const calculateBtn = $<HTMLButtonElement>('#calculate-btn');
const goBtn = $<HTMLButtonElement>('#go-btn');
const resetBtn = $<HTMLButtonElement>('#reset-btn');
const clock = $<HTMLOutputElement>('#clock');

function text(tag: string, value: string, className = ''): HTMLElement {
    const element = document.createElement(tag); element.textContent = value; element.className = className; return element;
}
function message(error: unknown) { msg.textContent = error instanceof Error ? error.message : String(error); }
function clear(element: Element) { element.replaceChildren(); }
function addOption(select: HTMLSelectElement, value: string | number, label: string) {
    const option = document.createElement('option'); option.value = String(value); option.textContent = label; select.append(option);
}
function setBusy(busy: boolean) {
    pending = busy;
    calculateBtn.disabled = busy || !topology;
    $<HTMLButtonElement>('#reset-settings-btn').disabled = busy;
    $<HTMLButtonElement>('#load-btn').disabled = busy;
    buildSelect.disabled = busy; scenarioSelect.disabled = busy;
}
function warnings(values: string[]) {
    const list = $('#warnings'); clear(list);
    [...new Set(values)].forEach(value => list.append(text('li', value)));
    $<HTMLElement>('#warnings-section').hidden = list.children.length === 0;
}
function floorName(id: string): string { return floors.find(f => String(f.id) === id)?.name || id; }
function nodeLabel(node: NetworkNode): string { return `${floorName(node.floorId)} · ${node.name || node.id}`; }
function table(target: Element, headers: string[], rows: string[][]) {
    clear(target); const element = document.createElement('table');
    const head = document.createElement('thead'), header = document.createElement('tr');
    headers.forEach(label => header.append(text('th', label))); head.append(header); element.append(head);
    const body = document.createElement('tbody');
    for (const row of rows) { const tr = document.createElement('tr'); row.forEach(value => tr.append(text('td', value))); body.append(tr); }
    if (!rows.length) { const tr = document.createElement('tr'); const td = text('td', 'Нет данных'); td.setAttribute('colspan', String(headers.length)); tr.append(td); body.append(tr); }
    element.append(body); target.append(element);
}
function number(value: number) { return value.toLocaleString('ru-RU', { maximumFractionDigits: 1 }); }
function stop() { running = false; goBtn.textContent = 'Воспроизвести'; }
function resetResult() {
    stop(); selectedRun = null; time = 0; timeline.value = '0'; timeline.disabled = true;
    goBtn.disabled = true; resetBtn.disabled = true; clock.textContent = '0,0 с';
    $<HTMLElement>('#result-section').hidden = true;
}
async function loadBuildings() {
    const available = await api.buildings.list(); clear(buildSelect);
    available.forEach(item => addOption(buildSelect, item.id, item.name));
    if (!available.length) { msg.textContent = 'Создайте здание и добавьте SVG-план.'; return; }
    if (!available.some(item => item.id === bid)) bid = available[0].id;
    buildSelect.value = String(bid);
    await loadScenarios();
}
async function loadScenarios() {
    resetResult(); topology = null; calculateBtn.disabled = true;
    bid = Number(buildSelect.value); building = await api.buildings.get(bid); floors = building.floors;
    clear(scenarioSelect);
    building.scenarios.forEach(item => addOption(scenarioSelect, item.id, item.name));
    if (!building.scenarios.some(item => item.id === sid)) sid = building.scenarios.find(item => item.isDefault)?.id || building.scenarios[0]?.id || 0;
    scenarioSelect.value = String(sid);
    if (!sid) { msg.textContent = 'Создайте сценарий в редакторе здания.'; return; }
    await loadScenario();
}
async function loadScenario() {
    if (pending || !bid) return;
    setBusy(true); resetResult(); topology = null; occupancyInputs.clear(); blockedInputs.clear();
    clear($('#occupancy-list')); clear($('#blocked-list'));
    sid = Number(scenarioSelect.value);
    msg.textContent = 'Загрузка сценария и плана...';
    try {
        [building, scenario, settings] = await Promise.all([
            api.buildings.get(bid), api.scenarios.get(bid, sid), api.simulation.settings(bid, sid),
        ]);
        floors = building.floors;
        currentFloor = String([...floors].sort((a, b) => a.level - b.level)[0]?.id || '');
        $<HTMLAnchorElement>('#edit-link').href = `/scenario.html?bid=${bid}&sid=${sid}`;
        history.replaceState(null, '', `/viewer.html?bid=${bid}&sid=${sid}`);
        renderStairLinks(); renderFloor();
        await refreshRuns();
        try {
            topology = await api.simulation.topology(bid, sid);
            renderTopology();
            msg.textContent = 'Проверьте заполненность и проходы, затем выполните расчёт.';
        } catch (error) {
            $<HTMLElement>('#mode-note').textContent = 'Для обычного SVG добавьте в редакторе метки людей и выходов. Для размеченного SVG задайте людей по помещениям.';
            warnings([]); message(error);
        }
    } catch (error) { message(error); }
    finally { setBusy(false); }
}
function renderTopology() {
    if (!topology) return;
    const semantic = topology.mode === 'semantic';
    $<HTMLElement>('#mode-note').textContent = semantic
        ? 'Размеченный план. Число людей задаётся по помещениям; масштаб берётся из data-scale каждого SVG (метры на единицу). Вместимость не означает заполненность.'
        : `План с метками. ${scenario?.params.people || 0} человек распределяются между метками «Люди». Масштаб: ${scenario?.params.scale} единиц SVG в метре.`;
    $<HTMLElement>('#occupancy-section').hidden = !semantic;
    const container = $('#occupancy-list'); clear(container); occupancyInputs.clear();
    if (semantic) for (const node of topology.nodes) {
        const label = document.createElement('label'); label.className = 'occupancy-row';
        label.append(text('span', `${nodeLabel(node)} (до ${node.capacity})`));
        const input = document.createElement('input'); input.type = 'number'; input.min = '0'; input.max = String(node.capacity); input.step = '1';
        input.value = String(Object.prototype.hasOwnProperty.call(settings.occupancy, node.id) ? settings.occupancy[node.id] : topology.occupancy[node.id] || 0);
        input.dataset.nodeId = node.id; input.setAttribute('aria-label', `Люди: ${nodeLabel(node)}`);
        input.addEventListener('input', () => { msg.textContent = 'Параметры изменены. Выполните новый расчёт.'; });
        label.append(input); container.append(label); occupancyInputs.set(node.id, input);
    }
    const blocked = $('#blocked-list'); clear(blocked); blockedInputs.clear();
    const resources = topology.passages.filter(p => p.throughput !== null);
    for (const passage of resources) {
        const label = document.createElement('label'); label.className = 'check-row';
        const input = document.createElement('input'); input.type = 'checkbox'; input.checked = settings.blockedConnections.includes(passage.id); input.dataset.connectionId = passage.id;
        label.append(input, text('span', `${passage.kind === 'stairs' || passage.kind === 'stair' ? 'Лестница' : passage.kind === 'exit' ? 'Выход' : 'Дверь'} · ${passage.id}`));
        blocked.append(label); blockedInputs.set(passage.id, input);
    }
    if (!resources.length) blocked.append(text('p', 'На плане нет проходов с ограничением потока.', 'muted'));
    warnings(topology.warnings || []);
}
function collectSettings(): SimulationSettings {
    const counts: Record<string, number> = Object.create(null);
    for (const [id, input] of occupancyInputs) {
        const value = Number(input.value);
        if (!Number.isInteger(value) || value < 0 || value > Number(input.max)) throw new Error(`Проверьте количество людей: ${id}`);
        counts[id] = value;
    }
    return { occupancy: topology?.mode === 'semantic' ? counts : topology?.mode === 'raster' ? {} : settings.occupancy,
        blockedConnections: !topology ? settings.blockedConnections : [...blockedInputs].filter(([, input]) => input.checked).map(([id]) => id), stairLinks: settings.stairLinks };
}
function markLabel(mark: Mark) { return `${floorName(String(mark.floorId))} · #${mark.id} (${number(mark.x)}, ${number(mark.y)})`; }
function renderStairLinks() {
    const marks = scenario?.marks.filter(mark => mark.kind === 'stair') || [];
    $<HTMLElement>('#stairs-section').hidden = marks.length === 0 && settings.stairLinks.length === 0;
    const from = $<HTMLSelectElement>('#stair-from'), to = $<HTMLSelectElement>('#stair-to'); clear(from); clear(to);
    marks.forEach(mark => { addOption(from, mark.id, markLabel(mark)); addOption(to, mark.id, markLabel(mark)); });
    const list = $('#stair-links'); clear(list);
    for (const [index, link] of settings.stairLinks.entries()) {
        const a = marks.find(mark => mark.id === link.fromMarkId), b = marks.find(mark => mark.id === link.toMarkId);
        const item = document.createElement('li');
        item.append(text('span', `${a ? markLabel(a) : `#${link.fromMarkId}`} → ${b ? markLabel(b) : `#${link.toMarkId}`}`));
        const remove = text('button', 'Убрать') as HTMLButtonElement; remove.type = 'button';
        remove.onclick = async () => { settings.stairLinks.splice(index, 1); await saveStairLinks(); };
        item.append(remove); list.append(item);
    }
}
async function saveStairLinks() {
    try {
        settings = await api.simulation.saveSettings(bid, sid, collectSettings());
        renderStairLinks(); topology = await api.simulation.topology(bid, sid); renderTopology(); calculateBtn.disabled = false;
        msg.textContent = 'Связи лестниц сохранены. Выполните новый расчёт.';
    } catch (error) { renderStairLinks(); message(error); }
}
async function calculate() {
    if (!topology || pending) return;
    stop(); setBusy(true); msg.textContent = 'Расчёт маршрутов и очередей...';
    try {
        settings = await api.simulation.saveSettings(bid, sid, collectSettings());
        const result = await api.simulation.calculate(bid, sid);
        showRun(result); await refreshRuns(result.id); msg.textContent = 'Расчёт сохранён. Воспроизведите движение или выберите момент на шкале.';
    } catch (error) { message(error); }
    finally { setBusy(false); }
}
async function refreshRuns(selectedId = '') {
    const runs = await api.simulation.runs(bid, sid); clear(runSelect); addOption(runSelect, '', 'Выберите расчёт');
    runs.forEach(run => addOption(runSelect, run.id, `${new Date(run.createdAt).toLocaleString('ru-RU')} · ${run.evacuatedPeople}/${run.totalPeople} · ${number(run.evacuationTimeSeconds)} с`));
    runSelect.value = selectedId; $<HTMLButtonElement>('#open-run-btn').disabled = !selectedId;
}
function showRun(run: SimulationRun) {
    selectedRun = run; floors = run.floors; stop(); time = 0;
    if (!floors.some(f => String(f.id) === currentFloor)) currentFloor = String(floors[0]?.id || '');
    const result = run.result;
    const duration = Math.max(result.evacuationTimeSeconds, ...result.people.map(p => p.finishSeconds), 0);
    timeline.max = String(duration); timeline.value = '0'; timeline.disabled = false;
    goBtn.disabled = duration === 0; resetBtn.disabled = false;
    const summary = $('#summary'); clear(summary);
    for (const [label, value] of [['Всего людей', result.totalPeople], ['Вышли', result.evacuatedPeople], ['Нет пути', result.unreachablePeople], ['Время, с', number(result.evacuationTimeSeconds)]]) {
        const card = document.createElement('div'); card.append(text('strong', String(value)), text('span', String(label))); summary.append(card);
    }
    $<HTMLElement>('#result-status').textContent = result.unreachablePeople > 0
        ? `Эвакуация не завершена: ${result.unreachablePeople} чел. остаются без пути наружу.`
        : result.totalPeople === 0 ? 'Сценарий пуст: людей не задано.' : 'По расчётной модели все заданные люди достигли выхода.';
    $<HTMLElement>('#run-info').textContent = `Сохранён ${new Date(run.createdAt).toLocaleString('ru-RU')}. Воспроизводится сохранённый план и результат.`;
    const names = new Map(result.nodes.map(node => [node.id, nodeLabel(node)]));
    table($('#comparisons'), ['Стратегия', 'Вышли', 'Нет пути', 'Время, с'], result.strategyComparisons.map(row => [
        `${row.strategy === 'QUEUE_AWARE' ? 'С учётом очередей' : 'Кратчайшие пути'}${row.strategy === result.strategy ? ' · выбрана' : ''}`,
        String(row.evacuatedPeople), String(row.unreachablePeople), number(row.evacuationTimeSeconds),
    ]));
    table($('#routes'), ['Откуда', 'Маршрут', 'Людей', 'Последний вышел, с'], result.routes.map(route => [
        names.get(route.sourceNodeId) || route.sourceNodeId, route.connectionIds.join(' → '), String(route.people), number(route.lastArrivalSeconds),
    ]));
    table($('#bottlenecks'), ['Проход', 'Прошло людей', 'Макс. ожидание, с', 'Занят, с'], result.bottlenecks.map(row => [row.connectionId, String(row.people), number(row.maxWaitSeconds), number(row.busySeconds)]));
    $<HTMLElement>('#result-section').hidden = false;
    warnings([...(run.warnings || []), ...(result.warnings || [])]); renderFloor();
}
function renderFloor() {
    const tabs = $('#floor-tabs'); clear(tabs);
    [...floors].sort((a, b) => a.level - b.level).forEach(floor => {
        const button = text('button', `${floor.name} (${floor.level})`) as HTMLButtonElement;
        button.classList.toggle('on', String(floor.id) === currentFloor);
        button.setAttribute('aria-pressed', String(String(floor.id) === currentFloor));
        button.onclick = () => { currentFloor = String(floor.id); renderFloor(); }; tabs.append(button);
    });
    const container = $('#plan-container'); clear(container); svg = null; dots = null;
    const floor = floors.find(f => String(f.id) === currentFloor);
    if (!floor) { container.append(text('p', 'Добавьте SVG-план этажа в редакторе здания.', 'muted')); return; }
    try { svg = safeSvgElement(floor.svg, floor.vb); } catch (error) { container.append(text('p', error instanceof Error ? error.message : String(error), 'error')); return; }
    svg.classList.add('viewer-svg');
    if (selectedRun) drawRoutes(svg, selectedRun, floor);
    else drawMarks(svg, floor);
    dots = document.createElementNS(ns, 'g'); svg.append(dots); container.append(svg); drawPeople();
}
function circle(parent: Element, point: Position, r: number, fill: string, title: string) {
    const dot = document.createElementNS(ns, 'circle');
    dot.setAttribute('cx', String(point.x)); dot.setAttribute('cy', String(point.y)); dot.setAttribute('r', String(r));
    dot.setAttribute('fill', fill); dot.setAttribute('stroke', '#fff'); dot.setAttribute('stroke-width', String(r / 4));
    const tip = document.createElementNS(ns, 'title'); tip.textContent = title; dot.append(tip); parent.append(dot);
}
function drawMarks(parent: Element, floor: Floor) {
    const radius = Math.max(floor.vb[2], floor.vb[3]) / 90;
    for (const mark of scenario?.marks || []) {
        if (String(mark.floorId) !== currentFloor) continue;
        circle(parent, { floorId: currentFloor, x: mark.x, y: mark.y }, radius,
            mark.kind === 'exit' ? '#108155' : mark.kind === 'stair' ? '#3e67c8' : '#b17c22', `${mark.kind} #${mark.id}`);
    }
}
function drawRoutes(parent: Element, run: SimulationRun, floor: Floor) {
    const unique = new Set<string>();
    for (const person of run.result.people) for (const segment of person.timeline) {
        if (segment.kind !== 'move' || segment.path.length < 2) continue;
        let part: Position[] = [];
        const drawPart = () => {
            if (part.length < 2) return;
            const points = part.map(p => `${p.x},${p.y}`).join(' '); if (unique.has(points)) return; unique.add(points);
            const line = document.createElementNS(ns, 'polyline'); line.setAttribute('points', points); line.setAttribute('fill', 'none');
            line.setAttribute('stroke', '#477c9e'); line.setAttribute('stroke-opacity', '.5'); line.setAttribute('stroke-width', String(Math.max(floor.vb[2], floor.vb[3]) / 350)); parent.append(line);
        };
        for (const point of segment.path) { if (point.floorId === currentFloor) part.push(point); else { drawPart(); part = []; } } drawPart();
    }
}
function drawPeople() {
    clock.textContent = `${number(time)} с`; timeline.value = String(time);
    if (!selectedRun || !dots) return;
    clear(dots);
    const floor = floors.find(f => String(f.id) === currentFloor); if (!floor) return;
    const nodes = new Map(selectedRun.result.nodes.map(node => [node.id, node]));
    const radius = Math.max(floor.vb[2], floor.vb[3]) / 180;
    for (const person of selectedRun.result.people) {
        const source = nodes.get(person.sourceNodeId) || null;
        const point = personAt(person, time, source);
        if (!point.position || point.position.floorId !== currentFloor) continue;
        circle(dots, point.position, radius, point.state === 'unreachable' ? '#bc5420' : point.state === 'wait' || point.state === 'passage' ? '#c89a19' : '#203744', `Человек ${person.id} · ${point.state === 'unreachable' ? 'нет пути' : point.state === 'wait' ? 'ожидание' : 'движение'}`);
    }
    const counts = playbackCounts(selectedRun.result.people, time);
    $('#playback-stats').textContent = `Вышли: ${counts.out}. В здании: ${counts.inside}, из них без пути: ${counts.unreachable}.`;
}
function frame(timestamp: number) {
    if (running && selectedRun) {
        const delta = Math.min((timestamp - lastFrame) / 1000, .25) * Number($<HTMLSelectElement>('#speed-select').value);
        time = Math.min(Number(timeline.max), time + delta); drawPeople();
        if (time >= Number(timeline.max)) stop();
    }
    lastFrame = timestamp; requestAnimationFrame(frame);
}
$('#load-btn').addEventListener('click', loadScenario);
buildSelect.addEventListener('change', () => loadScenarios().catch(message));
scenarioSelect.addEventListener('change', loadScenario);
calculateBtn.addEventListener('click', calculate);
$('#reset-settings-btn').addEventListener('click', async () => {
    if (pending || !bid || !sid) return;
    stop(); setBusy(true);
    let saved = false;
    try {
        settings = await api.simulation.saveSettings(bid, sid, { occupancy: {}, blockedConnections: [], stairLinks: [] });
        saved = true;
    } catch (error) { message(error); }
    finally { setBusy(false); }
    if (saved) {
        await loadScenario();
        if (topology) msg.textContent = 'Заполненность, блокировки и связи лестниц сброшены. Сохранённые результаты доступны в истории.';
    }
});
$('#fill-capacity').addEventListener('click', () => {
    for (const node of topology?.nodes || []) {
        if (node.kind !== 'room') continue;
        const input = occupancyInputs.get(node.id);
        if (input) input.value = input.max;
    }
    msg.textContent = 'Помещения заполнены по вместимости. Число людей в коридорах сохранено. Выполните расчёт.';
});
$('#clear-occupancy').addEventListener('click', () => { for (const input of occupancyInputs.values()) input.value = '0'; msg.textContent = 'Заполненность обнулена. Выполните расчёт.'; });
$('#add-stair-link').addEventListener('click', async () => {
    const from = Number($<HTMLSelectElement>('#stair-from').value), to = Number($<HTMLSelectElement>('#stair-to').value);
    const a = scenario?.marks.find(mark => mark.id === from), b = scenario?.marks.find(mark => mark.id === to);
    if (!a || !b || a.floorId === b.floorId) { msg.textContent = 'Выберите лестницы на разных этажах.'; return; }
    if (settings.stairLinks.some(link => link.fromMarkId === from && link.toMarkId === to)) return;
    settings.stairLinks.push({ fromMarkId: from, toMarkId: to }); await saveStairLinks();
});
runSelect.addEventListener('change', () => { $<HTMLButtonElement>('#open-run-btn').disabled = !runSelect.value; });
$('#open-run-btn').addEventListener('click', async () => {
    try { showRun(await api.simulation.run(bid, sid, runSelect.value)); msg.textContent = 'Открыт сохранённый расчёт.'; } catch (error) { message(error); }
});
goBtn.addEventListener('click', () => { if (!selectedRun) return; if (running) stop(); else { if (time >= Number(timeline.max)) time = 0; running = true; lastFrame = performance.now(); goBtn.textContent = 'Пауза'; } });
resetBtn.addEventListener('click', () => { stop(); time = 0; drawPeople(); });
timeline.addEventListener('input', () => {
    stop();
    const selected = Number(timeline.value), end = Number(timeline.max);
    // Range controls may serialize a double with fewer digits than the event log.
    time = Math.abs(end - selected) <= Math.max(1, end) * 1e-12 ? end : selected;
    drawPeople();
});
if (await Auth.requireAuth()) { Auth.renderHeader('Расчёт эвакуации'); await loadBuildings().catch(message); }
requestAnimationFrame(frame);
