#!/usr/bin/env python3
"""
Connectome Fly - a desktop overlay pet run by a simulation of the whole
FlyWire fruit fly brain.

Every one of the 138,639 neurons in the FlyWire connectome (public release
783) is simulated as a spiking neuron, wired with every one of its synapses.
Nothing in this file decides what the fly does. The code only:

  * EYES:  turns the cursor into spikes in four types of visual neuron
           (LC4, LPLC2, LC16, LC10a, left and right optic lobe),
  * BRAIN: runs the spiking network (no behaviour rules inside it),
  * MEMORY: the output synapses of visual neurons weaken each time they're
           used without harm (habituation) and strengthen when the fly is
           swatted right after (sensitization). Saved between runs.
  * BODY:  reads out four types of descending neuron and moves the drawing:
             DNp01 (Giant Fiber) -> jump
             DNa02 (left/right)  -> turn toward that side
             DNp09               -> walk forward
             MDN                 -> walk backward
  * DRAW:  paints the fly.

Neuron model: leaky integrate-and-fire with the parameters of Shiu et al.
(2024, Nature), plus two additions so the network can run continuously:
spike-frequency adaptation, and weak random synaptic "noise" on every
non-sensory neuron. Synapse signs come from each neuron's predicted
neurotransmitter: acetylcholine excites, GABA / glutamate / histamine inhibit,
and dopamine / serotonin / octopamine (slow neuromodulators) have no fast
effect. Sensory neurons fire only from sensory input.

Data (downloaded once, ~130 MB, then cached next to this file):
  * connectivity + neuron list: github.com/philshiu/Drosophila_brain_model
    (FlyWire 783, as used by Shiu et al. 2024)
  * cell types, sides, neurotransmitters: FlyWire hierarchical annotations
    via fafbseg (github.com/flyconnectome/flywire_annotations)

Usage
-----
    python fly_pet.py                # run the pet
    python fly_pet.py --noise 0.07   # more spontaneous brain activity
    python fly_pet.py --simulate 4   # no GUI: sweep a cursor at the fly, print
    python fly_pet.py --rebuild      # rebuild the cached brain from the data
"""

from __future__ import annotations

import argparse
import math
import random
import sys
import threading
import time
import urllib.request
from pathlib import Path

import numpy as np

# 1.0 hand-tuned circuit, 2.0 whole-brain simulation, 3.0 memory
__version__ = "3.0.0"

HERE = Path(__file__).resolve().parent
DATA_DIR = HERE / "brain_data"
CACHE_FILE = DATA_DIR / "brain_783.npz"
MEMORY_FILE = DATA_DIR / "memory.npz"
MODEL_REPO = "https://raw.githubusercontent.com/philshiu/Drosophila_brain_model/main/"
CONN_FILE = "Connectivity_783.parquet"
NEURON_FILE = "Completeness_783.csv"
ANNOT_URL = ("https://raw.githubusercontent.com/flyconnectome/flywire_annotations/"
             "main/supplemental_files/Supplemental_file1_neuron_annotations.tsv")

SIDES = ("L", "R")
ANNOT_SIDE = {"L": "left", "R": "right"}
SENSORY_TYPES = ("LC4", "LPLC2", "LC16", "LC10a")
MOTOR_TYPES = ("DNp01", "DNa02", "DNp09", "MDN")
SENSORY = [f"{t}_{s}" for t in SENSORY_TYPES for s in SIDES]
MOTOR = [f"{t}_{s}" for t in MOTOR_TYPES for s in SIDES]
GROUPS = SENSORY + MOTOR

INHIBITORY_NT = ("gaba", "glutamate", "histamine")
MODULATORY_NT = ("dopamine", "serotonin", "octopamine")


# --------------------------------------------------------------------------- #
#  Loading the connectome
# --------------------------------------------------------------------------- #

def _download(url: str, dest: Path):
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"[data] downloading {url.rsplit('/', 1)[-1]} ...", flush=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url) as r, open(tmp, "wb") as f:
        total = int(r.headers.get("Content-Length", 0))
        done = 0
        while chunk := r.read(1 << 20):
            f.write(chunk)
            done += len(chunk)
            if total:
                print(f"\r[data]   {done / 1e6:6.1f} / {total / 1e6:.1f} MB",
                      end="", flush=True)
    print()
    tmp.replace(dest)


