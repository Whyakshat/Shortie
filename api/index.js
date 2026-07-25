const fs = require('fs');
const path = require('path');

// Memory & File persistence for serverless environments
const DATA_FILE = path.join(process.env.TMPDIR || '/tmp', 'shortie-data.json');

let store = {};

function loadStore() {
  try {
    if (fs.existsSync(DATA_FILE)) {
      store = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
    }
  } catch (e) {
    store = {};
  }
}

function saveStore() {
  try {
    fs.writeFileSync(DATA_FILE, JSON.stringify(store, null, 2), 'utf8');
  } catch (e) {}
}

loadStore();

function generateCode() {
  const chars = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
  let code = '';
  for (let i = 0; i < 5; i++) {
    code += chars.charAt(Math.floor(Math.random() * chars.length));
  }
  return code;
}

module.exports = async (req, res) => {
  const rawUrl = req.url || '/';
  const urlPath = rawUrl.split('?')[0];
  const method = req.method;

  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (method === 'OPTIONS') {
    return res.status(200).end();
  }

  const host = req.headers['x-forwarded-host'] || req.headers['host'] || 'shortie.vercel.app';
  const proto = req.headers['x-forwarded-proto'] || 'https';
  const baseUrl = `${proto}://${host}`;

  // Serve Root HTML Page
  if (urlPath === '/' || urlPath === '/index.html') {
    const indexPath = path.join(process.cwd(), 'public', 'index.html');
    if (fs.existsSync(indexPath)) {
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      return res.status(200).end(fs.readFileSync(indexPath, 'utf8'));
    }
  }

  // Serve Style CSS
  if (urlPath === '/style.css') {
    const cssPath = path.join(process.cwd(), 'public', 'style.css');
    if (fs.existsSync(cssPath)) {
      res.setHeader('Content-Type', 'text/css; charset=utf-8');
      return res.status(200).end(fs.readFileSync(cssPath, 'utf8'));
    }
  }

  // Serve Logo PNG & Favicons
  if (urlPath === '/logo.png' || urlPath === '/favicon.ico' || urlPath === '/favicon.png' || urlPath === '/apple-touch-icon.png' || urlPath === '/apple-touch-icon-precomposed.png') {
    const logoPath = path.join(process.cwd(), 'public', 'logo.png');
    if (fs.existsSync(logoPath)) {
      res.setHeader('Content-Type', 'image/png');
      return res.status(200).end(fs.readFileSync(logoPath));
    }
  }

  // API Check Alias
  if (urlPath.startsWith('/api/check-alias/')) {
    const alias = decodeURIComponent(urlPath.substring('/api/check-alias/'.length));
    const available = !store[alias];
    const suggestion = alias ? `${alias}-1` : 'link-1';
    return res.status(200).json({ alias, available, suggestion });
  }

  // API Stats
  if (urlPath.startsWith('/api/stats/')) {
    const code = decodeURIComponent(urlPath.substring('/api/stats/'.length));
    const entry = store[code];
    if (!entry) return res.status(404).json({ error: 'Short link not found' });
    return res.status(200).json(entry);
  }

  // API Shorten
  if (urlPath === '/api/shorten' && method === 'POST') {
    let body = req.body;
    if (typeof body === 'string') {
      try { body = JSON.parse(body); } catch(e) {}
    }
    const targetUrl = body && body.url;
    let alias = body && body.alias;

    if (!targetUrl) return res.status(400).json({ error: 'Missing required field: url' });

    let code = alias ? alias.trim().replace(/[^a-zA-Z0-9_-]/g, '') : generateCode();
    if (alias && store[code]) {
      return res.status(409).json({ error: `Alias '${code}' is taken`, suggestion: `${code}-1` });
    }

    const entry = {
      code,
      shortUrl: `${baseUrl}/${code}`,
      originalUrl: targetUrl,
      clicks: 0,
      createdAt: new Date().toISOString()
    };

    store[code] = entry;
    saveStore();

    return res.status(201).json({ ...entry, message: 'Short link generated' });
  }

  // API List / Delete Router
  if (urlPath.startsWith('/api/links')) {
    if (method === 'GET') {
      const list = Object.values(store).map(e => ({
        ...e,
        shortUrl: `${baseUrl}/${e.code}`
      }));
      return res.status(200).json(list);
    }
    if (method === 'DELETE') {
      const code = decodeURIComponent(urlPath.substring('/api/links/'.length));
      if (store[code]) {
        delete store[code];
        saveStore();
        return res.status(200).json({ success: true });
      }
      return res.status(404).json({ error: 'Link not found' });
    }
  }

  // Handle Redirection: /:code
  const cleanCode = urlPath.substring(1);
  if (cleanCode && store[cleanCode]) {
    store[cleanCode].clicks = (store[cleanCode].clicks || 0) + 1;
    saveStore();
    res.writeHead(302, { Location: store[cleanCode].originalUrl });
    return res.end();
  }

  // Default Fallback
  return res.status(404).json({ error: 'Not found' });
};
