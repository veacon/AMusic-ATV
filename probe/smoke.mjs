import { LxHost, buildMusicInfo } from './lxhost.mjs'
const file = process.argv[2]
const song = JSON.parse(process.argv[3] || '{"title":"晴天","artist":"周杰伦","album":"叶惠美","durationSec":269}')
const quality = process.argv[4] || 'flac'
const h = new LxHost({ verbose: false })
const t0 = Date.now()
const info = h.load(file)
console.log('LOAD', Date.now() - t0, 'ms')
console.log('META', info.meta.name, info.meta.version)
for (const [k, v] of Object.entries(info.sources)) console.log('  source', k, v.name, '| qualitys:', (v.qualitys||[]).join(','))
const src = Object.keys(info.sources)[0]
for (const q of [quality]) {
  const t = Date.now()
  try {
    const url = await h.musicUrl(src, q, buildMusicInfo(song))
    console.log('OK', q, Date.now() - t, 'ms ->', String(url).slice(0, 160))
  } catch (e) {
    console.log('FAIL', q, Date.now() - t, 'ms ->', String(e && e.message || e).slice(0, 300))
  }
}
console.log('--- last logs ---')
console.log(h.logs.slice(-25).join('\n'))