def _load_annotations():
    """FlyWire cell types / sides / neurotransmitters, via fafbseg if possible."""
    import pandas as pd
    cols = ["root_id", "cell_type", "side", "super_class", "top_nt"]
    try:
        from fafbseg import flywire
        ann = flywire.get_hierarchical_annotations(verbose=False)
        return ann[cols]
    except Exception as e:
        print(f"[data] fafbseg annotations unavailable ({type(e).__name__}); "
              "downloading the annotation table directly")
    path = DATA_DIR / "flywire_annotations.tsv"
    if not path.exists():
        _download(ANNOT_URL, path)
    return pd.read_csv(path, sep="\t", usecols=cols, low_memory=False)


def build_brain_cache():
    """Turn the raw FlyWire data into a signed sparse weight matrix."""
    import pandas as pd
    import scipy.sparse as sp

    for name in (CONN_FILE, NEURON_FILE):
        if not (DATA_DIR / name).exists():
            _download(MODEL_REPO + name, DATA_DIR / name)

    print("[data] building the brain (one-off, takes ~30 s)...", flush=True)
    root_ids = pd.read_csv(DATA_DIR / NEURON_FILE).iloc[:, 0].to_numpy(np.int64)
    n = len(root_ids)
    ann = _load_annotations().drop_duplicates("root_id").set_index("root_id")
    ann = ann.reindex(root_ids)

    nt = ann.top_nt.to_numpy(dtype=object)
    sign = np.ones(n, np.float32)
    sign[np.isin(nt, INHIBITORY_NT)] = -1.0
    sign[np.isin(nt, MODULATORY_NT)] = 0.0
    sensory = (ann.super_class == "sensory").to_numpy()

    conn = pd.read_parquet(DATA_DIR / CONN_FILE, columns=[
        "Presynaptic_Index", "Postsynaptic_Index", "Connectivity"])
    pre = conn.Presynaptic_Index.to_numpy()
    post = conn.Postsynaptic_Index.to_numpy()
    syn = conn.Connectivity.to_numpy(np.float32) * sign[pre]
    keep = (syn != 0) & ~sensory[post]      # sensory neurons get no synaptic drive
    W = sp.csr_matrix((syn[keep], (pre[keep], post[keep])), shape=(n, n))
    W.sum_duplicates()

    groups = {}
    for g in GROUPS:
        ctype, side = g.rsplit("_", 1)
        mask = (ann.cell_type == ctype) & (ann.side == ANNOT_SIDE[side])
        groups[g] = np.flatnonzero(mask.to_numpy())
        print(f"[data]   {g:8s} {len(groups[g]):4d} neurons")

    np.savez(CACHE_FILE, indptr=W.indptr.astype(np.int64),
             indices=W.indices.astype(np.int32), data=W.data.astype(np.float32),
             sensory=np.flatnonzero(sensory), n=n, root_ids=root_ids,
             visual=np.flatnonzero((ann.super_class == "visual_projection").to_numpy()),
             **{f"group_{g}": idx for g, idx in groups.items()})
    print(f"[data] saved {CACHE_FILE.name}: {n:,} neurons, {W.nnz:,} connections")


def load_brain_data(rebuild: bool = False) -> dict:
    if not rebuild and CACHE_FILE.exists():
        with np.load(CACHE_FILE) as z:
            rebuild = "visual" not in z.files       # cache from an older version
    if rebuild or not CACHE_FILE.exists():
        build_brain_cache()
    z = np.load(CACHE_FILE)
    return {
        "n": int(z["n"]), "indptr": z["indptr"], "indices": z["indices"],
        "data": z["data"], "sensory": z["sensory"], "visual": z["visual"],
        "root_ids": z["root_ids"],
        "groups": {g: z[f"group_{g}"] for g in GROUPS},
    }


# --------------------------------------------------------------------------- #
#  The brain: a spiking network of every neuron in the connectome
# --------------------------------------------------------------------------- #

