import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import assert from 'node:assert/strict';
import { chromium } from 'playwright';

// UI contract smoke with fixed server results; the repository browser smoke covers the real Java API.
const floor = { id: 1, name: 'Этаж <img src=x onerror=alert(1)>', level: 1, svg: '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 100 40" transform="translate(5 0)" onload="window.__bad=true"><rect width="100" height="40" fill="white"/><rect id="reference-room" x="10" y="5" width="10" height="10" fill="#eee"/><defs><rect id="wall" x="60" y="5" width="2" height="20"/></defs><use id="wall-instance" href="#wall"/><use id="wall-instance-xlink" xlink:href="#wall" x="5"/></svg>', vb: [0, 0, 100, 40] };
const scenario = { id: 1, name: 'Тестовый сценарий', isDefault: true, params: { people: 2, speed: 1, fireRate: .4, scale: 1, stairTime: 12, exitFlow: 1, stairFlow: 1 }, fire: null, marks: [] };
const building = { id: 1, name: 'Тестовое здание', description: '', floors: [floor], scenarios: [scenario] };
const node = { id: '1/R1', floorId: '1', kind: 'room', name: 'Комната', capacity: 2, x: 20, y: 10 };
const corridor = { id: '1/C1', floorId: '1', kind: 'corridor', name: 'Коридор', capacity: 200, x: 45, y: 25 };
const passage = { id: '1/E1', kind: 'exit', from: '1/R1', to: 'OUTSIDE', throughput: 1, directed: true, beforeSeconds: 8, traversalSeconds: 0, afterSeconds: 0, beforePath: [{ floorId: '1', x: 20, y: 10 }, { floorId: '1', x: 90, y: 10 }], afterPath: [] };
let settings = { occupancy: { '1/R1': 0 }, blockedConnections: [], stairLinks: [] };
const result = { totalPeople: 2, evacuatedPeople: 2, unreachablePeople: 0, evacuationTimeSeconds: 10, strategy: 'QUEUE_AWARE', strategyComparisons: [{ strategy: 'QUEUE_AWARE', evacuatedPeople: 2, unreachablePeople: 0, evacuationTimeSeconds: 10 }], nodes: [node, corridor], passages: [passage], routes: [{ sourceNodeId: node.id, nodeIds: [node.id, 'OUTSIDE'], connectionIds: [passage.id], people: 2, firstArrivalSeconds: 9, lastArrivalSeconds: 10 }], bottlenecks: [{ connectionId: passage.id, people: 2, maxWaitSeconds: 1, busySeconds: 2 }], people: [1, 2].map(id => ({ id, sourceNodeId: node.id, status: 'evacuated', finishSeconds: 8 + id, nodeIds: [node.id, 'OUTSIDE'], connectionIds: [passage.id], timeline: [{ kind: 'move', startSeconds: 0, endSeconds: 8, path: passage.beforePath, connectionId: passage.id }, { kind: 'wait', startSeconds: 8, endSeconds: 8 + id, path: [passage.beforePath[1]], connectionId: passage.id }] })), warnings: ['Глобальная оптимальность не гарантирована.'] };
const run = { id: 'r1', createdAt: '2026-11-09T12:00:00Z', mode: 'semantic', result, floors: [floor], warnings: [], engineVersion: '0.3.0', inputSha256: 'x' };
const server = http.createServer(async (req, res) => {
    try {
        const pathname = new URL(req.url, 'http://localhost').pathname;
        const resource = path.join(process.cwd(), 'dist', pathname === '/' ? 'index.html' : pathname);
        const bytes = await fs.readFile(resource);
        res.setHeader('Content-Type', resource.endsWith('.js') ? 'application/javascript' : resource.endsWith('.css') ? 'text/css' : 'text/html');
        res.end(bytes);
    } catch { res.writeHead(404); res.end(); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
try {
    const page = await browser.newPage(); const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/api/**', async route => {
        const req = route.request(); const u = new URL(req.url()).pathname; let data;
        if (u === '/api/auth/me') data = { id: 1, username: 'test' };
        else if (u === '/api/buildings') data = [building];
        else if (u === '/api/buildings/1') data = building;
        else if (u.endsWith('/simulation-settings')) { if (req.method() === 'PUT') settings = req.postDataJSON(); data = settings; }
        else if (u.endsWith('/topology')) data = { mode: 'semantic', nodes: [node, corridor], passages: [passage], occupancy: { '1/R1': 0 }, warnings: [] };
        else if (u.endsWith('/runs/r1') || u.endsWith('/simulate')) data = run;
        else if (u.endsWith('/runs')) data = [{ ...result, id: 'r1', createdAt: run.createdAt }];
        else if (u.endsWith('/scenarios/1')) data = scenario;
        else { await route.fulfill({ status: 404, body: '{}' }); return; }
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) });
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/viewer.html?bid=1&sid=1`);
    await page.waitForFunction(() => !document.querySelector('#calculate-btn').disabled);
    assert.equal(await page.evaluate(() => window.__bad), undefined);
    await page.locator('[data-node-id="1/C1"]').fill('3');
    await page.locator('#fill-capacity').click();
    assert.equal(await page.locator('[data-node-id="1/C1"]').inputValue(), '3');
    await page.locator('#calculate-btn').click();
    await page.locator('#result-section').waitFor({ state: 'visible' });
    assert.equal(settings.occupancy['1/R1'], 2);
    assert.equal(settings.occupancy['1/C1'], 3);
    const geometry = await page.locator('#plan-container svg').evaluate(svg => {
        const reference = svg.querySelector('#reference-room').getBoundingClientRect();
        const person = svg.lastElementChild.querySelector('circle').getBoundingClientRect();
        const wall = svg.querySelector('#wall-instance').getBoundingClientRect();
        return { rootTransform: svg.getAttribute('transform'), dx: person.x + person.width / 2 - reference.x - reference.width / 2, dy: person.y + person.height / 2 - reference.y - reference.height / 2, wallWidth: wall.width, xlinkWallWidth: svg.querySelector('#wall-instance-xlink').getBoundingClientRect().width };
    });
    assert.equal(geometry.rootTransform, null);
    assert.ok(Math.abs(geometry.dx) < .5 && Math.abs(geometry.dy) < .5, 'the plan and world-coordinate overlay must align after root transform');
    assert.ok(geometry.wallWidth > 0 && geometry.xlinkWallWidth > 0, 'local fragment href and xlink:href use must preserve rendered walls');
    await page.locator('#timeline').evaluate(input => { input.value = '10'; input.dispatchEvent(new Event('input')); });
    assert.match(await page.locator('#playback-stats').textContent(), /Вышли: 2/);
    await page.locator('#reset-btn').click(); assert.match(await page.locator('#playback-stats').textContent(), /Вышли: 0/);
    await page.setViewportSize({ width: 390, height: 844 }); await page.waitForTimeout(100);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    const originalSvg = floor.svg;
    const cssCases = [
        '<style>.wall.important {fill:black}.wall {fill:white}</style><rect id="cascade-wall" class="wall important" x="72" y="5" width="4" height="20"/>',
        '<style>.wall {fill:black !important}</style><rect id="cascade-wall" class="wall" style="fill:white" x="72" y="5" width="4" height="20"/>',
        '<style>.wall {fill:white}</style><rect id="cascade-wall" class="wall" style="fill:black !important" x="72" y="5" width="4" height="20"/>',
        '<style>:root {fill:black}.wall {fill:inherit} body {display:none}</style><rect id="cascade-wall" class="wall" x="72" y="5" width="4" height="20"/>',
    ];
    for (const css of cssCases) {
        floor.svg = originalSvg.replace('</svg>', `${css}</svg>`);
        const nativeFill = await page.evaluate(raw => {
            const frame = document.createElement('iframe'); frame.setAttribute('sandbox', 'allow-same-origin');
            frame.style.visibility = 'hidden'; document.body.append(frame);
            try {
                const parsed = new DOMParser().parseFromString(raw.replace(/ onload="[^"]*"/, ''), 'image/svg+xml');
                frame.contentDocument.replaceChild(frame.contentDocument.importNode(parsed.documentElement, true), frame.contentDocument.documentElement);
                return frame.contentWindow.getComputedStyle(frame.contentDocument.querySelector('#cascade-wall')).fill;
            } finally { frame.remove(); }
        }, floor.svg);
        await page.reload();
        await page.locator('#plan-container #cascade-wall').waitFor();
        const normalized = await page.locator('#plan-container svg').evaluate(svg => ({
            fill: getComputedStyle(svg.querySelector('#cascade-wall')).fill,
            styles: svg.querySelectorAll('style').length,
            bodyVisible: getComputedStyle(document.body).display !== 'none',
        }));
        assert.equal(nativeFill, 'rgb(0, 0, 0)');
        assert.equal(normalized.fill, nativeFill, 'CSS specificity and important must preserve the native wall colour');
        assert.equal(normalized.styles, 0, 'SVG styles must not leak into the application');
        assert.equal(normalized.bodyVisible, true);
    }
    floor.svg = originalSvg.replace('</svg>', '<style>.unrelated {fill:red}.clipped {clip-path:url(#clip)}</style><defs><clipPath id="clip"><rect width="100" height="40"/></clipPath><rect id="white-source" width="4" height="20"/></defs><use id="white-use" href="#white-source" fill="white"/><rect id="local-clip" class="clipped" width="4" height="20"/></svg>');
    await page.reload(); await page.locator('#plan-container #white-use').waitFor();
    const reusableStyles = await page.locator('#plan-container svg').evaluate(svg => ({
        definitionFill: svg.querySelector('#white-source').style.fill,
        useFill: getComputedStyle(svg.querySelector('#white-use')).fill,
        clip: svg.querySelector('#local-clip').style.clipPath,
    }));
    assert.equal(reusableStyles.definitionFill, '', 'inherited defaults must not be frozen into reusable geometry');
    assert.equal(reusableStyles.useFill, 'rgb(255, 255, 255)');
    assert.match(reusableStyles.clip, /^url\(["']?#clip["']?\)$/);
    for (const css of ['<rect width="10" height="10" style="transform: translateX(5px)"/>', '<style>rect { transform: translateX(5px); }</style><rect width="10" height="10"/>']) {
        floor.svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 40">${css}</svg>`;
        await page.reload();
        await page.waitForFunction(() => document.querySelector('#plan-container').textContent.includes('используйте SVG transform'));
    }
    floor.svg = originalSvg;
    assert.deepEqual(errors, []);
    console.log('VIEWER CONTRACT SMOKE PASS: Java result playback, SVG cleanup, native CSS cascade/important/scoping, reusable geometry inheritance, local CSS fragment, root-transform geometry, local use wall, room-only capacity fill, CSS-transform rejection, seek/reset, 390px');
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
