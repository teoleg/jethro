// Render a JS page to its final DOM (a general capability for legitimately-accessible sources).
//   node emma-render.js <url>
// Drives the SYSTEM Chromium (no browser download). Identifies itself honestly (descriptive UA) and does
// NOT attempt to defeat bot-detection or access controls.
//
// NOTE: EMMA's Terms of Use PROHIBIT automated scraping/crawling and circumventing access measures, and its
// grid gates data against automation. Do NOT point the auto-fetcher at EMMA. Legitimate paths are: a human
// viewing/downloading OS PDFs for their own internal use (then the os-inbox folder-loader extracts them), or
// the MSRB's paid EMMA data subscription. See ADR-0008 / ADR-0015.
const fs = require('fs');
const { execSync } = require('child_process');
const puppeteer = require('puppeteer-core');

function resolveChromium(given) {
  const paths = [
    given,
    '/usr/bin/chromium', '/usr/bin/chromium-browser',
    '/snap/bin/chromium',
    '/usr/lib/chromium/chromium', '/usr/lib/chromium-browser/chromium-browser',
    '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
  ];
  for (const p of paths) {
    try { if (p && fs.existsSync(p) && fs.statSync(p).isFile()) return p; } catch (e) { /* skip */ }
  }
  for (const name of [given, 'chromium', 'chromium-browser', 'google-chrome', 'chrome'].filter(Boolean)) {
    try {
      const p = execSync('command -v ' + name + ' 2>/dev/null', { shell: '/bin/bash' }).toString().trim();
      if (p) return p;
    } catch (e) { /* not on PATH */ }
  }
  return null;
}

const SETTLE_MS = parseInt(process.env.MUNI_RENDER_SETTLE_MS || '3000', 10);

(async () => {
  const url = process.argv[2];
  if (!url) { console.error('usage: node emma-render.js <url>'); process.exit(2); }
  const executablePath = resolveChromium(process.env.MUNI_BROWSER_BIN || 'chromium-browser');
  if (!executablePath) {
    console.error('Chromium not found. Install it (sudo apt-get install -y chromium) and set '
      + 'MUNI_BROWSER_BIN to its path (e.g. /usr/bin/chromium).');
    process.exit(3);
  }
  const browser = await puppeteer.launch({
    executablePath, headless: true,
    timeout: parseInt(process.env.MUNI_LAUNCH_TIMEOUT_MS || '90000', 10), // Chromium is slow to start on a Pi
    protocolTimeout: 180000,
    args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage', '--disable-software-rasterizer', '--no-zygote']
      .concat(process.env.MUNI_BROWSER_PROXY ? ['--proxy-server=' + process.env.MUNI_BROWSER_PROXY, '--ignore-certificate-errors'] : []),
  });
  try {
    const page = await browser.newPage();
    // Honest, identifiable UA (ADR-0008) — no automation-detection evasion.
    await page.setUserAgent(process.env.MUNI_HTTP_UA || 'muni-world/0.1 (+municipal-data-collection)');
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 });
    await new Promise(r => setTimeout(r, SETTLE_MS));
    process.stdout.write(await page.content());
  } finally {
    await browser.close();
  }
})().catch(e => { console.error(String(e)); process.exit(1); });
