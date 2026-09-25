# Connectome Fly 🪰

Desktop pet fruit flies whose behaviour comes from simulations of **whole real
fly brains**:

* **The female** runs on the [FlyWire](https://flywire.ai) connectome
  (public release 783): all 138,639 neurons of one adult female fly's brain.
* **The male** (optional) runs on Janelia's
  [Male CNS connectome](https://male-cns.janelia.org) (v0.9): all 165,114
  neurons of one adult male fly's brain and nerve cord.

Every neuron is simulated as a spiking neuron, wired with its real synapses.
There are no behaviour rules in the code: no "if the cursor is close, jump",
no "if he's behind her, mate". The flies turn, walk, jump, sing and accept or
reject a mate only when the corresponding neurons in their simulated brains
fire.

```
SENSES (code)                  BRAIN (connectome)          BODY (code)
eyes: cursor + other fly  →    every neuron spiking   →    descending neurons
  LC4, LPLC2, LC16, LC10a      (each fly in its own          DNp01 (Giant Fiber) → jump
his forelegs: taste her        process)                      DNa02 left/right   → turn
  LgLG1 (ppk23 cells)                                        DNp09 / MDN        → walk fwd / back
her antennae: hear his song                                  pIP10 (male)       → sing
  JO-B                                                       vpoDN (female)     → accept
                                                             DNp13 (female)     → reject
```

The hand-written parts are the senses (how the world becomes spikes in
sensory neurons) and the body (how descending neuron firing becomes movement).
Everything in between is the connectome.

## What it does

These behaviours come out of the wiring. None of them are programmed:

* **A cursor moving nearby** excites LC10a (small-object neurons), which
  drives DNa02 on the same side. The fly turns to face your cursor and
  follows it as it moves.
* **A cursor rushing at it** excites LC4 and LPLC2 (looming detectors), which
  drive the Giant Fiber to 100–340 Hz. The fly jumps, and DNa02 on the far
  side tilts the take-off away from you.
* **Left alone**, weak random synaptic activity gives the occasional twitch
  or turn.

## Two flies: will they mate?

```bash
python fly_pet.py --male
```

This adds a male, running on the male connectome. They see each other the
same way they see your cursor. His forelegs taste her when he touches her,
and she hears his song when he's close.

What I found testing it:

* **On their own, they don't court.** Seeing her, even tasting her, barely
  activates his courtship neurons (P1 stays below 1 Hz). Her song-hearing
  neurons (JO-B) don't reach her "yes" neurons either. Real courtship also
  depends on smell, hormones and internal state, which aren't in the model.
* **With the lab switches on, their brains do the rest.** Scientists make
  flies court by switching on P1 (males) or pC1 (females) with light.
  Right-click a fly to flip the same switch:
  * **His P1 on:** his brain fires his song neuron pIP10 at 40–140 Hz, and
    he holds out a wing to sing (♪). If he sees her, he turns toward her
    while singing.
  * **Her pC1 on:** her vpoDN neuron ("yes, open to mate") fires at 30–110 Hz
    and her DNp13 neuron ("no, reject") stays silent.
* **Neither walks forward much**, so they rarely meet on their own. **Drag the
  male with the mouse** and drop him right behind her, facing the same way.
  If he's courting and she's accepting, they mate (♥): he rides on her back
  for 20 seconds (real flies take about 20 minutes). Afterwards she won't
  mate again for a minute.

The "What she's doing" and "What he's doing" windows show the mating neurons
live: his LgLG1, P1 and pIP10; her JO-B, pC1, vpoEN, vpoDN and DNp13.

## Memory: they learn about you

Each fly learns the way real flies do, by changing the strength of synapses.
All ~8,000 visual projection neurons carry a learnable strength that scales
every one of their output synapses.

* **Habituation.** Each time a visual neuron fires, its synapses get slightly
  weaker. Approach without hurting her, and her looming and escape pathways
  weaken so she lets the cursor get closer. In testing, about 10 harmless
  approaches cut her escape distance from ~135 px to ~80 px.
* **Sensitization.** **Click a fly to swat it.** The neurons that fired in
  the ~3 seconds before the swat get stronger, so whatever the fly just saw
  becomes scarier.
* **Forgetting.** Strengths drift back to normal over about 30 minutes.
* **Across runs.** Memory is saved to `brain_data/memory_female.npz` and
  `brain_data/memory_male.npz` every minute and on quit. Time spent closed
  counts as time to forget. Delete a file to give that fly a fresh start.

## Controls

* **Click** a fly: swat it.
* **Drag** a fly: carry it somewhere.
* **Right-click** a fly: its menu.
  * **What she sees / What he sees**: the fly's field of view all the way
    around (left eye, right eye, the overlap in front, the blind spot
    behind), with the cursor and the other fly drawn where and how big the
    fly sees them, a sentence describing it, and the visual neurons of each
    eye.
  * **Her brain / His brain**: every neuron at its position in the brain,
    lighting up when it fires. The male table has no positions, so his
    neurons are drawn at the position of the matching FlyWire cell type.
  * **What she's doing / What he's doing**: the current action in words,
    command neurons, mating neurons, and memory.
  * **Open all three**.
  * **Switch on her pC1 / his P1 neurons**.
  * **Pause / resume**, **Quit**.

## Install and run

```bash
pip install -r requirements.txt
python fly_pet.py            # the female
python fly_pet.py --male     # the female and a male
```

Tkinter ships with most Python installs (on Debian/Ubuntu:
`sudo apt install python3-tk`). No FlyWire token is needed.

* **The female:** the first launch downloads about 130 MB into `brain_data/`
  and builds her brain in about a minute.
* **The male:** the first `--male` launch asks before downloading 3.4 GB. It
  then takes a few minutes and a few GB of memory to build his brain
  (~80 MB). The 3.4 GB download is deleted afterwards.

To check your version, run `python fly_pet.py --version`. The version is also
printed at startup and shown in each window's title. The current version is
**4.0.0**.

Options:

```bash
python fly_pet.py --noise 0.07              # more spontaneous activity (0 = silent brain)
python fly_pet.py --simulate 4              # no window: rush a virtual cursor at her, print neuron rates
python fly_pet.py --simulate 4 --sex male   # the same for the male
python fly_pet.py --rebuild                 # rebuild the cached brain(s)
```

**Speed:** each brain runs in its own process, so a computer with 4 or more
cores helps. The brain window shows how fast each brain runs compared with
real time. Below 1.0× the fly reacts in slow motion, but it still behaves
correctly. On the test machine, each brain ran at 0.35–0.9× real time with
both flies running.

## Honest limitations

* **They rarely walk forward.** The forward-walking neuron (DNp09) is almost
  never driven in this model, so the flies mostly turn in place and jump.
* **Courtship needs the switches.** See above.
* **Mating itself is drawn, not simulated.** The brains decide *whether*
  (her vpoDN "yes" vs DNp13 "no", his courtship state). The body code decides
  that a willing pair, with him right behind her, counts as mating.
* **The learning rule is simplified.** Real flies learn mostly in the
  mushroom body, driven by dopamine. Here, learning happens directly at the
  visual neurons' synapses.
* **They are not conscious.** Nobody knows how to build consciousness, or
  even whether real fruit flies have it. This is a model of the flies'
  wiring, not of minds.
* **The neuron model is simple**, a leaky integrate-and-fire model with
  parameters from Shiu et al. 2024. Real neurons, neuromodulation, learning
  and hunger are all far richer.

## The model

* **Neurons**: leaky integrate-and-fire, using Shiu et al. 2024 (*Nature*)
  parameters: rest −52 mV, threshold −45 mV, membrane τ 20 ms, synaptic
  τ 5 ms, 0.275 mV per synapse, ~2 ms delay and refractory period. Time
  step is 1 ms.
* **Synapse signs** come from each neuron's predicted neurotransmitter.
  Acetylcholine excites; GABA, glutamate and histamine inhibit. Dopamine,
  serotonin and octopamine are slow neuromodulators, so they get no fast
  effect.
* **The male connectome** detects about 5× more synapses per connection than
  FlyWire. So only connections with 3 or more synapses are kept, and weights
  are scaled so a typical central-brain neuron gets the same total input as
  in the female. Neurons whose transmitter prediction is "unclear" (~9%) get
  no fast effect.
* **Added for continuous running**:
  * spike-frequency adaptation (1 mV per spike, τ 200 ms)
  * weak random synaptic events on every non-sensory neuron (`--noise`)

  Without these, some recurrent circuits (the mushroom body, the antennal
  lobe) run away into permanent seizure-like firing.
* **Sensory neurons** fire only from sensory input.

### Data sources

* Female connectivity and neuron list: FlyWire 783 as packaged by
  [Shiu et al. 2024](https://github.com/philshiu/Drosophila_brain_model).
  Connectome: Dorkenwald et al. 2024, Schlegel et al. 2024 (*Nature*).
* Female cell types, sides, neurotransmitters, positions: FlyWire
  hierarchical annotations (Schlegel et al. 2024), loaded with `fafbseg`.
* Male connectome: Male CNS v0.9, Berg et al. 2025 (FlyEM/Janelia,
  Cambridge, MRC LMB, Google), CC-BY, as compiled by the
  [BANC team's tutorial data](https://github.com/sjcabs/fly_connectome_data_tutorial).

## Platform notes

* **Windows**: fully transparent background (only the flies are visible).
* **macOS**: transparent background with the system Tk.
* **Linux/X11**: Tk has no per-pixel transparency, so each fly sits on a small
  beige tile.
