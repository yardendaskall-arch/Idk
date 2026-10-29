/* Penny Mogul — canvas charts (price/area chart with touch scrubbing, sparkline) and an SVG donut. */
(function () {
  'use strict';
  const PM = (globalThis.PM = globalThis.PM || {});
  const html = (PM.html = htm.bind(preact.h));
  const { useRef, useEffect, useLayoutEffect, useState } = preactHooks;

  const UP = '#2DE2A6';
  const DOWN = '#FF5C7C';
  PM.COLORS = { up: UP, down: DOWN };

  function rgba(hex, a) {
    const n = parseInt(hex.slice(1), 16);
    return 'rgba(' + (n >> 16) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')';
  }

  function bounds(data, pad) {
    let lo = Infinity, hi = -Infinity;
    for (let i = 0; i < data.length; i++) { const v = data[i]; if (v < lo) lo = v; if (v > hi) hi = v; }
    if (!(hi > lo)) { const m = Math.abs(hi) * 0.01 || 1; lo -= m; hi += m; }
    const span = hi - lo;
    return { lo: lo - span * pad, hi: hi + span * pad, rawLo: lo, rawHi: hi };
  }

  function draw(cv, W, H, data, o, scrubIdx) {
    const dpr = Math.min(3, window.devicePixelRatio || 1);
    if (cv.width !== Math.round(W * dpr) || cv.height !== Math.round(H * dpr)) {
      cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
    }
    const ctx = cv.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, W, H);
    const n = data.length;
    if (n < 2) return;

    const up = data[n - 1] >= data[0];
    const color = o.color || (up ? UP : DOWN);
    const padTop = o.spark ? 3 : 14, padBot = o.spark ? 3 : 12;
    const b = bounds(data, o.spark ? 0.02 : 0.06);
    const X = (i) => (i / (n - 1)) * W;
    const Y = (v) => padTop + (1 - (v - b.lo) / (b.hi - b.lo)) * (H - padTop - padBot);

    if (!o.spark) {
      ctx.lineWidth = 1;
      ctx.strokeStyle = 'rgba(255,255,255,0.045)';
      ctx.beginPath();
      for (let k = 1; k <= 3; k++) { const y = Math.round(padTop + ((H - padTop - padBot) * k) / 4) + 0.5; ctx.moveTo(0, y); ctx.lineTo(W, y); }
      ctx.stroke();
      if (o.base != null && o.base > b.lo && o.base < b.hi) {
        ctx.setLineDash([3, 5]);
        ctx.strokeStyle = 'rgba(255,255,255,0.16)';
        ctx.beginPath(); ctx.moveTo(0, Y(o.base)); ctx.lineTo(W, Y(o.base)); ctx.stroke();
        ctx.setLineDash([]);
      }
    }

    // smooth line via midpoints
    const px = new Array(n), py = new Array(n);
    for (let i = 0; i < n; i++) { px[i] = X(i); py[i] = Y(data[i]); }
    ctx.beginPath();
    ctx.moveTo(px[0], py[0]);
    for (let i = 1; i < n - 1; i++) ctx.quadraticCurveTo(px[i], py[i], (px[i] + px[i + 1]) / 2, (py[i] + py[i + 1]) / 2);
    ctx.lineTo(px[n - 1], py[n - 1]);

    ctx.lineJoin = 'round'; ctx.lineCap = 'round';
    if (o.fill !== false) {
      ctx.save();
      ctx.lineTo(W, H); ctx.lineTo(0, H); ctx.closePath();
      const g = ctx.createLinearGradient(0, padTop, 0, H);
      g.addColorStop(0, rgba(color, o.spark ? 0.22 : 0.3));
      g.addColorStop(1, rgba(color, 0));
      ctx.fillStyle = g; ctx.fill();
      ctx.restore();
      // redraw the stroke path (fill consumed it)
      ctx.beginPath();
      ctx.moveTo(px[0], py[0]);
      for (let i = 1; i < n - 1; i++) ctx.quadraticCurveTo(px[i], py[i], (px[i] + px[i + 1]) / 2, (py[i] + py[i + 1]) / 2);
      ctx.lineTo(px[n - 1], py[n - 1]);
    }
    ctx.strokeStyle = color;
    ctx.lineWidth = o.spark ? 1.6 : 2.2;
    if (!o.spark) { ctx.shadowColor = rgba(color, 0.55); ctx.shadowBlur = 8; }
    ctx.stroke();
    ctx.shadowBlur = 0;

    if (!o.spark) {
      ctx.fillStyle = 'rgba(160,172,205,0.7)';
      ctx.font = '600 10px system-ui, sans-serif';
      ctx.textAlign = 'left';
      ctx.textBaseline = 'top';
      if (o.label) {
        ctx.fillText(o.label(b.rawHi), 2, 1);
        ctx.textBaseline = 'bottom';
        ctx.fillText(o.label(b.rawLo), 2, H - 1);
      }
    }

    // end dot
    const ex = px[n - 1], ey = py[n - 1];
    if (scrubIdx == null) {
      const r = o.spark ? 2.2 : 4;
      if (!o.spark) { ctx.fillStyle = rgba(color, 0.22); ctx.beginPath(); ctx.arc(Math.min(ex, W - 6), ey, 9, 0, 6.3); ctx.fill(); }
      ctx.fillStyle = color; ctx.beginPath(); ctx.arc(Math.min(ex, W - (o.spark ? 3 : 6)), ey, r, 0, 6.3); ctx.fill();
    } else {
      const i = Math.max(0, Math.min(n - 1, scrubIdx));
      ctx.strokeStyle = 'rgba(255,255,255,0.35)'; ctx.lineWidth = 1;
      ctx.beginPath(); ctx.moveTo(px[i], 0); ctx.lineTo(px[i], H); ctx.stroke();
      ctx.fillStyle = rgba(color, 0.25); ctx.beginPath(); ctx.arc(px[i], py[i], 10, 0, 6.3); ctx.fill();
      ctx.fillStyle = '#fff'; ctx.beginPath(); ctx.arc(px[i], py[i], 4.5, 0, 6.3); ctx.fill();
      ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.stroke();
    }
  }

  /** Price / net-worth chart. `onScrub({index, value} | null)` fires while the user drags across it. */
  function PriceChart(props) {
    const { data, height = 190, spark, color, base, fill, label, onScrub } = props;
    const wrap = useRef(null);
    const cv = useRef(null);
    const [w, setW] = useState(0);
    const [scrub, setScrub] = useState(null);

    useLayoutEffect(() => {
      const el = wrap.current;
      const measure = () => setW((prev) => (Math.abs(prev - el.clientWidth) > 0.5 ? el.clientWidth : prev));
      measure();
      if (window.ResizeObserver) {
        const ro = new ResizeObserver(measure);
        ro.observe(el);
        return () => ro.disconnect();
      }
      window.addEventListener('resize', measure);
      return () => window.removeEventListener('resize', measure);
    }, []);

    useEffect(() => {
      if (cv.current && w > 0) draw(cv.current, w, height, data, { spark, color, base, fill, label }, scrub);
    });

    const move = (e) => {
      if (!onScrub || !data.length) return;
      const r = wrap.current.getBoundingClientRect();
      const x = Math.max(0, Math.min(r.width, e.clientX - r.left));
      const index = Math.round((x / r.width) * (data.length - 1));
      setScrub(index);
      onScrub({ index, value: data[index] });
    };
    const end = () => { if (scrub != null) { setScrub(null); onScrub && onScrub(null); } };

    return html`<div class="chart-wrap" ref=${wrap} style=${{ height: height + 'px' }}
        onPointerDown=${onScrub ? move : null} onPointerMove=${onScrub && scrub != null ? move : null}
        onPointerUp=${end} onPointerCancel=${end} onPointerLeave=${end}>
      <canvas ref=${cv} style=${{ width: w + 'px', height: height + 'px', touchAction: onScrub ? 'pan-y' : 'auto' }}></canvas>
    </div>`;
  }

  function Sparkline(props) {
    return html`<${PriceChart} data=${props.data} height=${props.height || 34} spark=${true} color=${props.color} />`;
  }

  /** Allocation donut. segments: [{value, color}] ; children render in the centre. */
  function Donut({ segments, size = 168, thickness = 15, children }) {
    const total = segments.reduce((a, s) => a + Math.max(0, s.value), 0);
    const r = 50 - thickness / 2;
    const C = 2 * Math.PI * r;
    let acc = 0;
    return html`<div class="donut" style=${{ width: size + 'px', height: size + 'px' }}>
      <svg viewBox="0 0 100 100" width=${size} height=${size}>
        <circle cx="50" cy="50" r=${r} fill="none" stroke="rgba(255,255,255,0.06)" stroke-width=${thickness} />
        ${total > 0 && segments.map((s) => {
          const frac = Math.max(0, s.value) / total;
          const len = Math.max(0, frac * C - (frac < 0.999 ? 1.6 : 0));
          const el = html`<circle cx="50" cy="50" r=${r} fill="none" stroke=${s.color} stroke-width=${thickness}
            stroke-linecap="butt" stroke-dasharray=${len + ' ' + (C - len)} stroke-dashoffset=${-acc * C}
            transform="rotate(-90 50 50)" style="transition: stroke-dasharray .5s, stroke-dashoffset .5s" />`;
          acc += frac;
          return el;
        })}
      </svg>
      <div class="donut-center">${children}</div>
    </div>`;
  }

  PM.Charts = { PriceChart, Sparkline, Donut };
})();
