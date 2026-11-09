import { Auth } from '../auth';
import { api } from '../api';
import { $, safeSvgElement } from '../shared';
import type { Building, ScenarioFull, Floor, Mark, Params } from '../types';

const query = new URLSearchParams(location.search);
const BID = Number(query.get('bid'));
const SID = Number(query.get('sid'));
const NS = 'http://www.w3.org/2000/svg';
let building: Building | null = null;
let scenario: ScenarioFull | null = null;
let currentFloorId: number | null = null;
let currentTool: 'stair' | 'exit' | 'spawn' | 'del' = 'stair';
let pendingMarks: Mark[] = [];
let persistedMarks = new Map<number, Mark>();
let nextTemporaryId = -1;
let saving = false;
const floorSelect = $<HTMLSelectElement>('#floor-select');
const planContainer = $<HTMLDivElement>('#plan-container');
const msg = $<HTMLParagraphElement>('#msg');
const saveButton = $<HTMLButtonElement>('#save-btn');

async function load() {
    [building, scenario] = await Promise.all([api.buildings.get(BID), api.scenarios.get(BID, SID)]);
    pendingMarks = scenario.marks.map(mark => ({ ...mark }));
    persistedMarks = new Map(scenario.marks.map(mark => [mark.id, { ...mark }]));
    $('#s-title').textContent = scenario.name;
    $('#s-building').textContent = `Здание: ${building.name}`;
    $<HTMLAnchorElement>('#back-link').href = `/building.html?id=${BID}`;
    $<HTMLAnchorElement>('#viewer-link').href = `/viewer.html?bid=${BID}&sid=${SID}`;
    floorSelect.replaceChildren();
    const floors = [...building.floors].sort((a, b) => a.level - b.level);
    for (const floor of floors) {
        const option = document.createElement('option');
        option.value = String(floor.id);
        option.textContent = `${floor.name} · уровень ${floor.level}`;
        floorSelect.append(option);
    }
    if (!floors.some(floor => floor.id === currentFloorId)) currentFloorId = floors[0]?.id ?? null;
    floorSelect.value = String(currentFloorId ?? '');
    fillParams(scenario.params);
    refreshFireState();
    drawPlan();
}

function refreshFireState() {
    const section = $<HTMLElement>('#stored-fire');
    section.hidden = !scenario?.fire;
    $('#fire-status').textContent = scenario?.fire
        ? 'В сценарии сохранён очаг. Для базового расчёта эвакуации его нужно убрать: модель огня ещё не подключена.' : '';
}
$('#clear-fire-btn').addEventListener('click', () => {
    if (!scenario || saving) return;
    scenario.fire = null;
    refreshFireState();
    msg.textContent = 'Очаг убран. Сохраните сценарий.';
    drawPlan();
});
floorSelect.onchange = () => { currentFloorId = Number(floorSelect.value); drawPlan(); };

