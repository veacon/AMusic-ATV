// 端到端 P0：Apple 元数据 → 平台搜索 → 匹配 → 落雪脚本 musicUrl → 直链校验
import fs from 'node:fs'
import path from 'node:path'
import { LxHost } from './lxhost.mjs'
import { searchAll } from './search.mjs'
import { verify } from './verify.mjs'

process.on('unhandledRejection', () => {})
process.on('uncaughtException', (e) => console.log('UNCAUGHT', e?.message))

const songs = JSON.parse(fs.readFileSync(process.env.SONGS || 'songs.json', 'utf8'))
const scriptFiles = (process.env.SCRIPTS || '../luoxue/音源/洛雪音源6.js').split(',')
const LADDER = (process.env.LADDER || 'master,flac24bit,flac,320k,128k').split(',')
const TIMEOUT = Number(process.env.CALL_TIMEOUT || 9000)
const LIMIT = Number(process.env.LIMIT || songs.length)

const withTimeout = (p, ms, label) => Promise.race([
  p, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout ' + ms + ' ' + (label || ''))), ms)),
])

const hosts = []
for (const f of scriptFiles) {
  try {
    const h = new LxHost({ timeout: TIMEOUT })
    const info = await h.load(f, 8000)
    hosts.push({ file: f, host: h, info })
    console.log('HOST', path.basename(f), info.meta?.name, 'sources=', Object.keys(info.sources).join(','))
  } catch (e) {
    console.log('HOSTFAIL', f, e.message)
  }
}

function musicInfo(cand, song) {
  return {
    source: cand.source,
    // Apple 侧信息
    name: song.title,
    songName: song.title,
    singer: song.artist,
    albumName: song.album,
    interval: `${Math.floor(song.durationSec / 60)}:${String(song.durationSec % 60).padStart(2, '0')}`,
    // 搜索层补上的平台 ID
    ...cand.extra,
    id: cand.id,
    songid: cand.id,
  }
}

const results = []
for (const song of songs.slice(0, LIMIT)) {
  const row = { song: `${song.title} — ${song.artist}`, tag: song.tag, durationSec: song.durationSec, attempts: [], result: null }
  const t0 = Date.now()
  let cands = []
  try { cands = await searchAll(song) } catch (e) { row.searchError = e.message }
  row.searchMs = Date.now() - t0
  const shortlist = cands.filter((c) => c.score >= 40).slice(0, 4)
  row.candidates = shortlist.map((c) => ({ s: c.source, score: c.score, t: c.title, a: c.artist }))

  outer:
  for (const h of hosts) {
    for (const cand of shortlist) {
      const qs = h.info.sources[cand.source]?.qualitys || []
      const ladder = LADDER.filter((q) => qs.includes(q))
      if (!ladder.length) continue
      for (const q of ladder.slice(0, 3)) {
        const t = Date.now()
        try {
          const url = await withTimeout(h.host.musicUrl(cand.source, q, musicInfo(cand, song)), TIMEOUT + 3000, cand.source)
          if (!url || !String(url).startsWith('http')) throw new Error('bad url')
          const v = await verify(url, song.durationSec)
          const attempt = { script: path.basename(h.file), src: cand.source, q, ms: Date.now() - t, ok: v.ok, kind: v.kind, kbps: v.kbps, status: v.status, ct: v.contentType, host: v.finalHost, err: v.err }
          row.attempts.push(attempt)
          console.log(`${v.ok ? 'OK ' : 'BAD'} ${song.tag} | ${song.title.slice(0, 26)} | ${cand.source} ${q} | ${v.kind} ${v.kbps}kbps | ${Date.now() - t}ms`)
          if (v.ok) { row.result = attempt; row.url = url; break outer }
        } catch (e) {
          row.attempts.push({ script: path.basename(h.file), src: cand.source, q, ms: Date.now() - t, ok: false, err: String(e?.message || e).slice(0, 100) })
        }
      }
    }
  }
  if (!row.result) console.log(`MISS ${song.tag} | ${song.title.slice(0, 30)} | cands=${shortlist.length}`)
  results.push(row)
}

fs.writeFileSync('e2e.json', JSON.stringify(results, null, 2))

const ok = results.filter((r) => r.result)
const flac = ok.filter((r) => r.result.kind === 'flac')
const hi = ok.filter((r) => r.result.kbps >= 700)
console.log('\n=== P0 SUMMARY ===')
console.log('songs', results.length, 'resolved', ok.length, `(${Math.round(ok.length / results.length * 100)}%)`)
console.log('flac container', flac.length, '| >=700kbps', hi.length)
const byKind = {}
for (const r of ok) byKind[r.result.kind] = (byKind[r.result.kind] || 0) + 1
console.log('kinds', byKind)
const lat = ok.map((r) => r.result.ms).sort((a, b) => a - b)
console.log('latency ms p50', lat[Math.floor(lat.length * 0.5)], 'p90', lat[Math.floor(lat.length * 0.9)], 'max', lat[lat.length - 1])
for (const r of results) console.log(r.result ? `OK   ${r.result.kind}/${r.result.kbps}kbps ${r.result.ms}ms  ${r.song}` : `MISS ${r.song}`)
process.exit(0)
