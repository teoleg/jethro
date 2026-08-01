/* Focus-hub tile summaries (ADR-0133). Each tile with a data-summary key gets ONE live headline pulled
   from the SAME REST endpoints the existing pages poll — read-only, best-effort. A failed/empty fetch
   leaves the tile's static description in place; a hub is navigation, never a place a number can mislead. */
(function () {
  "use strict";

  function money(n) {
    if (n == null || !isFinite(n)) return null;
    var a = Math.abs(n), s = n < 0 ? "-" : "";
    if (a >= 1e6) return s + "$" + (a / 1e6).toFixed(2) + "M";
    if (a >= 1e3) return s + "$" + (a / 1e3).toFixed(1) + "k";
    return s + "$" + a.toFixed(0);
  }
  function j(url) { return fetch(url, { cache: "no-store" }).then(function (r) {
    if (!r.ok) throw new Error(r.status); return r.json(); }); }

  // key -> async () -> { v, u, cls } | null   (v=value text, u=unit label, cls=up/down/flat)
  var LOADERS = {
    risk: function () { return j("/api/risk").then(function (r) {
      var t = typeof r.total === "object" && r.total ? (r.total.pnl != null ? r.total.pnl : r.total.total) : r.total;
      var m = money(Number(t));
      return m == null ? null : { v: m, u: "firm PnL", cls: Number(t) > 0 ? "up" : (Number(t) < 0 ? "down" : "flat") };
    }); },
    orders: function () { return j("/api/orders/day?page=0&size=1").then(function (r) {
      return r.total == null ? null : { v: String(r.total), u: "orders today" }; }); },
    regime: function () { return j("/api/market/regime").then(function (r) {
      var t = (r.trend || "—"), g = (r.regime || "—");
      return { v: t.toLowerCase(), u: g.toLowerCase(), cls: (t === "TREND" || g === "CALM") ? "up" : "flat" }; }); },
    strategy: function () { return j("/api/strategy/actions").then(function (a) {
      var n = Array.isArray(a) ? a.length : (a && a.actions ? a.actions.length : null);
      return n == null ? null : { v: String(n), u: "live actions" }; }); },
    fusion: function () { return j("/api/fusion/targets").then(function (b) {
      var n = (b.targets || []).length;
      return { v: String(n), u: b.routing ? "targets · live" : "targets · shadow", cls: b.routing ? "up" : "flat" }; }); },
    hypotheses: function () { return j("/api/hypotheses").then(function (a) {
      var n = Array.isArray(a) ? a.length : null;
      return n == null ? null : { v: String(n), u: "hypotheses" }; }); },
    feeds: function () { return j("/api/feeds").then(function (f) {
      var arr = Array.isArray(f) ? f : (f && f.feeds) || [];
      var on = arr.filter(function (x) { var s = (x.state || x.status || "").toString().toUpperCase();
        return s.indexOf("CONNECT") >= 0 || s === "LIVE" || s === "UP" || x.connected === true; }).length;
      return { v: on + "/" + arr.length, u: "feeds live", cls: on > 0 ? "up" : "flat" }; }); },
    improve: function () { return j("/api/improve/status").then(function (a) {
      var n = Array.isArray(a) ? a.length : null;
      return n == null ? null : { v: String(n), u: "scored cycles" }; }); }
  };

  function paint(el, r) {
    if (!r || r.v == null) return;
    el.innerHTML = '<span class="v ' + (r.cls || "") + '">' + r.v + '</span>' +
      (r.u ? '<span class="u">' + r.u + '</span>' : "");
  }

  function refresh() {
    document.querySelectorAll("[data-summary]").forEach(function (tile) {
      var key = tile.getAttribute("data-summary");
      var slot = tile.querySelector(".metric");
      if (!LOADERS[key] || !slot) return;
      LOADERS[key]().then(function (r) { paint(slot, r); }).catch(function () { /* keep static desc */ });
    });
  }

  document.addEventListener("DOMContentLoaded", function () { refresh(); setInterval(refresh, 5000); });
})();