function drawPlan() {
    planContainer.replaceChildren();
    const floor = building?.floors.find(item => item.id === currentFloorId);
    if (!floor) { planContainer.textContent = 'Добавьте хотя бы один этаж в редакторе здания.'; return; }
    const svg = safeSvgElement(floor.svg, floor.vb);
    svg.classList.add('plan-svg');
    svg.style.width = '100%';
    svg.style.height = 'auto';
    svg.style.maxHeight = '75vh';
    svg.setAttribute('aria-label', `План: ${floor.name}`);
    const marksLayer = document.createElementNS(NS, 'g');
    marksLayer.setAttribute('class', 'marker');
    const radius = Math.max(floor.vb[2], floor.vb[3]) / 65;
    const add = (x: number, y: number, label: string, color: string) => {
        const dot = document.createElementNS(NS, 'circle');
        dot.setAttribute('cx', String(x)); dot.setAttribute('cy', String(y));
        dot.setAttribute('r', String(radius)); dot.setAttribute('fill', color);
        dot.setAttribute('stroke', '#fff'); dot.setAttribute('stroke-width', String(radius / 5));
        const text = document.createElementNS(NS, 'text');
        text.setAttribute('x', String(x)); text.setAttribute('y', String(y - radius * 1.4));
        text.setAttribute('font-size', String(radius * 1.1)); text.setAttribute('text-anchor', 'middle');
        text.setAttribute('fill', color); text.setAttribute('stroke', '#fff');
        text.setAttribute('stroke-width', String(radius / 10)); text.setAttribute('paint-order', 'stroke');
        text.textContent = label;
        marksLayer.append(dot, text);
    };
    for (const mark of pendingMarks.filter(item => item.floorId === floor.id)) {
        const label = mark.kind === 'spawn' ? 'Люди' : `${mark.kind === 'exit' ? 'Выход' : 'Лестница'} ${mark.width} м`;
        const suffix = mark.id > 0 ? ` №${mark.id}` : ' (новая)';
        add(mark.x, mark.y, label + suffix, { spawn: '#b5730b', exit: '#0e8f5b', stair: '#2d5fd0' }[mark.kind]);
    }
    if (scenario?.fire?.floorId === floor.id) add(scenario.fire.x, scenario.fire.y, 'Сохранённый очаг', '#d6361f');
    svg.append(marksLayer);
    svg.addEventListener('click', event => placeMark(event, svg, floor));
    planContainer.append(svg);
    const annotated = !!svg.querySelector('[data-type="room"], [data-type="corridor"]');
    $('#plan-mode').textContent = annotated
        ? 'В этом SVG уже размечены помещения и проходы. Число людей по помещениям и закрытые проходы задайте на экране расчёта; масштаб берётся из data-scale (метры на единицу SVG).'
        : 'Для обычного SVG поставьте метки людей, выходов и лестниц. Межэтажные связи выберите на экране расчёта. Масштаб ниже задаётся в единицах SVG на метр.';
    renderMarkList(floor);
}

function placeMark(event: MouseEvent, svg: SVGSVGElement, floor: Floor) {
    if (saving || !scenario) return;
    const matrix = svg.getScreenCTM();
    if (!matrix) return;
    const point = svg.createSVGPoint(); point.x = event.clientX; point.y = event.clientY;
    const position = point.matrixTransform(matrix.inverse());
    if (!Number.isFinite(position.x) || !Number.isFinite(position.y)) return;
    if (currentTool === 'del') {
        const radius = Math.max(floor.vb[2], floor.vb[3]) / 30;
        const closest = pendingMarks.filter(mark => mark.floorId === floor.id)
            .map(mark => ({ mark, distance: Math.hypot(mark.x - position.x, mark.y - position.y) }))
            .filter(item => item.distance <= radius).sort((a, b) => a.distance - b.distance)[0];
        if (closest) pendingMarks = pendingMarks.filter(mark => mark.id !== closest.mark.id);
    } else {
        const widthText = $<HTMLInputElement>('#tool-width').value;
        const width = currentTool === 'spawn' ? null : Number(widthText);
        if (width !== null && (!widthText.trim() || !Number.isFinite(width) || width <= 0)) {
            msg.textContent = 'Ширина прохода должна быть положительным числом.'; return;
        }
        pendingMarks.push({ id: nextTemporaryId--, floorId: floor.id, kind: currentTool, x: position.x, y: position.y, width });
    }
    msg.textContent = 'Есть несохранённые изменения.';
    drawPlan();
}

function renderMarkList(floor: Floor) {
    const list = $('#mark-list'); list.replaceChildren();
    const marks = pendingMarks.filter(mark => mark.floorId === floor.id);
    if (!marks.length) { list.textContent = 'На этом этаже меток пока нет.'; return; }
    for (const mark of marks) {
        const row = document.createElement('div'); row.className = 'mark-row';
        const label = document.createElement('span');
        label.textContent = `${{ spawn: 'Люди', stair: 'Лестница', exit: 'Выход' }[mark.kind]} ${mark.id > 0 ? `№${mark.id}` : '(новая)'} · ${mark.x.toFixed(1)}, ${mark.y.toFixed(1)}`;
        const remove = document.createElement('button'); remove.type = 'button';
        remove.textContent = 'Удалить'; remove.disabled = saving;
        remove.onclick = () => {
            pendingMarks = pendingMarks.filter(item => item.id !== mark.id);
            msg.textContent = 'Есть несохранённые изменения.'; drawPlan();
        };
        row.append(label, remove); list.append(row);
    }
}

