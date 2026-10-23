const assert = require('node:assert/strict');
const { existsSync } = require('node:fs');
const { chromium } = require('playwright');

const base = process.env.EVAC_URL || 'http://127.0.0.1:8080';
const executablePath = process.env.CHROMIUM_PATH || (existsSync('/usr/bin/chromium') ? '/usr/bin/chromium' : undefined);

async function calculate(page) {
  await page.locator('#calculate').click();
  await page.waitForFunction(() => document.querySelector('#status').textContent.startsWith('Расчёт завершён:'));
}

async function upload(page, svg) {
  await page.locator('#file').setInputFiles({ name: 'test.svg', mimeType: 'image/svg+xml', buffer: Buffer.from(svg) });
  await page.waitForFunction(() => document.querySelector('#status').textContent.startsWith('План загружен.'));
}

(async () => {
  const browser = await chromium.launch({ executablePath, headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = [], externalRequests = [], checks = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('request', request => { if (request.url().startsWith('https://example.invalid/')) externalRequests.push(request.url()); });
  try {
    await page.goto(base);
    await page.waitForFunction(() => document.querySelector('#status').textContent.startsWith('Пример открыт:'));
    assert.equal(await page.locator('#occupancy input').count(), 11);
    assert.equal(await page.locator('#plan-info').textContent().then(text => text.includes('0,015 м/ед.')), true);
    await calculate(page);
    assert.equal(await page.locator('#total').textContent(), '66');
    assert.equal(await page.locator('#evacuated').textContent(), '66');
    assert.equal(await page.locator('#unreachable').textContent(), '0');
    assert.ok(await page.locator('#plan polyline[marker-end]').count() > 0);
    const bothExitTime = parseFloat((await page.locator('#duration').textContent()).replace(',', '.'));
    checks.push('Встроенный SVG: 66 человек, завершённые маршруты и расчётное время.');

    const left = page.locator('#connections label').filter({ hasText: 'E_left' }).locator('input');
    const right = page.locator('#connections label').filter({ hasText: 'E_right' }).locator('input');
    await left.check(); await right.check();
    assert.equal(await page.locator('#total').textContent(), '—');
    await calculate(page);
    assert.equal(await page.locator('#evacuated').textContent(), '0');
    assert.equal(await page.locator('#unreachable').textContent(), '66');
    assert.equal(await page.locator('#plan polyline[marker-end]').count(), 0);
    assert.match(await page.locator('#status').textContent(), /не имеют доступного пути/);
    checks.push('Блокировка двух выходов: все 66 человек недостижимы, прошлые маршруты очищены.');

    await left.uncheck();
    await calculate(page);
    assert.equal(await page.locator('#evacuated').textContent(), '66');
    const oneExitTime = parseFloat((await page.locator('#duration').textContent()).replace(',', '.'));
    assert.ok(oneExitTime > bothExitTime);
    checks.push('Один выход: эвакуация завершена, время больше сценария с двумя выходами.');

    const customSvg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 300 180" data-scale="0.1" data-floor="1" transform="translate(10 5)" onload="window.__unsafeSvg=true">
      <script>window.__unsafeSvg=true</script>
      <image href="https://example.invalid/unsafe.svg" width="10" height="10"/>
      <rect x="0" y="0" width="80" height="80" fill="#e6e6fa" data-type="room" data-id="R" data-capacity="10"/>
      <rect x="80" y="0" width="160" height="80" fill="#fff4d4" data-type="corridor" data-id="C" data-capacity="20"/>
      <line x1="80" y1="30" x2="80" y2="50" data-type="door" data-id="D" data-connects="R,C" data-throughput="1"/>
      <line x1="240" y1="30" x2="240" y2="50" data-type="exit" data-id="E" data-connects="C,OUTSIDE" data-throughput="1"/>
    </svg>`;
    await upload(page, customSvg);
    assert.deepEqual(await page.locator('#occupancy input').evaluateAll(inputs => inputs.map(input => input.value)), ['0', '0']);
    const roomInput = page.locator('#occupancy .occupancy-row').filter({ hasText: 'R ·' }).locator('input');
    await roomInput.fill('3');
    await calculate(page);
    assert.equal(await page.locator('#total').textContent(), '3');
    assert.equal(await page.locator('#evacuated').textContent(), '3');
    assert.equal(await page.evaluate(() => window.__unsafeSvg), undefined);
    assert.equal(await page.locator('#plan script, #plan image, #plan [onload]').count(), 0);
    assert.equal(await page.locator('#plan > svg').getAttribute('transform'), null);
    assert.equal(await page.locator('#plan > svg > g').first().getAttribute('transform'), 'translate(10 5)');
    assert.match(await page.locator('#plan polyline[marker-end]').first().getAttribute('points'), /^50,45 /);
    assert.deepEqual(externalRequests, []);
    checks.push('Свой SVG: отдельное число людей 3/10, root transform согласован с маршрутом, опасные SVG-элементы удалены.');

    await roomInput.fill('11');
    await page.locator('#calculate').click();
    await page.waitForFunction(() => document.querySelector('#status').classList.contains('error'));
    assert.match(await page.locator('#status').textContent(), /целое число людей от 0 до 10/);
    await page.locator('#clear-occupancy').click();
    await calculate(page);
    assert.equal(await page.locator('#total').textContent(), '0');
    assert.equal(await page.locator('#evacuated').textContent(), '0');
    checks.push('Проверка вместимости и расчёт пустого здания.');

    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    assert.deepEqual(errors, []);
    checks.push('Мобильная ширина 390 px без горизонтального переполнения и ошибок JavaScript.');
    console.log(JSON.stringify({ passed: checks.length, checks, bothExitTime, oneExitTime }, null, 2));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