class WholeBrain:
    DT = 1.0            # ms per step
    TAU_M = 20.0        # ms, membrane time constant        (Shiu et al. 2024)
    TAU_SYN = 5.0       # ms, synaptic time constant        (Shiu et al. 2024)
    V_TH = 7.0          # mV above rest (-45 vs -52 mV)     (Shiu et al. 2024)
    W_SYN = 0.275       # mV per synapse                    (Shiu et al. 2024)
    DELAY = 2           # steps (1.8 ms in Shiu et al.)
    REFRACTORY = 2      # steps (2.2 ms in Shiu et al.)
    ADAPT = 1.0         # mV added to adaptation per spike
    TAU_ADAPT = 200.0   # ms
    NOISE_KICK = 2.0    # mV per random synaptic event

    # Learning at the output synapses of visual projection neurons
    HABITUATION = 0.002       # fraction of strength lost per spike
    SENSITIZATION = 0.008     # strength gained per recent spike when hurt
    ELIGIBILITY_TAU = 3.0     # s, how far back "what I just saw" reaches
    MEMORY_TAU = 1800.0       # s, drift back to baseline (30 min)
    MIN_STRENGTH, MAX_STRENGTH = 0.15, 3.0

    def __init__(self, data: dict, noise: float = 0.05, seed: int | None = None):
        f32 = np.float32
        self.n = n = data["n"]
        self.indptr, self.indices = data["indptr"], data["indices"]
        self.weights = data["data"] * f32(self.W_SYN)
        self.groups = data["groups"]
        self.rng = np.random.default_rng(seed)

        self.v = np.zeros(n, f32)          # membrane potential (mV above rest)
        self.x = np.zeros(n, f32)          # synaptic input
        self.w = np.zeros(n, f32)          # adaptation
        self._tmp = np.zeros(n, f32)
        self._buf = np.zeros((self.DELAY, n), f32)
        self._recent = [np.empty(0, np.int64)] * self.REFRACTORY
        self._dx = f32(math.exp(-self.DT / self.TAU_SYN))
        self._dw = f32(math.exp(-self.DT / self.TAU_ADAPT))

        # Random synaptic events on every non-sensory neuron, half excitatory
        # and half inhibitory; `noise` = events per neuron per ms.
        pool = np.ones(n, bool)
        pool[data["sensory"]] = False
        self._noise_pool = np.flatnonzero(pool)
        k = int(len(self._noise_pool) * noise)
        self._noise_kicks = np.where(np.arange(k) % 2 == 0, self.NOISE_KICK,
                                     -self.NOISE_KICK).astype(f32)

        # Memory: one strength per visual projection neuron that scales all
        # of its output synapses, plus an eligibility trace of recent spikes.
        self.root_ids = data["root_ids"]
        self.plastic = data["visual"]
        self._is_plastic = np.zeros(n, bool)
        self._is_plastic[self.plastic] = True
        self.strength = np.ones(n, f32)
        self.eligibility = np.zeros(n, f32)
        self._hurt = False

        self._group_of = np.full(n, -1, np.int16)
        for i, g in enumerate(GROUPS):
            self._group_of[self.groups[g]] = i
        self.group_size = np.array([max(len(self.groups[g]), 1) for g in GROUPS])

        # Written by the eyes, read by the brain thread (Hz per sensory group).
        self.input_hz = {g: 0.0 for g in SENSORY}
        # Cumulative counters, read by the body without locking.
        self.group_spikes = np.zeros(len(GROUPS), np.int64)
        self.total_spikes = 0
        self.steps = 0
        self.speed = 1.0                   # brain time / wall time
        self._running = False

    def step(self):
        v, x, w, tmp = self.v, self.x, self.w, self._tmp
        buf = self._buf[self.steps % self.DELAY]
        x *= self._dx
        x += buf
        buf.fill(0)
        w *= self._dw
        np.subtract(x, v, out=tmp)
        tmp -= w
        tmp *= self.DT / self.TAU_M
        v += tmp
        if len(self._noise_kicks):
            v[self._noise_pool[self.rng.integers(0, len(self._noise_pool),
                                                 len(self._noise_kicks))]] += self._noise_kicks
        for r in self._recent:
            v[r] = 0.0
        for g, hz in list(self.input_hz.items()):
            if hz > 0:
                idx = self.groups[g]
                v[idx[self.rng.random(len(idx)) < hz * self.DT / 1000]] = self.V_TH

        s = np.flatnonzero(v >= self.V_TH)
        v[s] = 0.0
        w[s] += self.ADAPT
        self._recent = self._recent[1:] + [s]
        if len(s):
            starts = self.indptr[s]
            lens = self.indptr[s + 1] - starts
            offs = np.repeat(starts - np.cumsum(lens) + lens, lens)
            j = offs + np.arange(lens.sum())
            weights = self.weights[j] * np.repeat(self.strength[s], lens)
            buf += np.bincount(self.indices[j], weights=weights,
                               minlength=self.n).astype(np.float32)
            sp = s[self._is_plastic[s]]
            if len(sp):                   # habituation: used synapses weaken
                self.strength[sp] *= 1 - self.HABITUATION
                self.eligibility[sp] += 1
            g = self._group_of[s]
            g = g[g >= 0]
            if len(g):
                self.group_spikes += np.bincount(g, minlength=len(GROUPS))
        self.total_spikes += len(s)
        self.steps += 1
        if self.steps % 100 == 0:
            self._update_memory(0.1)

    def _update_memory(self, seconds: float):
        p = self.plastic
        if self._hurt:                    # sensitization: what I just saw hurt
            self._hurt = False
            self.strength[p] += self.SENSITIZATION * self.eligibility[p]
        st = self.strength[p]
        st += (1 - st) * (1 - math.exp(-seconds / self.MEMORY_TAU))
        self.strength[p] = np.clip(st, self.MIN_STRENGTH, self.MAX_STRENGTH)
        self.eligibility[p] *= math.exp(-seconds / self.ELIGIBILITY_TAU)

    def hurt(self):
        """Something bad just happened to the fly (it was swatted)."""
        self._hurt = True

    def memory_of(self, group: str) -> float:
        return float(self.strength[self.groups[group]].mean())

    def save_memory(self, path: Path):
        np.savez(path, root_ids=self.root_ids[self.plastic],
                 strength=self.strength[self.plastic], saved=time.time())

    def load_memory(self, path: Path):
        if not path.exists():
            return
        with np.load(path) as z:
            lookup = dict(zip(z["root_ids"].tolist(), z["strength"].tolist()))
            away = max(time.time() - float(z["saved"]), 0.0)
        forget = math.exp(-away / self.MEMORY_TAU)     # memories fade while off
        for i in self.plastic:
            v = lookup.get(int(self.root_ids[i]))
            if v is not None:
                self.strength[i] = 1 + (v - 1) * forget
        print(f"[memory] loaded (last run {away / 60:.0f} min ago)")

    # -- real-time loop in a background thread --------------------------------
    def start(self):
        self._running = True
        threading.Thread(target=self._loop, daemon=True).start()

    def stop(self):
        self._running = False

    def _loop(self):
        t0, s0 = time.perf_counter(), self.steps
        while self._running:
            for _ in range(10):
                self.step()
            wall = time.perf_counter() - t0
            brain = (self.steps - s0) * self.DT / 1000
            if brain > wall:                       # never run faster than life
                time.sleep(brain - wall)
            if wall > 2:
                self.speed = brain / max(wall, 1e-6)
                t0, s0 = time.perf_counter(), self.steps


