/* Shared polling helper for the static UI (CSP-safe, no deps). Include with
   <script src="/poll.js"></script> BEFORE the page script, then use poll()/pollFetch()
   instead of setInterval(fetchThing, ms).

   Why this exists: the pages refresh with many independent setInterval(fetch, ms) loops
   (index.html alone has ~11, the fastest at 2s). Raw intervals have three failure modes
   that, over a multi-hour run, wedged every open tab:
     1. No overlap guard — a new fetch fires each tick even if the previous hasn't returned.
        When the backend briefly stalls (e.g. the hourly CPU-heavy OOS selector backtest),
        pending requests + their promises accumulate behind the browser's ~6-conn/host cap.
     2. No timeout — a stalled fetch never aborts, so those pending requests never drain.
     3. No pause when the tab is hidden — backgrounded tabs kept polling all night.
   poll() fixes all three: one in-flight run at a time, paused while hidden (with an
   immediate refresh on return), and pollFetch() aborts a request that overruns. */
(function () {
  // fetch with a hard timeout — a stalled backend aborts the request instead of hanging
  // a poll loop forever. Returns a normal fetch promise; callers .json()/.catch() as usual.
  window.pollFetch = function (url, opts, timeoutMs) {
    var ctl = new AbortController();
    var t = setTimeout(function () { ctl.abort(); }, timeoutMs || 8000);
    return fetch(url, Object.assign({ signal: ctl.signal }, opts || {}))
      .finally(function () { clearTimeout(t); });
  };

  // poll(fn, ms): run fn() now, then every ms — but NEVER concurrently, and only while the
  // tab is visible. fn may be sync or return a promise; the next tick waits for it to settle,
  // so a slow backend throttles the cadence instead of piling up requests. Returns a stop().
  window.poll = function (fn, ms) {
    var running = false, cancelled = false;
    function tick() {
      if (cancelled || document.hidden || running) return;
      running = true;
      Promise.resolve().then(fn).catch(function () { /* fn owns its errors */ })
        .finally(function () { running = false; });
    }
    var id = setInterval(tick, ms);
    // Refresh immediately when the user returns to the tab, rather than waiting out the interval.
    var onVis = function () { if (!document.hidden) tick(); };
    document.addEventListener("visibilitychange", onVis);
    tick();
    return function stop() {
      cancelled = true;
      clearInterval(id);
      document.removeEventListener("visibilitychange", onVis);
    };
  };
})();
