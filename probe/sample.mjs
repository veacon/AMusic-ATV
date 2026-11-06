// 用 Apple 目录（未登录，仅 bearer）生成 P0 样本：真实标题后缀会压测匹配
import fs from 'node:fs'

const UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15'
const STOREFRONT = process.env.SF || 'us'

const html = await (await fetch('https://music.apple.com/', { headers: { 'User-Agent': UA } })).text()
const src = html.match(/src="([^"]*\/assets\/[^"]*\.js)"/)[1]
const js = await (await fetch('https://music.apple.com' + src, { headers: { 'User-Agent': UA } })).text()
const bearer = js.match(/eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+/)[0]
fs.writeFileSync('bearer.txt', bearer)

const queries = [
  ['华语流行', '周杰伦 晴天'],
  ['华语流行', '邓紫棋 光年之外'],
  ['华语流行', '陈奕迅 富士山下'],
  ['华语流行', '五月天 后来的我们'],
  ['欧美流行', 'Taylor Swift Anti-Hero'],
  ['欧美流行', 'The Weeknd Blinding Lights'],
  ['欧美流行', 'Dua Lipa Levitating'],
  ['欧美流行', 'Billie Eilish bad guy'],
  ['摇滚', 'Queen Bohemian Rhapsody'],
  ['摇滚', 'Radiohead Weird Fishes'],
  ['独立', '落日飞车 My Jinji'],
  ['独立', '草东没有派对 山海'],
  ['古典', 'Glenn Gould Goldberg Variations Aria'],
  ['古典', 'Beethoven Symphony No. 5'],
  ['古典', 'Chopin Nocturne Op. 9 No. 2'],
  ['现场', 'Adele Someone Like You Live'],
  ['OST', '久石让 Summer'],
  ['OST', 'Hans Zimmer Time Inception'],
  ['说唱', 'Kendrick Lamar HUMBLE'],
  ['日语', 'YOASOBI 夜に駆ける'],
]

const out = []
for (const [tag, term] of queries) {
  const u = new URL(`https://amp-api-edge.music.apple.com/v1/catalog/${STOREFRONT}/search`)
  u.searchParams.set('term', term)
  u.searchParams.set('types', 'songs')
  u.searchParams.set('limit', '3')
  const r = await fetch(u, { headers: { Authorization: 'Bearer ' + bearer, Origin: 'https://music.apple.com', 'User-Agent': UA } })
  const j = await r.json().catch(() => null)
  const list = j?.results?.songs?.data || []
  if (!list.length) { console.log('MISS', tag, term); continue }
  const s = list[0].attributes
  out.push({
    tag, query: term,
    appleId: list[0].id,
    title: s.name,
    artist: s.artistName,
    album: s.albumName,
    durationSec: Math.round((s.durationInMillis || 0) / 1000),
    isrc: s.isrc || '',
  })
  console.log('OK  ', tag, '|', s.name, '|', s.artistName, '|', s.albumName, '|', Math.round(s.durationInMillis / 1000) + 's', '|', s.isrc || '-')
}
fs.writeFileSync('songs.json', JSON.stringify(out, null, 2))
console.log('\nsaved', out.length)
