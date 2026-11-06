const UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15'
const html = await (await fetch('https://music.apple.com/', { headers: { 'User-Agent': UA } })).text()
console.log('html length', html.length)
const m = [...html.matchAll(/src="([^"]*\/assets\/[^"]*\.js)"/g)].map(x => x[1])
console.log('scripts', m.slice(0, 5), 'count', m.length)
for (const s of m.slice(0, 8)) {
  const url = s.startsWith('http') ? s : 'https://music.apple.com' + s
  try {
    const js = await (await fetch(url, { headers: { 'User-Agent': UA } })).text()
    const t = js.match(/eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+/)
    if (t) { console.log('TOKEN from', url, '->', t[0].slice(0, 30) + '...', 'len', t[0].length); break }
  } catch (e) { console.log('fail', url, e.message) }
}
