/* Focus-hub tile stats (ADR-0133). Each tile with a data-summary key gets a small row of live stats pulled
   from the SAME REST endpoints the existing pages poll — read-only, best-effort. A failed/empty fetch
   leaves whatever the tile already showed; a hub is navigation, never a place a number can mislead. */
(function () {
  "use strict";

  function money(n) {
    n = Number(n);
    if (n == null || !isFinite(n)) return null;
    var a = Math.abs(n), s = n < 0 ? "-" : "";
    if (a >= 1e6) return s + "$" + (a / 1e6).toFixed(2) + "M";
    if (a >= 1e3) return s + "$" + (a / 1e3).toFixed(1) + "k";
    return s + "$" + a.toFixed(0);
  }
  function cls(n) { n = Number(n); return n > 0 ? "up" : (n < 0 ? "down" : "flat"); }
  function pick() { for (var i = 0; i < arguments.length; i++) { var v = arguments[i];
    if (v !== undefined && v !== null && v !== "") return v; } return null; }
  function j(url) { return fetch(url, { cache: "no-store" }).then(function (r) {
    if (!r.ok) throw new Error(r.status); return r.json(); }); }

  // key -> async () -> [ {v, l, cls?} , ... ]   (v=value text, l=label)
  var LOADERS = {
    risk: function () { return j("/api/risk").then(function (r) {
      var t = (r.total && typeof r.total === "object") ? r.total : {};
      var tot = pick(t.totalPnl, t.pnl, (typeof r.total === "number" ? r.total : null));
      var gross = pick(t.grossExposure, r.gross, r.grossExposure);
      var net = pick(t.netExposure, r.net, r.netExposure);
      var out = [{ v: money(tot), l: "Firm PnL", cls: cls(tot) }];
      if (gross != null) out.push({ v: money(gross), l: "Gross" });
      if (net != null) out.push({ v: money(net), l: "Net" });
      return out; }); },
    orders: function () { return j("/api/orders/day?page=0&size=1").then(function (r) {
      return [{ v: r.total != null ? String(r.total) : null, l: "Orders today" }]; }); },
    regime: function () { return Promise.all([
      j("/api/market/regime").catch(function () { return {}; }),
      j("/api/feeds").catch(function () { return []; })]).then(function (a) {
      var m = a[0], f = a[1];
      var arr = Array.isArray(f) ? f : (f && f.feeds) || [];
      var on = arr.filter(function (x) { var s = (x.state || x.status || "").toString().toUpperCase();
        return s.indexOf("CONNECT") >= 0 || s === "LIVE" || s === "UP" || x.connected === true; }).length;
      var t = (m.trend || "—"), g = (m.regime || "—");
      return [{ v: t.toLowerCase(), l: "Trend", cls: t === "TREND" ? "up" : "flat" },
              { v: g.toLowerCase(), l: "Vol", cls: g === "CALM" ? "up" : (g === "ELEVATED" ? "down" : "flat") },
              { v: on + "/" + arr.length, l: "Feeds", cls: on > 0 ? "up" : "flat" }]; }); },
    rates: function () { return Promise.all([
      j("/api/dv01").catch(function () { return null; }),
      j("/api/swaps/book").catch(function () { return null; })]).then(function (a) {
      var d = a[0], b = a[1], out = [];
      var dv = d && pick(d.firm, d.total, d.dv01, d.firmDv01);
      if (dv != null) out.push({ v: money(dv), l: "Firm DV01" });
      var n = b && (Array.isArray(b) ? b.length : (b.swaps ? b.swaps.length : pick(b.count, b.total)));
      if (n != null) out.push({ v: String(n), l: "Swaps" });
      return out.length ? out : [{ v: "curve · DV01", l: "Rates book", mut: true }]; }); },
    strategy: function () { return j("/api/strategy/actions").then(function (a) {
      var n = Array.isArray(a) ? a.length : (a && a.actions ? a.actions.length : null);
      return [{ v: n != null ? String(n) : null, l: "Live actions" }]; }); },
    fusion: function () { return j("/api/fusion/targets").then(function (b) {
      var n = (b.targets || []).length;
      return [{ v: String(n), l: "Targets" },
              { v: b.routing ? "live" : "shadow", l: "Mode", cls: b.routing ? "up" : "flat" }]; }); },
    hypotheses: function () { return j("/api/hypotheses").then(function (a) {
      var n = Array.isArray(a) ? a.length : null;
      return [{ v: n != null ? String(n) : null, l: "Theses" }]; }); },
    sources: function () { return j("/api/social").then(function (s) {
      var arr = Array.isArray(s) ? s : (s && (s.sources || s.channels)) || [];
      return [{ v: String(arr.length), l: "Sources" }]; }); },
    discovery: function () { return Promise.all([
      j("/api/universe/proposals").catch(function () { return null; }),
      j("/api/discovery").catch(function () { return null; })]).then(function (a) {
      var p = a[0], d = a[1];
      var n = (p && (Array.isArray(p) ? p.length : (p.proposals ? p.proposals.length : pick(p.count, p.total))));
      if (n == null && d) n = Array.isArray(d) ? d.length : pick(d.count, d.total);
      return [{ v: n != null ? String(n) : "—", l: "Candidates" }]; }); },
    improve: function () { return j("/api/improve/status").then(function (a) {
      var arr = Array.isArray(a) ? a : (a && a.runs) || [];
      var last = arr[0] || {};
      var out = [{ v: String(arr.length), l: "Cycles" }];
      if (last.total_pnl != null) out.push({ v: money(last.total_pnl), l: "Last PnL", cls: cls(last.total_pnl) });
      return out; }); }
  };

  function render(stats) {
    return stats.filter(function (s) { return s && s.v != null; }).map(function (s) {
      return '<span class="s"><span class="sv ' + (s.mut ? "mut " : "") + (s.cls || "") + '">' +
        s.v + '</span><span class="sl">' + s.l + '</span></span>';
    }).join("");
  }

  function refresh() {
    document.querySelectorAll("[data-summary]").forEach(function (tile) {
      var key = tile.getAttribute("data-summary");
      var slot = tile.querySelector(".stats");
      if (!LOADERS[key] || !slot) return;
      LOADERS[key]().then(function (stats) {
        var html = render(stats);
        if (html) slot.innerHTML = html;
      }).catch(function () { /* keep whatever is shown */ });
    });
  }

  document.addEventListener("DOMContentLoaded", function () { refresh(); setInterval(refresh, 5000); });
})();
