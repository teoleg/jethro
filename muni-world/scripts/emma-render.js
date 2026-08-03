// Render a JS page to its FINAL DOM after network-idle (what CLI Chrome can't do), so EMMA's ajax grid
// actually loads before we read it. Prints the rendered HTML to stdout.
//   node emma-render.js <url>
// Drives the SYSTEM Chromium (no browser download). Resolves the binary robustly across Pi/Debian naming.
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
    args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage']
      .concat(process.env.MUNI_BROWSER_PROXY ? ['--proxy-server=' + process.env.MUNI_BROWSER_PROXY, '--ignore-certificate-errors'] : []),
  });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(process.env.MUNI_HTTP_UA || 'muni-world/0.1 (+municipal-data-collection)');
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 });
    await new Promise(r => setTimeout(r, 2500)); // let any late grid render settle
    process.stdout.write(await page.content());
  } finally {
    await browser.close();
  }
})().catch(e => { console.error(String(e)); process.exit(1); });