# --------------------------------------------------------------------------- #
#  Eyes: cursor -> spike rates of visual projection neurons
# --------------------------------------------------------------------------- #

def _sig(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-max(min(x, 50.0), -50.0)))


def _clamp(x: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return lo if x < lo else hi if x > hi else x


class Eyes:
    MAX_HZ = 150.0
    OBJECT_RADIUS = 14.0   # px, the cursor is seen as a small dark object

    def __init__(self):
        self._prev_alpha = None
        self.hz = {g: 0.0 for g in SENSORY}

    def see(self, dist: float, bearing: float, dt: float, cursor_speed: float):
        """dist in px; bearing in rad, 0 = ahead, + = fly's left."""
        alpha = 2 * math.atan(self.OBJECT_RADIUS / max(dist, 1.0))  # angular size
        if self._prev_alpha is None:
            self._prev_alpha = alpha
        looming = max((alpha - self._prev_alpha) / max(dt, 1e-3), 0.0)  # rad/s
        self._prev_alpha = alpha

        left = _sig(bearing / 0.35)            # binocular overlap in front
        field = {"L": left, "R": 1.0 - left}
        frontal = max(math.cos(bearing), 0.0)
        feature = {
            "LC4": _clamp((looming - 0.4) / 2.5),                   # fast expansion
            "LPLC2": _clamp(_sig((alpha - 0.35) / 0.08) * (0.3 + looming)),  # collision
            "LC16": _clamp(frontal ** 2 * _sig((alpha - 0.18) / 0.05)
                           * (0.4 + 0.6 * _clamp(looming * 2))),    # frontal approach
            "LC10a": _clamp(_sig((0.25 - alpha) / 0.05)
                            * _clamp(cursor_speed / 400)),          # small moving object
        }
        for g in SENSORY:
            ctype, side = g.rsplit("_", 1)
            self.hz[g] = self.MAX_HZ * feature[ctype] * field[side]
        return self.hz


# --------------------------------------------------------------------------- #
#  Body: descending neuron firing -> movement
# --------------------------------------------------------------------------- #

class Body:
    TURN_PER_HZ = 0.02       # rad/s per Hz of DNa02 left-right difference
    WALK_PER_HZ = 3.0        # px/s per Hz of DNp09
    BACK_PER_HZ = 3.0        # px/s per Hz of MDN
    JUMP_HZ = 25.0           # Giant Fiber rate that launches a jump

    def __init__(self, brain: WholeBrain):
        self.brain = brain
        self.hz = {g: 0.0 for g in GROUPS}
        self._last = brain.group_spikes.copy()

    def read(self, dt: float) -> dict:
        now = self.brain.group_spikes.copy()
        rates = (now - self._last) / self.brain.group_size / max(dt, 1e-3)
        self._last = now
        for i, g in enumerate(GROUPS):
            tau = 0.05 if g.startswith("DNp01") else 0.15
            a = min(dt / tau, 1.0)
            self.hz[g] += a * (rates[i] - self.hz[g])
        h = self.hz
        return {
            "turn": self.TURN_PER_HZ * (h["DNa02_L"] - h["DNa02_R"]),   # + = left
            "speed": (self.WALK_PER_HZ * (h["DNp09_L"] + h["DNp09_R"]) / 2
                      - self.BACK_PER_HZ * (h["MDN_L"] + h["MDN_R"]) / 2),
            "jump": max(h["DNp01_L"], h["DNp01_R"]) > self.JUMP_HZ,
        }


# --------------------------------------------------------------------------- #
#  Tkinter overlay
# --------------------------------------------------------------------------- #

class FlyPet:
    SIZE = 96          # window size (px)
    SCALE = 1.35       # fly drawing scale
    FPS = 40

    def __init__(self, brain: WholeBrain):
        import tkinter as tk
        self.tk = tk
        self.brain = brain
        self.eyes = Eyes()
        self.body = Body(brain)

        self.root = tk.Tk()
        self.root.title(f"Connectome Fly v{__version__}")
        self.root.overrideredirect(True)
        self.root.wm_attributes("-topmost", True)
        bg = self._setup_transparency()

        self.canvas = tk.Canvas(self.root, width=self.SIZE, height=self.SIZE,
                                bg=bg, highlightthickness=0, bd=0)
        self.canvas.pack()

        self.sw = self.root.winfo_screenwidth()
        self.sh = self.root.winfo_screenheight()
        self.x, self.y = self.sw * 0.5, self.sh * 0.6
        self.heading = random.uniform(-math.pi, math.pi)
        self.leg_phase = 0.0
        self.jump = None            # (t0, duration, from, to)
        self.paused = False
        self.panel = None
        self._last = time.perf_counter()
        self._last_cursor = self.root.winfo_pointerxy()

        self.menu = tk.Menu(self.root, tearoff=0)
        self.menu.add_command(label="Show brain activity", command=self.toggle_panel)
        self.menu.add_command(label="Pause / resume", command=self.toggle_pause)
        self.menu.add_separator()
        self.menu.add_command(label="Quit", command=self.quit)
        self.canvas.bind("<Button-1>", self._swat)
        self.canvas.bind("<Button-3>", self._popup)
        self.canvas.bind("<Button-2>", self._popup)   # macOS right-click
        self.root.bind("<Escape>", lambda e: self.quit())

        self._swatted_at = 0.0
        self.brain.load_memory(MEMORY_FILE)
        self._place()
        self.brain.start()
        self.root.after(0, self.tick)
        self.root.after(60_000, self._autosave)

    def _setup_transparency(self) -> str:
        """Make the window background see-through where the OS allows it."""
        if sys.platform.startswith("win"):
            key = "#ff00fe"
            self.root.config(bg=key)
            self.root.wm_attributes("-transparentcolor", key)
            return key
        if sys.platform == "darwin":
            try:
                self.root.wm_attributes("-transparent", True)
                self.root.config(bg="systemTransparent")
                return "systemTransparent"
            except Exception:
                pass
        # X11: Tk has no per-pixel transparency; use a small neutral tile.
        return "#e8e4dc"

    def _popup(self, event):
        self.menu.tk_popup(event.x_root, event.y_root)

    def toggle_pause(self):
        self.paused = not self.paused
        for g in SENSORY:
            self.brain.input_hz[g] = 0.0

    def _swat(self, event):
        """Left-click: swat the fly. What it saw just before becomes scarier."""
        self.brain.hurt()
        self._swatted_at = time.perf_counter()

    def _autosave(self):
        self.brain.save_memory(MEMORY_FILE)
        self.root.after(60_000, self._autosave)

    def quit(self):
        self.brain.stop()
        self.brain.save_memory(MEMORY_FILE)
        self.root.destroy()

    # ----------------------------------------------------------- simulation
    def tick(self):
        now = time.perf_counter()
        dt = min(now - self._last, 0.1)
        self._last = now
        if not self.paused:
            self.update(dt)
        self.draw()
        if self.panel:
            self.update_panel()
        self.root.after(int(1000 / self.FPS), self.tick)

    def update(self, dt: float):
        # Eyes: where is the cursor relative to the fly?
        cx, cy = self.root.winfo_pointerxy()
        lx, ly = self._last_cursor
        cursor_speed = math.hypot(cx - lx, cy - ly) / max(dt, 1e-3)
        self._last_cursor = (cx, cy)
        dx, dy = cx - self.x, cy - self.y
        # Screen y points down, so the fly's left is at heading - 90 degrees.
        bearing = _wrap(self.heading - math.atan2(dy, dx))
        self.brain.input_hz.update(
            self.eyes.see(math.hypot(dx, dy), bearing, dt, cursor_speed))

        # Body: move according to the descending neurons.
        motor = self.body.read(dt)
        if self.jump:
            t0, dur, (x0, y0), (x1, y1) = self.jump
            u = (time.perf_counter() - t0) / dur
            if u >= 1:
                self.jump = None
                self.x, self.y = x1, y1
            else:
                e = 1 - (1 - u) ** 3
                self.x, self.y = x0 + (x1 - x0) * e, y0 + (y1 - y0) * e
            self._place()
            return
        if motor["jump"]:
            self._start_jump()
            return

        self.heading = _wrap(self.heading - motor["turn"] * dt)
        speed = motor["speed"]
        self.x += math.cos(self.heading) * speed * dt
        self.y += math.sin(self.heading) * speed * dt
        self.leg_phase += (abs(speed) + 20 * abs(motor["turn"])) * dt * 0.25
        # The screen edge is a wall: the fly can't walk through it.
        m = self.SIZE / 2
        self.x = min(max(self.x, m), self.sw - m)
        self.y = min(max(self.y, m), self.sh - m)
        self._place()

    def _start_jump(self):
        # Take-off direction follows the fly's own descending neurons: DNa02
        # asymmetry tilts it sideways, MDN over DNp09 tips it backward.
        h = self.body.hz
        tilt = 1.2 * math.tanh((h["DNa02_R"] - h["DNa02_L"]) / 30)   # + = right
        if h["MDN_L"] + h["MDN_R"] > h["DNp09_L"] + h["DNp09_R"]:
            angle = self.heading + math.pi - tilt
        else:
            angle = self.heading + tilt
        dist = 200
        m = self.SIZE / 2
        x1 = min(max(self.x + math.cos(angle) * dist, m), self.sw - m)
        y1 = min(max(self.y + math.sin(angle) * dist, m), self.sh - m)
        self.jump = (time.perf_counter(), 0.3, (self.x, self.y), (x1, y1))

    def _place(self):
        h = self.SIZE // 2
        self.root.geometry(f"{self.SIZE}x{self.SIZE}+{int(self.x) - h}+{int(self.y) - h}")

    # ---------------------------------------------------------------- drawing
    def draw(self):
        c = self.canvas
        c.delete("all")
        o = self.SIZE / 2
        cos_h, sin_h = math.cos(self.heading), math.sin(self.heading)

        def tr(px, py):  # fly frame (x forward, y left) -> canvas
            px, py = px * self.SCALE, py * self.SCALE
            return (o + px * cos_h + py * sin_h, o + px * sin_h - py * cos_h)

        def poly(points, **kw):
            flat = [v for p in points for v in tr(*p)]
            return c.create_polygon(flat, smooth=True, **kw)

        def ellipse(cx, cy, rx, ry, n=14):
            return [(cx + rx * math.cos(2 * math.pi * i / n),
                     cy + ry * math.sin(2 * math.pi * i / n)) for i in range(n)]

        flying = self.jump is not None
        wing_spread = 0.9 if flying else 0.18
        # Wings (drawn first, under the body)
        for side in (1, -1):
            a = math.pi + side * wing_spread
            base = (-2, side * 3)
            tip = (base[0] + 24 * math.cos(a), base[1] + 24 * math.sin(a))
            mid = ((base[0] + tip[0]) / 2, (base[1] + tip[1]) / 2)
            nx_, ny_ = -math.sin(a) * 6, math.cos(a) * 6
            poly([base, (mid[0] + nx_, mid[1] + ny_), tip,
                  (mid[0] - nx_, mid[1] - ny_)],
                 fill="#cfe3ea", outline="#8aa6b0", stipple="gray50")
        # Legs: tripod gait
        for i, lx in enumerate((6, 0, -6)):
            for side in (1, -1):
                phase = self.leg_phase + (0 if (i % 2 == 0) == (side == 1) else math.pi)
                swing = 3 * math.sin(phase) if not flying else 0
                x0, y0 = tr(lx, side * 3)
                x1, y1 = tr(lx + 4 - i * 4 + swing, side * 13)
                x2, y2 = tr(lx + 6 - i * 6 + swing, side * 17)
                c.create_line(x0, y0, x1, y1, x2, y2, fill="#2b2118", width=1.5)
        # Abdomen, thorax, head, eyes
        poly(ellipse(-10, 0, 10, 6.5), fill="#6b4a2b", outline="#3a2816")
        for sx in (-6, -10, -14):
            x0, y0 = tr(sx, 5.5)
            x1, y1 = tr(sx, -5.5)
            c.create_line(x0, y0, x1, y1, fill="#3a2816", width=1.5)
        poly(ellipse(2, 0, 6, 5), fill="#8a6a3c", outline="#3a2816")
        poly(ellipse(10, 0, 3.5, 4.5), fill="#8a6a3c", outline="#3a2816")
        for side in (1, -1):
            poly(ellipse(10.5, side * 3.4, 3.2, 2.6, n=10), fill="#c0262a",
                 outline="#6d1012")
        # Red flash when swatted
        if time.perf_counter() - self._swatted_at < 0.3:
            x, y = tr(0, 0)
            c.create_oval(x - 22, y - 22, x + 22, y + 22, outline="#e0323a", width=3)
        # Giant Fiber flash when escape fires
        if flying:
            x, y = tr(0, 0)
            c.create_oval(x - 30, y - 30, x + 30, y + 30, outline="#f2b705", width=2)


    # ------------------------------------------------------ brain activity UI
    def toggle_panel(self):
        tk = self.tk
        if self.panel:
            self.panel.destroy()
            self.panel = None
            return
        self.panel = tk.Toplevel(self.root)
        self.panel.title("Fly brain activity")
        self.panel.wm_attributes("-topmost", True)
        self.panel.protocol("WM_DELETE_WINDOW", self.toggle_panel)
        self.panel.configure(bg="#14161a")
        self.pcanvas = tk.Canvas(self.panel, width=440, height=330, bg="#14161a",
                                 highlightthickness=0)
        self.pcanvas.pack(padx=8, pady=8)
        self._panel_last = (time.perf_counter(), self.brain.total_spikes)

    def update_panel(self):
        c = self.pcanvas
        c.delete("all")
        t, spikes = time.perf_counter(), self.brain.total_spikes
        t0, s0 = self._panel_last
        if t - t0 > 0.5:
            self._panel_rate = (spikes - s0) / (t - t0)
            self._panel_last = (t, spikes)
        rate = getattr(self, "_panel_rate", 0.0)
        c.create_text(10, 8, anchor="nw", fill="#9aa4b2", font=("TkDefaultFont", 9),
                      text=f"Connectome Fly v{__version__}  ·  {self.brain.n:,} spiking "
                           "neurons (FlyWire 783)")
        c.create_text(10, 24, anchor="nw", fill="#9aa4b2", font=("TkDefaultFont", 9),
                      text=f"{rate:,.0f} spikes/s   brain speed {self.brain.speed:.2f}x real time")

        def bars(title, names, hz, x, colour):
            c.create_text(x, 48, anchor="nw", fill="#e6e9ee",
                          font=("TkDefaultFont", 10, "bold"), text=title)
            for i, n in enumerate(names):
                y = 70 + i * 24
                c.create_text(x, y, anchor="nw", fill="#c6ccd6",
                              font=("TkDefaultFont", 9), text=n)
                c.create_rectangle(x + 70, y + 2, x + 170, y + 14, outline="#3a404a")
                c.create_rectangle(x + 70, y + 2, x + 70 + 100 * min(hz[n] / 150, 1),
                                   y + 14, fill=colour, width=0)
                c.create_text(x + 175, y, anchor="nw", fill="#8a93a0",
                              font=("TkDefaultFont", 8), text=f"{hz[n]:.0f}")

        bars("Eyes (Hz)", SENSORY, self.brain.input_hz, 10, "#4aa3df")
        bars("Descending (Hz)", MOTOR, self.body.hz, 230, "#e0864a")

        mem = "   ".join(f"{t} {self.brain.memory_of(f'{t}_L') * 0.5 + self.brain.memory_of(f'{t}_R') * 0.5:.2f}x"
                         for t in SENSORY_TYPES)
        c.create_text(10, 272, anchor="nw", fill="#e6e9ee",
                      font=("TkDefaultFont", 10, "bold"), text="Memory (visual synapse strength)")
        c.create_text(10, 292, anchor="nw", fill="#f2d16b", font=("TkDefaultFont", 9),
                      text=mem)
        c.create_text(10, 310, anchor="nw", fill="#8a93a0", font=("TkDefaultFont", 8),
                      text="below 1 = getting used to you   above 1 = wary (swatted)")

    def run(self):
        self.root.mainloop()


def _wrap(a: float) -> float:
    return (a + math.pi) % (2 * math.pi) - math.pi


# --------------------------------------------------------------------------- #

def simulate(brain: WholeBrain, seconds: float):
    """Headless check: a cursor rushes at the fly from its left."""
    eyes = Eyes()
    body = Body(brain)
    frame = 0.025
    t = 0.0
    while t < seconds:
        dist = max(700 - 300 * max(t - 1.0, 0), 10)     # 1 s still, then approach
        speed = 300 if t > 1.0 else 0
        brain.input_hz.update(eyes.see(dist, 0.6, frame, speed))
        for _ in range(int(frame * 1000)):
            brain.step()
        motor = body.read(frame)
        t += frame
        if round(t / frame) % 10 == 0:
            h = body.hz
            print(f"t={t:4.2f}s cursor {dist:4.0f}px | GF {h['DNp01_L']:3.0f}/{h['DNp01_R']:3.0f}"
                  f"  DNa02 {h['DNa02_L']:3.0f}/{h['DNa02_R']:3.0f}"
                  f"  DNp09 {h['DNp09_L']:3.0f}/{h['DNp09_R']:3.0f}"
                  f"  MDN {h['MDN_L']:3.0f}/{h['MDN_R']:3.0f}"
                  f"  -> turn {motor['turn']:+.2f} speed {motor['speed']:+4.0f}"
                  f"{'  JUMP' if motor['jump'] else ''}", flush=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", action="version",
                    version=f"Connectome Fly {__version__}")
    ap.add_argument("--noise", type=float, default=0.05,
                    help="random synaptic events per neuron per ms (default 0.05)")
    ap.add_argument("--rebuild", action="store_true",
                    help="rebuild the cached brain from the downloaded data")
    ap.add_argument("--simulate", type=float, metavar="SECONDS",
                    help="run without a window and print the brain's output")
    args = ap.parse_args()

    print(f"Connectome Fly v{__version__}")
    data = load_brain_data(rebuild=args.rebuild)
    brain = WholeBrain(data, noise=args.noise)
    print(f"[brain] {brain.n:,} neurons, {len(brain.indices):,} connections, "
          f"noise {args.noise}")
    if args.simulate:
        simulate(brain, args.simulate)
        return
    FlyPet(brain).run()


if __name__ == "__main__":
    main()
