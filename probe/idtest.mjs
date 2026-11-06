// 第二轮 P0：带上平台 ID 后，逐个脚本测「是否能真的拿到 FLAC / 高码率」
import fs from 'node:fs'
import path from 'node:path'
import { LxHost } from './lxhost.mjs'
import { searchAll } from './search.mjs'
import { verify } from './verify.mjs'

process.on('unhandledRejection', () => {})
process.on('uncaughtException', (e) => console.log('UNCAUGHT', e?.message))

const song = { title: process.argv[2] || '晴天', artist: process.argv[3] || '周杰伦', album: '叶惠美', durationSec: Number(process.argv[4] || 269), tag: 'probe' }
const dir = '../luoxue/音源'
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.js')).sort((a, b) => parseInt(a.match(/\d+/)[0]) - parseInt(b.match(/\d+/)[0]))
const LADDER = (process.env.LADDER || 'master,flac24bit,flac,320k').split(',')

const withTimeout = (p, ms, label) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout ' + ms + ' ' + (label || ''))), ms))])

const cands = await searchAll(song)
const bySource = {}
for (const c of cands) if (!bySource[c.source] && c.score >= 40) bySource[c.source] = c
console.log('CANDIDATES', Object.entries(bySource).map(([k, v]) => `${k}=${v.id}(${v.score})`).join(' '))

function musicInfo(cand) {
  return {
    source: cand.source,
    name: song.title, songName: song.title, singer: song.artist, albumName: song.album,
    interval: `${Math.floor(song.durationSec / 60)}:${String(song.durationSec % 60).padStart(2, '0')}`,
    ...cand.extra, id: cand.id, songid: cand.id, songId: cand.id,
  }
}

const rows = []
for (const f of files) {
  const row = { file: f, name: '', hits: [] }
  let host
  try {
    host = new LxHost({ timeout: 9000 })
    const info = await host.load(path.join(dir, f), 7000)
    row.name = info.meta?.name || ''
    for (const [src, cand] of Object.entries(bySource)) {
      const qs = info.sources[src]?.qualitys || []
      if (!qs.length) continue
      const ladder = LADDER.filter((q) => qs.includes(q)).slice(0, 2)
      for (const q of ladder) {
        const t = Date.now()
        try {
          const url = await withTimeout(host.musicUrl(src, q, musicInfo(cand)), 12000, src + q)
          if (!url || !String(url).startsWith('http')) throw new Error('bad url')
          const v = await verify(url, song.durationSec)
          row.hits.push({ src, q, ok: v.ok, kind: v.kind, kbps: v.kbps, ms: Date.now() - t, status: v.status })
          console.log(`${v.ok ? 'OK ' : 'BAD'} ${f} ${src} ${q} -> ${v.kind} ${v.kbps}kbps ${Date.now() - t}ms`)
          break
        } catch (e) {
          row.hits.push({ src, q, ok: false, err: String(e?.message || e).slice(0, 90), ms: Date.now() - t })
          console.log(`ERR ${f} ${src} ${q} ${String(e?.message || e).slice(0, 70)}`)
        }
      }
    }
  } catch (e) {
    row.loadError = String(e?.message || e).slice(0, 100)
    console.log('LOADFAIL', f, row.loadError)
  }
  rows.push(row)
}

fs.writeFileSync('idtest.json', JSON.stringify(rows, null, 2))
console.log('\n=== 结果矩阵 ===')
for (const r of rows) {
  const best = r.hits.filter((h) => h.ok).sort((a, b) => b.kbps - a.kbps)[0]
  console.log(best ? `BEST ${String(best.kbps).padStart(4)}kbps ${best.kind.padEnd(8)} ${r.file} ${r.name}` : `NONE                    ${r.file} ${r.name} ${r.loadError || ''}`)
}
process.exit(0)
