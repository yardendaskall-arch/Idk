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
  toward each other. Flies constantly jiggle their retinas (Fenk et al. 2022),
  so even a still fly registers a little.
* **Arousal sharpens their eyes for each other.** In courting males, arousal
  turns up the visual neurons' response to the female (Hindmarsh Sten et al.
  2021). Here his arousal does that, and so does her "yes": while she's
  saying yes, her eyes respond 4× more strongly to him, so her own
  walking circuits take her to him. (That this also happens in females is my
  extension; real receptive females mostly slow down and wait.)
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
  moment, and their bodies touch, whoever walked to whom. Then he mounts her
  from behind and rides on her back for 20 seconds (real flies take ~20
  minutes). Afterwards her mood drops to zero and stays low for about 5
  minutes, so she rejects him (real mated females reject males for days,
  because of sex peptide from the male).
* **Egg laying.** Mating gives her 10 fertilised eggs, and one ripens every
  15 seconds. A ripe egg sends an "egg ready" signal into her brain through
  the ascending neurons that feed her egg-laying command neurons (oviDN).
  When oviDN has fired enough, she pauses and lays the egg, which stays on
  your screen. That's how real oviDN works: its activity rises until it
  crosses a threshold, then the egg is laid (Vijayan et al. 2023). Her pC1
  mating drive also excites oviIN, which holds egg laying back, as in
  Wang et al. 2020. She never lays while mating. Up to 40 eggs stay on
  screen.

**Lab override.** Right-click a fly and choose "Lab override" to force its P1
or pC1 fully on, as scientists do with light. With her pC1 forced on, she
says yes almost every time.

You can still **drag** either fly to put them together.

The "What she's doing" and "What he's doing" windows show the mating neurons
live (his LgLG1, P1 and pIP10; her JO-B, pC1, vpoEN, vpoDN, DNp13, and the
egg-laying neurons egg-AN, oviIN and oviDN), plus arousal, her mood and her
egg count.

## Everyday life: eating, grooming, and a life cycle

**Eating.** Drops of sugary food appear on the screen every 40 seconds (up to
3), and you can right-click a fly and choose "Put food in front of her/him".
When a fly stands on food, its sugar-taste neurons (LB3) fire more strongly
the hungrier it is, as in real flies. In her brain, sugar drives MN9, the
proboscis motor neuron, to 40–130 Hz. That's the main result of the published
whole-brain model this app is based on (Shiu et al. 2024). When MN9 fires, she
stops, puts out her proboscis and eats. The drop shrinks, her hunger goes
down, and once she's full she stops. Hunger builds back up over ~10 minutes.

**Smell.** Food odour reaches the antenna facing it and excites fruity-smell
olfactory neurons. In her brain that nudges her turning neurons, but not
reliably toward the food, so she mostly finds food by chance or when you put
it in front of her.

**Grooming.** Dust collects on the antennae over a few minutes, and faster
when you swat a fly. Dust excites Johnston's organ touch neurons (JO-C/E). In
his brain those drive DNg12, the anterior-grooming command neuron (Guo et
al. 2022), to ~20–30 Hz. He stops, rubs his front legs over his head, and the
dust comes off.

**What doesn't work, honestly:**
* **He can't eat.** In the male connectome model, sugar doesn't reach his MN9.
* **She doesn't groom.** In the female model, antennal touch doesn't reach her
  DNg12.

Their windows show this: they'll be hungry or dusty and their neurons stay
quiet.

**Life cycle.** Each egg she lays hatches into a larva after a minute. The
larva crawls about and eats any food it finds, pupates after two minutes,
and a new adult fly emerges from the pupa a minute later (real flies take
about 10 days). The new fly is a daughter or a son at random:

* **A brain of its own.** Daughters run on the FlyWire female brain and sons
  on the male brain, each in its own process, like their parents.
* **Starting out.** New flies start pale and are too young to court for 3
  minutes.
* **Brain limit.** Each brain needs about one CPU core, so only 4 flies with
  brains can live at once (change it with `--max-flies`). When the limit is
  reached, newly hatched flies fly away off the top of the screen.
* **No memory saved.** New flies don't keep memory between runs. Larvae and
  pupae are drawn, not simulated: the separate larval connectome isn't
  included.

## Memory: they learn about you

Each fly learns the way real flies do, by changing the strength of synapses.
About 7,600 visual projection neurons carry a learnable strength that scales
every one of their output synapses. The object-tracking neurons (LC10a, LC9)
are left out: they fire almost constantly, and letting them fade would stop
the flies from finding each other and food.

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

* **Click** a fly: swat it (and ruffle its antennae).
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
  * **Put food in front of her / him**.
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
**5.0.1**.

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
* **Hunger, dust and the life-cycle timings are hand-written.** The brains
  decide what to *do* about them (eat, groom, lay).
* **Egg ripening is hand-written.** How many eggs and how fast they ripen
  is body code. *When* each one is laid comes from her oviDN neurons.
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
* **The eyes ignore the fly's own turning**, as real flies do with an
  efference copy, and walking damps LC9 (Turner et al. 2022). Each object
  excites its own patch of the eye, so objects don't add up. The
  object-tracking channels (LC10a, LC9) top out at 90 Hz. Above that, in this
  model, they spill into the Giant Fiber and the flies jump constantly.
* **A jump needs a real Giant Fiber volley** (over 70 Hz; a looming threat
  drives it to 100–340 Hz) and a second's recovery after landing.

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
