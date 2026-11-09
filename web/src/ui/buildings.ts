import { Auth } from '../auth';
import { api } from '../api';
import { $ } from '../shared';
import type { BuildingSummary } from '../types';

const listEl = $<HTMLDivElement>('#list');
const msgEl = $<HTMLParagraphElement>('#msg');
const nameInput = $<HTMLInputElement>('#b-name');
const descInput = $<HTMLInputElement>('#b-desc');
const createButton = $<HTMLButtonElement>('#create-btn');

async function loadBuildings() {
    try { renderList(await api.buildings.list()); }
    catch (error: any) { listEl.textContent = error.message; }
}

function renderList(buildings: BuildingSummary[]) {
    listEl.replaceChildren();
    if (!buildings.length) {
        listEl.textContent = 'У вас пока нет зданий. Создайте первое.';
        return;
    }
    for (const building of buildings) {
        const card = document.createElement('div');
        card.className = 'card';
        const info = document.createElement('div');
        const title = document.createElement('b');
        title.textContent = building.name;
        const description = document.createElement('p');
        description.className = 'muted';
        description.textContent = building.description || 'Без описания';
        const count = document.createElement('small');
        count.textContent = `Этажей: ${building.floorCount} · Сценариев: ${building.scenarioCount}`;
        info.append(title, description, count);
        const actions = document.createElement('div');
        actions.className = 'actions';
        const open = document.createElement('a');
        open.className = 'btn';
        open.href = `/building.html?id=${building.id}`;
        open.textContent = 'Открыть';
        const remove = document.createElement('button');
        remove.className = 'del';
        remove.textContent = 'Удалить';
        remove.onclick = async () => {
            if (!confirm(`Удалить здание «${building.name}»?`)) return;
            remove.disabled = true;
            try { await api.buildings.delete(building.id); await loadBuildings(); }
            catch (error: any) { msgEl.textContent = error.message; remove.disabled = false; }
        };
        actions.append(open, remove);
        card.append(info, actions);
        listEl.append(card);
    }
}

createButton.onclick = async () => {
    const name = nameInput.value.trim();
    if (!name) { msgEl.textContent = 'Введите название.'; return; }
    createButton.disabled = true;
    msgEl.textContent = 'Создаю…';
    try {
        await api.buildings.create(name, descInput.value.trim());
        nameInput.value = '';
        descInput.value = '';
        msgEl.textContent = 'Здание создано.';
        await loadBuildings();
    } catch (error: any) { msgEl.textContent = error.message; }
    finally { createButton.disabled = false; }
};

void Auth.requireAuth().then(async ok => {
    if (!ok) return;
    Auth.renderHeader('Мои здания');
    await loadBuildings();
});
