# Connectome Fly 🪰

A small desktop pet: a fruit fly that walks around on top of your other windows
and reacts to your mouse cursor. Its "brain" is a small circuit taken from the
[FlyWire](https://flywire.ai) whole-brain connectome of *Drosophila*.

```
cursor ──► visual projection neurons ──► (interneurons) ──► descending neurons ──► behaviour
           LC4, LPLC2, LC16, LC10a                           DNp01 (Giant Fiber)   escape jump
           (left + right optic lobe)                         DNa02                 turn
                                                             MDN                   walk backward
                                                             DNp09                 walk forward
```

* **Far, moving cursor**: LC10a (small-object detector) → DNa02/DNp09. The fly turns toward it and follows it.
* **Cursor in front, getting closer**: LC16 → MDN. The fly backs away ("moonwalks").
* **Cursor rushing in**: LC4/LPLC2 (looming detectors) → DNp01, the Giant Fiber. The fly jumps away.
* **Cursor touching the fly**: a mechanosensory "touch" input drives the Giant Fiber directly.
* **Nothing happening**: the fly wanders around on its own.

Right-click the fly for a menu. **Show brain activity** opens a live panel with
the firing rate of every sensory and descending neuron group and the path the
signal is currently taking (with a FlyWire circuit this includes the relay
interneurons, e.g. `LPLC2_R -> <interneuron> (7205…) -> DNp01_R`).
Use **Quit** in the menu to close it.

## Install

```bash
pip install -r requirements.txt
```

Tkinter ships with most Python installs. On Debian/Ubuntu you may need
`sudo apt install python3-tk`.

## Run

```bash
python fly_pet.py
```

The first run tries to build the circuit from FlyWire (see below). If that
fails, it uses a built-in fallback circuit, so the pet always runs.

### Using the real connectome

To download connectivity you need a free FlyWire CAVE token:

1. Sign in at <https://global.daf-apis.com/auth/api/v1/create_token> with a
   Google account. You may first need to accept the FlyWire public data terms at
   <https://codex.flywire.ai>.
2. Build the circuit once with your token:

   ```bash
   python fly_pet.py --token YOUR_TOKEN
   ```

This stores the token (through `fafbseg`), downloads the data and caches the
result to `fly_circuit.json`. After that, the pet starts instantly and works
offline. Run `python fly_pet.py --build` any time to rebuild the cache.

What `--build` does:

1. **fafbseg** looks up every neuron of the 8 cell types in the FlyWire
   hierarchical annotations (public release, materialization 783), split by
   brain hemisphere.
2. It fetches all downstream partners of the sensory neurons and all upstream
   partners of the descending neurons (≥5 synapses), and keeps only the direct
   paths and the two-hop sensory → interneuron → DN paths.
3. Edge weights are output fractions (sensory side) and input fractions (DN
   side). The graph is loaded into **navis**, and `navis.models.TraversalModel`
   runs repeated probabilistic signal propagation from each sensory group. How
   often each DN is reached, and after how many hops, gives the sensory → motor
   gain. The strongest route is saved so the brain panel can display it.

At runtime a small rate model (time constant 80 ms) turns those gains into
descending neuron activity 40 times per second.

### Other options

```bash
python fly_pet.py --offline        # always use the built-in fallback circuit
python fly_pet.py --simulate 3     # no GUI: move a virtual cursor toward the fly, print its reactions
```

## Platform notes

* **Windows**: fully transparent background (only the fly is visible).
* **macOS**: transparent background with the system Tk.
* **Linux/X11**: Tk has no per-pixel transparency, so the fly sits on a small
  beige tile.

## Caveats

This is a toy, not a biophysical model. The connectome supplies *which* neurons
connect and *how strongly*. Neuron dynamics, thresholds, and the way the cursor
is turned into visual input are all simplified by hand. The fallback circuit is
qualitative and based on published behaviour studies (von Reyn 2017, Ache 2019,
Wu 2016, Ribeiro 2018). It is not measured synapse data.