document.querySelectorAll<HTMLButtonElement>('.tool-btn').forEach(button => {
    button.onclick = () => {
        if (saving) return;
        document.querySelectorAll('.tool-btn').forEach(item => item.classList.remove('on'));
        button.classList.add('on');
        currentTool = button.dataset.tool as typeof currentTool;
        $<HTMLElement>('#wrap-w').style.display = currentTool === 'spawn' || currentTool === 'del' ? 'none' : 'block';
    };
});

function fillParams(params: Params) {
    for (const field of ['people', 'speed', 'scale', 'stairTime', 'exitFlow', 'stairFlow'] as const) {
        $<HTMLInputElement>(`#p-${field}`).value = String(params[field]);
    }
}
function readParams(): Params {
    const number = (field: string, label: string, allowZero = false) => {
        const input = $<HTMLInputElement>(`#p-${field}`);
        const value = input.value.trim() ? Number(input.value) : NaN;
        if (!Number.isFinite(value) || (allowZero ? value < 0 : value <= 0)) throw new Error(`${label}: введите ${allowZero ? 'неотрицательное' : 'положительное'} число.`);
        return value;
    };
    const people = number('people', 'Количество людей', true);
    if (!Number.isInteger(people) || people > 10_000) throw new Error('Количество людей должно быть целым числом от 0 до 10 000.');
    return {
        people, speed: number('speed', 'Скорость'), scale: number('scale', 'Масштаб'),
        stairTime: number('stairTime', 'Время лестницы'), exitFlow: number('exitFlow', 'Пропускная способность выхода'),
        stairFlow: number('stairFlow', 'Пропускная способность лестницы'), fireRate: scenario?.params.fireRate ?? 0.4
    };
}

saveButton.onclick = async () => {
    if (!scenario || saving) return;
    saving = true; saveButton.disabled = true;
    msg.textContent = 'Сохранение…';
    try {
        const params = readParams();
        await api.scenarios.update(BID, SID, { name: scenario.name, params, fire: scenario.fire });
        scenario.params = params;
        const desiredIds = new Set(pendingMarks.filter(mark => mark.id > 0).map(mark => mark.id));
        for (const id of [...persistedMarks.keys()]) {
            if (!desiredIds.has(id)) { await api.marks.delete(BID, SID, id); persistedMarks.delete(id); }
        }
        for (let index = 0; index < pendingMarks.length; index++) {
            const mark = pendingMarks[index];
            const { id, ...data } = mark;
            if (id <= 0) {
                const created = await api.marks.create(BID, SID, data);
                pendingMarks[index] = created;
                persistedMarks.set(created.id, { ...created });
            } else if (JSON.stringify(persistedMarks.get(id)) !== JSON.stringify(mark)) {
                const updated = await api.marks.update(BID, SID, id, data);
                pendingMarks[index] = updated;
                persistedMarks.set(id, { ...updated });
            }
        }
        await load();
        msg.textContent = 'Сценарий сохранён. Можно перейти к расчёту.';
    } catch (error: any) { msg.textContent = `Ошибка: ${error.message}`; }
    finally { saving = false; saveButton.disabled = false; if (building) drawPlan(); }
};

void Auth.requireAuth().then(async ok => {
    if (!ok) return;
    if (!Number.isSafeInteger(BID) || BID <= 0 || !Number.isSafeInteger(SID) || SID <= 0) { location.href = '/buildings.html'; return; }
    Auth.renderHeader('Настройка сценария');
    try { await load(); }
    catch (error: any) { msg.textContent = error.message; saveButton.disabled = true; }
});
