/* Shared price-history chart widget. Include with <script src="/chart.js"></script>,
   then call window.openChart(instrumentId). Self-contained: injects its own styles and
   modal, fetches /api/history, draws an inline SVG line chart (CSP-safe, offline). */
(function () {
  const style = document.createElement("style");
  style.textContent = `
    .chart-overlay { position:fixed; inset:0; background:rgba(6,8,13,.66); backdrop-filter:blur(3px);
      display:none; align-items:flex-start; justify-content:center; z-index:60; }
    .chart-overlay.open { display:flex; }
    .chart-box { background:var(--surface,#141926); border:1px solid var(--line-2,#323c50); border-radius:16px;
      padding:18px 20px; margin-top:7vh; width:min(840px,94vw); box-shadow:0 24px 60px rgba(0,0,0,.5);
      color:var(--txt,#e6eaf2); font:14px/1.5 -apple-system,system-ui,sans-serif; }
    .chart-box h3 { font-size:15px; display:flex; align-items:center; gap:12px; margin:0 0 12px; }
    .chart-box .x { cursor:pointer; color:var(--dim,#8b95a8); background:none; border:0; font-size:22px; line-height:1; }
    .chart-ranges { display:flex; gap:6px; }
    .chart-ranges button { padding:4px 11px; border-radius:999px; border:1px solid var(--line-2,#323c50);
      background:var(--surface-2,#1a2030); color:var(--dim,#8b95a8); font:inherit; font-size:12px; cursor:pointer; }
    .chart-ranges button.on { color:#fff; background:var(--brand,#5b8cff); border-color:var(--brand,#5b8cff); }
    .chart-plot svg { width:100%; height:auto; display:block; }
    .chart-plot svg text { fill:var(--dim,#8b95a8); font-size:11px; font-variant-numeric:tabular-nums; }
    .chart-plot svg .grid { stroke:var(--line,#252d3d); stroke-width:1; }
    .chart-plot svg .line { fill:none; stroke:var(--brand,#5b8cff); stroke-width:1.6; }
    .chart-plot svg .area { fill:var(--brand,#5b8cff); opacity:.08; }
    .chart-muted { color:var(--dim,#8b95a8); font-size:12px; }
  `;
  document.head.appendChild(style);

  const overlay = document.createElement("div");
  overlay.className = "chart-overlay";
  overlay.innerHTML =
    '<div class="chart-box"><h3><span id="chartTitle"></span><span style="flex:1"></span>' +
    '<span class="chart-ranges" id="chartRanges"></span><button class="x" id="chartClose">&times;</button></h3>' +
    '<div class="chart-plot" id="chartPlot"><span class="chart-muted">loading…</span></div>' +
    '<div class="chart-muted" id="chartMeta" style="margin-top:8px"></div></div>';
  document.body.appendChild(overlay);

  const RANGES = [{ label: "30m", min: 30 }, { label: "1h", min: 60 }, { label: "2h", min: 120 }];
  let instrument = null, minutes = 120;

  const close = () => overlay.classList.remove("open");
  overlay.querySelector("#chartClose").onclick = close;
  overlay.onclick = e => { if (e.target === overlay) close(); };
  document.addEventListener("keydown", e => { if (e.key === "Escape") close(); });

  function renderRanges() {
    const el = overlay.querySelector("#chartRanges");
    el.innerHTML = RANGES.map(r => `<button data-m="${r.min}" class="${r.min === minutes ? "on" : ""}">${r.label}</button>`).join("");
    el.querySelectorAll("button").forEach(b => b.onclick = () => { minutes = +b.dataset.m; renderRanges(); load(); });
  }

  async function load() {
    const plot = overlay.querySelector("#chartPlot"), meta = overlay.querySelector("#chartMeta");
    try {
      const pts = await (await fetch(`/api/history/${encodeURIComponent(instrument)}?minutes=${minutes}`)).json();
      plot.innerHTML = svg(pts);
      if (pts.length > 1) {
        const f = Number(pts[0].price), l = Number(pts[pts.length - 1].price), ch = (l - f) / f * 100;
        meta.textContent = `${pts.length} points · ${new Date(pts[0].t).toLocaleTimeString()} → ${new Date(pts[pts.length - 1].t).toLocaleTimeString()} · change ${ch >= 0 ? "+" : ""}${ch.toFixed(2)}%`;
      } else meta.textContent = "";
    } catch (e) { plot.innerHTML = '<span class="chart-muted">history unavailable</span>'; meta.textContent = ""; }
  }

  function svg(points) {
    if (!points || points.length < 2) return '<span class="chart-muted">not enough history yet — give it a minute of ticks</span>';
    const W = 800, H = 300, pl = 64, pr = 14, pt = 14, pb = 26;
    const t0 = points[0].t, t1 = points[points.length - 1].t || (t0 + 1);
    const ys = points.map(p => Number(p.price)), mn = Math.min(...ys), mx = Math.max(...ys), span = (mx - mn) || 1;
    const X = t => pl + (t1 === t0 ? 0 : (t - t0) / (t1 - t0)) * (W - pl - pr);
    const Y = v => H - pb - ((v - mn) / span) * (H - pt - pb);
    const line = points.map((p, i) => `${i ? "L" : "M"}${X(p.t).toFixed(1)},${Y(Number(p.price)).toFixed(1)}`).join(" ");
    const area = `M${X(t0).toFixed(1)},${H - pb} ` + points.map(p => `L${X(p.t).toFixed(1)},${Y(Number(p.price)).toFixed(1)}`).join(" ") + ` L${X(t1).toFixed(1)},${H - pb} Z`;
    const fmt = v => v.toLocaleString(undefined, { maximumFractionDigits: 4 });
    const grid = [mx, (mx + mn) / 2, mn].map(v =>
      `<line class="grid" x1="${pl}" y1="${Y(v).toFixed(1)}" x2="${W - pr}" y2="${Y(v).toFixed(1)}"/><text x="${pl - 8}" y="${(Y(v) + 3).toFixed(1)}" text-anchor="end">${fmt(v)}</text>`).join("");
    return `<svg viewBox="0 0 ${W} ${H}">${grid}<path class="area" d="${area}"/><path class="line" d="${line}"/>` +
      `<text x="${pl}" y="${H - 8}">${new Date(t0).toLocaleTimeString()}</text>` +
      `<text x="${W - pr}" y="${H - 8}" text-anchor="end">${new Date(t1).toLocaleTimeString()}</text></svg>`;
  }

  window.openChart = function (instrumentId) {
    instrument = instrumentId; minutes = 120;
    overlay.querySelector("#chartTitle").textContent = instrumentId + "  ·  price history";
    overlay.classList.add("open");
    renderRanges(); load();
  };
})();
