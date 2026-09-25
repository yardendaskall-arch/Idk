#!/usr/bin/env python3
"""
Connectome Fly - desktop pet fruit flies, each run by a simulation of a whole
real fly brain.

  * The FEMALE runs on the FlyWire connectome (public release 783): all
    138,639 neurons of one adult female fly brain.
  * The MALE (optional, --male) runs on Janelia's Male CNS connectome (v0.9):
    all 165,114 neurons of one adult male fly's brain and nerve cord.

Every neuron is simulated as a spiking neuron, wired with its synapses.
Nothing in this file decides what a fly does. The code only:

  * SENSES: turns the world into spikes in sensory neurons
      - eyes: the cursor and the other fly excite visual neurons
        (LC4, LPLC2, LC16, LC10a, LC9, left and right optic lobe)
      - male forelegs: touching the female excites his pheromone-taste
        neurons (LgLG1, the ppk23 cells)
      - female antennae: the male's song excites her hearing neurons (JO-B)
        and song-tuned vpoEN neurons
      - mouth: standing on food excites sugar-taste neurons (LB3)
      - antennae: food odour excites olfactory neurons, dust excites
        Johnston's organ neurons (JO-C/E)
  * BRAIN: runs the spiking network (no behaviour rules inside it),
  * MEMORY: visual synapses weaken with harmless use and strengthen after a
           swat. Saved between runs.
  * AROUSAL: a slow hand-written stand-in for dopamine and hormones (not in
           any connectome). It rises while a fly senses a partner and drives
           his P1 courtship / her pC1 mating-drive neurons partway; her mood
           also wanders and drops after mating. A lab override can force
           P1 / pC1 fully on, as scientists do with light.
  * BODY: reads out descending neurons and moves the drawing:
             DNp01 (Giant Fiber) -> jump
             DNa02 (left/right)  -> turn toward that side
             DNp09               -> walk forward (driven by LC9: walk to objects)
             MDN                 -> walk backward
             pIP10 (male)        -> sing (wing extension)
             vpoDN (female)      -> accept a male (vaginal plate opening)
             DNp13 (female)      -> reject (ovipositor extrusion)
           (her yes and no neurons compete; whichever fires more wins)
             oviDN (female)      -> lay an egg (when its spikes reach a threshold)
             MN9                 -> extend the proboscis and eat
             DNg12               -> groom the head and antennae
  * LIFE CYCLE: eggs hatch into larvae, which pupate and emerge as new flies,
           each with its own whole brain (up to --max-flies brains at once).
  * DRAW: paints the flies.

Neuron model: leaky integrate-and-fire with the parameters of Shiu et al.
(2024, Nature), plus spike-frequency adaptation and weak random synaptic
noise so the network can run continuously. Synapse signs come from each
neuron's predicted neurotransmitter: acetylcholine excites, GABA / glutamate /
histamine inhibit, dopamine / serotonin / octopamine (slow neuromodulators)
have no fast effect. Sensory neurons fire only from sensory input.

Data (downloaded once, then cached in brain_data/):
  * female: github.com/philshiu/Drosophila_brain_model (FlyWire 783, ~130 MB)
    and FlyWire annotations via fafbseg
  * male: Male CNS v0.9 compiled by the BANC team (~3.4 GB download,
    male-cns.janelia.org), used under CC-BY

Usage
-----
    python fly_pet.py                # the female fly
    python fly_pet.py --male         # the female and a male
    python fly_pet.py --male --max-flies 6   # allow more hatched flies
    python fly_pet.py --noise 0.07   # more spontaneous brain activity
    python fly_pet.py --simulate 4   # no GUI: rush a cursor at the fly, print
    python fly_pet.py --rebuild      # rebuild the cached brain(s)
"""

from __future__ import annotations

import argparse
import math
import multiprocessing as mp
import random
import sys
import time
import urllib.request
from pathlib import Path

import numpy as np

# 1.0 hand-tuned circuit, 2.0 whole-brain simulation, 3.0 memory,
# 3.1 "what he sees and thinks" view, 3.2 split into three windows,
# 4.0 female + male flies, 4.1 walking (LC9), arousal, her choice,
# 4.2 courtship conditioning (he learns from rejection),
# 4.3 egg laying (oviDN), she approaches him when she says yes,
# 5.0 life cycle (eggs hatch into new flies), food and eating, dust and grooming
__version__ = "5.0.0"

HERE = Path(__file__).resolve().parent
DATA_DIR = HERE / "brain_data"
CACHE_FORMAT = {"female": 6, "male": 5}
CACHE_FILE = {"female": DATA_DIR / "brain_female_783.npz",
              "male": DATA_DIR / "brain_male_cns09.npz"}
REJECTION_FILE = DATA_DIR / "courtship_male.json"
MEMORY_FILE = {"female": DATA_DIR / "memory_female.npz",
               "male": DATA_DIR / "memory_male.npz"}

MODEL_REPO = "https://raw.githubusercontent.com/philshiu/Drosophila_brain_model/main/"
CONN_FILE = "Connectivity_783.parquet"
NEURON_FILE = "Completeness_783.csv"
ANNOT_URL = ("https://raw.githubusercontent.com/flyconnectome/flywire_annotations/"
             "main/supplemental_files/Supplemental_file1_neuron_annotations.tsv")
MALE_URL = ("https://storage.googleapis.com/lee-lab_brain-and-nerve-cord-fly-connectome/"
            "compiled_data/malecns_09/")
MALE_META = "malecns_09_meta.feather"
MALE_EDGES = "malecns_09_simple_edgelist.feather"
MALE_MIN_SYNAPSES = 3        # the male data detects ~5x more synapses per link
FEMALE_MEDIAN_INPUT = 218.0  # median synapses onto a FlyWire central neuron

SIDES = ("L", "R")
ANNOT_SIDE = {"L": "left", "R": "right"}
SENSORY_TYPES = ("LC4", "LPLC2", "LC16", "LC10a", "LC9")
MOTOR_TYPES = ("DNp01", "DNa02", "DNp09", "MDN")
SENSORY = [f"{t}_{s}" for t in SENSORY_TYPES for s in SIDES]
MOTOR = [f"{t}_{s}" for t in MOTOR_TYPES for s in SIDES]

# Sex-specific neuron groups: name -> regular expression on the cell type
EXTRA_GROUPS = {
    "female": {"JO-B": r"^JO-B",            # hears song
               "pC1": r"^pC1[a-e]$",        # mating drive
               "vpoEN": r"^vpoEN$",         # song -> receptivity
               "vpoDN": r"^DNp37$",         # vaginal plate opening = accept
               "DNp13": r"^DNp13$",         # ovipositor extrusion = reject
               # ascending neurons carrying body signals onto oviDN ("egg ready")
               "egg-AN": r"^(AN_multi_96|AN_SMP_3|AN_SLP_LH_1|AN_multi_18)$",
               "oviIN": r"^oviIN$",         # holds egg laying back (driven by pC1)
               "oviDN": r"^oviDN"},         # egg-laying command
    "male": {"LgLG1": r"^LgLG1[ab]$",       # foreleg taste of female pheromone
             "P1": r"^P1_",                 # courtship command
             "pIP10": r"^pIP10$"},          # courtship song
}
SENSE_INPUTS = {"female": ["JO-B", "vpoEN", "egg-AN"], "male": ["LgLG1"]}
# Everyday behaviour: taste sugar -> MN9 (proboscis, eat), as in Shiu et al.
# 2024; antennae touched -> DNg12 (anterior grooming, Guo et al. 2022).
# Food odour -> olfactory receptor neurons for fruity/fermenting smells.
FOOD_ODOUR_ORNS = r"^ORN_(DM1|DM2|DM4|VM2|DP1m)$"
BEHAVIOUR_GROUPS = {
    "female": {"sugar": r"^LB3", "MN9": r"^CB0701$",       # MN9 is CB0701 in FlyWire
               "JO-CE": r"^JO-(C|E)", "DNg12": r"^DNg12",
               "odour_L": (FOOD_ODOUR_ORNS, "L"), "odour_R": (FOOD_ODOUR_ORNS, "R")},
    "male": {"sugar": r"^LB3", "MN9": r"^MN9$",
             "JO-CE": r"^JO-C/D/E$", "DNg12": r"^DNg12",
             "odour_L": (FOOD_ODOUR_ORNS, "L"), "odour_R": (FOOD_ODOUR_ORNS, "R")},
}
BEHAVIOUR_INPUTS = ["sugar", "JO-CE", "odour_L", "odour_R"]
SWITCH = {"female": "pC1", "male": "P1"}     # driven by arousal (or a lab override)
ROLE = {"DNp01": "Giant Fiber: jump", "DNa02": "turn to this side",
        "DNp09": "walk forward", "MDN": "walk backward",
        "JO-B": "hearing song", "pC1": "mating drive", "vpoEN": "song → yes",
        "vpoDN": "yes: opens to mate", "DNp13": "no: rejects",
        "LgLG1": "tasting a female", "P1": "courtship mode", "pIP10": "sings",
        "egg-AN": "egg ready (from body)", "oviIN": "holds eggs back",
        "oviDN": "lay an egg", "sugar": "tasting sugar", "MN9": "proboscis: eat",
        "JO-CE": "dust on antennae", "DNg12": "groom",
        "odour_L": "smells food (left)", "odour_R": "smells food (right)"}


def group_names(sex: str) -> list[str]:
    return SENSORY + MOTOR + list(EXTRA_GROUPS[sex]) + list(BEHAVIOUR_GROUPS[sex])


def input_groups(sex: str) -> list[str]:
    return SENSORY + SENSE_INPUTS[sex] + BEHAVIOUR_INPUTS + [SWITCH[sex]]


INHIBITORY_NT = ("gaba", "glutamate", "histamine")
MODULATORY_NT = ("dopamine", "serotonin", "octopamine")


# --------------------------------------------------------------------------- #
#  Loading the connectomes
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
                print(f"\r[data]   {done / 1e6:7.1f} / {total / 1e6:.1f} MB",
                      end="", flush=True)
    print()
    tmp.replace(dest)


def _load_annotations():
    """FlyWire cell types / sides / neurotransmitters, via fafbseg if possible."""
    import pandas as pd
    cols = ["root_id", "cell_type", "side", "super_class", "top_nt", "pos_x", "pos_y"]
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


def _groups_from_table(cell_type, side, sex: str) -> dict:
    """Neuron indices for every group, from cell type + side columns."""
    groups = {}
    for g in SENSORY + MOTOR:
        ctype, s = g.rsplit("_", 1)
        groups[g] = np.flatnonzero(((cell_type == ctype) & (side == ANNOT_SIDE[s])).to_numpy())
    for g, pattern in {**EXTRA_GROUPS[sex], **BEHAVIOUR_GROUPS[sex]}.items():
        mask = cell_type.astype(str).str.match(pattern if isinstance(pattern, str)
                                                else pattern[0])
        if not isinstance(pattern, str):                   # (pattern, side)
            mask &= side == ANNOT_SIDE[pattern[1]]
        groups[g] = np.flatnonzero(mask.to_numpy())
    for g, idx in groups.items():
        print(f"[data]   {g:8s} {len(idx):4d} neurons")
    return groups


