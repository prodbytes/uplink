// uplink-web front end. The checking runs in uplink-web.js (Java compiled to
// WebAssembly by GraalVM Web Image), which calls uplinkRender(json) with the state
// of the run a few times a second; this file only draws it and handles input, like
// the TUI dashboard's view and key bindings.
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const TABS = ['summary', 'broken', 'latency', 'uptime', 'reports'];
  /** Pass reports kept, newest first; each holds a blob URL until dropped. */
  const MAX_REPORTS = 50;
  const PAGE_ROWS = 10;
  const ui = { tab: 'summary', cursor: { broken: 0, latency: 0 }, state: null, ready: false,
    uptimeRows: null, reports: [] };

  // ---- address list: every address started on, sorted and unique, kept in this browser ----

  const STORE = 'uplink.addresses';
  let addresses = loadAddresses();

  function loadAddresses() {
    try {
      const saved = JSON.parse(localStorage.getItem(STORE) || '[]');
      return Array.isArray(saved) ? saved.filter((a) => typeof a === 'string' && /^https?:\/\//.test(a)) : [];
    } catch {
      return [];
    }
  }

  function saveAddresses() {
    try {
      localStorage.setItem(STORE, JSON.stringify(addresses));
    } catch {
      // Private windows may refuse storage; the list then lasts until the page closes.
    }
  }

  /** An http(s) address as uplink normalizes it (lowercase host, no default port or fragment), or null. */
  function normalizeAddress(raw) {
    try {
      const url = new URL(raw.includes('://') ? raw : 'https://' + raw);
      if (!/^https?:$/.test(url.protocol) || !url.hostname) return null;
      url.hash = '';
      return url.href;
    } catch {
      return null;
    }
  }

  /** Adds each address in {@code text} (separated by , ; or spaces); returns the ones that are not http(s) URLs. */
  function addAddresses(text) {
    const rejected = [];
    for (const part of text.split(/[,;\s]+/).filter(Boolean)) {
      const address = normalizeAddress(part);
      if (address) addresses.push(address);
      else rejected.push(part);
    }
    addresses = [...new Set(addresses)].sort();
    saveAddresses();
    renderAddresses();
    return rejected;
  }

  function renderAddresses() {
    $('addresses').innerHTML = addresses.map((a) => `<li>${link(a)}
      <button type="button" class="remove" data-address="${esc(a)}" aria-label="Remove ${esc(a)}">×</button></li>`).join('');
  }

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
    const more = s.siteCount > 1 ? span(` +${s.siteCount - 1} more`, 'accent') : '';
    $('target-line').innerHTML = span(s.siteCount > 1 ? 'Targets ' : 'Target  ', 'dim') + link(s.root, 'target') + more
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

  function renderUptimeTab(s) {
    const sites = s.uptime;
    if (!sites.length) {
      setTitle('uptime-tab', 'Uptime', 'dim');
      setRows('uptime-tab', [], 'No checks yet', 'dim');
      ui.uptimeRows = null;
      return;
    }
    const down = sites.filter((u) => u.state === 'down').length;
    const unverified = sites.filter((u) => u.state === 'unverified').length;
    setTitle('uptime-tab', `Uptime of ${sites.length} address${sites.length === 1 ? '' : 'es'}: `
      + `${sites.length - down - unverified} up, ${down} down, ${unverified} unverified`,
      down ? 'bad' : unverified ? 'warn' : 'ok');
    const rows = sites.map((u) => {
      const tone = { up: 'ok', down: 'bad', unverified: 'warn' }[u.state];
      const ratio = u.ratio === null ? '—' : (Math.floor(u.ratio * 1000) / 10).toFixed(1) + '%';
      const ratioTone = u.ratio === null ? 'dim' : u.ratio === 1 ? 'ok' : u.ratio >= 0.99 ? 'warn' : 'bad';
      return `<li class="uptime-row"><div class="cells">
          ${span(u.state, tone + ' strong kind')} ${span(ratio, ratioTone + ' strong pct')}
          ${span(u.status, tone + ' code')} ${span(u.millis + 'ms', 'ms')} ${link(u.url, 'url')}
          ${span(`${u.checks} check${u.checks === 1 ? '' : 's'}`, 'dim tag')}</div>
        <div class="timeline" role="img" aria-label="${esc(`${u.up} up, ${u.down} down, ${u.unverified} unverified`)}">
          ${timeline(u.timeline, s.slowMillis)}</div></li>`;
    }).join('');
    // Redrawn only when a check came in, so a bar's tooltip stays open between checks.
    if (ui.uptimeRows !== rows) {
      $('uptime-tab').querySelector('.rows').innerHTML = rows;
      ui.uptimeRows = rows;
    }
  }

  /** The checks of one address as bars: colour by outcome, height by response time up to the slow threshold. */
  function timeline(checks, slowMillis) {
    return checks.map((c) => {
      const tone = c.state === 'down' ? 'bad' : c.state === 'unverified' ? 'warn'
        : c.millis >= slowMillis ? 'slow' : 'ok';
      const height = c.state === 'up' ? Math.max(15, Math.min(100, c.millis / slowMillis * 100)) : 100;
      const tip = `pass #${c.pass} at ${c.time}: ${c.state} ${c.status} in ${c.millis}ms${c.detail ? ' (' + c.detail + ')' : ''}`;
      return `<span class="tick ${tone}-bg" style="height:${height.toFixed(0)}%" title="${esc(tip)}"></span>`;
    }).join('');
  }

  /** The pass reports, drawn only when one arrives so an open preview stays open. */
  function renderReportsTab() {
    const reports = ui.reports;
    setTitle('reports-tab', reports.length ? `Pass reports: ${reports.length}` : 'Pass reports',
      reports.length ? 'accent' : 'dim');
    setRows('reports-tab', reports.map((r) => {
      const tone = r.broken ? 'bad' : r.unverified ? 'warn' : 'ok';
      return `<li class="report-row"><div class="cells">
          ${span(`#${r.pass}`, 'strong kind')} ${span(r.finishedAt, 'dim')}
          ${span(`${r.broken} broken`, (r.broken ? 'bad' : 'ok') + ' strong')}
          ${span(`${r.unverified} unverified`, r.unverified ? 'warn' : 'ok')}
          ${span(`${r.good} good`, 'ok')} ${span(`${r.slow} slow`, r.slow ? 'slow' : 'dim')}
          ${span(r.sites, 'dim url')}
          <a class="download ${tone}" href="${esc(r.href)}" download="${esc(r.file)}">Download</a></div>
        <details class="preview"><summary class="dim">Show report</summary><pre>${esc(r.text)}</pre></details></li>`;
    }), 'No pass completed yet', 'dim');
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

  /** A notice: a title, a sentence on why, an optional fix and an optional list, each on its own line. */
  const noticeHtml = ({ title, why, fix = '', items = '' }) =>
    `<p class="notice-title"><span aria-hidden="true">⚠</span> ${title}</p>`
    + `<p class="notice-why">${why}</p>`
    + (fix ? `<p class="notice-fix"><span class="notice-label">Fix</span> ${fix}</p>` : '')
    + (items ? `<ul class="notice-list">${items}</ul>` : '');

  function renderNotice(s) {
    const notice = $('cors-notice');
    const header = '<code>Access-Control-Allow-Origin</code>';
    if (s.hiddenPages) {
      const one = s.hiddenPages === 1;
      const rest = s.hiddenPages - s.hiddenExamples.length;
      notice.innerHTML = noticeHtml({
        title: `${s.hiddenPages} page${one ? '' : 's'} could not be crawled from the browser`,
        why: `${one ? 'It' : 'They'} answered without CORS headers (no ${header}), so the browser won't let uplink `
          + `read ${one ? 'it' : 'them'}, and ${one ? 'its' : 'their'} links weren't followed.`,
        fix: `send ${header} on ${one ? 'that page' : 'those pages'}, or crawl with the uplink CLI, which has no such limit.`,
        items: s.hiddenExamples.map((p) => `<li>${link(p.url)}</li>`).join('')
          + (rest > 0 ? `<li class="dim">… and ${rest} more, listed in the browser console</li>` : ''),
      });
      notice.hidden = false;
    } else if (s.hidden) {
      const one = s.hidden === 1;
      notice.innerHTML = noticeHtml({
        title: `${s.hidden} link${one ? '' : 's'} to other sites can't be checked from the browser`,
        why: `${one ? 'It' : 'They'} answered without CORS headers, so the browser hides ${one ? 'its' : 'their'} `
          + `status; ${one ? 'it counts' : 'they count'} as good.`,
      });
      notice.hidden = false;
    } else {
      notice.hidden = true;
    }
  }

  function renderTabs(s) {
    const counts = { summary: '', broken: s.root ? ` (${s.badTotal})` : '', latency: s.root ? ` (${s.pagesTotal})` : '',
      uptime: '', reports: ui.reports.length ? ` (${ui.reports.length})` : '' };
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
    if (s.error) showMessage(s.error, 'bad');
    else if ($('message').classList.contains('bad') && s.running) showMessage('');
    $('start').textContent = s.running ? 'Restart' : 'Start';
    $('start').disabled = !ui.ready;
    $('stop').disabled = !s.running;
    $('state').textContent = s.state || 'idle';
    document.title = s.root ? `uplink ${s.root}${s.siteCount > 1 ? ` +${s.siteCount - 1}` : ''}` : 'uplink web';
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
    if (ui.tab === 'uptime') renderUptimeTab(s);
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

  globalThis.uplinkPassReport = (json) => {
    let r;
    try {
      r = JSON.parse(json);
    } catch (e) {
      console.error('uplink: unreadable pass report', e);
      return;
    }
    r.href = URL.createObjectURL(new Blob([r.text], { type: 'text/plain;charset=utf-8' }));
    r.file = `uplink-pass-${r.pass}-${r.finishedAt.replace(/\D/g, '')}.txt`;
    ui.reports.unshift(r);
    ui.reports.splice(MAX_REPORTS).forEach((old) => URL.revokeObjectURL(old.href));
    renderReportsTab();
    if (ui.state) renderTabs(ui.state);
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

  /** Checks every address in the list, restarting a running check. */
  function start() {
    if (!ui.ready) return;
    const form = new FormData($('start-form'));
    form.set('sites', addresses.join(','));
    ui.cursor = { broken: 0, latency: 0 };
    globalThis.uplinkStart(new URLSearchParams(form).toString());
  }

  function showMessage(text, tone) {
    $('message').textContent = text;
    $('message').className = 'message ' + (tone || '');
  }

  $('start-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const rejected = addAddresses($('sites').value);
    if (rejected.length) {
      // The valid addresses are already in the list; leave only the ones to fix.
      $('sites').value = rejected.join(' ');
      showMessage(`Not an http(s) address: ${rejected.join(', ')}`, 'bad');
      return;
    }
    if (!addresses.length) {
      showMessage('Add an address to check, such as https://example.com', 'bad');
      return;
    }
    $('sites').value = '';
    start();
  });

  // Removing an address restarts a running check without it; an empty list stops it.
  $('addresses').addEventListener('click', (e) => {
    const button = e.target.closest('button.remove');
    if (!button) return;
    addresses = addresses.filter((a) => a !== button.dataset.address);
    saveAddresses();
    renderAddresses();
    if (ui.state && ui.state.running) {
      if (addresses.length) start();
      else globalThis.uplinkStop();
    }
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
    if (tab === 'reports') renderReportsTab();
    if (!ui.state || !ui.state.root) renderTabs({});
  }

  document.querySelectorAll('#tabs [role="tab"]').forEach((b) => b.addEventListener('click', () => selectTab(b.dataset.tab)));

  document.addEventListener('keydown', (e) => {
    const typing = e.target.closest('input, textarea, select');
    if (typing || e.ctrlKey || e.metaKey || e.altKey) return;
    const i = TABS.indexOf(ui.tab);
    if (e.key >= '1' && e.key <= String(TABS.length)) {
      selectTab(TABS[Number(e.key) - 1]);
    } else if (e.key === 'ArrowRight') {
      selectTab(TABS[(i + 1) % TABS.length]);
    } else if (e.key === 'ArrowLeft') {
      selectTab(TABS[(i + TABS.length - 1) % TABS.length]);
    } else if (ui.tab === 'broken' || ui.tab === 'latency') {
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

  renderAddresses();
  renderReportsTab();
  renderTabs({});
})();
