// uplink-web front end. The checking runs in uplink-web.js (Java compiled to
// WebAssembly by GraalVM Web Image), which calls uplinkRender(json) with the state
// of the run a few times a second; this file only draws it and handles input, like
// the TUI dashboard's view and key bindings.
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const TABS = ['summary', 'broken', 'latency'];
  const PAGE_ROWS = 10;
  const ui = { tab: 'summary', cursor: { broken: 0, latency: 0 }, state: null, ready: false };

  const version = document.querySelector('meta[name="uplink-version"]').content;
  $('version').textContent = version.startsWith('$') || version === 'dev' ? 'dev' : 'v' + version;

  // ---- helpers ---------------------------------------------------------------

  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  /** A link to a checked URL; uplink only reports http(s) URLs. */
  const link = (url, cls = '') => /^https?:\/\//i.test(url)
    ? `<a class="${cls}" href="${esc(url)}" target="_blank" rel="noopener noreferrer">${esc(url)}</a>`
    : `<span class="${cls}">${esc(url)}</span>`;

  const span = (text, cls) => `<span class="${cls}">${esc(text)}</span>`;

  function setTitle(panel, text, tone) {
    const el = $(panel);
    el.querySelector('.panel-title').textContent = text;
    el.dataset.tone = tone;
  }

  function setRows(panel, rows, empty, emptyTone = 'ok') {
    const list = $(panel).querySelector('.rows');
    list.innerHTML = rows.length ? rows.join('') : `<li class="empty ${emptyTone}">${esc(empty)}</li>`;
  }

  // ---- drawing ---------------------------------------------------------------

  function renderHeader(s) {
    if (!s.root) {
      $('target-line').innerHTML = span('Target  ', 'dim') + span('enter a URL above and press Start', 'dim');
      $('now-line').innerHTML = span('idle', 'dim');
      $('last-line').innerHTML = span('no pass completed yet', 'dim');
      return;
    }
    const phaseTone = s.phase === 'crawling' ? 'warn' : s.phase === 'waiting' ? 'ok' : 'dim';
    $('target-line').innerHTML = span('Target  ', 'dim') + link(s.root, 'target')
      + span(`   pass #${s.pass}`, 'strong') + span(`   ${s.phase}`, phaseTone);

    const n = s.now;
    $('now-line').innerHTML = n
      ? [`${n.checked} checked`, `${n.inFlight} in flight`, `${n.queued} queued`, `${n.pages} pages`,
          `${n.requests} requests`].map((t) => span(t, '')).join('   ')
        + span(`   ${n.elapsed}`, 'dim') + (n.checking ? span(`   ${n.checking}`, 'dim') : '')
      : span('idle', 'dim');

    const l = s.last;
    $('last-line').innerHTML = l
      ? span(`#${l.number}  `, '') + span(`${l.ok} good`, 'ok strong')
        + span(`   ${l.broken} broken`, (l.broken ? 'bad' : 'ok') + ' strong')
        + span(`   ${l.blocked} unverified`, l.blocked ? 'warn' : 'ok')
        + span(`   ${l.slow} slow (≥ ${s.slowMillis}ms)`, l.slow ? 'slow' : 'ok')
        + span(`   ${l.pages} pages   ${l.requests} requests`, '')
        + span(`   took ${l.took}, finished ${l.finishedAt}`, 'dim')
      : span('no pass completed yet', 'dim');
  }

  function renderGauge(s) {
    const p = s.progress;
    const ratio = p ? Math.max(0, Math.min(1, p.ratio)) : 0;
    $('gauge').dataset.kind = s.phase === 'crawling' ? 'links' : 'next';
    $('gauge-fill').style.width = (ratio * 100).toFixed(1) + '%';
    $('gauge-label').textContent = p ? p.label : '';
    $('gauge').setAttribute('aria-valuenow', Math.round(ratio * 100));
  }

  function foundOn(r) {
    return r.referrer
      ? `<div class="found">on ${link(r.referrer, 'dim')}</div>`
      : '<div class="found dim">start URL</div>';
  }

  function badRow(r, columns) {
    const tone = r.broken ? 'bad' : 'warn';
    return `<li class="row-2">
      <div class="cells">
        ${columns ? '' : span(r.broken ? '✗' : '!', tone + ' strong mark')}
        ${span(r.status, tone + ' strong code')}
        ${columns ? span(r.broken ? 'broken' : 'unverified', tone + ' kind') : ''}
        ${link(r.url, 'url')}
        ${span(r.detail, tone + '-soft detail')}
        ${r.internal ? '' : span('external', 'dim tag')}
      </div>
      ${foundOn(r)}
    </li>`;
  }

  const more = (shown, total) => (total > shown ? [`<li class="empty dim">… ${total - shown} more</li>`] : []);

  function renderSummary(s) {
    const none = s.last ? 'All links OK' : 'None found so far';
    if (s.badTotal) {
      setTitle('broken-panel', `Broken links: ${s.brokenCount} broken, ${s.unverifiedCount} unverified`,
        s.brokenCount ? 'bad' : 'warn');
    } else {
      setTitle('broken-panel', 'Broken links', 'ok');
    }
    setRows('broken-panel', s.bad.map((r) => badRow(r, false)).concat(more(s.bad.length, s.badTotal)), none);

    setTitle('slow-panel', `Slow links (≥ ${s.slowMillis}ms): ${s.slowTotal}`, s.slowTotal ? 'slow' : 'ok');
    setRows('slow-panel', s.slow.map((r) => `<li><div class="cells">
        ${span(r.millis + 'ms', 'slow strong ms')}
        ${span(r.internal ? 'int' : 'ext', r.internal ? 'accent tag' : 'dim tag')}
        ${link(r.url, 'url')}</div></li>`).concat(more(s.slow.length, s.slowTotal)), 'None');

    const list = $('events-panel').querySelector('.rows');
    const atEnd = list.scrollHeight - list.scrollTop - list.clientHeight < 8;
    const tone = { PASS: 'accent', BROKEN: 'bad', ERROR: 'bad', UNVERIFIED: 'warn', RECOVERED: 'ok', SLOW: 'slow', GONE: 'dim' };
    setRows('events-panel', s.events.map((e) => `<li><div class="cells">
        ${span(e.time, 'dim')} ${span(e.kind, (tone[e.kind] || '') + ' strong kind')}
        ${span(e.message, e.kind === 'PASS' ? '' : 'muted')}</div></li>`), 'No events yet', 'dim');
    if (atEnd) list.scrollTop = list.scrollHeight;
  }

  function renderBrokenTab(s) {
    if (!s.badTotal) {
      setTitle('broken-tab', 'Broken links', 'ok');
      setRows('broken-tab', [], s.last ? 'All links OK' : 'None found so far');
      return;
    }
    const codes = s.codes.map((c) => `${c.count} × ${c.code}`).join(', ');
    setTitle('broken-tab', `${s.brokenCount} broken, ${s.unverifiedCount} unverified: ${codes}`,
      s.brokenCount ? 'bad' : 'warn');
    setRows('broken-tab', s.bad.map((r) => badRow(r, true)).concat(more(s.bad.length, s.badTotal)), '');
    markCursor('broken', s.bad.length);
  }

  function renderLatencyTab(s) {
    if (!s.pagesTotal) {
      setTitle('latency-tab', 'Pages by latency', 'dim');
      setRows('latency-tab', [], 'No pages checked yet', 'dim');
    } else {
      const l = s.latency;
      setTitle('latency-tab',
        `${s.pagesTotal} pages, slowest first: p50 ${l.p50}ms, p95 ${l.p95}ms, max ${l.max}ms`, 'accent');
      setRows('latency-tab', s.pages.map((r) => {
        const tone = r.bad ? 'bad' : r.millis >= s.slowMillis ? 'slow' : 'ok';
        return `<li><div class="cells">${span(r.millis + 'ms', tone + ' strong ms')}
          ${span(r.status, r.bad ? 'bad code' : 'dim code')} ${link(r.url, 'url')}</div></li>`;
      }).concat(more(s.pages.length, s.pagesTotal)), '');
      markCursor('latency', s.pages.length);
    }
    const peak = Math.max(1, ...s.histogram.map((b) => b.count));
    $('histogram').querySelector('.bars').innerHTML = s.histogram.map((b) => `
      <div class="bar-row">
        <span class="bar-label">${esc(b.label)}</span>
        <span class="bar-track"><span class="bar ${b.slow ? 'slow-bg' : 'accent-bg'}"
          style="width:${(b.count / peak * 100).toFixed(1)}%"></span></span>
        <span class="bar-count">${b.count}</span>
      </div>`).join('');
  }

  /** Highlights the cursor row of a list tab, clamped to the list, and keeps it in view. */
  function markCursor(tab, size, scroll = false) {
    const list = $(tab === 'broken' ? 'broken-tab' : 'latency-tab').querySelector('.rows');
    ui.cursor[tab] = Math.max(0, Math.min(ui.cursor[tab], size - 1));
    const row = list.children[ui.cursor[tab]];
    if (!row) return;
    row.classList.add('cursor');
    if (scroll) row.scrollIntoView({ block: 'nearest' });
  }

  function renderNotice(s) {
    const notice = $('cors-notice');
    if (s.hiddenPages) {
      notice.textContent = `${s.hiddenPages} page${s.hiddenPages === 1 ? '' : 's'} on the crawled site answered without `
        + `CORS headers, so the browser would not let uplink read ${s.hiddenPages === 1 ? 'its' : 'their'} links. `
        + 'Only pages on sites that allow '
        + 'cross-origin reads can be crawled from a browser; the uplink CLI has no such limit.';
      notice.hidden = false;
    } else if (s.hidden) {
      notice.textContent = `${s.hidden} link${s.hidden === 1 ? '' : 's'} to other sites answered, but without CORS `
        + 'headers, so their status is hidden from the browser; they count as good.';
      notice.hidden = false;
    } else {
      notice.hidden = true;
    }
  }

  function renderTabs(s) {
    const counts = { summary: '', broken: s.root ? ` (${s.badTotal})` : '', latency: s.root ? ` (${s.pagesTotal})` : '' };
    document.querySelectorAll('#tabs [role="tab"]').forEach((b, i) => {
      const t = b.dataset.tab;
      b.textContent = `${i + 1} ${t[0].toUpperCase()}${t.slice(1)}${counts[t]}`;
      b.setAttribute('aria-selected', String(t === ui.tab));
    });
    TABS.forEach((t) => { $('view-' + t).hidden = t !== ui.tab; });
  }

  function render() {
    const s = ui.state;
    if (!s) return;
    $('message').textContent = s.error || '';
    $('message').classList.toggle('bad', Boolean(s.error));
    $('start').textContent = s.running ? 'Restart' : 'Start';
    $('start').disabled = !ui.ready;
    $('stop').disabled = !s.running;
    $('state').textContent = s.state || 'idle';
    document.title = s.root ? `uplink ${s.root}` : 'uplink web';
    renderTabs(s);
    renderHeader(s);
    if (!s.root) {
      $('gauge').hidden = true;
      $('cors-notice').hidden = true;
      return;
    }
    $('gauge').hidden = false;
    renderGauge(s);
    renderNotice(s);
    // Only the visible tab is drawn; the next redraw catches up the others.
    if (ui.tab === 'summary') renderSummary(s);
    if (ui.tab === 'broken') renderBrokenTab(s);
    if (ui.tab === 'latency') renderLatencyTab(s);
    $('report-panel').hidden = !s.report;
    $('report').textContent = s.report || '';
  }

  // ---- calls from uplink-web.js ---------------------------------------------

  globalThis.uplinkRender = (json) => {
    try {
      ui.state = JSON.parse(json);
    } catch (e) {
      console.error('uplink: unreadable state', e);
      return;
    }
    render();
  };

  globalThis.uplinkReady = () => {
    ui.ready = true;
    $('start').disabled = false;
    $('start').textContent = 'Start';
    // Fills in the form only: a link must not make a visitor's browser start crawling on its own.
    const fromUrl = new URLSearchParams(location.search).get('url');
    if (fromUrl && !$('sites').value) {
      $('sites').value = fromUrl;
    }
  };

  globalThis.uplinkLoadFailed = () => {
    $('start').textContent = 'Unavailable';
    $('message').textContent = 'uplink-web.js could not be loaded. Build it with "make web" and serve this '
      + 'folder over HTTP (WebAssembly does not load from file://).';
    $('message').classList.add('bad');
  };

  // A browser without WebAssembly GC never calls uplinkReady.
  setTimeout(() => {
    if (!ui.ready && !$('message').textContent) {
      $('message').textContent = 'uplink is taking long to start. It needs a browser with WebAssembly GC '
        + '(Chrome or Edge 119+, Firefox 120+, Safari 18.2+); check the console for errors.';
      $('message').classList.add('warn');
    }
  }, 15000);

  // ---- input -----------------------------------------------------------------

  function start() {
    if (!ui.ready) return;
    const params = new URLSearchParams(new FormData($('start-form'))).toString();
    ui.cursor = { broken: 0, latency: 0 };
    globalThis.uplinkStart(params);
  }

  $('start-form').addEventListener('submit', (e) => {
    e.preventDefault();
    start();
  });
  $('stop').addEventListener('click', () => globalThis.uplinkStop && globalThis.uplinkStop());

  $('download').addEventListener('click', () => {
    const blob = new Blob([$('report').textContent], { type: 'text/plain' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'uplink-report.txt';
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  });

  function selectTab(tab) {
    ui.tab = tab;
    render();
    if (!ui.state || !ui.state.root) renderTabs({});
  }

  document.querySelectorAll('#tabs [role="tab"]').forEach((b) => b.addEventListener('click', () => selectTab(b.dataset.tab)));

  document.addEventListener('keydown', (e) => {
    const typing = e.target.closest('input, textarea, select');
    if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
    const i = TABS.indexOf(ui.tab);
    if (e.key >= '1' && e.key <= '3') {
      selectTab(TABS[Number(e.key) - 1]);
    } else if (e.key === 'ArrowRight') {
      selectTab(TABS[(i + 1) % TABS.length]);
    } else if (e.key === 'ArrowLeft') {
      selectTab(TABS[(i + TABS.length - 1) % TABS.length]);
    } else if (ui.tab !== 'summary') {
      const step = { ArrowUp: -1, ArrowDown: 1, PageUp: -PAGE_ROWS, PageDown: PAGE_ROWS }[e.key];
      const c = ui.cursor;
      if (step !== undefined) c[ui.tab] = Math.max(0, c[ui.tab] + step);
      else if (e.key === 'Home') c[ui.tab] = 0;
      else if (e.key === 'End') c[ui.tab] = Number.MAX_SAFE_INTEGER;
      else return;
      render();
      const s = ui.state;
      if (s && s.root) markCursor(ui.tab, ui.tab === 'broken' ? s.bad.length : s.pages.length, true);
    } else {
      return;
    }
    e.preventDefault();
  });

  renderTabs({});
})();