def _save_cache(sex, W, n, root_ids, sensory, visual, pos, left_x, groups):
    np.savez(CACHE_FILE[sex], fmt=CACHE_FORMAT[sex], sex=sex,
             indptr=W.indptr.astype(np.int64), indices=W.indices.astype(np.int32),
             data=W.data.astype(np.float32), n=n, root_ids=root_ids,
             sensory=np.flatnonzero(sensory), visual=np.flatnonzero(visual),
             pos=pos.astype(np.float32), left_x=np.float32(left_x),
             **{f"group_{g}": idx for g, idx in groups.items()})
    print(f"[data] saved {CACHE_FILE[sex].name}: {n:,} neurons, {W.nnz:,} connections")


def build_female_cache():
    """FlyWire 783 -> signed sparse weight matrix (synapse counts)."""
    import pandas as pd
    import scipy.sparse as sp

    for name in (CONN_FILE, NEURON_FILE):
        if not (DATA_DIR / name).exists():
            _download(MODEL_REPO + name, DATA_DIR / name)

    print("[data] building the female brain (one-off, takes ~30 s)...", flush=True)
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

    groups = _groups_from_table(ann.cell_type, ann.side, "female")
    pos = np.stack([ann.pos_x.to_numpy(np.float32), ann.pos_y.to_numpy(np.float32)], 1)
    left_x = np.nanmean(ann.pos_x[ann.side == "left"])
    _save_cache("female", W, n, root_ids, sensory,
                (ann.super_class == "visual_projection").to_numpy(), pos, left_x, groups)
    old = DATA_DIR / "brain_783.npz"                 # cache from before v4
    if old.exists():
        old.unlink()


def build_male_cache():
    """Janelia Male CNS v0.9 -> signed sparse weight matrix."""
    import pandas as pd
    import pyarrow as pa
    import pyarrow.compute as pc
    import pyarrow.ipc as ipc
    import scipy.sparse as sp

    male_dir = DATA_DIR / "male"
    if not (male_dir / MALE_META).exists():
        _download(MALE_URL + MALE_META, male_dir / MALE_META)
    if not (male_dir / MALE_EDGES).exists():
        _download(MALE_URL + MALE_EDGES, male_dir / MALE_EDGES)

    print("[data] building the male brain (one-off, takes a few minutes)...", flush=True)
    meta = pd.read_feather(male_dir / MALE_META)
    ids = meta.malecns_09_id.astype(np.int64).to_numpy()
    n = len(ids)
    index = pd.Index(ids)

    # Read the 143 million connections in chunks, keeping links of 3+ synapses.
    pre, post, count = [], [], []
    with pa.memory_map(str(male_dir / MALE_EDGES)) as src:
        reader = ipc.open_file(src)
        for i in range(reader.num_record_batches):
            b = reader.get_batch(i)
            b = b.filter(pc.greater_equal(b.column("count"), MALE_MIN_SYNAPSES))
            pre.append(index.get_indexer(pc.cast(b.column("pre"), pa.int64()).to_numpy()))
            post.append(index.get_indexer(pc.cast(b.column("post"), pa.int64()).to_numpy()))
            count.append(b.column("count").to_numpy().astype(np.float32))
    pre, post, count = np.concatenate(pre), np.concatenate(post), np.concatenate(count)
    ok = (pre >= 0) & (post >= 0)
    pre, post, count = pre[ok], post[ok], count[ok]

    # Acetylcholine excites, GABA/glutamate/histamine inhibit; neuromodulators
    # and "unclear" predictions (~9% of neurons here) get no fast effect.
    nt = meta.neurotransmitter_predicted.to_numpy(dtype=object)
    sign = np.zeros(n, np.float32)
    sign[nt == "acetylcholine"] = 1.0
    sign[np.isin(nt, INHIBITORY_NT)] = -1.0
    sensory = (meta.super_class == "sensory").to_numpy()
    keep = (sign[pre] != 0) & ~sensory[post]
    pre, post, count = pre[keep], post[keep], count[keep]

    # The male data finds more synapses per connection than FlyWire, so scale
    # so that a typical central-brain neuron gets the same total input.
    central = ((meta.region == "central_brain").to_numpy() & ~sensory)
    total_in = np.bincount(post, weights=count, minlength=n)
    scale = FEMALE_MEDIAN_INPUT / float(np.median(total_in[central & (total_in > 0)]))
    print(f"[data]   synapse scale {scale:.3f}")
    W = sp.csr_matrix((count * sign[pre] * scale, (pre, post)), shape=(n, n))
    W.sum_duplicates()

    groups = _groups_from_table(meta.cell_type, meta.side, "male")

    # No neuron positions in the male table: place each neuron at the position
    # of a FlyWire neuron of the matching cell type and side (for the brain map).
    ann = _load_annotations()
    pos = np.full((n, 2), np.nan, np.float32)
    by_type = {k: v[["pos_x", "pos_y"]].to_numpy(np.float32)
               for k, v in ann.groupby([ann.cell_type, ann.side])}
    used = {}
    for i, key in enumerate(zip(meta.fafb_cell_type, meta.side)):
        cand = by_type.get(key)
        if cand is not None:
            j = used.get(key, 0)
            pos[i] = cand[j % len(cand)]
            used[key] = j + 1
    left_x = np.nanmean(ann.pos_x[ann.side == "left"])
    _save_cache("male", W, n, ids, sensory,
                (meta.super_class == "visual_projection").to_numpy(), pos, left_x, groups)
    (male_dir / MALE_EDGES).unlink()          # 3.4 GB; --rebuild downloads it again
    print("[data] deleted the 3.4 GB download (the built brain is kept)")


def load_brain_data(sex: str, rebuild: bool = False) -> dict:
    path = CACHE_FILE[sex]
    if not rebuild and path.exists():
        with np.load(path) as z:
            rebuild = "fmt" not in z.files or int(z["fmt"]) != CACHE_FORMAT[sex]
    if rebuild or not path.exists():
        (build_female_cache if sex == "female" else build_male_cache)()
    z = np.load(path)
    return {
        "sex": sex, "n": int(z["n"]), "indptr": z["indptr"], "indices": z["indices"],
        "data": z["data"], "sensory": z["sensory"], "visual": z["visual"],
        "root_ids": z["root_ids"], "pos": z["pos"], "left_x": float(z["left_x"]),
        "groups": _load_groups(sex, z),
    }


def ensure_cache(sex: str):
    path = CACHE_FILE[sex]
    if path.exists():
        with np.load(path) as z:
            if "fmt" in z.files and int(z["fmt"]) == CACHE_FORMAT[sex]:
                return
    load_brain_data(sex, rebuild=True)


def _load_groups(sex: str, z) -> dict:
    """Groups from the cache; groups added in a later version are looked up in
    the cell-type table instead, so the (big) cache doesn't need rebuilding."""
    groups = {g: z[f"group_{g}"] for g in group_names(sex) if f"group_{g}" in z.files}
    missing = [g for g in group_names(sex) if g not in groups]
    if not missing:
        return groups
    side_file = DATA_DIR / f"groups_{sex}.npz"
    if side_file.exists():
        with np.load(side_file) as extra:
            groups.update({g: extra[g] for g in missing if g in extra.files})
    missing = [g for g in group_names(sex) if g not in groups]
    if missing:
        import pandas as pd
        if sex == "female":
            ann = _load_annotations().drop_duplicates("root_id").set_index("root_id")
            table = ann.reindex(z["root_ids"])
        else:
            meta_path = DATA_DIR / "male" / MALE_META
            if not meta_path.exists():
                _download(MALE_URL + MALE_META, meta_path)
            table = pd.read_feather(meta_path)
        found = _groups_from_table(table.cell_type.reset_index(drop=True),
                                   table.side.reset_index(drop=True), sex)
        groups.update({g: found[g] for g in missing})
        old = dict(np.load(side_file)) if side_file.exists() else {}
        np.savez(side_file, **old, **{g: found[g] for g in missing})
    return groups


def load_brain_layout(sex: str) -> dict:
    """The light parts of a cached brain the GUI needs (no connections)."""
    with np.load(CACHE_FILE[sex]) as z:
        groups = _load_groups(sex, z)
        return {"n": int(z["n"]), "pos": z["pos"], "left_x": float(z["left_x"]),
                "group_size": np.array([max(len(groups[g]), 1)
                                        for g in group_names(sex)])}


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
        self.sex = data["sex"]
        self.n = n = data["n"]
        self.indptr, self.indices = data["indptr"], data["indices"]
        self.weights = data["data"] * f32(self.W_SYN)
        self.groups = data["groups"]
        self.group_names = group_names(self.sex)
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
        # The object-tracking neurons (LC10a, LC9) fire almost all the time,
        # so they're left out: otherwise flies would soon stop tracking each
        # other and food. Memory is about the threat pathways.
        tracking = np.concatenate([self.groups[g] for g in SENSORY
                                   if g.startswith(("LC10a", "LC9"))])
        self.plastic = np.setdiff1d(data["visual"], tracking)
        self._is_plastic = np.zeros(n, bool)
        self._is_plastic[self.plastic] = True
        self.strength = np.ones(n, f32)
        self.eligibility = np.zeros(n, f32)
        self._hurt = False

        self._group_of = np.full(n, -1, np.int16)
        for i, g in enumerate(self.group_names):
            self._group_of[self.groups[g]] = i
        self.group_size = np.array([max(len(self.groups[g]), 1) for g in self.group_names])

        # Hz per input group, set by the senses (and the optogenetic switch).
        self.input_hz = {g: 0.0 for g in input_groups(self.sex)}
        # Cumulative spike counters, read by the body without locking.
        self.group_spikes = np.zeros(len(self.group_names), np.int64)
        self.spike_count = np.zeros(n, np.int32)
        self.total_spikes = 0
        self.steps = 0

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
                self.group_spikes += np.bincount(g, minlength=len(self.group_names))
        self.total_spikes += len(s)
        self.spike_count[s] += 1
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
        print(f"[memory] {self.sex}: loaded (last run {away / 60:.0f} min ago)")


# --------------------------------------------------------------------------- #
#  Each brain runs in its own process; the GUI talks to it via shared memory
# --------------------------------------------------------------------------- #

def _brain_process(sex, noise, inputs, group_spikes, spike_count, stats, memory,
                   hurt, stop, memory_file):
    brain = WholeBrain(load_brain_data(sex), noise=noise)
    brain.group_spikes = np.frombuffer(group_spikes, np.int64)
    brain.spike_count = np.frombuffer(spike_count, np.int32)
    inp = np.frombuffer(inputs, np.float64)
    mem = np.frombuffer(memory, np.float64)
    names = input_groups(sex)
    if memory_file:
        brain.load_memory(memory_file)
    stats[2] = 1.0                                   # ready
    t0, s0 = time.perf_counter(), brain.steps
    last_mem = last_save = time.perf_counter()
    while not stop.is_set():
        for i, g in enumerate(names):
            brain.input_hz[g] = float(inp[i])
        if hurt.value:
            hurt.value = 0
            brain.hurt()
        for _ in range(10):
            brain.step()
        now = time.perf_counter()
        wall, sim = now - t0, (brain.steps - s0) * brain.DT / 1000
        if sim > wall:                               # never run faster than life
            time.sleep(sim - wall)
        if wall > 2:
            stats[1] = sim / max(wall, 1e-6)
            t0, s0 = time.perf_counter(), brain.steps
        stats[0] = brain.total_spikes
        stats[3] = brain.steps
        if now - last_mem > 0.5:
            mem[:] = [brain.memory_of(g) for g in SENSORY]
            last_mem = now
        if memory_file and now - last_save > 60:
            brain.save_memory(memory_file)
            last_save = now
    if memory_file:
        brain.save_memory(memory_file)


