const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('../web/node_modules/playwright');
const base = process.env.EVAC_URL || 'http://127.0.0.1:8080';
const evidence = path.resolve(__dirname, '../test-results');
fs.mkdirSync(evidence, { recursive: true });
async function main() {
  for (let attempt = 0; ; attempt++) {
    try { if ((await fetch(base + '/api/health')).ok) break; } catch {}
    if (attempt >= 60) throw new Error('Packaged server did not become ready');
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_PATH || undefined, args: ['--no-sandbox'] });
  const context = await browser.newContext({ baseURL: base, viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  try {
    const username = 'smoke_' + Date.now().toString(36);
    await page.goto('/login.html');
    await page.locator('#to-register').click();
    await page.locator('#username').fill(username);
    await page.locator('#password').fill('browser-test-password');
    await page.locator('#login-btn').click();
    await page.waitForURL('**/buildings.html');
    await page.locator('#b-name').fill('Единый проект — проверка');
    const created = page.waitForResponse(r => r.url().endsWith('/api/buildings') && r.request().method() === 'POST');
    await page.locator('#create-btn').click();
    const bid = (await (await created).json()).id;
    assert.ok(bid);
    async function api(method, url, data) {
      const response = await context.request.fetch(url, { method, data });
      const body = await response.text();
      assert.ok(response.ok(), method + ' ' + url + ': ' + response.status() + ' ' + body);
      return body ? JSON.parse(body) : null;
    }
    const office = fs.readFileSync(path.resolve(__dirname, '../evac_sim/src/main/resources/examples/example_office.svg'), 'utf8');
    const floor = await api('POST', '/api/buildings/' + bid + '/floors', { name: 'Офис', level: 1, svg: office, vb: [0, 0, 1200, 800] });
    const scenario = await api('POST', '/api/buildings/' + bid + '/scenarios', { name: 'Базовая эвакуация' });
    const scene = '/api/buildings/' + bid + '/scenarios/' + scenario.id;
    await page.goto('/viewer.html?bid=' + bid + '&sid=' + scenario.id);
    await page.locator('#calculate-btn:not([disabled])').waitFor();
    await page.locator('#fill-capacity').click();
    const response = page.waitForResponse(r => r.url().endsWith('/simulate') && r.request().method() === 'POST');
    await page.locator('#calculate-btn').click();
    const run = await (await response).json();
    assert.equal(run.result.totalPeople, 66);
    assert.equal(run.result.evacuatedPeople, 66);
    assert.equal(run.result.unreachablePeople, 0);
    assert.equal(run.result.people.length, 66);
    await page.locator('#result-section:not([hidden])').waitFor();
    assert.match(await page.locator('#result-status').innerText(), /все заданные люди/);
    await page.locator('#timeline').evaluate(el => { el.value = el.max; el.dispatchEvent(new Event('input', { bubbles: true })); });
    assert.match(await page.locator('#playback-stats').innerText(), /66/);
    await page.locator('#reset-btn').click();
    assert.equal(await page.locator('#timeline').inputValue(), '0');
    await page.screenshot({ path: path.join(evidence, 'unified-semantic-desktop.png'), fullPage: true });
    await page.reload();
    await page.locator('#calculate-btn:not([disabled])').waitFor();
    await page.locator('#run-select').selectOption(run.id);
    await page.locator('#open-run-btn').click();
    await page.locator('#result-section:not([hidden])').waitFor();
    assert.match(await page.locator('#summary').innerText(), /66/);
    const topology = await api('GET', scene + '/topology');
    for (const exit of topology.passages.filter(p => p.kind === 'exit')) {
      await page.locator('#blocked-list input').evaluateAll((inputs, id) => {
        const input = inputs.find(i => i.dataset.connectionId === id); input.checked = true;
      }, exit.id);
    }
    const blockedResponse = page.waitForResponse(r => r.url().endsWith('/simulate') && r.request().method() === 'POST');
    await page.locator('#calculate-btn').click();
    const blocked = await (await blockedResponse).json();
    assert.equal(blocked.result.unreachablePeople, 66);
    assert.equal(blocked.result.evacuatedPeople, 0);
    await page.waitForFunction(() => document.querySelector('#result-status').textContent.includes('не завершена'));
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(evidence, 'unified-mobile.png'), fullPage: true });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false, 'mobile horizontal overflow');
    await page.setViewportSize({ width: 1440, height: 1000 });
    const originalRoomId = topology.nodes.find(n => n.kind === 'room').id.split('/').slice(1).join('/');
    await api('PUT', '/api/buildings/' + bid + '/floors/' + floor.id, { name: 'Изменённый офис', level: 1,
      svg: office.replaceAll(originalRoomId, originalRoomId + '_renamed'), vb: [0, 0, 1200, 800] });
    await page.reload();
    await page.waitForFunction(() => document.querySelector('#msg').textContent.includes('отсутствует в плане'));
    await page.locator('#reset-settings-btn').click();
    await page.locator('#calculate-btn:not([disabled])').waitFor();
    assert.ok((await page.locator('#occupancy-list input').evaluateAll(inputs => inputs.map(input => Number(input.value)))).every(value => value === 0));
    assert.ok(await page.locator('#run-select option').count() >= 3);
    const rasterBuilding = await api('POST', '/api/buildings', { name: 'Два этажа с метками' });
    const rb = '/api/buildings/' + rasterBuilding.id;
    const rawSvg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 120" onload="window.svgExecuted=true"><rect width="200" height="120" fill="white"/><text x="12" y="108" font-size="10">Floor plan</text></svg>';
    const lower = await api('POST', rb + '/floors', { name: 'Первый этаж', level: 1, svg: rawSvg, vb: [0, 0, 200, 120] });
    const upper = await api('POST', rb + '/floors', { name: 'Второй этаж', level: 2, svg: rawSvg, vb: [0, 0, 200, 120] });
    const rs = await api('POST', rb + '/scenarios', { name: 'По лестнице', params: { people: 8, speed: 1.3, scale: 20, stairTime: 4, exitFlow: 1, stairFlow: 1 } });
    const rc = rb + '/scenarios/' + rs.id;
    const stairLow = await api('POST', rc + '/marks', { floorId: lower.id, kind: 'stair', x: 44, y: 44, width: 1 });
    const stairHigh = await api('POST', rc + '/marks', { floorId: upper.id, kind: 'stair', x: 124, y: 44, width: 1 });
    await api('POST', rc + '/marks', { floorId: lower.id, kind: 'exit', x: 164, y: 44, width: 1 });
    await api('POST', rc + '/marks', { floorId: upper.id, kind: 'spawn', x: 44, y: 44 });
    await api('PUT', rc + '/simulation-settings', { occupancy: {}, blockedConnections: [], stairLinks: [{ fromMarkId: stairHigh.id, toMarkId: stairLow.id }] });
    await page.goto('/viewer.html?bid=' + rasterBuilding.id + '&sid=' + rs.id);
    await page.locator('#calculate-btn:not([disabled])').waitFor();
    const rasterResponse = page.waitForResponse(r => r.url().endsWith('/simulate') && r.request().method() === 'POST');
    await page.locator('#calculate-btn').click();
    const raster = await (await rasterResponse).json();
    assert.equal(raster.mode, 'raster');
    assert.equal(raster.result.evacuatedPeople, 8);
    assert.equal(raster.result.people.length, 8);
    assert.ok(raster.result.people.every(person => person.timeline.some(s => s.kind === 'stairs')));
    await page.locator('#result-section:not([hidden])').waitFor();
    await page.locator('#floor-tabs button').last().click();
    await page.locator('#go-btn').click();
    await page.waitForTimeout(250);
    await page.locator('#go-btn').click();
    assert.equal(await page.evaluate(() => window.svgExecuted === true), false);
    await page.screenshot({ path: path.join(evidence, 'unified-two-floors.png'), fullPage: true });
    const other = await browser.newContext({ baseURL: base });
    assert.equal((await other.request.get(scene + '/runs')).status(), 401);
    await other.close();
    assert.deepEqual(errors, []);
    const results = { scenarios: ['registration and building UI', 'semantic Java calculation 66 people', 'saved run reload and seek/reset', 'blocked exits conserve 66 unreachable', 'mobile390', 'reset stale settings after plan edit', 'raster explicit two-floor Java playback', 'safe SVG and unauthenticated access'], semanticTime: run.result.evacuationTimeSeconds, rasterTime: raster.result.evacuationTimeSeconds, browserErrors: errors };
    fs.writeFileSync(path.join(evidence, 'browser-smoke.json'), JSON.stringify(results, null, 2));
    console.log(JSON.stringify(results));
  } catch (error) {
    await page.screenshot({ path: path.join(evidence, 'browser-failure.png'), fullPage: true }).catch(() => {});
    throw error;
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
