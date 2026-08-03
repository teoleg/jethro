// Render a JS page to its FINAL DOM after its ajax content loads (what CLI Chrome can't do), so EMMA's grid
// actually populates before we read it. Prints the rendered HTML to stdout.
//   node emma-render.js <url>
// Drives the SYSTEM Chromium (no browser download). Looks like a normal browser so sites that gate content
// on automation-detection (EMMA runs FullStory) still load their data.
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

// A real desktop-Chrome UA so the site treats us as a normal browser (override with MUNI_RENDER_UA).
const REAL_UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) '
  + 'Chrome/124.0.0.0 Safari/537.36';
const SETTLE_MS = parseInt(process.env.MUNI_RENDER_SETTLE_MS || '7000', 10);

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
    args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage', '--disable-software-rasterizer',
      '--no-zygote', '--disable-blink-features=AutomationControlled', '--window-size=1440,900', '--lang=en-US']
      .concat(process.env.MUNI_BROWSER_PROXY ? ['--proxy-server=' + process.env.MUNI_BROWSER_PROXY, '--ignore-certificate-errors'] : []),
  });
  try {
    const page = await browser.newPage();
    // hide the two clearest automation tells before any page script runs
    await page.evaluateOnNewDocument(() => {
      Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
      Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
      Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3] });
    });
    await page.setUserAgent(process.env.MUNI_RENDER_UA || REAL_UA);
    await page.setViewport({ width: 1440, height: 900 });
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 60000 });
    await new Promise(r => setTimeout(r, SETTLE_MS)); // let a slow ajax grid finish populating
    process.stdout.write(await page.content());
  } finally {
    await browser.close();
  }
})().catch(e => { console.error(String(e)); process.exit(1); });
