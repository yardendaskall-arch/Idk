// In-memory global store — persists across requests within the same warm instance.
// For multi-instance reliability swap this for @vercel/kv (add KV store in Vercel dashboard).
const store = { js: '', css: '' };

export default function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') return res.status(200).end();

  if (req.method === 'GET') {
    return res.json(store);
  }

  if (req.method === 'POST') {
    const { type, code } = req.body ?? {};
    if (type === 'css') store.css = code ?? '';
    else               store.js  = code ?? '';
    return res.json({ ok: true });
  }

  res.status(405).end();
}
