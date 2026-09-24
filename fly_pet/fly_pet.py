#!/usr/bin/env python3
"""
Connectome Fly - a desktop overlay pet driven by the FlyWire fruit fly connectome.

A small fly walks around your screen. Your mouse cursor is treated as a visual
object: the fly "sees" it with identified visual projection neurons (LC4, LPLC2,
LC16, LC10a on each side of the brain), that activity is propagated through
connectome-derived weights to identified descending neurons (DNp01 / Giant
Fiber, DNa02, MDN, DNp09), and those descending neurons drive behaviour:

    DNp01 (Giant Fiber)  -> escape jump
    DNa02 (left/right)   -> steering (ipsilateral turn)
    MDN                  -> backward walking ("moonwalking")
    DNp09                -> forward walking / pursuit

How the connectome is used
--------------------------
With ``--build`` the script uses ``fafbseg`` to look up every neuron of those
cell types in the FlyWire annotations, fetches their synaptic partners, and
keeps all direct (sensory -> DN) and two-hop (sensory -> interneuron -> DN)
paths. The resulting graph is loaded into ``navis`` and a probabilistic signal
traversal (``navis.models.TraversalModel``) is run from each sensory group to
estimate how strongly and how quickly activity reaches each descending neuron.
The result is cached to ``fly_circuit.json`` so later launches are instant and
work offline.

Fetching connectivity needs a free FlyWire CAVE token (see README). Without it,
the pet falls back to a small hand-tuned circuit based on the published
literature so it still runs.

Usage
-----
    python fly_pet.py                 # run (cached circuit, else fallback)
    python fly_pet.py --build         # (re)build the circuit from FlyWire
    python fly_pet.py --token XXXX    # store your CAVE token, then build
    python fly_pet.py --offline       # ignore cache, use the built-in circuit
    python fly_pet.py --simulate 5    # headless: run the brain for 5 s, print
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE_FILE = HERE / "fly_circuit.json"

SIDES = ("L", "R")
ANNOT_SIDE = {"L": "left", "R": "right"}

# Visual projection neurons we treat as the fly's "sensors" for the cursor.
SENSORY_TYPES = {
    "LC4": "fast looming (expansion speed)",
    "LPLC2": "looming / imminent collision",
    "LC16": "frontal approaching object",
    "LC10a": "small moving object",
}

# Descending neurons we read out as motor commands.
MOTOR_TYPES = {
    "DNp01": "Giant Fiber - escape jump",
    "DNa02": "ipsilateral turning",
    "MDN": "backward walking",
    "DNp09": "forward walking",
}

SENSORY = [f"{t}_{s}" for t in SENSORY_TYPES for s in SIDES]
MOTOR = [f"{t}_{s}" for t in MOTOR_TYPES for s in SIDES]


def _other(side: str) -> str:
    return "R" if side == "L" else "L"


# --------------------------------------------------------------------------- #
#  Circuit construction
# --------------------------------------------------------------------------- #

def fallback_circuit() -> dict:
    """Hand-tuned sensory->DN gains based on published fly behaviour studies.

    Used when no FlyWire token / network is available. These are NOT measured
    synapse counts, just a qualitative stand-in:
      * LC4 and LPLC2 converge on the ipsilateral Giant Fiber (von Reyn 2017,
        Ache 2019).
      * LC16 activation evokes backward walking via MDN (Wu 2016).
      * LC10a tracks small objects, feeding turning/pursuit (Ribeiro 2018).
    """
    gains = {s: {m: 0.0 for m in MOTOR} for s in SENSORY}
    paths = {}
    for s in SIDES:
        o = _other(s)
        gains[f"LPLC2_{s}"][f"DNp01_{s}"] = 1.0
        gains[f"LPLC2_{s}"][f"DNp01_{o}"] = 0.15
        gains[f"LC4_{s}"][f"DNp01_{s}"] = 0.8
        gains[f"LC4_{s}"][f"DNa02_{o}"] = 0.4        # veer away
        gains[f"LC16_{s}"][f"MDN_{s}"] = 0.7
        gains[f"LC16_{s}"][f"MDN_{o}"] = 0.5
        gains[f"LC16_{s}"][f"DNa02_{o}"] = 0.3
        gains[f"LC10a_{s}"][f"DNa02_{s}"] = 0.8      # orient toward object
        gains[f"LC10a_{s}"][f"DNp09_{s}"] = 0.6
        gains[f"LC10a_{s}"][f"DNp09_{o}"] = 0.4
    for s_name, row in gains.items():
        for m_name, g in row.items():
            if g > 0:
                paths[f"{s_name}>{m_name}"] = [s_name, m_name]
    return {
        "source": "fallback (hand-tuned, literature based)",
        "gains": gains,
        "latency": {k: 1.0 for k in paths},
        "paths": paths,
        "stats": {},
    }


def build_connectome_circuit(materialization: int = 783, min_syn: int = 5,
                             iterations: int = 300, verbose: bool = True) -> dict:
    """Derive sensory->DN gains from the FlyWire connectome (fafbseg + navis)."""
    import networkx as nx
    import navis
    import pandas as pd
    from fafbseg import flywire
    from navis.models import TraversalModel

    def log(*a):
        if verbose:
            print("[build]", *a, flush=True)

    flywire.set_default_dataset("public")
    navis.set_pbars(hide=not verbose)
    log(f"Loading FlyWire annotations (materialization {materialization})...")
    ann = flywire.get_hierarchical_annotations(materialization=materialization,
                                               verbose=verbose)
    ann = ann[["root_id", "cell_type", "side"]].copy()
    type_of = dict(zip(ann.root_id, ann.cell_type.fillna("?")))

    # --- map every neuron of our cell types to a group like "LC4_L" --------
    group_of: dict[int, str] = {}
    for ctype in list(SENSORY_TYPES) + list(MOTOR_TYPES):
        rows = ann[ann.cell_type == ctype]
        for s in SIDES:
            ids = rows[rows.side == ANNOT_SIDE[s]].root_id.tolist()
            for rid in ids:
                group_of[int(rid)] = f"{ctype}_{s}"
            log(f"  {ctype}_{s}: {len(ids)} neurons")
    sens_ids = [r for r, g in group_of.items() if g in SENSORY]
    dn_ids = [r for r, g in group_of.items() if g in MOTOR]
    if not sens_ids or not dn_ids:
        raise RuntimeError("Annotation lookup returned no neurons.")

    # --- connectivity -------------------------------------------------------
    log(f"Fetching downstream partners of {len(sens_ids)} sensory neurons...")
    down = flywire.get_connectivity(sens_ids, upstream=False, downstream=True,
                                    materialization=materialization,
                                    progress=verbose)
    log(f"Fetching upstream partners of {len(dn_ids)} descending neurons...")
    up = flywire.get_connectivity(dn_ids, upstream=True, downstream=False,
                                  materialization=materialization,
                                  progress=verbose)
    down = down[down.weight >= min_syn]
    up = up[up.weight >= min_syn]

    # Input fraction onto each DN (exact: we have all of its inputs).
    up = up.assign(frac=up.weight / up.groupby("post").weight.transform("sum"))
    # Output fraction of each sensory neuron (all of its outputs are known).
    down = down.assign(frac=down.weight / down.groupby("pre").weight.transform("sum"))

    # Keep only interneurons that sit on a sensory -> X -> DN path, plus
    # direct sensory -> DN edges.
    relay = set(down.post) & set(up.pre)
    edges = pd.concat([
        down[down.post.isin(relay) | down.post.isin(dn_ids)],
        up[up.pre.isin(relay)],
    ], ignore_index=True).drop_duplicates(["pre", "post"])
    log(f"  {len(relay)} relay interneurons, {len(edges)} edges")

    # Collapse sensory and DN neurons into their groups (interneurons stay
    # individual). navis casts edge lists to float, which would corrupt 18-digit
    # root IDs, so every node gets a compact integer index instead: groups are
    # 0..15, interneurons follow.
    group_names = SENSORY + MOTOR
    gid = {g: i for i, g in enumerate(group_names)}
    gname = {v: k for k, v in gid.items()}
    root_of: dict[int, int] = {}

    def node_id(rid):
        g = group_of.get(rid)
        if g is not None:
            return gid[g]
        rid = int(rid)
        idx = gid.setdefault(rid, len(gid))
        root_of[idx] = rid
        return idx

    edges["source"] = [node_id(p) for p in edges.pre]
    edges["target"] = [node_id(p) for p in edges.post]
    edges = edges[edges.source != edges.target]
    grouped = (edges.groupby(["source", "target"])
                    .agg(syn=("weight", "sum"), frac=("frac", "sum"))
                    .reset_index())
    # Average the summed fractions over all neurons of the collapsed group
    # (sensory group as source, DN group as target), not just connected ones.
    group_size = {g: list(group_of.values()).count(g) for g in group_names}
    size = [group_size[gname[a]] if a in gname and gname[a] in SENSORY else
            group_size[gname[b]] if b in gname else 1
            for a, b in zip(grouped.source, grouped.target)]
    grouped["frac"] = grouped.frac / size
    # Traversal weights in [0, 1]; the default navis activation function goes
    # from 0% to 100% traversal probability between weights 0 and 0.3.
    grouped["weight"] = grouped.frac.clip(0, 1)

    G = navis.network2nx(grouped[["source", "target", "weight"]])
    G = nx.relabel_nodes(G, int)
    for u, v, d in G.edges(data=True):
        d["cost"] = -math.log(max(d["weight"], 1e-6))

    def label(node):
        if node in gname:
            return gname[node]
        rid = root_of[node]
        return f"{type_of.get(rid, '?')} ({rid})"

    # --- signal propagation per sensory group ------------------------------
    gains = {s: {m: 0.0 for m in MOTOR} for s in SENSORY}
    latency, paths = {}, {}
    for s in SENSORY:
        seed = gid[s]
        if seed not in G:
            continue
        log(f"Traversal from {s} ({iterations} iterations)...")
        model = TraversalModel(grouped[["source", "target", "weight"]],
                               seeds=[seed], max_steps=4)
        model.run(iterations=iterations)
        res = model.results
        res = res.assign(node=res.node.astype(int))
        reach = res.node.value_counts() / iterations
        layer = res.groupby("node").steps.mean()
        for m in MOTOR:
            node = gid[m]
            p = float(reach.get(node, 0.0))
            if p <= 0:
                continue
            steps = float(layer.get(node, 3.0))
            # Faster (fewer-hop) and more reliable routes count more.
            gains[s][m] = p / max(steps - 1.0, 1.0)
            latency[f"{s}>{m}"] = steps - 1.0
            try:
                path = nx.shortest_path(G, seed, node, weight="cost")
                paths[f"{s}>{m}"] = [label(n) for n in path]
            except nx.NetworkXNoPath:
                pass

    # Normalise per descending neuron so each DN's best input has gain 1
    # (DN excitability isn't in the wiring diagram), and drop negligible
    # routes so only each DN's dominant sensory inputs drive it.
    for m in MOTOR:
        col = max(gains[s][m] for s in SENSORY)
        for s in SENSORY:
            g = gains[s][m] / col if col > 0 else 0.0
            gains[s][m] = g if g >= 0.15 else 0.0

    return {
        "source": f"FlyWire connectome, materialization {materialization}",
        "gains": gains,
        "latency": latency,
        "paths": paths,
        "stats": {"relay_neurons": len(relay), "edges": int(len(edges)),
                  "built": time.strftime("%Y-%m-%d %H:%M")},
    }


def load_circuit(args) -> dict:
    if args.offline:
        return fallback_circuit()
    if args.token:
        from fafbseg import flywire
        flywire.set_chunkedgraph_secret(args.token, overwrite=True)
        args.build = True
    if CACHE_FILE.exists() and not args.build:
        with open(CACHE_FILE) as f:
            return json.load(f)
    try:
        circuit = build_connectome_circuit(materialization=args.materialization)
    except Exception as e:  # no token, no network, fafbseg missing, ...
        print(f"[connectome] Could not build from FlyWire ({type(e).__name__}: {e}).")
        print("[connectome] Using the built-in fallback circuit. "
              "See README for how to set up a FlyWire token.")
        return fallback_circuit()
    with open(CACHE_FILE, "w") as f:
        json.dump(circuit, f, indent=1)
    print(f"[connectome] Saved circuit to {CACHE_FILE}")
    return circuit


# --------------------------------------------------------------------------- #
#  Brain simulation (rate model on the derived circuit)
# --------------------------------------------------------------------------- #

def _sig(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-x))


def _clamp(x: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return lo if x < lo else hi if x > hi else x


class FlyBrain:
    """Maps cursor geometry -> sensory rates -> descending neuron rates."""

    TAU = 0.08            # s, DN membrane/rate time constant
    GF_THRESHOLD = 0.55   # Giant Fiber "spike" threshold

    def __init__(self, circuit: dict):
        self.circuit = circuit
        self.gains = circuit["gains"]
        self.sens = {s: 0.0 for s in SENSORY}
        self.rate = {m: 0.0 for m in MOTOR}
        self.touch = 0.0
        self._prev_alpha = None
        self._wander = 0.0
        self._walk_urge = 0.3

    def sense(self, dist: float, bearing: float, dt: float, touching: bool,
              cursor_speed: float):
        """Encode the cursor as visual input.

        dist:    px from fly to cursor
        bearing: radians, 0 = straight ahead, + = fly's left
        """
        R = 14.0                                   # virtual object radius, px
        alpha = 2 * math.atan(R / max(dist, 1.0))  # angular size (rad)
        if self._prev_alpha is None:
            self._prev_alpha = alpha
        expansion = (alpha - self._prev_alpha) / max(dt, 1e-3)  # rad/s
        self._prev_alpha = alpha

        left = _sig(bearing / 0.35)                # binocular overlap in front
        field = {"L": left, "R": 1.0 - left}
        frontal = max(math.cos(bearing), 0.0)
        looming = max(expansion, 0.0)

        lc4 = _clamp((looming - 0.4) / 2.5)                     # fast expansion
        lplc2 = _clamp(_sig((alpha - 0.35) / 0.08) * (0.3 + looming))
        lc16 = _clamp(frontal ** 2 * _sig((alpha - 0.18) / 0.05)
                      * (0.4 + 0.6 * _clamp(looming * 2)))
        lc10a = _clamp(_sig((0.25 - alpha) / 0.05) * _clamp(cursor_speed / 400)
                       * (1 - lc4))
        for s in SIDES:
            f = field[s]
            self.sens[f"LC4_{s}"] = lc4 * f
            self.sens[f"LPLC2_{s}"] = lplc2 * f
            self.sens[f"LC16_{s}"] = lc16 * f
            self.sens[f"LC10a_{s}"] = lc10a * f
        self.touch = 1.0 if touching else self.touch * 0.8

    def step(self, dt: float) -> dict:
        # Slow random drives so the fly explores when nothing is happening.
        self._wander += random.gauss(0, 1.2) * dt - self._wander * 0.8 * dt
        self._walk_urge += random.gauss(0, 0.5) * dt + (0.3 - self._walk_urge) * 0.3 * dt

        drive = {m: 0.0 for m in MOTOR}
        for s, r in self.sens.items():
            if r <= 0:
                continue
            for m, g in self.gains[s].items():
                drive[m] += g * r * 1.6
        for s in SIDES:
            drive[f"DNp01_{s}"] += self.touch            # mechanosensory startle
            drive[f"DNp09_{s}"] += max(self._walk_urge, 0)
            drive[f"DNa02_{s}"] += max(self._wander if s == "L" else -self._wander, 0)

        # Simple action selection: escape / backing up suppress forward walking.
        gf = max(self.rate["DNp01_L"], self.rate["DNp01_R"])
        mdn = max(self.rate["MDN_L"], self.rate["MDN_R"])
        for s in SIDES:
            drive[f"DNp09_{s}"] -= 1.5 * gf + 1.2 * mdn

        k = dt / self.TAU
        for m in MOTOR:
            target = _clamp(drive[m] + random.gauss(0, 0.02))
            self.rate[m] += k * (target - self.rate[m])

        r = self.rate
        return {
            "jump": gf > self.GF_THRESHOLD,
            "jump_bias": r["DNp01_L"] - r["DNp01_R"],     # + = threat on left
            "turn": r["DNa02_L"] - r["DNa02_R"],          # + = turn left
            "forward": (r["DNp09_L"] + r["DNp09_R"]) / 2,
            "backward": (r["MDN_L"] + r["MDN_R"]) / 2,
        }

    def dominant_path(self) -> str:
        """Human-readable description of the most active sensory->DN route."""
        best, best_v = None, 0.05
        for s, r in self.sens.items():
            for m, g in self.gains[s].items():
                v = r * g * self.rate[m]
                if v > best_v:
                    best, best_v = (s, m), v
        if self.touch > 0.3:
            return "touch (mechanosensory) -> DNp01 Giant Fiber"
        if not best:
            return "spontaneous walking (no strong sensory drive)"
        key = f"{best[0]}>{best[1]}"
        path = self.circuit["paths"].get(key, list(best))
        return " -> ".join(path)


# --------------------------------------------------------------------------- #
#  Tkinter overlay
# --------------------------------------------------------------------------- #

class FlyPet:
    SIZE = 96          # window size (px)
    SCALE = 1.35       # fly drawing scale
    FPS = 40

    def __init__(self, brain: FlyBrain):
        import tkinter as tk
        self.tk = tk
        self.brain = brain

        self.root = tk.Tk()
        self.root.title("Connectome Fly")
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
        self.jump_cooldown = 0.0
        self.paused = False
        self.panel = None
        self._last = time.perf_counter()
        self._last_cursor = self.root.winfo_pointerxy()

        self.menu = tk.Menu(self.root, tearoff=0)
        self.menu.add_command(label="Show brain activity", command=self.toggle_panel)
        self.menu.add_command(label="Pause / resume", command=self.toggle_pause)
        self.menu.add_separator()
        self.menu.add_command(label="Quit", command=self.root.destroy)
        self.canvas.bind("<Button-3>", self._popup)
        self.canvas.bind("<Button-2>", self._popup)   # macOS right-click
        self.root.bind("<Escape>", lambda e: self.root.destroy())

        self._place()
        self.root.after(0, self.tick)

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
        cx, cy = self.root.winfo_pointerxy()
        lx, ly = self._last_cursor
        cursor_speed = math.hypot(cx - lx, cy - ly) / max(dt, 1e-3)
        self._last_cursor = (cx, cy)

        dx, dy = cx - self.x, cy - self.y
        dist = math.hypot(dx, dy)
        # Screen y points down, so the fly's left is at heading - 90 degrees.
        bearing = _wrap(self.heading - math.atan2(dy, dx))
        self.brain.sense(dist, bearing, dt, touching=dist < 16,
                         cursor_speed=cursor_speed)
        motor = self.brain.step(dt)

        self.jump_cooldown = max(self.jump_cooldown - dt, 0.0)
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

        if motor["jump"] and self.jump_cooldown <= 0:
            self._start_jump(dx, dy, motor["jump_bias"])
            return

        self.heading = _wrap(self.heading - 4.0 * motor["turn"] * dt)
        speed = 150 * motor["forward"] - 120 * motor["backward"]
        self.x += math.cos(self.heading) * speed * dt
        self.y += math.sin(self.heading) * speed * dt
        self.leg_phase += abs(speed) * dt * 0.25

        # Walls: turn around instead of walking off-screen.
        m = self.SIZE / 2
        if not (m < self.x < self.sw - m and m < self.y < self.sh - m):
            self.x = min(max(self.x, m), self.sw - m)
            self.y = min(max(self.y, m), self.sh - m)
            to_centre = math.atan2(self.sh / 2 - self.y, self.sw / 2 - self.x)
            self.heading = _wrap(self.heading + 0.25 * _wrap(to_centre - self.heading))
        self._place()

    def _start_jump(self, dx, dy, bias):
        away = math.atan2(-dy, -dx) + random.uniform(-0.5, 0.5)
        # Giant Fiber asymmetry nudges the take-off direction further away.
        away += 0.6 * bias
        dist = random.uniform(140, 260)
        m = self.SIZE / 2
        x1 = min(max(self.x + math.cos(away) * dist, m), self.sw - m)
        y1 = min(max(self.y + math.sin(away) * dist, m), self.sh - m)
        self.jump = (time.perf_counter(), 0.28, (self.x, self.y), (x1, y1))
        self.heading = away
        self.jump_cooldown = 1.2

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

    def update_panel(self):
        c = self.pcanvas
        c.delete("all")
        c.create_text(10, 10, anchor="nw", fill="#9aa4b2", font=("TkDefaultFont", 9),
                      text=f"circuit: {self.brain.circuit['source']}")

        def bars(title, names, rates, x, colour):
            c.create_text(x, 34, anchor="nw", fill="#e6e9ee",
                          font=("TkDefaultFont", 10, "bold"), text=title)
            for i, n in enumerate(names):
                y = 56 + i * 24
                c.create_text(x, y, anchor="nw", fill="#c6ccd6",
                              font=("TkDefaultFont", 9), text=n)
                c.create_rectangle(x + 80, y + 2, x + 200, y + 14, outline="#3a404a")
                c.create_rectangle(x + 80, y + 2, x + 80 + 120 * rates[n], y + 14,
                                   fill=colour, width=0)

        bars("Sensory (visual)", SENSORY, self.brain.sens, 10, "#4aa3df")
        bars("Descending (motor)", MOTOR, self.brain.rate, 225, "#e0864a")
        c.create_text(10, 258, anchor="nw", fill="#e6e9ee",
                      font=("TkDefaultFont", 10, "bold"), text="Active path")
        c.create_text(10, 278, anchor="nw", fill="#f2d16b", width=420,
                      font=("TkDefaultFont", 9), text=self.brain.dominant_path())

    def run(self):
        self.root.mainloop()


def _wrap(a: float) -> float:
    return (a + math.pi) % (2 * math.pi) - math.pi


# --------------------------------------------------------------------------- #

def simulate(brain: FlyBrain, seconds: float):
    """Headless check: sweep a cursor toward the fly and print the response."""
    dt = 1 / 40
    t = 0.0
    while t < seconds:
        # Cursor starts far away on the fly's left and rushes in.
        dist = max(600 - 250 * t, 8)
        bearing = 0.6
        brain.sense(dist, bearing, dt, touching=dist < 16, cursor_speed=250)
        motor = brain.step(dt)
        if abs(t * 4 - round(t * 4)) < dt * 2:
            print(f"t={t:4.2f}s dist={dist:5.0f}px  "
                  + "  ".join(f"{k}={v:+.2f}" if isinstance(v, float) else f"{k}={v}"
                              for k, v in motor.items()))
            print("        path:", brain.dominant_path())
        t += dt


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build", action="store_true",
                    help="(re)build the circuit from FlyWire and cache it")
    ap.add_argument("--token", help="FlyWire CAVE token to store (implies --build)")
    ap.add_argument("--offline", action="store_true",
                    help="use the built-in fallback circuit")
    ap.add_argument("--materialization", type=int, default=783,
                    help="FlyWire materialization version (default: 783, public)")
    ap.add_argument("--simulate", type=float, metavar="SECONDS",
                    help="run the brain headless and print motor output")
    args = ap.parse_args()

    circuit = load_circuit(args)
    print(f"[connectome] circuit source: {circuit['source']}")
    brain = FlyBrain(circuit)
    if args.simulate:
        simulate(brain, args.simulate)
        return
    FlyPet(brain).run()


if __name__ == "__main__":
    main()
