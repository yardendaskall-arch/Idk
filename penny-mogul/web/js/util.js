/* Penny Mogul — formatting, sound, haptics and screen effects. */
(function () {
  'use strict';
  const PM = (globalThis.PM = globalThis.PM || {});

  /* ------------------------------------------------------------ formatting */
  const SUFFIX = ['', 'K', 'M', 'B', 'T', 'Qa', 'Qi', 'Sx', 'Sp'];
  const commas = (n, d) => n.toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d });

  function compact(n, digits) {
    let i = 0;
    while (Math.abs(n) >= 1000 && i < SUFFIX.length - 1) { n /= 1000; i++; }
    let d = i === 0 ? digits : Math.abs(n) >= 100 ? 0 : Math.abs(n) >= 10 ? 1 : 2;
    if (i > 0 && i < SUFFIX.length - 1 && Math.abs(Number(n.toFixed(d))) >= 1000) { // 999.9K rounds up to 1000K -> 1.00M
      n /= 1000; i++;
      d = Math.abs(n) >= 100 ? 0 : Math.abs(n) >= 10 ? 1 : 2;
    }
    return (i === 0 ? commas(n, d) : n.toFixed(d)) + SUFFIX[i];
  }

  const trim = (s) => s.replace(/(\.\d*?)0+(?=[A-Za-z]*$)/, '$1').replace(/\.(?=[A-Za-z]*$)/, ''); // 1.20K -> 1.2K, 15.0M -> 15M

  const fmt = (PM.fmt = {
    /** Money. <$1K shows cents, <$1M whole dollars with commas (or 12.3K when compact), above that 1.23M style. */
    money(n, o) {
      o = o || {};
      if (!isFinite(n)) return '$—';
      const a = Math.abs(n);
      let s;
      if (a < 1000) s = o.compact && a % 1 === 0 ? String(a) : commas(a, 2);
      else if (a < 1e6) s = o.compact ? trim(compact(a, 2)) : commas(a, 0);
      else s = o.compact ? trim(compact(a, 2)) : compact(a, 2);
      return (n < 0 ? '-' : o.sign ? '+' : '') + '$' + s;
    },
    price(n) { return '$' + commas(n, 2); },
    pct(x, o) {
      const v = x * 100;
      const a = Math.abs(v);
      const s = a >= 100 ? a.toFixed(0) : a >= 10 ? a.toFixed(1) : a.toFixed(2);
      return (v > 0.004 ? '+' : v < -0.004 ? '-' : '') + s + '%';
    },
    num(n) { // shares / units
      const a = Math.abs(n);
      if (a >= 1e6) return compact(n, 2);
      if (a >= 1000) return commas(n, 0);
      if (a >= 100) return n.toFixed(1);
      if (a >= 10) return n.toFixed(2);
      return n.toFixed(a < 1 ? 4 : 3);
    },
    units(n) {
      const a = Math.abs(n);
      if (a >= 1000) return compact(n, 0);
      if (a >= 100) return n.toFixed(0);
      if (a >= 10) return n.toFixed(1);
      return n.toFixed(2);
    },
    rate(n) { return fmt.money(n, { compact: true }) + '/s'; },
    dur(sec) {
      sec = Math.max(0, Math.round(sec));
      if (sec < 60) return sec + 's';
      const m = Math.floor(sec / 60), s = sec % 60;
      if (m < 60) return m + 'm' + (s ? ' ' + String(s).padStart(2, '0') + 's' : '');
      const h = Math.floor(m / 60);
      return h + 'h ' + String(m % 60).padStart(2, '0') + 'm';
    },
    ago(sec) {
      if (sec < 8) return 'now';
      if (sec < 60) return Math.round(sec) + 's';
      if (sec < 3600) return Math.floor(sec / 60) + 'm';
      return Math.floor(sec / 3600) + 'h';
    },
  });

  /* ---------------------------------------------------------------- haptics */
  PM.haptic = function (ms) {
    try {
      if (PM.game && !PM.game.s.settings.haptics) return;
      if (window.PMNative && window.PMNative.vibrate) window.PMNative.vibrate(ms);
      else if (navigator.vibrate) navigator.vibrate(ms);
    } catch (e) { /* ignore */ }
  };

  /* ------------------------------------------------------------------ sound */
  PM.sfx = (function () {
    let ctx = null;
    function ac() {
      if (!ctx) {
        try { ctx = new (window.AudioContext || window.webkitAudioContext)(); } catch (e) { return null; }
      }
      if (ctx.state === 'suspended') ctx.resume();
      return ctx;
    }
    function tone(freq, dur, o) {
      o = o || {};
      if (PM.game && !PM.game.s.settings.sound) return;
      const c = ac();
      if (!c) return;
      const t = c.currentTime + (o.delay || 0);
      const osc = c.createOscillator();
      const g = c.createGain();
      osc.type = o.type || 'sine';
      osc.frequency.setValueAtTime(freq, t);
      if (o.slide) osc.frequency.exponentialRampToValueAtTime(freq * o.slide, t + dur);
      const vol = o.vol || 0.06;
      g.gain.setValueAtTime(0.0001, t);
      g.gain.exponentialRampToValueAtTime(vol, t + 0.008);
      g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
      osc.connect(g); g.connect(c.destination);
      osc.start(t); osc.stop(t + dur + 0.03);
    }
    return {
      unlock() { ac(); },
      tap(combo) { tone(480 + Math.min(combo, 40) * 16, 0.07, { type: 'triangle', vol: 0.05, slide: 1.35 }); },
      buy() { tone(440, 0.09, { type: 'triangle' }); tone(660, 0.14, { type: 'triangle', delay: 0.07 }); },
      sell() { tone(660, 0.09, { type: 'triangle' }); tone(440, 0.14, { type: 'triangle', delay: 0.07 }); },
      cash() { tone(880, 0.07, { type: 'square', vol: 0.03 }); tone(1320, 0.12, { type: 'square', vol: 0.03, delay: 0.06 }); },
      win() { [523, 659, 784, 1047].forEach((f, i) => tone(f, 0.2, { type: 'triangle', vol: 0.07, delay: i * 0.09 })); },
      error() { tone(170, 0.16, { type: 'square', vol: 0.035 }); },
      pop() { tone(700, 0.05, { type: 'sine', vol: 0.04, slide: 1.5 }); },
    };
  })();

  /* ------------------------------------------------------------ screen FX */
  PM.fx = (function () {
    let canvas, ctx, W = 0, H = 0, dpr = 1, raf = 0;
    const parts = [];
    const floaters = { n: 0 };

    function ensure() {
      if (canvas) return true;
      if (!document.body) return false;
      canvas = document.createElement('canvas');
      canvas.className = 'fx-canvas';
      document.body.appendChild(canvas);
      ctx = canvas.getContext('2d');
      resize();
      window.addEventListener('resize', resize);
      return true;
    }
    function resize() {
      dpr = Math.min(2, window.devicePixelRatio || 1);
      W = window.innerWidth; H = window.innerHeight;
      canvas.width = Math.round(W * dpr); canvas.height = Math.round(H * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }
    function loop() {
      ctx.clearRect(0, 0, W, H);
      for (let i = parts.length - 1; i >= 0; i--) {
        const p = parts[i];
        p.vy += p.g; p.vx *= p.drag; p.vy *= p.drag;
        p.x += p.vx; p.y += p.vy; p.rot += p.vr; p.life--;
        if (p.life <= 0 || p.y > H + 30) { parts.splice(i, 1); continue; }
        const a = Math.min(1, p.life / 18);
        ctx.globalAlpha = a;
        ctx.fillStyle = p.color;
        if (p.shape === 'rect') {
          ctx.save(); ctx.translate(p.x, p.y); ctx.rotate(p.rot); ctx.fillRect(-p.size / 2, -p.size / 3, p.size, p.size * 0.6); ctx.restore();
        } else {
          ctx.beginPath(); ctx.arc(p.x, p.y, p.size * (0.5 + 0.5 * a), 0, 6.2832); ctx.fill();
        }
      }
      ctx.globalAlpha = 1;
      raf = parts.length ? requestAnimationFrame(loop) : 0;
      if (!raf) ctx.clearRect(0, 0, W, H);
    }
    function kick() { if (!raf) raf = requestAnimationFrame(loop); }
    const rnd = (a, b) => a + Math.random() * (b - a);

    return {
      burst(x, y, o) {
        if (!ensure()) return;
        o = o || {};
        const colors = o.colors || ['#FFD66B', '#FFB020', '#FFF2B8'];
        const n = o.n || 8;
        for (let i = 0; i < n; i++) {
          const ang = rnd(-Math.PI, 0) + (o.spread ? rnd(-o.spread, o.spread) : 0);
          const sp = rnd(2, o.speed || 6);
          parts.push({ x, y, vx: Math.cos(ang) * sp, vy: Math.sin(ang) * sp, g: o.gravity == null ? 0.22 : o.gravity, drag: 0.985,
            size: rnd(2.5, o.size || 5), color: colors[(Math.random() * colors.length) | 0], life: rnd(28, 52), rot: 0, vr: 0, shape: 'dot' });
        }
        if (parts.length > 400) parts.splice(0, parts.length - 400);
        kick();
      },
      confetti() {
        if (!ensure()) return;
        const now = Date.now();
        if (now - (this._lastConfetti || 0) < 2500) return; // avoid back-to-back storms
        this._lastConfetti = now;
        const colors = ['#7C6CFF', '#22D3EE', '#2DE2A6', '#FFC857', '#FF5C7C', '#FFFFFF'];
        for (let i = 0; i < 110; i++) {
          parts.push({ x: rnd(0, W), y: rnd(-40, H * 0.25), vx: rnd(-2.5, 2.5), vy: rnd(1, 5), g: 0.12, drag: 0.992,
            size: rnd(6, 11), color: colors[(Math.random() * colors.length) | 0], life: rnd(70, 130), rot: rnd(0, 6), vr: rnd(-0.25, 0.25), shape: 'rect' });
        }
        kick();
      },
      floatText(x, y, text, cls) {
        if (floaters.n > 24) return;
        const el = document.createElement('div');
        el.className = 'float-text ' + (cls || '');
        el.textContent = text;
        el.style.left = x + 'px';
        el.style.top = y + 'px';
        el.style.setProperty('--dx', Math.round(rnd(-22, 22)) + 'px');
        document.body.appendChild(el);
        floaters.n++;
        el.addEventListener('animationend', () => { el.remove(); floaters.n--; });
      },
    };
  })();
})();
