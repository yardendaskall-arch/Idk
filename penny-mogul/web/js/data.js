/* Penny Mogul — static game data (stocks, news, industries, upgrades, achievements). */
(function () {
  'use strict';
  const PM = (globalThis.PM = globalThis.PM || {});

  /* ---------------------------------------------------------------- sectors */
  PM.SECTORS = {
    tech:     { name: 'Tech',      color: '#6C8CFF' },
    consumer: { name: 'Consumer',  color: '#FFB547' },
    mobility: { name: 'Mobility',  color: '#2EE6A6' },
    health:   { name: 'Health',    color: '#FF6B9A' },
    finance:  { name: 'Finance',   color: '#B38CFF' },
    media:    { name: 'Media',     color: '#4DD8FF' },
    space:    { name: 'Space',     color: '#8C9BFF' },
  };

  /* ----------------------------------------------------------------- stocks
     vol  = idiosyncratic std-dev of the per-second log return
     beta = sensitivity to the market-wide factor
     div  = dividend, as a fraction of price, paid every 60 seconds           */
  PM.STOCKS = [
    { sym: 'NBLD', name: 'Nebula Dynamics',   sector: 'tech',     icon: '🌌', color: '#6C5CE7', price: 182,  vol: 0.0042, beta: 1.2, div: 0,
      blurb: 'Cloud computing for people who lost their clouds.', prods: ['QuantumToaster 9', 'Cloud Sprinkler', 'Nebula OS'] },
    { sym: 'QCKL', name: 'Quackle Foods',     sector: 'consumer', icon: '🦆', color: '#F5A524', price: 24,   vol: 0.0025, beta: 0.6, div: 0.0008,
      blurb: 'Duck-themed snacks. Surprisingly nutritious.', prods: ['Quackers', 'Duck Jerky', 'Pond Water Soda'] },
    { sym: 'VOLT', name: 'VoltRider Motors',  sector: 'mobility', icon: '⚡', color: '#12B886', price: 96,   vol: 0.0068, beta: 1.5, div: 0,
      blurb: 'Electric everything. Mostly cars.', prods: ['VoltRider S', 'Zap Scooter', 'Self-Parking Bike'] },
    { sym: 'PIXL', name: 'Pixelworks Studios', sector: 'media',   icon: '🎮', color: '#FF4D8D', price: 58,   vol: 0.0058, beta: 1.3, div: 0,
      blurb: 'Games that eat your weekends.', prods: ['Dungeon Dash 4', 'Pixel Farm', 'Sequel Simulator'] },
    { sym: 'HYDR', name: 'HydroGen Energy',   sector: 'mobility', icon: '💧', color: '#22B8F0', price: 41,   vol: 0.0040, beta: 0.9, div: 0.0006,
      blurb: 'Clean power from very wet ideas.', prods: ['H2 Cell', 'Tidal Turbine', 'Hydro Battery'] },
    { sym: 'BIOX', name: 'BioNova Labs',      sector: 'health',   icon: '🧬', color: '#F06595', price: 73,   vol: 0.0075, beta: 1.0, div: 0,
      blurb: 'One breakthrough away from greatness.', prods: ['NovaGel', 'Gene Patch', 'ZeroSneeze'] },
    { sym: 'MEOW', name: 'Catnip Cloud',      sector: 'tech',     icon: '🐱', color: '#FF922B', price: 7.4,  vol: 0.0095, beta: 1.6, div: 0,
      blurb: 'Storage for cat photos. Valuation: vibes.', prods: ['Whisker Drive', 'Purr-Sync', 'Litter Box 2.0'] },
    { sym: 'ORBT', name: 'Orbital Freight',   sector: 'space',    icon: '🛰️', color: '#748FFC', price: 215,  vol: 0.0080, beta: 1.4, div: 0,
      blurb: 'Next-day delivery. Next-orbit, actually.', prods: ['Moon Courier', 'Cargo Pod', 'Satellite Swarm'] },
    { sym: 'GLDB', name: 'Goldleaf Bank',     sector: 'finance',  icon: '🏦', color: '#CC5DE8', price: 128,  vol: 0.0022, beta: 0.8, div: 0.0010,
      blurb: 'Your money, but with extra steps.', prods: ['Savings+', 'Gold Card', 'Mega Mortgage'] },
    { sym: 'SNKR', name: 'Stride Athletics',  sector: 'consumer', icon: '👟', color: '#FA5252', price: 64,   vol: 0.0038, beta: 1.0, div: 0.0004,
      blurb: 'Run faster. Or at least look like it.', prods: ['AirStride One', 'Limited Drop 12', 'Cloud Slides'] },
    { sym: 'PIZZ', name: 'Slice & Co.',       sector: 'consumer', icon: '🍕', color: '#E8590C', price: 33,   vol: 0.0030, beta: 0.7, div: 0.0007,
      blurb: 'Pizza. Franchises. More pizza.', prods: ['Mega Slice', 'Stuffed Crust 9000', 'Pizza Drone'] },
    { sym: 'ROBO', name: 'Robotica',          sector: 'tech',     icon: '🤖', color: '#845EF7', price: 310,  vol: 0.0060, beta: 1.3, div: 0,
      blurb: 'Robots that do your chores. Eventually.', prods: ['ChoreBot', 'Butler-9', 'Warehouse Walker'] },
  ];

  /* ------------------------------------------------------------------- news */
  PM.NEWS = {
    stockPos: [
      '{n} unveils new {p}; analysts are "cautiously ecstatic"',
      '{n} smashes earnings estimates',
      '{t} upgraded to Strong Buy by "a guy on the internet"',
      '{n} signs mega-deal to supply {p}',
      'Celebrity spotted using {n} {p}',
      '{n} announces surprise stock buyback',
      '{n} CEO promises "literally the best quarter ever"',
      'Patent granted: {n}\'s {p} is "legally distinct"',
      '{n} expands to 40 new countries (and a moon base)',
      '{t} hits record demand as {p} sells out',
    ],
    stockNeg: [
      '{n} recalls {p} after "minor" incident',
      '{n} misses earnings; blames indoor weather',
      'Regulators open probe into {n}',
      '{n} CEO caught napping during keynote',
      'Supply chain hiccup delays {p}',
      '{t} downgraded: analysts "just not feeling it"',
      'Data leak at {n} exposes everyone\'s snack preferences',
      '{n} loses key engineer to a rival\'s ping-pong table',
      'Class-action lawsuit targets {n}\'s {p}',
      'Short seller publishes 47-page report on {n}',
    ],
    moon: ['{n} goes VIRAL — shares rocket!', 'Surprise buyout rumors send {t} through the roof'],
    crash: ['SCANDAL: {n} shares plunge', '{n} accounting "irregularities" shock investors'],
    sectorPos: {
      tech:     ['AI hype wave lifts tech stocks', 'Chip shortage finally eases'],
      consumer: ['Shoppers splurge: consumer spending jumps', 'Holiday sales smash forecasts'],
      mobility: ['Government announces EV subsidies', 'Oil prices fall, road trips rise'],
      health:   ['Breakthrough trial boosts biotech', 'Health reform seen as friendly'],
      finance:  ['Rate decision widens bank margins', 'Loan defaults at record low'],
      media:    ['Streaming subscriptions surge', 'Blockbuster weekend lifts media stocks'],
      space:    ['Launch success sends space stocks soaring', 'Orbital contracts awarded'],
    },
    sectorNeg: {
      tech:     ['Tech selloff as "the algorithm" blinks', 'New privacy law spooks tech'],
      consumer: ['Consumer confidence dips as coupons expire', 'Inflation bites consumer brands'],
      mobility: ['Battery recall rattles mobility sector', 'Fuel prices spike overnight'],
      health:   ['Drug-pricing crackdown hits health stocks', 'Trial results disappoint across biotech'],
      finance:  ['Credit worries weigh on banks', 'Surprise stress-test failures'],
      media:    ['Piracy spike hurts studios', 'Viewers abandon cable (again)'],
      space:    ['Rocket "rapidly disassembles"; space stocks dive', 'Solar flare knocks satellites offline'],
    },
    marketPos: ['Central bank cuts rates; markets rally', 'Jobs report beats expectations', 'Trade deal ends tariff drama'],
    marketNeg: ['Surprise inflation print rattles investors', 'Central bank hints at higher rates', 'Global jitters trigger broad selloff'],
  };

  /* ------------------------------------------------------------- industries
     Numbers are derived so every industry has a similar "payback" shape:
       profit per line per second  ≈ startup × tier / (330 + 90 × industryTier)
     rate (units/s per line) and price are computed from that.                 */
  const PROFIT_TIER = [1, 2.3, 5.2, 12];
  const RATE_TIER = [1, 0.8, 0.6, 0.45];
  const RD_COST = [0, 0.8, 4, 20]; // × startup
  const RD_TIME = [0, 40, 150, 480]; // seconds, scaled by tier below

  function sig(x, n) { // round to n significant digits
    if (!isFinite(x) || x === 0) return 0;
    const m = Math.pow(10, n - 1 - Math.floor(Math.log10(Math.abs(x))));
    return Math.round(x * m) / m;
  }

  function industry(def) {
    const products = def.products.map((pr, k) => {
      const target = def.startup * PROFIT_TIER[k] / (330 + def.tier * 90); // profit/s of one line (later industries pay back slower)
      const cf = pr.cf;
      let price = sig(target / (def.rate * RATE_TIER[k] * (1 - cf)), 2);
      if (price < 1) price = Math.round(price * 100) / 100;
      const cost = sig(price * cf, 2);
      const rate = sig(target / ((price - cost) || 1), 2);
      return {
        id: pr.id, name: pr.name, icon: pr.icon, unit: pr.unit, tier: k,
        price, cost, rate,
        demand: sig(rate * 1.25, 3), // units/s at market price with no marketing
        rdCost: Math.round(def.startup * RD_COST[k]),
        rdTime: Math.round(RD_TIME[k] * (1 + def.tier * 0.12)),
      };
    });
    return Object.assign({}, def, { products });
  }

  PM.INDUSTRIES = [
    industry({ id: 'lemonade', short: 'Lemonade', tier: 0, name: 'Lemonade Co.', icon: '🍋', startup: 100, rate: 0.5, color: '#FFD43B',
      desc: 'Start small. Squeeze big.',
      names: ['Zesty', 'Sunny Side', 'Puckered', 'Tangy', 'Pitcher Perfect'], suffix: ['Lemonade Co.', 'Stand', 'Refreshments'],
      products: [
        { id: 'lemonade', name: 'Classic Lemonade', icon: '🍋', unit: 'cup', cf: 0.35 },
        { id: 'icedtea', name: 'Iced Tea', icon: '🧋', unit: 'cup', cf: 0.33 },
        { id: 'smoothie', name: 'Fruit Smoothie', icon: '🥤', unit: 'cup', cf: 0.30 },
        { id: 'soda', name: 'Craft Soda', icon: '🫧', unit: 'bottle', cf: 0.28 },
      ] }),
    industry({ id: 'bakery', short: 'Bakery', tier: 1, name: 'Bakery', icon: '🥐', startup: 1200, rate: 0.6, color: '#FFA94D',
      desc: 'Fresh from the oven, straight to your bank account.',
      names: ['Rise & Shine', 'Flour Power', 'Crumb Theory', 'Dough Re Mi'], suffix: ['Bakery', 'Bakehouse', 'Patisserie'],
      products: [
        { id: 'bread', name: 'Sourdough Loaf', icon: '🍞', unit: 'loaf', cf: 0.35 },
        { id: 'cupcake', name: 'Cupcakes', icon: '🧁', unit: 'box', cf: 0.33 },
        { id: 'cake', name: 'Layer Cake', icon: '🎂', unit: 'cake', cf: 0.30 },
        { id: 'wedding', name: 'Wedding Cake', icon: '💒', unit: 'tier', cf: 0.28 },
      ] }),
    industry({ id: 'coffee', short: 'Coffee', tier: 2, name: 'Coffee Roasters', icon: '☕', startup: 15000, rate: 0.8, color: '#C08457',
      desc: 'Bean there, done that, profited.',
      names: ['Bean Scene', 'Grind House', 'Roast Report', 'Mocha Motion'], suffix: ['Roasters', 'Coffee Co.', 'Brew Lab'],
      products: [
        { id: 'drip', name: 'Drip Coffee', icon: '☕', unit: 'case', cf: 0.35 },
        { id: 'coldbrew', name: 'Cold Brew', icon: '🧊', unit: 'keg', cf: 0.33 },
        { id: 'beans', name: 'Single-Origin Beans', icon: '🫘', unit: 'sack', cf: 0.30 },
        { id: 'reserve', name: 'Gold Reserve', icon: '🏆', unit: 'crate', cf: 0.28 },
      ] }),
    industry({ id: 'sneakers', short: 'Sneaker', tier: 3, name: 'Sneaker Factory', icon: '👟', startup: 180000, rate: 0.6, color: '#FF6B6B',
      desc: 'Hype by the pallet.',
      names: ['Sole Mate', 'Kick Theory', 'Lace Up', 'Tread Lightly'], suffix: ['Footwear', 'Sneaker Co.', 'Athletics'],
      products: [
        { id: 'canvas', name: 'Canvas Kicks', icon: '👟', unit: 'pallet', cf: 0.35 },
        { id: 'runner', name: 'Running Shoes', icon: '🏃', unit: 'pallet', cf: 0.33 },
        { id: 'drop', name: 'Limited Drop', icon: '🔥', unit: 'pallet', cf: 0.30 },
        { id: 'collab', name: 'Gold Collab Edition', icon: '👑', unit: 'pallet', cf: 0.28 },
      ] }),
    industry({ id: 'electronics', short: 'Electronics', tier: 4, name: 'Electronics', icon: '📱', startup: 2200000, rate: 0.5, color: '#4DABF7',
      desc: 'Gadgets people definitely need.',
      names: ['Volt', 'Circuit City-ish', 'Byte Me', 'Nano'], suffix: ['Electronics', 'Devices', 'Labs'],
      products: [
        { id: 'earbuds', name: 'Wireless Earbuds', icon: '🎧', unit: 'batch', cf: 0.35 },
        { id: 'watch', name: 'Smartwatch', icon: '⌚', unit: 'batch', cf: 0.33 },
        { id: 'phone', name: 'Smartphone', icon: '📱', unit: 'batch', cf: 0.30 },
        { id: 'foldable', name: 'Foldable Phone', icon: '📲', unit: 'batch', cf: 0.28 },
      ] }),
    industry({ id: 'games', short: 'Gaming', tier: 5, name: 'Game Studio', icon: '🎮', startup: 26000000, rate: 2.5, color: '#9775FA',
      desc: 'Zero marginal cost. Infinite crunch.',
      names: ['Pixel Pioneers', 'Critical Hit', 'Respawn Ranch', 'Loot Box'], suffix: ['Studios', 'Games', 'Interactive'],
      products: [
        { id: 'mobile', name: 'Mobile Game', icon: '📱', unit: 'license', cf: 0.14 },
        { id: 'indie', name: 'Indie Hit', icon: '🕹️', unit: 'license', cf: 0.13 },
        { id: 'aaa', name: 'AAA Blockbuster', icon: '🎬', unit: 'license', cf: 0.12 },
        { id: 'vr', name: 'VR Epic', icon: '🥽', unit: 'license', cf: 0.12 },
      ] }),
    industry({ id: 'auto', short: 'Auto', tier: 6, name: 'Auto Works', icon: '🚗', startup: 320000000, rate: 4, color: '#38D9A9',
      desc: 'Four wheels, eight figures.',
      names: ['Apex', 'Torque', 'Gearhead', 'Redline'], suffix: ['Motors', 'Automotive', 'Auto Works'],
      products: [
        { id: 'compact', name: 'Compact Car', icon: '🚗', unit: 'fleet', cf: 0.35 },
        { id: 'ev', name: 'Electric Hatch', icon: '🔋', unit: 'fleet', cf: 0.33 },
        { id: 'sedan', name: 'Luxury Sedan', icon: '🚘', unit: 'fleet', cf: 0.30 },
        { id: 'hyper', name: 'Hypercar', icon: '🏎️', unit: 'fleet', cf: 0.28 },
      ] }),
    industry({ id: 'pharma', short: 'Pharma', tier: 7, name: 'Pharma Labs', icon: '💊', startup: 3800000000, rate: 3, color: '#FF8787',
      desc: 'Pills, patents, profits.',
      names: ['Vita', 'Cura', 'Helix', 'Remedy'], suffix: ['Pharma', 'Therapeutics', 'Labs'],
      products: [
        { id: 'vitamins', name: 'Vitamins', icon: '💊', unit: 'pallet', cf: 0.30 },
        { id: 'antibiotic', name: 'Antibiotics', icon: '🧪', unit: 'pallet', cf: 0.27 },
        { id: 'wonder', name: 'Wonder Drug', icon: '✨', unit: 'pallet', cf: 0.24 },
        { id: 'gene', name: 'Gene Therapy', icon: '🧬', unit: 'pallet', cf: 0.22 },
      ] }),
    industry({ id: 'space', short: 'Aerospace', tier: 8, name: 'Aerospace', icon: '🚀', startup: 46000000000, rate: 1.5, color: '#74C0FC',
      desc: 'The sky was never the limit.',
      names: ['Stellar', 'Apogee', 'Nova', 'Zenith'], suffix: ['Aerospace', 'Launch Systems', 'Space Co.'],
      products: [
        { id: 'drones', name: 'Drone Swarm', icon: '🛸', unit: 'swarm', cf: 0.35 },
        { id: 'sats', name: 'Satellite', icon: '🛰️', unit: 'unit', cf: 0.32 },
        { id: 'rockets', name: 'Cargo Rocket', icon: '🚀', unit: 'launch', cf: 0.29 },
        { id: 'moon', name: 'Moon Shuttle', icon: '🌕', unit: 'trip', cf: 0.26 },
      ] }),
  ];

  PM.IND = {};
  PM.INDUSTRIES.forEach((i) => { PM.IND[i.id] = i; });

  /* ----------------------------------------------- business tuning constants */
  PM.BIZ = {
    elasticity: 1.6,          // demand ~ (marketPrice/price)^elasticity
    priceMin: 0.5, priceMax: 2.5,
    maxLines: 10,
    maxCompanyUp: { mkt: 12, eff: 8, spd: 10, wh: 6 },
    lineCost: (S, n) => S * 0.5 * Math.pow(1.8, n - 2),            // n = line number being built (>=2)
    upCost: {
      mkt: (S, l) => S * 0.4 * Math.pow(1.9, l),
      eff: (S, l) => S * 0.6 * Math.pow(2.1, l),
      spd: (S, l) => S * 0.5 * Math.pow(1.95, l),
      wh:  (S, l) => S * 0.15 * Math.pow(1.7, l),
    },
    upInfo: {
      mkt: { name: 'Marketing', icon: '📣', desc: '+30% customer demand per level' },
      eff: { name: 'Efficiency', icon: '⚙️', desc: '-7% material cost per level' },
      spd: { name: 'Assembly Speed', icon: '⚡', desc: '+25% production per level' },
      wh:  { name: 'Warehouse', icon: '📦', desc: '+1× storage capacity per level' },
    },
  };

  /* ------------------------------------------------------ personal upgrades */
  PM.UPGRADES = [
    { id: 'tap',    name: 'Tap Power',     icon: '👆', max: 60, cost: (l) => 25 * Math.pow(1.55, l),
      desc: (l) => '+$' + (0.6).toFixed(1) + ' per tap' },
    { id: 'hustle', name: 'Side Hustle',   icon: '🛵', max: 60, cost: (l) => 40 * Math.pow(1.7, l),
      desc: () => 'Earn money automatically every second' },
    { id: 'broker', name: 'Broker Perks',  icon: '📈', max: 5,  cost: (l) => 500 * Math.pow(4, l),
      desc: () => 'Cut trading fees by 20% each level (free at max)' },
    { id: 'lab',    name: 'Lab Grants',    icon: '🔬', max: 6,  cost: (l) => 1500 * Math.pow(3.5, l),
      desc: () => 'Product research is 12% faster per level' },
  ];

  /* ----------------------------------------------------------- achievements */
  PM.ACHIEVEMENTS = [
    { id: 'tap1',     reward: 2,     icon: '👆', name: 'First Dollar',      desc: 'Tap the coin for the first time',      hint: 'Tap the coin!',                         test: (s) => s.stats.taps >= 1 },
    { id: 'trade1',   reward: 25,    icon: '📊', name: 'Market Debut',      desc: 'Make your first trade',                hint: 'Open Market and buy a stock',           test: (s) => s.stats.trades >= 1 },
    { id: 'nw100',    reward: 20,    icon: '💵', name: 'Hundred-aire',      desc: 'Reach $100 net worth',                 hint: 'Keep tapping to reach $100',            test: (s, g) => g.netWorth() >= 100 },
    { id: 'founder',  reward: 100,   icon: '🏭', name: 'Founder',           desc: 'Start your first company',             hint: 'Start a company in Business',           test: (s) => s.companies.length >= 1 },
    { id: 'profit',   reward: 50,    icon: '🟢', name: 'In the Green',      desc: 'Sell a stock for a profit',            hint: 'Sell a stock when it is up',            test: (s) => s.stats.bestTrade > 0 },
    { id: 'inventor', reward: 250,   icon: '💡', name: 'Inventor',          desc: 'Finish researching a new product',     hint: 'Research a product in your company',    test: (s) => s.stats.researched >= 1 },
    { id: 'nw1k',     reward: 100,   icon: '💰', name: 'Thousand-aire',     desc: 'Reach $1,000 net worth',               hint: 'Reach $1,000 net worth',                test: (s, g) => g.netWorth() >= 1e3 },
    { id: 'diverse',  reward: 500,   icon: '🧺', name: 'Diversified',       desc: 'Hold 5 different stocks at once',      hint: 'Own 5 different stocks',                test: (s) => Object.keys(s.holdings).length >= 5 },
    { id: 'tap1k',    reward: 250,   icon: '💪', name: 'Finger Workout',    desc: 'Tap 1,000 times',                      hint: 'Tap 1,000 times',                       test: (s) => s.stats.taps >= 1000 },
    { id: 'div100',   reward: 300,   icon: '🏖️', name: 'Passive Income',    desc: 'Collect $100 in dividends',            hint: 'Hold dividend stocks (GLDB, QCKL, PIZZ)', test: (s) => s.stats.dividends >= 100 },
    { id: 'tycoon',   reward: 2000,  icon: '🏢', name: 'Tycoon',            desc: 'Run 3 companies at once',              hint: 'Run 3 companies',                       test: (s) => s.companies.length >= 3 },
    { id: 'bigwin',   reward: 1000,  icon: '🚀', name: 'To the Moon',       desc: 'Sell a position for a 50%+ gain',      hint: 'Sell a stock that is up 50%',           test: (s) => s.stats.bestPct >= 0.5 },
    { id: 'nw1m',     reward: 5e4,   icon: '🤑', name: 'Millionaire',       desc: 'Reach $1,000,000 net worth',           hint: 'Reach $1M net worth',                   test: (s, g) => g.netWorth() >= 1e6 },
    { id: 'empire',   reward: 2.5e5, icon: '🌍', name: 'Empire',            desc: 'Run 6 companies at once',              hint: 'Run 6 companies',                       test: (s) => s.companies.length >= 6 },
    { id: 'nw1b',     reward: 5e7,   icon: '💎', name: 'Billionaire',       desc: 'Reach $1B net worth',                  hint: 'Reach $1B net worth',                   test: (s, g) => g.netWorth() >= 1e9 },
    { id: 'nw1t',     reward: 5e10,  icon: '👑', name: 'Trillionaire',      desc: 'Reach $1T net worth. You win capitalism.', hint: 'Reach $1T net worth',               test: (s, g) => g.netWorth() >= 1e12 },
  ];

  /* ------------------------------------------------------------ name pieces */
  PM.COMPANY_ADJ = ['Mega', 'Hyper', 'Prime', 'Golden', 'Lucky', 'Rapid', 'Bright', 'Royal'];
})();
