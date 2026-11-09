import { Auth } from '../auth';
import { api } from '../api';
import { $, cleanSvg } from '../shared';
import type { Building } from '../types';

const BID = Number(new URLSearchParams(location.search).get('id'));
let building: Building | null = null;
const message = $<HTMLParagraphElement>('#f-msg');

function switchTab(tab: 'floors' | 'scenarios') {
    for (const [name, selectors] of [
        ['floors', ['#view-floors', '#form-floor']],
        ['scenarios', ['#view-scens', '#form-scen']]
    ] as const) {
        for (const selector of selectors) $<HTMLElement>(selector).style.display = tab === name ? 'block' : 'none';
    }
    $('#tab-floors').classList.toggle('on', tab === 'floors');
    $('#tab-scens').classList.toggle('on', tab === 'scenarios');
}
$('#tab-floors').addEventListener('click', () => switchTab('floors'));
$('#tab-scens').addEventListener('click', () => switchTab('scenarios'));

function actionButton(text: string, callback: () => Promise<void>) {
    const button = document.createElement('button');
    button.textContent = text;
    button.onclick = async () => {
        button.disabled = true;
        try { await callback(); }
        catch (error: any) { message.textContent = error.message; }
        finally { button.disabled = false; }
    };
    return button;
}
function link(text: string, href: string) {
    const element = document.createElement('a');
    element.className = 'btn';
    element.textContent = text;
    element.href = href;
    return element;
}
function card(name: string) {
    const item = document.createElement('div');
    item.className = 'card';
    const title = document.createElement('b');
    title.textContent = name;
    const actions = document.createElement('div');
    actions.className = 'actions';
    item.append(title, actions);
    return { item, actions };
}

async function loadBuilding() {
    building = await api.buildings.get(BID);
    $('#b-title').textContent = building.name;
    $('#b-desc').textContent = building.description || 'Без описания';
    const floorsList = $('#floors-list');
    const scenariosList = $('#scens-list');
    floorsList.replaceChildren();
    scenariosList.replaceChildren();
    if (!building.floors.length) floorsList.textContent = 'Этажей пока нет. Добавьте первый.';
    if (!building.scenarios.length) scenariosList.textContent = 'Сценариев пока нет. Создайте первый.';
    for (const floor of [...building.floors].sort((a, b) => a.level - b.level)) {
        const { item, actions } = card(`${floor.name} · уровень ${floor.level}`);
        actions.append(actionButton('Удалить', async () => {
            if (!confirm('Удалить этаж и его метки?')) return;
            await api.floors.delete(BID, floor.id);
            await loadBuilding();
        }));
        floorsList.append(item);
    }
    for (const scenario of building.scenarios) {
        const { item, actions } = card(`${scenario.name}${scenario.isDefault ? ' · по умолчанию' : ''}`);
        actions.append(link('Настроить', `/scenario.html?bid=${BID}&sid=${scenario.id}`));
        actions.append(link('Рассчитать', `/viewer.html?bid=${BID}&sid=${scenario.id}`));
        if (!scenario.isDefault) actions.append(actionButton('Сделать главным', async () => {
            await api.scenarios.setDefault(BID, scenario.id);
            await loadBuilding();
        }));
        actions.append(actionButton('Удалить', async () => {
            if (!confirm('Удалить сценарий?')) return;
            await api.scenarios.delete(BID, scenario.id);
            await loadBuilding();
        }));
        scenariosList.append(item);
    }
}

$<HTMLButtonElement>('#f-create').onclick = async () => {
    const button = $<HTMLButtonElement>('#f-create');
    const name = $<HTMLInputElement>('#f-name').value.trim();
    const levelText = $<HTMLInputElement>('#f-level').value;
    const level = levelText.trim() ? Number(levelText) : NaN;
    const input = $<HTMLInputElement>('#f-file');
    if (!name || !Number.isInteger(level) || !input.files?.length) {
        message.textContent = 'Введите название, целый номер этажа и выберите SVG.';
        return;
    }
    button.disabled = true;
    message.textContent = 'Обработка…';
    try {
        const cleaned = cleanSvg(await input.files[0].text());
        await api.floors.create(BID, { name, level, svg: cleaned.svg, vb: cleaned.vb });
        message.textContent = 'Этаж добавлен.';
        $<HTMLInputElement>('#f-name').value = '';
        $<HTMLInputElement>('#f-level').value = String(level + 1);
        input.value = '';
        await loadBuilding();
    } catch (error: any) { message.textContent = error.message; }
    finally { button.disabled = false; }
};
$<HTMLButtonElement>('#s-create').onclick = async () => {
    const button = $<HTMLButtonElement>('#s-create');
    const name = $<HTMLInputElement>('#s-name').value.trim();
    const msg = $('#s-msg');
    if (!name) { msg.textContent = 'Введите название.'; return; }
    button.disabled = true;
    try {
        await api.scenarios.create(BID, name);
        $<HTMLInputElement>('#s-name').value = '';
        msg.textContent = 'Сценарий создан.';
        await loadBuilding();
    } catch (error: any) { msg.textContent = error.message; }
    finally { button.disabled = false; }
};

switchTab('floors');
void Auth.requireAuth().then(async ok => {
    if (!ok) return;
    if (!Number.isSafeInteger(BID) || BID <= 0) { location.href = '/buildings.html'; return; }
    Auth.renderHeader('Редактор здания');
    try { await loadBuilding(); }
    catch (error: any) { message.textContent = error.message; }
});
