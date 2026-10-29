/* Penny Mogul — UI core: navigation store, toasts, icons and shared widgets. */
(function () {
  'use strict';
  const PM = globalThis.PM;
  const html = PM.html;
  const { useRef, useEffect, useLayoutEffect, useState } = preactHooks;
  const { fmt } = PM;

  /* ----------------------------------------------------------- UI store */
  const ui = (PM.ui = {
    tab: 'earn',
    stack: [],      // overlays: {type, closing, ...props}
    toasts: [],
    listTf: '10m',
    filter: 'all',
    stockTf: '10m',
    netTf: '1h',
    unreadNews: false,
    render() {},    // replaced by <App/>

    setTab(t) {
      if (ui.tab === t) return;
      ui.tab = t;
      if (t === 'market') ui.unreadNews = false;
      PM.sfx.pop(); PM.haptic(8);
      ui.render();
    },
    push(o) { ui.stack.push(o); ui.render(); return o; },
    replace(item, o) {
      const i = ui.stack.indexOf(item);
      if (i >= 0) ui.stack.splice(i, 1, o); else ui.stack.push(o);
      ui.render();
    },
    pop() {
      const top = ui.stack[ui.stack.length - 1];
      if (!top || top.closing) return false;
      top.closing = true;
      ui.render();
      setTimeout(() => {
        const i = ui.stack.indexOf(top);
        if (i >= 0) ui.stack.splice(i, 1);
        ui.render();
      }, 210);
      return true;
    },
    confirm(o) { ui.push(Object.assign({ type: 'confirm' }, o)); },

    toast(o) {
      const t = Object.assign({ id: ++ui._tid, ms: 3600 }, o);
      ui.toasts.push(t);
      if (ui.toasts.length > 2) ui.toasts.shift();
      setTimeout(() => dismiss(t), t.ms);
      ui.render();
    },
    _tid: 0,
  });
  function dismiss(t) {
    if (!ui.toasts.includes(t)) return;
    t.out = true; ui.render();
    setTimeout(() => { const i = ui.toasts.indexOf(t); if (i >= 0) ui.toasts.splice(i, 1); ui.render(); }, 260);
  }

  // Android back button
  window.__pmBack = function () {
    if (ui.stack.length) { ui.pop(); return true; }
    if (ui.tab !== 'earn') { ui.setTab('earn'); return true; }
    return false;
  };

  /* -------------------------------------------------------------- icons */
  const ICONS = {
    coin: '<circle cx="12" cy="12" r="9"/><path d="M14.8 9.3c-.4-1-1.5-1.6-2.8-1.6-1.6 0-2.8.8-2.8 2 0 3 5.6 1.4 5.6 4.4 0 1.2-1.3 2.1-2.9 2.1-1.4 0-2.6-.6-3-1.7M12 5.6v2m0 8.8v2"/>',
    chart: '<path d="M3 3v18h18"/><path d="M7 15l4-5 3 3 5-7"/>',
    pie: '<path d="M21 12A9 9 0 1 1 12 3v9z"/><path d="M15.5 3.4A9 9 0 0 1 20.6 8.5H15.5z"/>',
    factory: '<path d="M3 21V9l6 4V9l6 4V4h6v17z"/><path d="M7 17h1.5M12 17h1.5M17 17h1.5"/>',
    grid: '<rect x="4" y="4" width="7" height="7" rx="2"/><rect x="13" y="4" width="7" height="7" rx="2"/><rect x="4" y="13" width="7" height="7" rx="2"/><rect x="13" y="13" width="7" height="7" rx="2"/>',
    back: '<path d="M15 5l-7 7 7 7"/>',
    x: '<path d="M6 6l12 12M18 6L6 18"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    dice: '<rect x="4" y="4" width="16" height="16" rx="4"/><g fill="currentColor" stroke="none"><circle cx="9" cy="9" r="1.3"/><circle cx="15" cy="15" r="1.3"/><circle cx="15" cy="9" r="1.3"/><circle cx="9" cy="15" r="1.3"/></g>',
    lock: '<rect x="5" y="11" width="14" height="9" rx="3"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
    chevron: '<path d="M9 5l7 7-7 7"/>',
    check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  };
  function Icon({ name, size }) {
    return html`<svg viewBox="0 0 24 24" width=${size || 24} height=${size || 24} fill="none" stroke="currentColor" stroke-width="2"
      stroke-linecap="round" stroke-linejoin="round" dangerouslySetInnerHTML=${{ __html: ICONS[name] || '' }}></svg>`;
  }

  /* ---------------------------------------------------------- widgets */
  /** Number that eases to its new value. `value` is a number, `format` turns it into text. */
  function AnimNum({ value, format, ms }) {
    const el = useRef(null);
    const st = useRef({ cur: value, from: value, to: value, t0: 0, raf: 0, first: true });
    const f = format || fmt.money;
    useLayoutEffect(() => {
      const s = st.current;
      if (s.first) { s.first = false; el.current.textContent = f(value); return; }
      s.from = s.cur; s.to = value; s.t0 = performance.now();
      cancelAnimationFrame(s.raf);
      const dur = ms || 420;
      const step = (now) => {
        const k = Math.min(1, (now - s.t0) / dur);
        s.cur = s.from + (s.to - s.from) * (1 - Math.pow(1 - k, 3));
        if (el.current) el.current.textContent = f(s.cur);
        if (k < 1) s.raf = requestAnimationFrame(step); else { s.cur = s.to; }
      };
      s.raf = requestAnimationFrame(step);
      return () => cancelAnimationFrame(s.raf);
    }, [value]);
    return html`<span ref=${el}></span>`;
  }

  function Change({ pct, abs, small }) {
    const ref = pct != null ? pct : abs; // with no % (tiny baseline) colour by the absolute change
    const dir = ref > 0.00004 ? 'up' : ref < -0.00004 ? 'down' : 'flat';
    const absTxt = abs != null ? fmt.money(Math.abs(abs), { compact: true }) : '';
    const pctTxt = pct != null ? fmt.pct(pct).replace(/^[+-]/, '') : '';
    return html`<span class=${'pill ' + dir} style=${small ? { padding: '2px 7px', fontSize: '12px' } : null}>
      ${dir === 'up' ? '▲' : dir === 'down' ? '▼' : '•'} ${absTxt}${absTxt && pctTxt ? ' · ' : ''}${pctTxt}
    </span>`;
  }

  function StockBadge({ d, size }) {
    return html`<div class=${'badge ' + (size || '')} style=${{ '--c1': d.color, '--c2': shade(d.color) }}>${d.icon}</div>`;
  }
  function IndBadge({ ind, size }) {
    return html`<div class=${'badge sq ' + (size || '')} style=${{ '--c1': ind.color, '--c2': shade(ind.color, -45) }}>${ind.icon}</div>`;
  }
  function shade(hex, amt) {
    amt = amt == null ? 40 : amt;
    const n = parseInt(hex.slice(1), 16);
    const c = (v) => Math.max(0, Math.min(255, v + amt));
    return '#' + ((1 << 24) | (c(n >> 16) << 16) | (c((n >> 8) & 255) << 8) | c(n & 255)).toString(16).slice(1);
  }

  function Switch({ on, onChange }) {
    return html`<button class=${'switch' + (on ? ' on' : '')} onClick=${() => onChange(!on)} aria-pressed=${on}></button>`;
  }

  /** Bottom sheet with drag-to-dismiss on the handle. */
  function Sheet({ item, children }) {
    const ref = useRef(null);
    const drag = useRef(null);
    const down = (e) => { drag.current = { y: e.clientY, dy: 0 }; e.currentTarget.setPointerCapture(e.pointerId); };
    const move = (e) => {
      if (!drag.current) return;
      const dy = Math.max(0, e.clientY - drag.current.y);
      drag.current.dy = dy;
      ref.current.style.transition = 'none';
      ref.current.style.transform = 'translateY(' + dy + 'px)';
    };
    const up = () => {
      if (!drag.current) return;
      const dy = drag.current.dy;
      drag.current = null;
      ref.current.style.transition = '';
      if (dy > 110) ui.pop(); else ref.current.style.transform = '';
    };
    return html`<div class=${'backdrop' + (item.closing ? ' closing' : '')} onClick=${() => ui.pop()}></div>
      <div class=${'sheet' + (item.closing ? ' closing' : '')} ref=${ref}>
        <div style="padding:2px 0 0;touch-action:none;flex:none" onPointerDown=${down} onPointerMove=${move} onPointerUp=${up} onPointerCancel=${up}>
          <div class="sheet-handle"></div>
        </div>
        ${children}
      </div>`;
  }

  function Modal({ item, children }) {
    return html`<div class=${'backdrop' + (item.closing ? ' closing' : '')} style="z-index:60" onClick=${item.sticky ? null : () => ui.pop()}></div>
      <div class=${'modal' + (item.closing ? ' closing' : '')}>${children}</div>`;
  }

  function ConfirmModal({ item }) {
    return html`<${Modal} item=${item}>
      <div class="modal-emoji">${item.emoji || '🤔'}</div>
      <h2>${item.title}</h2>
      <p class="lead">${item.text}</p>
      <div class="col gap8">
        <button class=${'btn block ' + (item.danger ? 'btn-down' : 'btn-primary')} onClick=${() => { ui.pop(); item.onYes && item.onYes(); }}>${item.yes || 'Confirm'}</button>
        <button class="btn block btn-ghost" onClick=${() => ui.pop()}>${item.no || 'Cancel'}</button>
      </div>
    <//>`;
  }

  function ToastLayer() {
    return html`<div class=${'toasts' + (ui.stack.length ? ' passive' : '')}>${ui.toasts.map((t) => html`
      <button key=${t.id} class=${'toast ' + (t.kind || '') + (t.out ? ' out' : '')}
        onClick=${() => { dismiss(t); t.onTap && t.onTap(); }}>
        <span class="ico">${t.icon}</span>
        <span class="grow"><div class="t">${t.title}</div>${t.sub ? html`<div class="s">${t.sub}</div>` : null}</span>
      </button>`)}</div>`;
  }

  PM.UI = { Icon, AnimNum, Change, StockBadge, IndBadge, Switch, Sheet, Modal, ConfirmModal, ToastLayer, shade, dismiss };
})();