class BrainLink:
    """The GUI's handle on a brain running in another process."""

    def __init__(self, sex: str, noise: float, memory_file: Path | None = None):
        ctx = mp.get_context("spawn")
        ensure_cache(sex)                            # build the cache if needed
        layout = load_brain_layout(sex)
        self.sex = sex
        self.n, self.pos, self.left_x = layout["n"], layout["pos"], layout["left_x"]
        self.group_names = group_names(sex)
        self.group_size = layout["group_size"]
        self._input_names = input_groups(sex)
        self._inputs = ctx.RawArray("d", len(self._input_names))
        self._group_spikes = ctx.RawArray("q", len(self.group_names))
        self._spike_count = ctx.RawArray("i", self.n)
        self._stats = ctx.RawArray("d", 4)          # total spikes, speed, ready, steps
        self._memory = ctx.RawArray("d", len(SENSORY))
        self._hurt = ctx.RawValue("i", 0)
        self._stop = ctx.Event()
        self.input_hz = {g: 0.0 for g in self._input_names}
        self.group_spikes = np.frombuffer(self._group_spikes, np.int64)
        self.spike_count = np.frombuffer(self._spike_count, np.int32)
        self._mem = np.frombuffer(self._memory, np.float64)
        self._mem[:] = 1.0
        self.proc = ctx.Process(
            target=_brain_process, daemon=True,
            args=(sex, noise, self._inputs, self._group_spikes, self._spike_count,
                  self._stats, self._memory, self._hurt, self._stop, memory_file))
        self.proc.start()

    def set_inputs(self, hz: dict):
        self.input_hz.update(hz)
        for i, g in enumerate(self._input_names):
            self._inputs[i] = self.input_hz[g]

    def hurt(self):
        self._hurt.value = 1

    def memory_of(self, group: str) -> float:
        return float(self._mem[SENSORY.index(group)])

    @property
    def total_spikes(self) -> int:
        return int(self._stats[0])

    @property
    def speed(self) -> float:
        return self._stats[1] or 1.0

    @property
    def ready(self) -> bool:
        return self._stats[2] > 0

    @property
    def steps(self) -> int:
        """Brain time in ms since it started."""
        return int(self._stats[3])

    def stop(self):
        self._stop.set()
        self.proc.join(timeout=5)


# --------------------------------------------------------------------------- #
#  Eyes: objects in view -> spike rates of visual projection neurons
# --------------------------------------------------------------------------- #

def _sig(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-max(min(x, 50.0), -50.0)))


