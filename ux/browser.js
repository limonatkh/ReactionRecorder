const { chromium } = require('playwright');
const home = process.argv[2], secs = process.argv[3] || '30';
(async () => {
  const b = await chromium.launch({ env: Object.fromEntries(Object.entries({ ...process.env, HOME: home }).filter(([k]) => !/proxy/i.test(k))), args: ['--host-resolver-rules=MAP rr.local 127.0.0.1', '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--no-proxy-server'] });
  const p = await b.newPage({ viewport: { width: 420, height: 900 }, deviceScaleFactor: 1 });
  p.on('console', m => console.log('console:', m.text()));
  await p.goto('http://rr.local:47680/');
  await p.waitForTimeout(4000);
  await p.screenshot({ path: process.argv[4] + '/browser_setup.png', fullPage: true });
  const ready = await p.isVisible('#ready');
  console.log('SETUP ready =', ready, '| steps shown =', await p.isVisible('#steps'));
  if (!ready) { await b.close(); return; }
  await p.click('#go'); await p.waitForTimeout(2000);
  console.log('secure =', await p.evaluate(() => window.isSecureContext), location = p.url());
  await p.fill('#name', 'لابتوب التجربة'); await p.click('#joinBtn');
  console.log('WAITING_APPROVAL'); await p.waitForSelector('#check:not(.hide)', { timeout: 180000 }); console.log('APPROVED');
  await p.click('#runBtn');
  await p.waitForSelector('#recCard:not(.hide)', { timeout: 30000 });
  await p.selectOption('#recLen', secs === '30' ? '30' : secs);
  await p.click('#recBtn');
  await p.waitForFunction(() => document.getElementById('r_record'), null, { timeout: (parseInt(secs) + 60) * 1000 });
  await p.waitForTimeout(3000);
  await p.screenshot({ path: process.argv[4] + '/browser_check.png', fullPage: true });
  const rows = await p.$$eval('#res tr', t => t.map(r => r.innerText.replace(/\s+/g, ' ')));
  console.log(rows.join('\n'));
  await b.close();
})().catch(e => { console.log('ERR', e.message); process.exit(1); });
