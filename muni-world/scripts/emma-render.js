// Render a JS page to its FINAL DOM after network-idle (what CLI Chrome can't do), so EMMA's ajax grid
// actually loads before we read it. Prints the rendered HTML to stdout.
//   node emma-render.js <url>
// Uses the system Chromium at MUNI_BROWSER_BIN (default chromium-browser). No browser download.
const puppeteer = require('puppeteer-core');
(async () => {
  const url = process.argv[2];
  if (!url) { console.error('usage: node emma-render.js <url>'); process.exit(2); }
  const executablePath = process.env.MUNI_BROWSER_BIN || 'chromium-browser';
  const browser = await puppeteer.launch({
    executablePath, headless: true,
    args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'].concat(process.env.MUNI_BROWSER_PROXY ? ['--proxy-server=' + process.env.MUNI_BROWSER_PROXY, '--ignore-certificate-errors'] : []),
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
