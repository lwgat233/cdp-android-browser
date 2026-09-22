// 最小 CDP 客户端：连上 browser target，调 Network.getAllCookies，按域过滤打印
// 用法: node cdp_cookies.mjs <wsUrl> [域过滤]
const url = process.argv[2];
const filt = process.argv[3] || '';
const ws = new WebSocket(url);
const t = setTimeout(() => { console.log('TIMEOUT'); process.exit(2); }, 15000);
ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'Network.getAllCookies' }));
ws.onerror = (e) => { console.log('ERR', e.message || e.type); process.exit(3); };
ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id !== 1) return;
  clearTimeout(t);
  const cs = (m.result && m.result.cookies) || [];
  const out = cs.filter(c => !filt || String(c.domain).includes(filt));
  console.log('总数=' + cs.length + ' 命中=' + out.length);
  for (const c of out) {
    console.log(JSON.stringify({
      domain: c.domain, name: c.name, value: c.value, path: c.path,
      httpOnly: c.httpOnly, secure: c.secure, session: c.session,
      expires: c.expires, sameSite: c.sameSite, size: c.size
    }));
  }
  ws.close(); process.exit(0);
};
