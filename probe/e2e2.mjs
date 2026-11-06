// P0 终测：搜索 → 匹配 → 按音质优先的脚本级联 → 直链校验（真格式/真码率）
import fs from 'node:fs'
import path from 'node:path'
import { LxHost } from './lxhost.mjs'
import { searchAll } from './search.mjs'
import { verify } from './verify.mjs'

process.on('unhandledRejection', () => {})
process.on('uncaughtException', (e) => console.log('UNCAUGHT', e?.message))

const songs = JSON.parse(fs.readFileSync(process.env.SONGS || 'songs.json', 'utf8'))
const dir = '../luoxue/音源'
const TIMEOUT = Number(process.env.CALL_TIMEOUT || 10000)

// 来自 idtest 的实测优先表：每源的首选/备选脚本（按历史最高码率排序）
const PREF = {
  wy: ['洛雪音源14.js', '洛雪音源1.js', '洛雪音源15.js', '洛雪音源13.js'],
  tx: ['洛雪音源14.js', '洛雪音源13.js', '洛雪音源3.js', '洛雪音源10.js'],
  kg: ['洛雪音源3.js', '洛雪音源10.js', '洛雪音源13.js', '洛雪音源15.js'],
  kw: ['洛雪音源14.js', '洛雪音源3.js'],
  mg: ['洛雪音源14.js', '洛雪音源13.js'],
}
const LADDER = (process.env.LADDER || 'master,flac24bit,hires,flac,320k,128k').split(',')
const ACCEPT_KBPS = Number(process.env.ACCEPT_KBPS || 200)

const withTimeout = (p, ms, label) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout ' + ms + ' ' + (label || ''))), ms))])

const hosts = {}
for (const f of new Set(Object.values(PREF).flat())) {
  try {
    const h = new LxHost({ timeout: TIMEOUT })
    const info = await h.load(path.join(dir, f), 8000)
    hosts[f] = { host: h, info }
  } catch (e) { console.log('HOSTFAIL', f, e.message) }
}
console.log('HOSTS', Object.keys(hosts).join(', '))

const qualityOrder = (info, src) => {
  const qs = info.sources[src]?.qualitys || []
  return LADDER.filter((q) => qs.includes(q))
}

const results = []
for (const song of songs) {
  const row = { song, attempts: [], result: null }
  const t0 = Date.now()
  let cands = []
  try { cands = await searchAll(song) } catch (e) { row.searchError = e.message }
  row.searchMs = Date.now() - t0
  const shortlist = []
  const seen = new Set()
  for (const c of cands) {
    if (c.score < 45) continue
    if (seen.has(c.source)) continue
    seen.add(c.source); shortlist.push(c)
  }
  row.matched = shortlist.map((c) => ({ src: c.source, id: c.id, score: c.score, title: c.title }))

  for (const cand of shortlist.slice(0, 3)) {
    for (const scriptName of (PREF[cand.source] || [])) {
      const h = hosts[scriptName]
      if (!h) continue
      const ladder = qualityOrder(h.info, cand.source).slice(0, 3)
      for (const q of ladder) {
        const t = Date.now()
        try {
          const info = {
            source: cand.source,
            name: song.title, songName: song.title, singer: song.artist, albumName: song.album,
            interval: `${Math.floor(song.durationSec / 60)}:${String(song.durationSec % 60).padStart(2, '0')}`,
            ...cand.extra, id: cand.id, songid: cand.id, songId: cand.id,
          }
          const url = await withTimeout(h.host.musicUrl(cand.source, q, info), TIMEOUT + 4000, cand.source + q)
          if (!url || !String(url).startsWith('http')) throw new Error('bad url')
          const v = await verify(url, song.durationSec)
          row.attempts.push({ script: scriptName, src: cand.source, q, ok: v.ok, kind: v.kind, kbps: v.kbps, ms: Date.now() - t, status: v.status })
          if (v.ok && (v.kind === 'flac' || v.kbps >= ACCEPT_KBPS)) {
            row.result = { script: scriptName, src: cand.source, q, kind: v.kind, kbps: v.kbps, ms: Date.now() - t }
            row.url = url
            break
          }
        } catch (e) {
          row.attempts.push({ script: scriptName, src: cand.source, q, ok: false, ms: Date.now() - t, err: String(e?.message || e).slice(0, 80) })
        }
      }
      if (row.result) break
    }
    if (row.result) break
  }
  row.totalMs = Date.now() - t0
  console.log(row.result
    ? `OK   ${String(row.result.kbps).padStart(4)}kbps ${row.result.kind.padEnd(7)} ${row.result.ms}ms  ${song.tag} | ${song.title.slice(0, 24)}`
    : `MISS ${song.tag} | ${song.title.slice(0, 30)} | cands=${shortlist.length}`)
  results.push(row)
}

fs.writeFileSync('e2e2.json', JSON.stringify(results, null, 2))
const ok = results.filter((r) => r.result)
const flac = ok.filter((r) => r.result.kind === 'flac')
console.log('\n=== P0 FINAL ===')
console.log('songs', results.length, 'resolved', ok.length, `${Math.round((ok.length / results.length) * 100)}%`, '| FLAC', flac.length)
const lats = ok.map((r) => r.result.ms).sort((a, b) => a - b)
console.log('resolve latency p50', lats[Math.floor(lats.length / 2)], 'p90', lats[Math.floor(lats.length * 0.9)], 'max', lats.at(-1))
const tot = ok.map((r) => r.totalMs).sort((a, b) => a - b)
console.log('end-to-end p50', tot[Math.floor(tot.length / 2)], 'p90', tot[Math.floor(tot.length * 0.9)])
for (const r of results) console.log(r.result ? `OK   ${r.result.kind}/${r.result.kbps} ${r.result.script} ${r.result.src}  ${r.song.title.slice(0, 34)}` : `MISS ${r.song.title.slice(0, 34)}`)
process.exit(0)
