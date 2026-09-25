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
  LC4, LPLC2, LC16,            (each fly in its own          DNp01 (Giant Fiber) → jump
  LC10a, LC9                   process)                      DNa02 left/right   → turn
his forelegs: taste her                                      DNp09 / MDN        → walk fwd / back
  LgLG1 (ppk23 cells)          AROUSAL (code)                pIP10 (male)       → sing
her antennae: hear his song    stand-in for dopamine and     vpoDN (female)     → accept
  JO-B, vpoEN                  hormones → P1 / pC1           DNp13 (female)     → reject
```

The hand-written parts are:

* **The senses:** how the world becomes spikes in sensory neurons.
* **Arousal:** one slow number per fly, standing in for dopamine and hormones.
* **The body:** how descending neuron firing becomes movement.

Everything in between is the connectome.

## What it does

These behaviours come out of the wiring. None of them are programmed:

* **A cursor moving nearby** excites LC10a and LC9 (small-object neurons).
  LC10a drives DNa02 on the same side, and LC9 drives DNp09 (P9), the
  forward-walking neuron. The fly turns toward your cursor and walks after it.
  That's the same pathway male flies use to chase females (Bidaye et al.
  2020).
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

In testing, with no help at all, they walked to each other within seconds.
He got aroused and sang, she went back and forth between yes and no, and
about 90 seconds in they mated. Afterwards her mood dropped, and when he
courted her again she rejected him.

How each part works:

* **Finding each other.** A moving fly is a small moving object, so it
  excites LC9 → DNp09 and LC10a → DNa02 in the other fly's brain. They walk
  toward each other.
* **Arousal (the one hand-written part in the middle).** In the simulation,
  the senses alone never switch on his P1 courtship neurons or her pC1
  mating-drive neurons: her pC1 is actually *inhibited* when she sees him. In
  real flies, those neurons are gated by dopamine and hormones, which build
  up over seconds to minutes and aren't in any connectome. So each fly has
  one slow arousal number:
  * **His** rises while he sees her nearby and even more while he tastes
    her, and fades over ~30 s. It drives P1 up to 60 Hz. After mating he
    rests for a minute.
  * **Hers** rises while she hears his song and fades over ~40 s. It's
    multiplied by her **mood**, which is random each run, drifts over
    minutes, and drops to zero after mating. It drives pC1 up to 50 Hz.
* **His song comes from his brain.** P1 → pIP10 (the song neuron) fires at
  about twice the P1 rate. Above 30 Hz he holds a wing out and sings (♪).
* **Her yes or no comes from her brain.** His song drives her song-tuned
  vpoEN neurons. With her pC1 low, her brain turns that song into **no**:
  DNp13 wins, and she extrudes her ovipositor at him. With her pC1 higher,
  **yes** wins: vpoDN opens her to mating. In between, noise tips it either
  way, so the same fly can go back and forth.
* **He learns from rejection (courtship conditioning).** Real male flies
  court persistently (following, tapping, singing), but after being
  rejected for a while they court much less for hours. That's one of the
  best-studied kinds of learning in flies. Here, every second she rejects
  him up close lowers his arousal right away and lowers how high it can
  climb later. After ~45 seconds of rejection he mostly gives up. It fades
  over ~15 minutes (hours in real flies) and is remembered between runs in
  `brain_data/courtship_male.json`. His window shows it as "interest ×0.40".
* **Mating (♥).** It happens when he's courting, she's saying yes at that
  moment, and he's touching her with his forelegs while facing her. Then he
  mounts her from behind and rides on her back for 20 seconds (real flies
  take ~20 minutes). She won't mate again for at least a minute, and her
  mood takes minutes to recover.

**Lab override.** Right-click a fly and choose "Lab override" to force its P1
or pC1 fully on, as scientists do with light. With her pC1 forced on, she
says yes almost every time.

You can still **drag** either fly to put them together.

The "What she's doing" and "What he's doing" windows show the mating neurons
live (his LgLG1, P1 and pIP10; her JO-B, pC1, vpoEN, vpoDN and DNp13), plus
arousal and her mood.

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
  * **Lab override: force her pC1 / his P1 neurons on**.
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
**4.2.0**.

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

* **They only walk toward things that move.** LC9 and LC10a respond to
  objects sweeping across the eye. A fly that sees nothing moving mostly
  stays put, apart from the occasional twitch.
* **Arousal is hand-written.** The brains decide what to *do* with arousal
  (sing, say yes or no), but how arousal rises and falls is my stand-in for
  dopamine and hormones, which no connectome contains.
* **Her ears are partly hand-written.** In the simulation, her hearing
  neurons don't carry song as far as vpoEN, so song drives her song-tuned
  vpoEN neurons directly, the same way the eyes drive visual feature
  detectors.
* **Mating itself is drawn, not simulated.** The brains decide *whether*
  (her vpoDN "yes" vs DNp13 "no", his courtship state). The body code decides
  that a willing pair in contact counts as mating, and animates the mount.
* **His learning from rejection is hand-written**, like arousal. In real
  flies it involves the mushroom body and the pheromones of mated females.
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
