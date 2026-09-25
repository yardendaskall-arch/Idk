# Connectome Fly 🪰

A desktop pet fruit fly whose behaviour comes from a simulation of its **whole
brain**. All 138,639 neurons in the [FlyWire](https://flywire.ai) connectome
(public release 783) are simulated as spiking neurons, wired with every
synapse between them. There are no behaviour rules in the code: no "if the
cursor is close, jump". The fly turns, walks and jumps only when the
corresponding neurons in its simulated brain fire.

```
EYES (code)                 BRAIN (connectome)                  BODY (code)
cursor → spikes in     →    138,639 spiking neurons,     →     descending neurons
LC4, LPLC2, LC16, LC10a     14 million connections              DNp01 (Giant Fiber) → jump
(left & right eye)          (FlyWire 783)                       DNa02 left/right   → turn
                                                                DNp09              → walk forward
                                                                MDN                → walk backward
```

The only hand-written parts are the two ends:

* **Eyes**: how the cursor becomes spikes in four types of visual neuron. This
  stands in for the retina and optic lobe, which aren't simulated from pixels.
* **Body**: how descending neuron firing becomes movement. The fly's nerve
  cord and legs are not part of the brain connectome.

## What it does

These behaviours come out of the wiring. None of them are programmed:

* **A cursor moving nearby** excites LC10a (small-object neurons), which
  drives DNa02 on the same side. The fly turns to face your cursor and
  follows it as it moves around.
* **A cursor rushing at it** excites LC4 and LPLC2 (looming detectors), which
  drive the Giant Fiber to 100–270 Hz. The fly jumps, and DNa02 on the far
  side tilts the take-off away from you.
* **Left alone**, weak random synaptic activity gives the occasional
  spontaneous twitch or turn.

## Memory: it learns about you

The fly learns the way real flies do: by changing the strength of synapses.
All ~8,000 visual projection neurons carry a learnable strength that scales
every one of their output synapses.

* **Habituation.** Each time a visual neuron fires, its synapses get slightly
  weaker. If you keep approaching without hurting him, his looming and
  escape pathways weaken and he lets the cursor get closer. In testing, about
  10 harmless approaches cut his escape distance from ~135 px to ~80 px.
* **Sensitization.** **Left-click the fly to swat it.** The neurons that
  fired in the ~3 seconds before the swat get stronger, so whatever he just
  saw becomes scarier. One swat undoes several approaches' worth of calming.
* **Forgetting.** Strengths drift back to normal over about 30 minutes.
* **Across runs.** Memory is saved to `brain_data/memory.npz` every minute and
  when you quit, and reloaded at start. Time spent closed counts as time to
  forget.

The "What he's doing" window shows the current strengths ("1.00x" is untouched, below 1
means he's getting used to you, above 1 means he's wary). Delete
`brain_data/memory.npz` to give him a fresh start.

Left-click to swat him. Right-click the fly to open any of three live
windows (or **Open all three**):

* **What he sees**: his field of view all the way around him (left eye,
  right eye, the overlap in front, and the blind spot behind), with your
  cursor drawn where and how big he sees it. It turns red when it rushes at
  him. Below are a sentence describing it, e.g. "A small object is moving,
  18° to his left", and the firing of his visual neurons for each eye.
* **His brain**: all 138,639 neurons drawn at their real positions, viewed
  from behind his head. Each one lights up when it fires, so you can watch
  activity spread from his optic lobes into the central brain.
* **What he's doing**: his current action in words, read from his command
  neurons (e.g. "He's turning left (DNa02)" or "Escape! His Giant Fiber is
  firing"), the firing of each command neuron, and his memory.

Click a menu item again, or close the window, to hide it. Choose **Quit** to close the pet.

### Honest limitations

* **It rarely walks forward.** In this model the forward-walking neuron
  (DNp09) is almost never driven by the visual input or background activity,
  so the fly mostly turns in place and jumps.
* **Backing up is weak.** LC16 → MDN only shows up briefly, during escapes.
* **The learning rule is simplified.** Real flies learn mostly in the
  mushroom body, driven by dopamine. Here, learning happens directly at the
  visual neurons' synapses.
* **It is not conscious.** Nobody knows how to build consciousness, or even
  whether real fruit flies have it. This is a model of the fly's wiring, not
  of a mind.
* **The neuron model is simple**, a leaky integrate-and-fire model with
  parameters from Shiu et al. 2024. Real neurons, neuromodulation, learning
  and hunger are all far richer.

## Install and run

```bash
pip install -r requirements.txt
python fly_pet.py
```

Tkinter ships with most Python installs (on Debian/Ubuntu:
`sudo apt install python3-tk`). The first launch downloads about 130 MB of
connectome data into `brain_data/` and builds the brain, which takes about a
minute. After that it starts in a few seconds. No FlyWire token is needed.

To check which version you have, run `python fly_pet.py --version`. The
version is also printed at startup and shown in the title of each window.
The current version is **3.2.0**.

Options:

```bash
python fly_pet.py --noise 0.07    # more spontaneous activity (0 = silent brain)
python fly_pet.py --simulate 4    # no window: rush a virtual cursor at the fly, print neuron rates
python fly_pet.py --rebuild       # rebuild the cached brain from the downloaded data
```

A fast computer is needed. Each millisecond of brain time processes every
spike through 14 million connections. The panel shows the brain speed. Below
1.0× the fly reacts in slow motion, but it still behaves correctly.

## The model

* **Neurons**: leaky integrate-and-fire, using Shiu et al. 2024 (*Nature*)
  parameters: rest −52 mV, threshold −45 mV, membrane τ 20 ms, synaptic
  τ 5 ms, 0.275 mV per synapse, ~2 ms delay and refractory period. Time
  step is 1 ms.
* **Synapse signs** come from each neuron's predicted neurotransmitter.
  Acetylcholine excites; GABA, glutamate and histamine inhibit. Dopamine,
  serotonin and octopamine are slow neuromodulators, so they get no fast
  effect.
* **Added for continuous running**:
  * spike-frequency adaptation (1 mV per spike, τ 200 ms)
  * weak random synaptic events on every non-sensory neuron (`--noise`)

  Without these, a few recurrent circuits (the mushroom body, the antennal
  lobe) run away into permanent seizure-like firing.
* **Sensory neurons** fire only from sensory input.

### Data sources

* Connectivity and neuron list: FlyWire 783 as packaged by
  [Shiu et al. 2024](https://github.com/philshiu/Drosophila_brain_model).
* Cell types, sides and neurotransmitters: FlyWire hierarchical annotations
  (Schlegel et al. 2024), loaded with `fafbseg`.
* Connectome: Dorkenwald et al. 2024, Schlegel et al. 2024 (*Nature*).

## Platform notes

* **Windows**: fully transparent background (only the fly is visible).
* **macOS**: transparent background with the system Tk.
* **Linux/X11**: Tk has no per-pixel transparency, so the fly sits on a small
  beige tile.