def _clamp(x: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return lo if x < lo else hi if x > hi else x


class Eyes:
    MAX_HZ = 150.0
    BLIND_SPOT = math.radians(165)   # can't see further back than this

    def __init__(self):
        self._prev_alpha = {}
        self._prev_bearing = {}
        self.hz = {g: 0.0 for g in SENSORY}
        self.seen = []               # what's in view, for the "what she sees" window

    MICROSACCADE = 0.15   # rad/s: flies jiggle their retinas, so still things
                          # still move a little on the eye (Fenk et al. 2022)

    def see(self, objects, dt: float):
        """objects: (name, dist px, bearing rad [+ = fly's left], radius px,
        speed px/s, gain). `gain` scales the object-tracking neurons (LC10a,
        LC9) for that object: arousal turns up a fly's visual response to its
        partner (Hindmarsh Sten et al. 2021)."""
        feature_sum = {g: 0.0 for g in SENSORY}
        self.seen = []
        for name, dist, bearing, radius, speed, gain in objects:
            alpha = 2 * math.atan(radius / max(dist, 1.0))        # angular size
            prev = self._prev_alpha.get(name, alpha)
            looming = max((alpha - prev) / max(dt, 1e-3), 0.0)    # rad/s
            self._prev_alpha[name] = alpha
            # How fast it sweeps across the eye (its own motion or the fly's turning)
            sweep = (abs(_wrap(bearing - self._prev_bearing.get(name, bearing)))
                     / max(dt, 1e-3) + self.MICROSACCADE)
            self._prev_bearing[name] = bearing

            visible = _sig((self.BLIND_SPOT - abs(bearing)) / 0.05)
            left = _sig(bearing / 0.35)            # binocular overlap in front
            field = {"L": left * visible, "R": (1.0 - left) * visible}
            frontal = max(math.cos(bearing), 0.0)
            feature = {
                "LC4": _clamp((looming - 0.4) / 2.5),                   # fast expansion
                "LPLC2": _clamp(_sig((alpha - 0.35) / 0.08) * looming),  # collision course
                "LC16": _clamp(frontal ** 2 * _sig((alpha - 0.18) / 0.05)
                               * (0.4 + 0.6 * _clamp(looming * 2))),    # frontal approach
                "LC10a": _clamp(gain * _sig((0.25 - alpha) / 0.05)
                                * _clamp(sweep / 1.2)),                 # small moving object
                "LC9": _clamp(gain * _sig((0.3 - alpha) / 0.06)
                              * _clamp(sweep / 1.2)),                   # small object
            }
            for g in SENSORY:
                ctype, side = g.rsplit("_", 1)
                feature_sum[g] += feature[ctype] * field[side]
            self.seen.append({"name": name, "bearing": bearing, "alpha": alpha,
                              "looming": looming, "speed": speed, "visible": visible})
        for g in SENSORY:
            self.hz[g] = self.MAX_HZ * _clamp(feature_sum[g])
        return self.hz


# --------------------------------------------------------------------------- #
#  Body: descending neuron firing -> movement
# --------------------------------------------------------------------------- #

class Body:
    TURN_PER_HZ = 0.02       # rad/s per Hz of DNa02 left-right difference
    WALK_PER_HZ = 3.0        # px/s per Hz of DNp09
    BACK_PER_HZ = 3.0        # px/s per Hz of MDN
    JUMP_HZ = 25.0           # Giant Fiber rate that launches a jump
    SING_HZ = 30.0           # pIP10 rate that extends a wing (male song)
    OPEN_HZ = 20.0           # vpoDN rate that opens the vaginal plate (female)
    REJECT_HZ = 12.0         # DNp13 rate that extrudes the ovipositor (female)
    EAT_HZ = 30.0            # MN9 rate that extends the proboscis
    GROOM_HZ = 10.0          # DNg12 rate that starts anterior grooming

    def __init__(self, brain):
        self.brain = brain
        self.names = brain.group_names
        self.hz = {g: 0.0 for g in self.names}
        self._last = brain.group_spikes.copy()
        self._last_steps = brain.steps

    def read(self, dt: float) -> dict:
        # Rates per second of *brain* time, so a brain running slower than
        # real time still reports its true firing rates.
        steps = self.brain.steps
        brain_dt = (steps - self._last_steps) * WholeBrain.DT / 1000
        if brain_dt > 0:
            now = self.brain.group_spikes.copy()
            rates = (now - self._last) / self.brain.group_size / brain_dt
            self._last, self._last_steps = now, steps
            for i, g in enumerate(self.names):
                tau = 0.05 if g.startswith("DNp01") else 0.15
                a = min(brain_dt / tau, 1.0)
                self.hz[g] += a * (rates[i] - self.hz[g])
        h = self.hz
        return {
            "turn": self.TURN_PER_HZ * (h["DNa02_L"] - h["DNa02_R"]),   # + = left
            "speed": (self.WALK_PER_HZ * (h["DNp09_L"] + h["DNp09_R"]) / 2
                      - self.BACK_PER_HZ * (h["MDN_L"] + h["MDN_R"]) / 2),
            "jump": max(h["DNp01_L"], h["DNp01_R"]) > self.JUMP_HZ,
            "sing": h.get("pIP10", 0.0) > self.SING_HZ,
            # Her yes (vpoDN) and no (DNp13) neurons compete; the stronger wins.
            "open": (h.get("vpoDN", 0.0) > self.OPEN_HZ
                     and h.get("vpoDN", 0.0) > 1.2 * h.get("DNp13", 0.0)),
            "reject": (h.get("DNp13", 0.0) > self.REJECT_HZ
                       and h.get("DNp13", 0.0) >= h.get("vpoDN", 0.0)),
            "eat": h.get("MN9", 0.0) > self.EAT_HZ,
            "groom": h.get("DNg12", 0.0) > self.GROOM_HZ,
        }


# --------------------------------------------------------------------------- #
#  Arousal: the slow internal state the connectome doesn't contain
# --------------------------------------------------------------------------- #

class Arousal:
    """A hand-written stand-in for dopamine and hormones.

    In real flies, P1 (male courtship) and pC1 (female mating drive) are set
    by slow signals - dopamine, hormones, and whether the fly has just mated -
    that build up over seconds to minutes. The connectome only has fast
    synapses, and in the simulation neither P1 nor pC1 switches on from the
    senses alone. So each fly gets one slow number, 0..1, that rises while it
    senses a partner and fades otherwise, and drives P1 / pC1 partway. What
    happens next (singing, saying yes or no) is up to the simulated brain.
    """

    P1_HZ = 60.0        # male: P1 rate at full arousal
    PC1_HZ = 50.0       # female: pC1 rate at full arousal and best mood

    def __init__(self, sex: str, remember: bool = False):
        self.sex = sex
        self.remember = remember
        self.level = 0.0
        # Female only: a slowly wandering willingness to mate, different each run.
        self.mood = random.uniform(0.2, 0.9)
        self.mated_at = -1e9
        # Male only: courtship conditioning. Seconds of rejection he has
        # experienced, fading over ~15 minutes (in real flies, hours).
        self.rejected = 0.0
        if sex == "male" and remember:
            self._load_rejections()

    REJECTION_TAU = 900.0      # s

    def interest(self) -> float:
        """How much being near her still excites him (1 = fully, 0 = not)."""
        return 1.0 / (1.0 + self.rejected / 10.0)

    def was_rejected(self, dt: float):
        """She's rejecting him up close: he loses interest now, and learns."""
        self.rejected += dt
        self.level *= 1 - 0.5 * dt

    def _load_rejections(self):
        try:
            import json
            d = json.loads(REJECTION_FILE.read_text())
            away = max(time.time() - d["saved"], 0.0)
            self.rejected = d["rejected"] * math.exp(-away / self.REJECTION_TAU)
        except (OSError, ValueError, KeyError):
            pass

    def save_rejections(self):
        if not self.remember:
            return
        import json
        REJECTION_FILE.write_text(json.dumps({"rejected": self.rejected,
                                              "saved": time.time()}))

    def update(self, dt: float, now: float, cue: float):
        """cue 0..1: how strongly the fly senses a partner right now."""
        if self.sex == "male":
            sated = now - self.mated_at < 60           # males rest after mating
            # Rejection lowers how high his arousal can climb.
            rise = 0.0 if sated else 0.5 * cue * max(self.interest() - self.level, 0.0)
            self.level += (rise - self.level / 30) * dt
            self.rejected *= math.exp(-dt / self.REJECTION_TAU)
        else:
            self.level += (0.4 * cue * (1 - self.level) - self.level / 40) * dt
            # Mood drifts around 0.5 over minutes. After mating it drops to 0 and
            # stays low for several minutes: sex peptide from the male makes
            # real mated females reject males for days.
            since = now - self.mated_at
            target = 0.5 * (1 - math.exp(-since / 300))
            self.mood += ((target - self.mood) * dt / 90
                          + random.gauss(0, 0.2 * math.sqrt(2 * dt / 90)))
        self.level = _clamp(self.level)
        self.mood = _clamp(self.mood)

    def just_mated(self, now: float):
        self.mated_at = now
        self.level = 0.0
        if self.sex == "female":
            self.mood = 0.0

    def drive_hz(self) -> float:
        if self.sex == "male":
            return self.P1_HZ * self.level
        return self.PC1_HZ * self.mood * self.level


# --------------------------------------------------------------------------- #
#  Things on the screen that aren't flies: food, eggs, larvae, pupae
# --------------------------------------------------------------------------- #

PRONOUNS = {"female": ("she", "her", "her"), "male": ("he", "his", "him")}


def _wrap(a: float) -> float:
    return (a + math.pi) % (2 * math.pi) - math.pi


class Food:
    """A drop of sugary food. Flies see it as a small object and taste it."""
    RADIUS = 6

    def __init__(self, app, x: float, y: float):
        self.app, self.x, self.y = app, x, y
        self.amount = 1.0
        self.win = app.small_window(20, x, y)
        self.canvas = self.win.children_canvas
        self.draw()

    def draw(self):
        c = self.canvas
        c.delete("all")
        r = 2 + 6 * math.sqrt(max(self.amount, 0.0))
        c.create_oval(10 - r, 10 - r * 0.8, 10 + r, 10 + r * 0.8,
                      fill="#f2c94c", outline="#c9971c")
        c.create_oval(10 - r * 0.4, 10 - r * 0.5, 10 - r * 0.1, 10 - r * 0.2,
                      fill="#fff4c2", outline="")

    def eat(self, amount: float):
        self.amount -= amount
        self.draw()

    def destroy(self):
        self.win.destroy()


class Brood:
    """An egg that hatches into a larva, which pupates and ecloses as an adult.

    Real fruit flies take ~10 days (egg 1 day, larva 4 days, pupa 4-5 days);
    here it's a few minutes. Larvae and pupae are drawn, not simulated: their
    brains (a separate, much smaller larval connectome) aren't included.
    """
    EGG_SECONDS = 60
    LARVA_SECONDS = 120
    PUPA_SECONDS = 60

    def __init__(self, app, x: float, y: float):
        self.app, self.x, self.y = app, x, y
        self.stage = "egg"
        self.stage_start = time.perf_counter()
        self.heading = random.uniform(-math.pi, math.pi)
        self.wiggle = 0.0
        self.size = 1.0
        self.win = app.small_window(28, x, y)
        self.canvas = self.win.children_canvas
        self.done = False
        self.draw()

    def update(self, dt: float, now: float):
        age = now - self.stage_start
        if self.stage == "egg" and age > self.EGG_SECONDS:
            self.stage, self.stage_start = "larva", now
        elif self.stage == "larva":
            # Larvae crawl about with peristaltic waves and eat what they find.
            self.wiggle += dt * 6
            self.heading = _wrap(self.heading + random.gauss(0, 1.5) * dt)
            step = 10 * (0.6 + 0.4 * math.sin(self.wiggle)) * dt
            self.x = min(max(self.x + math.cos(self.heading) * step, 20), self.app.sw - 20)
            self.y = min(max(self.y + math.sin(self.heading) * step, 20), self.app.sh - 20)
            for food in self.app.food:
                if math.hypot(food.x - self.x, food.y - self.y) < 14:
                    food.eat(0.01 * dt)
                    self.size = min(self.size + 0.05 * dt, 1.8)
            self.place()
            if age > self.LARVA_SECONDS:
                self.stage, self.stage_start = "pupa", now
        elif self.stage == "pupa" and age > self.PUPA_SECONDS:
            self.done = True
            self.app.eclose(self.x, self.y)
        self.draw()

    def place(self):
        self.win.geometry(f"28x28+{int(self.x) - 14}+{int(self.y) - 14}")

    def draw(self):
        c = self.canvas
        c.delete("all")
        cos_h, sin_h = math.cos(self.heading), math.sin(self.heading)

        def tr(px, py):
            return 14 + px * cos_h - py * sin_h, 14 + px * sin_h + py * cos_h

        if self.stage == "egg":
            c.create_oval(10, 8, 18, 20, fill="#fbf8ef", outline="#c9c2ae")
            c.create_line(12, 9, 10, 5, fill="#c9c2ae")            # dorsal filaments
            c.create_line(16, 9, 18, 5, fill="#c9c2ae")
        elif self.stage == "larva":
            k = self.size
            for i in range(6):                                     # segments
                px = (5 - 2 * i) * k
                r = (2.4 - 0.15 * abs(i - 2)) * k * (1 + 0.1 * math.sin(self.wiggle - i))
                x, y = tr(px, 0)
                c.create_oval(x - r, y - r, x + r, y + r, fill="#f4f1e6",
                              outline="#d8d2bd")
            x, y = tr(6.5 * k, 0)
            c.create_oval(x - 1, y - 1, x + 1, y + 1, fill="#222", outline="")  # mouth hooks
        else:
            shade = min((time.perf_counter() - self.stage_start) / self.PUPA_SECONDS, 1)
            col = "#%02x%02x%02x" % (int(200 - 110 * shade), int(150 - 90 * shade),
                                     int(80 - 50 * shade))
            x0, y0 = tr(-7, 0)
            x1, y1 = tr(7, 0)
            c.create_line(x0, y0, x1, y1, fill=col, width=7, capstyle="round")
            x, y = tr(7, 2)
            c.create_line(x, y, *tr(9, 3), fill="#5a3a1a")        # spiracles
            x, y = tr(7, -2)
            c.create_line(x, y, *tr(9, -3), fill="#5a3a1a")

    def destroy(self):
        self.win.destroy()


class LeavingFly:
    """A newly hatched fly that flies away (when the brain limit is reached)."""

    def __init__(self, app, x: float, y: float):
        self.app, self.x, self.y = app, x, y
        self.vx, self.vy = random.uniform(-80, 80), -160
        self.win = app.small_window(28, x, y)
        c = self.win.children_canvas
        c.create_oval(9, 11, 19, 19, fill="#8a6a3c", outline="#3a2816")
        c.create_oval(4, 6, 12, 12, fill="#cfe3ea", outline="#8aa6b0")
        c.create_oval(16, 6, 24, 12, fill="#cfe3ea", outline="#8aa6b0")
        self.done = False

    def update(self, dt: float, now: float):
        self.x += self.vx * dt
        self.y += self.vy * dt
        self.win.geometry(f"28x28+{int(self.x) - 14}+{int(self.y) - 14}")
        if self.y < -30:
            self.done = True

    def destroy(self):
        self.win.destroy()


# --------------------------------------------------------------------------- #
#  A fly on the screen
# --------------------------------------------------------------------------- #

class Fly:
    SIZE = 96          # window size (px)
    MATURE_AFTER = 180             # s: newly hatched flies can't court at first
    EGG_RIPEN_SECONDS = 15         # one fertilised egg ripens this often after mating
    EGGS_PER_MATING = 10           # (real flies: dozens per day, for days)
    OVI_THRESHOLD = 12             # oviDN spikes that trigger laying (rise to threshold)

    def __init__(self, app, sex: str, brain: BrainLink, x: float, y: float,
                 label: str, newborn: bool = False):
        tk = app.tk
        self.app, self.sex, self.brain, self.label = app, sex, brain, label
        self.he, self.his, self.him = PRONOUNS[sex]
        self.scale = 1.35 if sex == "female" else 1.15      # females are bigger
        self.radius = 12 if sex == "female" else 10         # as seen by others
        self.eyes = Eyes()
        self.body = Body(brain)
        self.motor = {"turn": 0.0, "speed": 0.0, "jump": False, "sing": False,
                      "open": False, "reject": False, "eat": False, "groom": False}
        self.x, self.y = x, y
        self.heading = random.uniform(-math.pi, math.pi)
        self.leg_phase = 0.0
        self.jump = None            # (t0, duration, from, to)
        self.speed_px = 0.0         # how fast it actually moves (for others' eyes)
        self.born = time.perf_counter() if newborn else -1e9
        self.taste = 0.0            # male: forelegs touching a female
        self.hearing = 0.0          # female: loudness of a male's song
        self.sing_side = 1          # male: wing toward the female (+1 = left)
        self.mated_until = 0.0
        self.eggs_ripe = 0          # female: fertilised eggs ready to lay
        self.eggs_to_come = 0       # female: eggs that will still ripen
        self._egg_timer = 0.0
        self._ovi_spikes = 0.0      # oviDN spikes since the egg was ready
        self._last_ovi = None
        self.eggs_laid = 0
        self.laying_until = 0.0
        self.hunger = random.uniform(0.2, 0.5)   # 0 = full, 1 = starving
        self.dust = 0.0             # dust on the antennae, 0..1
        self.food_here = None       # the food drop the fly is standing on
        self.swatted_at = 0.0
        self._press = None
        self.dragging = False
        self.switch_on = tk.BooleanVar(value=False)    # lab override
        self.arousal = Arousal(sex, remember=(label == "the male"))

        self.win = tk.Toplevel(app.root)
        self.win.title(f"Connectome Fly v{__version__} ({label})")
        self.win.overrideredirect(True)
        self.win.wm_attributes("-topmost", True)
        bg = app.transparent_background(self.win)
        self.canvas = tk.Canvas(self.win, width=self.SIZE, height=self.SIZE,
                                bg=bg, highlightthickness=0, bd=0)
        self.canvas.pack()

        His = self.his.capitalize()
        self.menu = tk.Menu(self.win, tearoff=0)
        self.menu.add_command(label=f"{label.capitalize()} ({sex})", state="disabled")
        self.menu.add_command(label=f"What {self.he} sees",
                              command=lambda: app.toggle_window(self, "eyes"))
        self.menu.add_command(label=f"{His} brain",
                              command=lambda: app.toggle_window(self, "brain"))
        self.menu.add_command(label=f"What {self.he}'s doing",
                              command=lambda: app.toggle_window(self, "neurons"))
        self.menu.add_command(label="Open all three",
                              command=lambda: app.open_all_windows(self))
        self.menu.add_separator()
        self.menu.add_command(label=f"Put food in front of {self.him}",
                              command=lambda: app.add_food(*self.ahead(45)))
        self.menu.add_checkbutton(
            label=f"Lab override: force {self.his} {SWITCH[sex]} neurons on",
            variable=self.switch_on)
        self.menu.add_separator()
        self.menu.add_command(label="Pause / resume", command=app.toggle_pause)
        self.menu.add_command(label="Quit", command=app.quit)
        self.canvas.bind("<Button-3>", self._popup)
        self.canvas.bind("<Button-2>", self._popup)          # macOS right-click
        self.canvas.bind("<ButtonPress-1>", self._on_press)
        self.canvas.bind("<B1-Motion>", self._on_drag)
        self.canvas.bind("<ButtonRelease-1>", self._on_release)
        self.place()

    @property
    def mature(self) -> bool:
        return time.perf_counter() - self.born > self.MATURE_AFTER

    # ------------------------------------------------------------ mouse
    def _popup(self, event):
        self.menu.tk_popup(event.x_root, event.y_root)

    def _on_press(self, event):
        self._press = (event.x_root, event.y_root, self.x, self.y)
        self.dragging = False

    def _on_drag(self, event):
        if not self._press:
            return
        x0, y0, fx, fy = self._press
        if abs(event.x_root - x0) + abs(event.y_root - y0) > 5:
            self.dragging = True
            self.jump = None
            self.x, self.y = fx + event.x_root - x0, fy + event.y_root - y0
            self.place()

    def _on_release(self, event):
        if self._press and not self.dragging:       # a click, not a drag: swat
            self.brain.hurt()
            self.swatted_at = time.perf_counter()
            self.dust = min(self.dust + 0.3, 1.0)   # a knock ruffles the antennae
        self._press = None
        self.dragging = False

    # ------------------------------------------------------------ senses + body
    def sense(self, dt: float, cursor, cursor_speed: float, others, food):
        # Arousal turns up the eyes' response to the partner: his with his
        # arousal, hers while she's saying yes - so she walks to him.
        if self.sex == "male":
            partner_gain = 1.0 + 3.0 * self.arousal.level
        else:
            partner_gain = 4.0 if self.motor["open"] else 1.0
        things = [("cursor", cursor, 14.0, cursor_speed, 1.0)]
        for o in others:
            partner = o.sex != self.sex and o.mature and self.mature
            things.append((o.label, (o.x, o.y), o.radius, o.speed_px,
                           partner_gain if partner else 1.0))
        for i, f in enumerate(food):
            things.append((f"food {i + 1}", (f.x, f.y), Food.RADIUS, 0.0, 1.0))
        objects = []
        for name, (ox, oy), radius, speed, gain in things:
            dx, dy = ox - self.x, oy - self.y
            # Screen y points down, so the fly's left is at heading - 90 degrees.
            bearing = _wrap(self.heading - math.atan2(dy, dx))
            objects.append((name, math.hypot(dx, dy), bearing, radius, speed, gain))
        hz = dict(self.eyes.see(objects, dt))
        if self.sex == "female":
            hz["JO-B"] = 150.0 * self.hearing     # antennal hearing neurons
            hz["vpoEN"] = 60.0 * self.hearing     # song-tuned neurons (Wang et al. 2021)
            hz["egg-AN"] = 120.0 if self.eggs_ripe else 0.0   # an egg is in the uterus
        else:
            hz["LgLG1"] = 120.0 * self.taste
        # Taste: standing on food excites the sugar neurons, more when hungry
        # (hunger makes real sugar neurons more sensitive, Inagaki et al. 2012;
        # a full fly barely responds).
        hx, hy = self.head()
        self.food_here = next((f for f in food
                               if math.hypot(f.x - hx, f.y - hy) < Food.RADIUS + 8), None)
        hz["sugar"] = 100.0 * self.hunger if self.food_here else 0.0
        # Dust on the antennae deflects them: Johnston's organ neurons fire.
        hz["JO-CE"] = 120.0 * self.dust
        # Smell: food odour reaches the antenna on the side it comes from.
        smell = {"L": 0.0, "R": 0.0}
        for fd in food:
            d = math.hypot(fd.x - self.x, fd.y - self.y)
            bearing = _wrap(self.heading - math.atan2(fd.y - self.y, fd.x - self.x))
            conc = 1.5 * fd.amount / (1 + (d / 400) ** 2)
            left = _sig(bearing / 0.25)
            smell["L"] += conc * left
            smell["R"] += conc * (1 - left)
        hz["odour_L"] = 150.0 * _clamp(smell["L"])
        hz["odour_R"] = 150.0 * _clamp(smell["R"])
        hz[SWITCH[self.sex]] = 100.0 if self.switch_on.get() else self.arousal.drive_hz()
        self.brain.set_inputs(hz)
        self.motor = self.body.read(dt)

    def live(self, dt: float):
        """Slow body processes: getting hungry and dusty, eating, grooming."""
        self.hunger = min(self.hunger + dt / 600, 1.0)      # hungry in ~10 min
        self.dust = min(self.dust + dt / 180, 1.0)          # dusty in ~3 min
        if self.motor["eat"] and self.food_here:
            self.food_here.eat(0.03 * dt)
            self.hunger = max(self.hunger - 0.15 * dt, 0.0)
        if self.motor["groom"]:
            self.dust = max(self.dust - 0.2 * dt, 0.0)
            self.leg_phase += 12 * dt

    def busy(self) -> bool:
        """Eating and grooming flies stand still."""
        return (self.motor["eat"] and self.food_here is not None) or self.motor["groom"]

    def move(self, dt: float, frozen: bool):
        x0, y0 = self.x, self.y
        motor = self.motor
        if frozen or self.dragging:
            pass
        elif self.jump:
            t0, dur, (jx0, jy0), (jx1, jy1) = self.jump
            u = (time.perf_counter() - t0) / dur
            if u >= 1:
                self.jump = None
                self.x, self.y = jx1, jy1
            else:
                e = 1 - (1 - u) ** 3
                self.x, self.y = jx0 + (jx1 - jx0) * e, jy0 + (jy1 - jy0) * e
        elif motor["jump"]:
            self._start_jump()
        elif not self.busy():
            self.heading = _wrap(self.heading - motor["turn"] * dt)
            speed = motor["speed"]
            self.x += math.cos(self.heading) * speed * dt
            self.y += math.sin(self.heading) * speed * dt
            self.leg_phase += (abs(speed) + 20 * abs(motor["turn"])) * dt * 0.25
            # The screen edge is a wall: the fly can't walk through it.
            m = self.SIZE / 2
            self.x = min(max(self.x, m), self.app.sw - m)
            self.y = min(max(self.y, m), self.app.sh - m)
        self.speed_px = math.hypot(self.x - x0, self.y - y0) / max(dt, 1e-3)
        self.place()

    def _start_jump(self):
        # Take-off direction follows the fly's own descending neurons: DNa02
        # asymmetry tilts it sideways, MDN over DNp09 tips it backward.
        h = self.body.hz
        tilt = 1.2 * math.tanh((h["DNa02_R"] - h["DNa02_L"]) / 30)   # + = right
        if h["MDN_L"] + h["MDN_R"] > h["DNp09_L"] + h["DNp09_R"]:
            angle = self.heading + math.pi - tilt
        else:
            angle = self.heading + tilt
        m = self.SIZE / 2
        x1 = min(max(self.x + math.cos(angle) * 200, m), self.app.sw - m)
        y1 = min(max(self.y + math.sin(angle) * 200, m), self.app.sh - m)
        self.jump = (time.perf_counter(), 0.3, (self.x, self.y), (x1, y1))

    def update_eggs(self, dt: float, now: float):
        """Female: ripen eggs after mating; lay one when her oviDN neurons have
        fired enough (Vijayan et al. 2023: oviDN activity rises to a threshold,
        then the egg is laid). Returns where an egg was laid, or None."""
        if self.sex != "female":
            return None
        if self.eggs_to_come and not self.eggs_ripe:
            self._egg_timer += dt
            if self._egg_timer > self.EGG_RIPEN_SECONDS:
                self._egg_timer = 0.0
                self.eggs_to_come -= 1
                self.eggs_ripe = 1
                self._ovi_spikes = 0.0
        count = int(self.brain.group_spikes[self.body.names.index("oviDN")])
        new = 0 if self._last_ovi is None else count - self._last_ovi
        self._last_ovi = count
        if self.eggs_ripe:
            self._ovi_spikes += new
            if self._ovi_spikes >= self.OVI_THRESHOLD:
                self.eggs_ripe = 0
                self._ovi_spikes = 0.0
                self.eggs_laid += 1
                self.laying_until = now + 1.0             # she pauses to lay it
                return (self.x - math.cos(self.heading) * 24 * self.scale,
                        self.y - math.sin(self.heading) * 24 * self.scale)
        return None

    def head(self):
        return (self.x + math.cos(self.heading) * 10 * self.scale,
                self.y + math.sin(self.heading) * 10 * self.scale)

    def ahead(self, dist: float):
        return (self.x + math.cos(self.heading) * dist,
                self.y + math.sin(self.heading) * dist)

    def place(self):
        h = self.SIZE // 2
        self.win.geometry(f"{self.SIZE}x{self.SIZE}+{int(self.x) - h}+{int(self.y) - h}")

    # ---------------------------------------------------------------- drawing
    def draw(self, mating: bool):
        c = self.canvas
        c.delete("all")
        o = self.SIZE / 2
        cos_h, sin_h = math.cos(self.heading), math.sin(self.heading)
        k = self.scale
        male = self.sex == "male"
        young = time.perf_counter() - self.born < 60        # pale when just hatched
        body_col, dark_col = ("#c9b48f", "#8a7650") if young else ("#6b4a2b", "#3a2816")
        thorax_col = "#d6c29c" if young else "#8a6a3c"

        def tr(px, py):  # fly frame (x forward, y left) -> canvas
            px, py = px * k, py * k
            return (o + px * cos_h + py * sin_h, o + px * sin_h - py * cos_h)

        def poly(points, **kw):
            flat = [v for p in points for v in tr(*p)]
            return c.create_polygon(flat, smooth=True, **kw)

        def ellipse(cx, cy, rx, ry, n=14):
            return [(cx + rx * math.cos(2 * math.pi * i / n),
                     cy + ry * math.sin(2 * math.pi * i / n)) for i in range(n)]

        flying = self.jump is not None
        grooming = self.motor["groom"] and not flying
        # Wings (drawn first, under the body). A singing male holds one wing out.
        for side in (1, -1):
            spread = 0.9 if flying else 0.18
            if male and self.motor["sing"] and side == self.sing_side and not flying:
                spread = 1.45
            a = math.pi + side * spread
            base = (-2, side * 3)
            tip = (base[0] + 24 * math.cos(a), base[1] + 24 * math.sin(a))
            mid = ((base[0] + tip[0]) / 2, (base[1] + tip[1]) / 2)
            nx_, ny_ = -math.sin(a) * 6, math.cos(a) * 6
            poly([base, (mid[0] + nx_, mid[1] + ny_), tip,
                  (mid[0] - nx_, mid[1] - ny_)],
                 fill="#cfe3ea", outline="#8aa6b0", stipple="gray50")
        # Legs: tripod gait. When grooming, the front legs rub over the head.
        for i, lx in enumerate((6, 0, -6)):
            for side in (1, -1):
                phase = self.leg_phase + (0 if (i % 2 == 0) == (side == 1) else math.pi)
                x0, y0 = tr(lx, side * 3)
                if grooming and i == 0:
                    rub = 2.5 * math.sin(self.leg_phase + (0 if side == 1 else math.pi))
                    x1, y1 = tr(11, side * 7)
                    x2, y2 = tr(14 + rub, side * (2 + rub / 2))
                else:
                    swing = 3 * math.sin(phase) if not flying else 0
                    x1, y1 = tr(lx + 4 - i * 4 + swing, side * 13)
                    x2, y2 = tr(lx + 6 - i * 6 + swing, side * 17)
                c.create_line(x0, y0, x1, y1, x2, y2, fill="#2b2118", width=1.5)
        # Abdomen: the female's is long, striped and pointed; the male's is
        # short and round with a dark tip.
        if male:
            poly(ellipse(-8, 0, 8, 6), fill=body_col, outline=dark_col)
            poly(ellipse(-12.5, 0, 3.8, 4.6, n=10), fill="#241810", outline="#241810")
            x0, y0 = tr(-6, 5)
            x1, y1 = tr(-6, -5)
            c.create_line(x0, y0, x1, y1, fill=dark_col, width=1.5)
        else:
            poly(ellipse(-10, 0, 10, 6.5), fill=body_col, outline=dark_col)
            poly([(-18, 2.5), (-23, 0), (-18, -2.5)], fill=body_col, outline=dark_col)
            for sx in (-6, -10, -14):
                x0, y0 = tr(sx, 5.5)
                x1, y1 = tr(sx, -5.5)
                c.create_line(x0, y0, x1, y1, fill=dark_col, width=1.5)
            if self.motor["reject"]:              # ovipositor extruded
                x0, y0 = tr(-22, 0)
                x1, y1 = tr(-28, 0)
                c.create_line(x0, y0, x1, y1, fill="#a0522d", width=2)
        poly(ellipse(2, 0, 6, 5), fill=thorax_col, outline=dark_col)
        poly(ellipse(10, 0, 3.5, 4.5), fill=thorax_col, outline=dark_col)
        for side in (1, -1):
            poly(ellipse(10.5, side * 3.4, 3.2, 2.6, n=10), fill="#c0262a",
                 outline="#6d1012")
        if self.motor["eat"] and self.food_here:  # proboscis out, eating
            x0, y0 = tr(13, 0)
            x1, y1 = tr(18, 0)
            c.create_line(x0, y0, x1, y1, fill="#7a5a30", width=2)
            c.create_oval(x1 - 2, y1 - 2, x1 + 2, y1 + 2, fill="#9a7a48", outline="")
        if self.dust > 0.5:                        # visibly dusty antennae
            x, y = tr(13, 0)
            c.create_text(x, y - 8, text="·:·", fill="#9a8f7a", font=("TkDefaultFont", 7))
        if male and self.motor["sing"]:            # song
            x, y = tr(-4, self.sing_side * 20)
            c.create_text(x, y, text="♪", fill="#3d6fb6", font=("TkDefaultFont", 12, "bold"))
        if mating:
            c.create_text(o, 10, text="♥", fill="#e0457b", font=("TkDefaultFont", 14, "bold"))
        # Red flash when swatted
        if time.perf_counter() - self.swatted_at < 0.3:
            x, y = tr(0, 0)
            c.create_oval(x - 22, y - 22, x + 22, y + 22, outline="#e0323a", width=3)
        # Giant Fiber flash when escape fires
        if flying:
            x, y = tr(0, 0)
            c.create_oval(x - 30, y - 30, x + 30, y + 30, outline="#f2b705", width=2)

    # ------------------------------------------------------- words for the windows
    def describe_sight(self) -> str:
        lines = []
        near = sorted(self.eyes.seen, key=lambda s: -s["alpha"])[:2]   # the biggest
        for s in near:
            what = "The cursor" if s["name"] == "cursor" else s["name"].capitalize()
            if s["visible"] < 0.1:
                lines.append(f"{what} is behind {self.him}, in {self.his} blind spot.")
                continue
            deg = math.degrees(s["bearing"])
            where = ("straight ahead" if abs(deg) < 15 else
                     f"{abs(deg):.0f}° to {self.his} {'left' if deg > 0 else 'right'}")
            size = math.degrees(s["alpha"])
            if s["looming"] > 1.0:
                lines.append(f"{what} is rushing at {self.him}, {where}! ({size:.0f}° and growing)")
            elif s["looming"] > 0.2:
                lines.append(f"{what} is coming closer, {where} ({size:.0f}° wide).")
            elif s["speed"] > 40:
                lines.append(f"{what} is moving, {where} ({size:.0f}° wide).")
            else:
                lines.append(f"{what} is still, {where} ({size:.0f}° wide).")
        return "\n".join(lines)

    def describe_thought(self) -> str:
        """Plain words for what the fly's descending (command) neurons are doing."""
        if self.app.is_mating(self):
            return "Mating!"
        h = self.body.hz
        gf = max(h["DNp01_L"], h["DNp01_R"])
        turn = h["DNa02_L"] - h["DNa02_R"]
        back = (h["MDN_L"] + h["MDN_R"]) / 2
        fwd = (h["DNp09_L"] + h["DNp09_R"]) / 2
        if self.jump or gf > Body.JUMP_HZ:
            return "Escape! Giant Fiber firing - jumping away."
        if self.sex == "female" and time.perf_counter() < self.laying_until:
            return "Laying an egg! (her oviDN neurons reached threshold)"
        parts = []
        if self.motor["eat"] and self.food_here:
            parts.append("eating (sugar → MN9 → proboscis out)")
        if self.motor["groom"]:
            parts.append("grooming (DNg12)")
        if self.sex == "male":
            if self.motor["sing"]:
                parts.append("singing (pIP10)")
            elif h["P1"] > 20:
                parts.append("in courtship mode (P1)")
        else:
            if self.motor["reject"]:
                parts.append("saying no - rejecting (DNp13)")
            elif self.motor["open"]:
                parts.append("saying yes - opening to mate (vpoDN)")
            if self.eggs_ripe:
                parts.append("has an egg ready (oviDN building up)")
        if gf > Body.JUMP_HZ / 3:
            parts.append("getting nervous (Giant Fiber charging)")
        if abs(turn) > 8:
            parts.append(f"turning {'left' if turn > 0 else 'right'} (DNa02)")
        if back > 8:
            parts.append("backing away (MDN)")
        if fwd > 8:
            parts.append("walking forward (DNp09)")
        young = "" if self.mature else " (too young to court)"
        if not parts:
            return f"Resting - {self.his} command neurons are quiet.{young}"
        return f"{self.he.capitalize()}'s " + ", ".join(parts) + "." + young


# --------------------------------------------------------------------------- #
#  The app: flies, how they sense each other, and windows into their minds
# --------------------------------------------------------------------------- #

class App:
    FPS = 40
    BG = "#14161a"
    MAP_W, MAP_H = 660, 316          # brain map size (px)
    MATING_SECONDS = 20              # real flies stay together ~20 minutes
    REMATING_PAUSE = 60              # s before a mated female can mate again
    FOOD_EVERY = 40                  # s between new food drops
    MAX_FOOD = 3
    MAX_BROOD = 40                   # eggs + larvae + pupae on screen

    def __init__(self, brains: list[BrainLink], noise: float, max_flies: int):
        import tkinter as tk
        self.tk = tk
        self.noise, self.max_flies = noise, max_flies
        self.root = tk.Tk()
        self.root.withdraw()
        self.sw = self.root.winfo_screenwidth()
        self.sh = self.root.winfo_screenheight()
        self.flies = []
        for i, brain in enumerate(brains):
            x = self.sw * (0.5 - 0.12 * i)
            self.flies.append(Fly(self, brain.sex, brain, x, self.sh * 0.6,
                                  label=f"the {brain.sex}"))
        self.windows = {}                # (fly label, name) -> (window, canvas, state)
        self.paused = False
        self.matings = []                # [male, female, end time]
        self.food = []
        self.brood = []                  # eggs, larvae, pupae, departing flies
        self.born = {"female": 0, "male": 0}
        self.flew_away = 0
        self._last = time.perf_counter()
        self._last_food = self._last - self.FOOD_EVERY + 5
        self._last_save = self._last
        self._last_cursor = self.root.winfo_pointerxy()
        self.root.after(0, self.tick)

    def transparent_background(self, win) -> str:
        """Make a window's background see-through where the OS allows it."""
        if sys.platform.startswith("win"):
            key = "#ff00fe"
            win.config(bg=key)
            win.wm_attributes("-transparentcolor", key)
            return key
        if sys.platform == "darwin":
            try:
                win.wm_attributes("-transparent", True)
                win.config(bg="systemTransparent")
                return "systemTransparent"
            except Exception:
                pass
        # X11: Tk has no per-pixel transparency; use a small neutral tile.
        return "#e8e4dc"

    def small_window(self, size: int, x: float, y: float):
        """A tiny borderless window for food, eggs, larvae and pupae."""
        win = self.tk.Toplevel(self.root)
        win.overrideredirect(True)
        win.wm_attributes("-topmost", True)
        bg = self.transparent_background(win)
        win.children_canvas = self.tk.Canvas(win, width=size, height=size, bg=bg,
                                             highlightthickness=0, bd=0)
        win.children_canvas.pack()
        win.geometry(f"{size}x{size}+{int(x) - size // 2}+{int(y) - size // 2}")
        for f in self.flies:                                   # keep flies on top
            f.win.lift()
        return win

    def toggle_pause(self):
        self.paused = not self.paused

    def quit(self):
        self._save_rejections()
        for f in self.flies:
            f.brain.stop()
        self.root.destroy()

    def _save_rejections(self):
        for f in self.flies:
            if f.label == "the male":
                f.arousal.save_rejections()

    def run(self):
        self.root.mainloop()

    # ------------------------------------------------------------- main loop
    def tick(self):
        now = time.perf_counter()
        dt = min(now - self._last, 0.1)
        self._last = now
        cx, cy = self.root.winfo_pointerxy()
        lx, ly = self._last_cursor
        cursor_speed = math.hypot(cx - lx, cy - ly) / max(dt, 1e-3)
        self._last_cursor = (cx, cy)
        if not self.paused:
            self._between_flies(now, dt)
            for f in self.flies:
                if f.brain.ready:
                    f.sense(dt, (cx, cy), cursor_speed,
                            [o for o in self.flies if o is not f], self.food)
                    f.live(dt)
                f.move(dt, frozen=(not f.brain.ready or self.is_mating(f)
                                   or now < f.laying_until))
                spot = f.update_eggs(dt, now) if not self.is_mating(f) else None
                if spot:
                    self.lay_egg(*spot)
            self._update_mating(now)
            self._update_world(dt, now)
        for f in self.flies:
            f.draw(self.is_mating(f))
        if self.windows:
            self.update_windows()
        if now - self._last_save > 60:
            self._save_rejections()
            self._last_save = now
        self.root.after(int(1000 / self.FPS), self.tick)

    # ------------------------------------------------------- food and brood
    def add_food(self, x: float, y: float):
        m = 30
        x, y = min(max(x, m), self.sw - m), min(max(y, m), self.sh - m)
        self.food.append(Food(self, x, y))

    def lay_egg(self, x: float, y: float):
        self.brood.append(Brood(self, x, y))
        while len([b for b in self.brood if isinstance(b, Brood)]) > self.MAX_BROOD:
            oldest = next(b for b in self.brood if isinstance(b, Brood))
            oldest.destroy()
            self.brood.remove(oldest)

    def eclose(self, x: float, y: float):
        """A pupa hatches. The new fly gets its own whole brain if there's
        room; otherwise it flies away."""
        sex = random.choice(("female", "male"))
        if sex == "male" and not CACHE_FILE["male"].exists():
            sex = "female"
        if len(self.flies) >= self.max_flies:
            self.flew_away += 1
            self.brood.append(LeavingFly(self, x, y))
            return
        self.born[sex] += 1
        label = f"{'daughter' if sex == 'female' else 'son'} {self.born[sex]}"
        brain = BrainLink(sex, self.noise)          # no saved memory: a new fly
        self.flies.append(Fly(self, sex, brain, x, y, label=label, newborn=True))

    def _update_world(self, dt: float, now: float):
        if len(self.food) < self.MAX_FOOD and now - self._last_food > self.FOOD_EVERY:
            self._last_food = now
            self.add_food(random.uniform(100, self.sw - 100),
                          random.uniform(100, self.sh - 100))
        for f in list(self.food):
            if f.amount <= 0:
                f.destroy()
                self.food.remove(f)
        for b in list(self.brood):
            b.update(dt, now)
            if b.done:
                b.destroy()
                self.brood.remove(b)

    # ------------------------------------------------ flies sensing each other
    def is_mating(self, fly) -> bool:
        return any(fly in pair[:2] for pair in self.matings)

    def _between_flies(self, now: float, dt: float):
        """The senses that connect males and females (sight, touch/taste,
        song), and the slow arousal each builds up from sensing the other."""
        males = [f for f in self.flies if f.sex == "male" and f.mature and f.brain.ready]
        females = [f for f in self.flies if f.sex == "female" and f.mature and f.brain.ready]
        for f in females:
            f.hearing = 0.0
        for m in males:
            m.taste = 0.0
            cue = 0.0
            hx, hy = m.head()
            for f in females:
                # His forelegs taste her if his head touches her body.
                if math.hypot(hx - f.x, hy - f.y) < 16 * f.scale:
                    m.taste = 1.0
                d = math.hypot(m.x - f.x, m.y - f.y)
                bearing = _wrap(m.heading - math.atan2(f.y - m.y, f.x - m.x))
                sees = (abs(bearing) < Eyes.BLIND_SPOT) * _clamp((600 - d) / 400)
                if sees > cue:
                    cue = sees
                    m.sing_side = 1 if bearing > 0 else -1
                # She hears his song if he's close: fly song is near-field sound.
                loud = _clamp((m.body.hz["pIP10"] - 10) / 60) * _clamp((300 - d) / 200)
                f.hearing = max(f.hearing, loud)
                # Being rejected up close dampens his arousal now and teaches
                # him to court her less (courtship conditioning).
                if f.motor["reject"] and d < 80:
                    m.arousal.was_rejected(dt)
            # His arousal rises while he sees a female nearby (real males also
            # smell her) and more when he tastes her.
            m.arousal.update(dt, now, _clamp(0.6 * cue + m.taste))
        for f in females:
            f.arousal.update(dt, now, f.hearing)
        for fly in self.flies:
            if not fly.mature:
                fly.arousal.update(dt, now, 0.0)

    def _update_mating(self, now: float):
        for pair in list(self.matings):
            m, f, end = pair
            if m not in self.flies or f not in self.flies:
                self.matings.remove(pair)
            elif now > end:                          # done: separate
                self.matings.remove(pair)
                f.mated_until = now + self.REMATING_PAUSE
                m.arousal.just_mated(now)
                f.arousal.just_mated(now)
                f.eggs_to_come += Fly.EGGS_PER_MATING          # fertilised eggs
                m.heading = _wrap(m.heading + math.pi)
                m.x -= math.cos(f.heading) * 30
                m.y -= math.sin(f.heading) * 30
                m.place()
            else:                                    # he rides on her back
                m.heading = f.heading
                m.x = f.x - math.cos(f.heading) * 8
                m.y = f.y - math.sin(f.heading) * 8
                m.place()
                m.win.lift()
        # Copulation happens when he's courting, she's accepting at that moment
        # (vaginal plate open, not rejecting), and their bodies touch - however
        # they met. Then the body does what real males do: he walks around and
        # mounts her from behind.
        for m in self.flies:
            if m.sex != "male" or not m.mature or self.is_mating(m) or m.jump:
                continue
            courting = m.motor["sing"] or m.body.hz["P1"] > 20
            if not courting:
                continue
            for f in self.flies:
                if (f.sex != "female" or not f.mature or self.is_mating(f) or f.jump
                        or now < f.mated_until):
                    continue
                accepting = f.motor["open"] and not f.motor["reject"]
                touching = (math.hypot(m.x - f.x, m.y - f.y)
                            < 1.6 * (m.radius + f.radius) * f.scale)
                if accepting and touching:
                    self.matings.append([m, f, now + self.MATING_SECONDS])
                    break

    # ------------------------------------------------ windows into their minds
    def _window_specs(self, fly):
        His = fly.his.capitalize()
        if fly.label == "the female":    # her windows on the left, his on the right
            spots = {"eyes": (20, 20), "brain": (560, 20), "neurons": (20, 390)}
        elif fly.label == "the male":
            spots = {"eyes": (self.sw - 548, 20), "brain": (560, 470),
                     "neurons": (self.sw - 748, 390)}
        else:                            # offspring: cascade from the middle
            k = 40 * (self.flies.index(fly) % 6)
            spots = {"eyes": (300 + k, 60 + k), "brain": (340 + k, 100 + k),
                     "neurons": (380 + k, 140 + k)}
        return {
            "eyes": (f"What {fly.he} sees", 520, 342, self._draw_eyes, spots["eyes"]),
            "brain": (f"{His} brain", 680, 420, self._draw_brain, spots["brain"]),
            "neurons": (f"What {fly.he}'s doing", 740, 484, self._draw_neurons,
                        spots["neurons"]),
        }

    def toggle_window(self, fly, name: str):
        key = (fly.label, name)
        if key in self.windows:
            self.windows.pop(key)[0].destroy()
            return
        title, w, h, _, (ox, oy) = self._window_specs(fly)[name]
        win = self.tk.Toplevel(self.root)
        win.title(f"{title} ({fly.label}) - Connectome Fly v{__version__}")
        win.wm_attributes("-topmost", True)
        win.geometry(f"+{ox}+{oy}")
        win.configure(bg=self.BG)
        win.protocol("WM_DELETE_WINDOW", lambda: self.toggle_window(fly, name))
        canvas = self.tk.Canvas(win, width=w, height=h, bg=self.BG, highlightthickness=0)
        canvas.pack(padx=4, pady=4)
        state = self._setup_brain_map(canvas, fly) if name == "brain" else None
        self.windows[key] = (win, canvas, state)

    def open_all_windows(self, fly):
        for name in ("eyes", "brain", "neurons"):
            if (fly.label, name) not in self.windows:
                self.toggle_window(fly, name)

    def update_windows(self):
        for (label, name), (win, canvas, state) in list(self.windows.items()):
            fly = next((f for f in self.flies if f.label == label), None)
            if fly is None:
                win.destroy()
                del self.windows[(label, name)]
                continue
            canvas.delete("dyn")
            self._window_specs(fly)[name][3](canvas, fly, state)

    @staticmethod
    def _text(c, x, y, s, colour="#c6ccd6", size=9, bold=False, right=False):
        font = ("TkDefaultFont", size, "bold") if bold else ("TkDefaultFont", size)
        c.create_text(x, y, anchor="ne" if right else "nw", fill=colour, font=font,
                      text=s, tags="dyn")

    def _bar(self, c, x, y, label, hz, colour, label_w=70, bar_w=100):
        self._text(c, x, y, label)
        c.create_rectangle(x + label_w, y + 2, x + label_w + bar_w, y + 14,
                           outline="#3a404a", tags="dyn")
        c.create_rectangle(x + label_w, y + 2, x + label_w + bar_w * min(hz / 150, 1),
                           y + 14, fill=colour, width=0, tags="dyn")
        self._text(c, x + label_w + bar_w + 5, y, f"{hz:.0f} Hz", "#8a93a0", 8)

    # -- window 1: what the fly sees -------------------------------------------
    def _draw_eyes(self, c, fly, _state):
        t = self._text
        t(c, 10, 6, f"What {fly.he} sees ({fly.label})", "#e6e9ee", 11, True)
        if not fly.brain.ready:
            t(c, 10, 30, "Brain loading...", "#9fd3ff", 10)
            return
        x0, x1, y0, y1 = 10, 510, 60, 150
        cx, half = (x0 + x1) / 2, (x1 - x0) / 2

        def bx(bearing):  # bearing (+ = fly's left) -> x, its left on the left
            return cx - bearing / math.pi * half

        c.create_rectangle(x0, y0, x1, y1, fill="#1d232b", outline="#3a404a", tags="dyn")
        for sgn in (1, -1):                                   # blind spot behind
            a, b = sorted((bx(sgn * Eyes.BLIND_SPOT), bx(sgn * math.pi)))
            c.create_rectangle(a, y0, b, y1, fill="#0c0e11", outline="", tags="dyn")
        a, b = sorted((bx(0.35), bx(-0.35)))                  # both eyes overlap
        c.create_rectangle(a, y0, b, y1, fill="#23303b", outline="", tags="dyn")
        c.create_line(cx, y0, cx, y1, fill="#3a404a", dash=(2, 3), tags="dyn")
        t(c, x0 + 26, y0 + 3, "left eye", "#6d7785", 8)
        t(c, x1 - 26, y0 + 3, "right eye", "#6d7785", 8, right=True)
        t(c, cx - 14, y1 + 3, "ahead", "#6d7785", 8)
        t(c, x0, y1 + 3, "behind (blind)", "#6d7785", 8)
        t(c, x1, y1 + 3, "behind (blind)", "#6d7785", 8, right=True)
        for s in fly.eyes.seen:
            if s["visible"] < 0.1:
                continue
            r = max(s["alpha"] / math.pi * half / 2, 2.5)
            ox, oy = bx(s["bearing"]), (y0 + y1) / 2
            if s["name"] == "cursor":
                colour = "#ff5a4f" if s["looming"] > 1.0 else "#f2f2f2"
            elif s["name"].startswith("food"):
                colour = "#f2c94c"
            else:
                other = next((f for f in self.flies if f.label == s["name"]), None)
                colour = "#e8a13a" if other and other.sex == "female" else "#5fb0e8"
            c.create_oval(ox - r, oy - min(r, 42), ox + r, oy + min(r, 42),
                          fill=colour, outline="", tags="dyn")
        t(c, 10, 28, fly.describe_sight(), "#9fd3ff", 9)

        t(c, 10, 178, f"{fly.his.capitalize()} visual neurons", "#e6e9ee", 10, True)
        t(c, 10, 196, "left eye", "#6d7785", 8)
        t(c, 270, 196, "right eye", "#6d7785", 8)
        for i, ctype in enumerate(SENSORY_TYPES):
            y = 214 + i * 20
            for j, side in enumerate(SIDES):
                g = f"{ctype}_{side}"
                self._bar(c, 10 + 260 * j, y, ctype, fly.brain.input_hz[g], "#4aa3df",
                          label_w=50, bar_w=110)
        t(c, 10, 316, "LC4 fast looming · LPLC2 collision · LC16 approach · "
                      "LC10a / LC9 small moving object", "#6d7785", 8)

    # -- window 2: the whole brain ---------------------------------------------
    def _setup_brain_map(self, canvas, fly) -> dict:
        """Pixel position of every neuron in a view of the brain from behind."""
        w, h = self.MAP_W // 2, self.MAP_H // 2        # draw at half size, then 2x
        pos = fly.brain.pos
        ok = ~np.isnan(pos).any(1)
        x, y = pos[:, 0], pos[:, 1]
        x0, x1 = np.nanmin(x), np.nanmax(x)
        y0, y1 = np.nanmin(y), np.nanmax(y)
        px = (x - x0) / (x1 - x0) * (w - 1)
        if fly.brain.left_x > (x0 + x1) / 2:           # fly's left on the left
            px = (w - 1) - px
        py = (y - y0) / (y1 - y0) * (h - 1)
        pix = (np.nan_to_num(py[ok]).astype(int) * w + np.nan_to_num(px[ok]).astype(int))
        density = np.bincount(pix, minlength=w * h).astype(np.float32)
        photo = self.tk.PhotoImage(width=self.MAP_W, height=self.MAP_H)
        canvas.create_image(10, 70, anchor="nw", image=photo)
        return {"ok": np.flatnonzero(ok), "pix": pix, "photo": photo, "frame": 0,
                "bg": (np.log1p(density) / np.log1p(density.max()) * 70).astype(np.float32),
                "act": np.zeros(w * h, np.float32), "last": fly.brain.spike_count.copy(),
                "rate": 0.0, "rate_t": (time.perf_counter(), fly.brain.total_spikes),
                "shown": int(ok.sum())}

    def _update_brain_map(self, st, fly):
        w, h = self.MAP_W // 2, self.MAP_H // 2
        counts = fly.brain.spike_count.copy()
        new = (counts - st["last"])[st["ok"]]
        st["last"] = counts
        st["act"] *= 0.6                                         # glow fades
        st["act"] += np.bincount(st["pix"], weights=new, minlength=w * h)
        heat = 1 - np.exp(-st["act"] / 1.5)                       # 0..1
        r = np.clip(st["bg"] + heat * 3 * 255, 0, 255)
        g = np.clip(st["bg"] + (heat * 3 - 1) * 255, 0, 255)
        b = np.clip(st["bg"] * 1.3 + (heat * 3 - 2) * 255, 0, 255)
        img = np.stack([r, g, b], 1).astype(np.uint8).reshape(h, w, 3)
        img = img.repeat(2, 0).repeat(2, 1)
        header = f"P6 {self.MAP_W} {self.MAP_H} 255 ".encode()
        st["photo"].configure(data=header + img.tobytes(), format="PPM")

    def _draw_brain(self, c, fly, st):
        t = self._text
        now, spikes = time.perf_counter(), fly.brain.total_spikes
        t0, s0 = st["rate_t"]
        if now - t0 > 0.5:
            st["rate"] = (spikes - s0) / (now - t0)
            st["rate_t"] = (now, spikes)
        source = ("FlyWire 783" if fly.sex == "female" else "Janelia Male CNS v0.9")
        t(c, 10, 6, f"{fly.his.capitalize()} brain: {fly.brain.n:,} neurons ({source})",
          "#e6e9ee", 11, True)
        status = ("brain loading..." if not fly.brain.ready else
                  f"{st['rate']:,.0f} spikes/s   ·   running at {fly.brain.speed:.2f}x real time")
        t(c, 10, 28, status, "#9aa4b2")
        note = ("Each dot is one neuron at its real position, seen from behind the head. "
                "Bright = firing now." if fly.sex == "female" else
                f"{st['shown']:,} brain neurons shown at the position of the matching "
                "FlyWire cell type. Bright = firing.")
        t(c, 10, 48, note, "#6d7785", 8)
        t(c, 14, 74, "left eye", "#8a93a0", 8)
        t(c, 666, 74, "right eye", "#8a93a0", 8, right=True)
        st["frame"] += 1
        if st["frame"] % 3 == 0:                                # ~13 updates/s
            self._update_brain_map(st, fly)
        t(c, 10, 394, fly.describe_thought(), "#f2d16b", 10, True)

    # -- window 3: what the fly is doing -----------------------------------------
    def _draw_neurons(self, c, fly, _state):
        t = self._text
        t(c, 10, 6, f"What {fly.he}'s doing ({fly.label})", "#e6e9ee", 11, True)
        t(c, 10, 30, fly.describe_thought(), "#f2d16b", 10, True)

        # left column: moving, eating, grooming
        t(c, 10, 60, "Command (descending) neurons", "#e6e9ee", 10, True)
        for i, g in enumerate(MOTOR):
            y = 82 + i * 22
            self._bar(c, 10, y, g, fly.body.hz[g], "#e0864a", label_w=75, bar_w=90)
            t(c, 360, y, ROLE[g.rsplit("_", 1)[0]], "#6d7785", 8, right=True)
        t(c, 10, 266, "Eating and grooming", "#e6e9ee", 10, True)
        for i, g in enumerate(BEHAVIOUR_GROUPS[fly.sex]):
            y = 288 + i * 22
            self._bar(c, 10, y, g, fly.body.hz[g], "#6fbf73", label_w=75, bar_w=90)
            t(c, 360, y, ROLE[g], "#6d7785", 8, right=True)
        t(c, 10, 424, f"Hunger {fly.hunger:.2f} · dust on antennae {fly.dust:.2f}",
          "#f2d16b", 9)
        t(c, 10, 442, "(hunger and dust are body states, written by hand)", "#6d7785", 8)

        # right column: mating, eggs, memory
        x = 380
        t(c, x, 60, "Mating neurons", "#e6e9ee", 10, True)
        for i, g in enumerate(EXTRA_GROUPS[fly.sex]):
            y = 82 + i * 22
            self._bar(c, x, y, g, fly.body.hz[g], "#c77ddb", label_w=60, bar_w=100)
            t(c, 730, y, ROLE[g], "#6d7785", 8, right=True)
        y = 82 + len(EXTRA_GROUPS[fly.sex]) * 22 + 4
        a = fly.arousal
        if fly.switch_on.get():
            line = f"Lab override ON: {SWITCH[fly.sex]} forced on"
        elif not fly.mature:
            line = "Too young to court yet"
        elif fly.sex == "male":
            line = (f"Arousal {a.level:.2f} → P1 {a.drive_hz():.0f} Hz · "
                    f"interest ×{a.interest():.2f}")
        else:
            line = f"Arousal {a.level:.2f} · mood {a.mood:.2f} → pC1 {a.drive_hz():.0f} Hz"
        t(c, x, y, line, "#f2d16b", 9)
        t(c, x, y + 16, "(arousal stands in for dopamine and hormones)", "#6d7785", 8)
        if fly.sex == "female":
            t(c, x, y + 34, f"Eggs: {fly.eggs_laid} laid · {fly.eggs_ripe} ready · "
                            f"{fly.eggs_to_come} ripening", "#f2d16b", 9)
        y = 380
        t(c, x, y - 22 if fly.sex == "male" else y, "Memory (visual synapse strength)",
          "#e6e9ee", 9, True)
        mem = " ".join(
            f"{ct} {(fly.brain.memory_of(f'{ct}_L') + fly.brain.memory_of(f'{ct}_R')) / 2:.2f}"
            for ct in ("LC4", "LPLC2", "LC16"))
        t(c, x, (y - 4 if fly.sex == "male" else y + 18), mem, "#f2d16b", 8)
        t(c, 10, 464, "Click a fly to swat it · drag to carry it · right-click for food.",
          "#6d7785", 8)


# --------------------------------------------------------------------------- #

def simulate(sex: str, seconds: float, noise: float):
    """Headless check: a cursor rushes at the fly from its left."""
    brain = WholeBrain(load_brain_data(sex), noise=noise)
    print(f"[brain] {sex}: {brain.n:,} neurons, {len(brain.indices):,} connections")
    eyes, body = Eyes(), Body(brain)
    frame, t = 0.025, 0.0
    while t < seconds:
        dist = max(700 - 300 * max(t - 1.0, 0), 10)     # 1 s still, then approach
        speed = 300 if t > 1.0 else 0
        bearing = 0.6 + (0.4 * math.sin(3 * t) if t > 1.0 else 0)   # weaving
        brain.input_hz.update(eyes.see([("cursor", dist, bearing, 14.0, speed, 1.0)], frame))
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


def _confirm_male_download() -> bool:
    if CACHE_FILE["male"].exists():
        return True
    print("The male fly needs Janelia's Male CNS connectome: a one-time 3.4 GB\n"
          "download, then a few minutes (and a few GB of memory) to build his brain.\n"
          "The download is deleted afterwards; his built brain takes ~80 MB.")
    try:
        return input("Continue? [y/N] ").strip().lower() in ("y", "yes")
    except EOFError:
        return False


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", action="version",
                    version=f"Connectome Fly {__version__}")
    ap.add_argument("--male", action="store_true",
                    help="add a male fly (Janelia Male CNS connectome)")
    ap.add_argument("--max-flies", type=int, default=4,
                    help="most flies with a running brain at once (default 4); "
                         "each needs about one CPU core")
    ap.add_argument("--noise", type=float, default=0.05,
                    help="random synaptic events per neuron per ms (default 0.05)")
    ap.add_argument("--rebuild", action="store_true",
                    help="rebuild the cached brain(s) from the data")
    ap.add_argument("--simulate", type=float, metavar="SECONDS",
                    help="run without a window and print the brain's output")
    ap.add_argument("--sex", choices=("female", "male"), default="female",
                    help="which fly --simulate uses (default female)")
    args = ap.parse_args()

    print(f"Connectome Fly v{__version__}")
    old_memory = DATA_DIR / "memory.npz"                # from before v4
    if old_memory.exists() and not MEMORY_FILE["female"].exists():
        old_memory.rename(MEMORY_FILE["female"])
    sexes = ["female"] + (["male"] if args.male or args.sex == "male" else [])
    if "male" in sexes and not _confirm_male_download():
        sexes.remove("male")
        if args.simulate and args.sex == "male":
            return
    for sex in sexes:
        if args.rebuild:
            load_brain_data(sex, rebuild=True)
    if args.simulate:
        simulate(args.sex, args.simulate, args.noise)
        return
    brains = [BrainLink(sex, args.noise, MEMORY_FILE[sex]) for sex in sexes]
    App(brains, args.noise, args.max_flies).run()


if __name__ == "__main__":
    main()
