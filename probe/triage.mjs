import fs from 'node:fs'
import path from 'node:path'
import { LxHost, buildMusicInfo } from './lxhost.mjs'

process.on('unhandledRejection', () => {})
process.on('uncaughtException', (e) => { console.log('UNCAUGHT', e?.message) })

const dir = '../luoxue/音源'
const files = fs.readdirSync(dir).filter(f => f.endsWith('.js'))
  .sort((a, b) => parseInt(a.match(/\d+/)[0]) - parseInt(b.match(/\d+/)[0]))

const song = JSON.parse(process.env.SONG || '{"title":"晴天","artist":"周杰伦","album":"叶惠美","durationSec":269}')
const CALL_TIMEOUT = Number(process.env.CALL_TIMEOUT || 9000)
const INIT_TIMEOUT = Number(process.env.INIT_TIMEOUT || 6000)

const withTimeout = (p, ms, label) => Promise.race([
  p, new Promise((_, rej) => setTimeout(() => rej(new Error('timeout ' + ms + ' ' + (label || ''))), ms)),
])

const out = fs.createWriteStream('triage.jsonl', { flags: 'w' })

async function runOne(f) {
  const file = path.join(dir, f)
  const row = { file: f, name: '', sources: [], results: [], loadError: '' }
  let host
  try {
    host = new LxHost({ timeout: CALL_TIMEOUT })
    const info = await host.load(file, INIT_TIMEOUT)
    row.name = info.meta?.name || ''
    row.sources = Object.keys(info.sources).filter(s => s !== 'local')
    for (const src of row.sources) {
      const qs = info.sources[src].qualitys || []
      const q = qs.includes('320k') ? '320k' : (qs.find(x => x.endsWith('k')) || qs[0])
      if (!q) { row.results.push({ src, q, ok: false, ms: 0, err: 'no quality' }); continue }
      const t = Date.now()
      try {
        const url = await withTimeout(host.musicUrl(src, q, buildMusicInfo(song)), CALL_TIMEOUT + 4000, src)
        const ok = typeof url === 'string' && url.startsWith('http')
        row.results.push({ src, q, ok, ms: Date.now() - t, url: ok ? url : String(url).slice(0, 120) })
        console.log(`${ok ? 'OK  ' : 'BAD '} ${f} ${src} ${q} ${Date.now() - t}ms ${String(url).slice(0, 80)}`)
      } catch (e) {
        row.results.push({ src, q, ok: false, ms: Date.now() - t, err: String(e?.message || e).slice(0, 140) })
        console.log(`FAIL ${f} ${src} ${q} ${Date.now() - t}ms ${String(e?.message || e).slice(0, 90)}`)
      }
    }
  } catch (e) {
    row.loadError = String(e?.message || e).slice(0, 160)
    console.log('LOADFAIL ' + f + ' :: ' + row.loadError)
  }
  out.write(JSON.stringify(row) + '\n')
  return row
}

const rows = []
let idx = 0
async function worker() {
  while (idx < files.length) {
    const f = files[idx++]
    rows.push(await runOne(f))
  }
}
await Promise.all([worker(), worker(), worker()])
out.end()
console.log('\n=== SUMMARY ===')
for (const r of rows.sort((a, b) => parseInt(a.file.match(/\d+/)[0]) - parseInt(b.file.match(/\d+/)[0]))) {
  const ok = r.results.filter(x => x.ok).length
  console.log(`${ok}/${r.results.length}  ${r.file}  ${r.name}  [${r.sources.join(',')}]  ${r.loadError}`)
}
process.exit(0)
