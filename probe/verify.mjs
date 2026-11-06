// 校验解析出的直链：是否可播、真实容器格式、实际码率
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

function sniff(buf) {
  if (buf.length < 12) return 'unknown'
  if (buf.slice(0, 3).toString() === 'ID3') return 'mp3'
  if (buf[0] === 0xff && (buf[1] & 0xe0) === 0xe0) return 'mp3'
  if (buf.slice(0, 4).toString() === 'fLaC') return 'flac'
  if (buf.slice(4, 8).toString() === 'ftyp') return 'mp4/aac'
  if (buf.slice(0, 4).toString() === 'RIFF') return 'wav'
  if (buf.slice(0, 4).toString() === 'OggS') return 'ogg'
  if (buf.slice(0, 5).toString() === '#EXTM') return 'hls'
  if (buf.slice(0, 4).toString() === 'MThd') return 'midi'
  return 'unknown:' + buf.slice(0, 8).toString('hex')
}

export async function verify(url, durationSec, extraHeaders = {}) {
  const t = Date.now()
  const ac = new AbortController()
  const timer = setTimeout(() => ac.abort(), 12000)
  try {
    const res = await fetch(url, {
      headers: { 'User-Agent': UA, Range: 'bytes=0-65535', ...extraHeaders },
      signal: ac.signal,
      redirect: 'follow',
    })
    const ct = (res.headers.get('content-type') || '').split(';')[0]
    const cr = res.headers.get('content-range') || ''
    const total = cr.includes('/') ? Number(cr.split('/')[1]) : Number(res.headers.get('content-length') || 0)

    let buf = Buffer.alloc(0)
    if (res.body) {
      const reader = res.body.getReader()
      const chunks = []
      let got = 0
      while (got < 65536) {
        const { done, value } = await reader.read()
        if (done) break
        chunks.push(Buffer.from(value))
        got += value.length
      }
      try { await reader.cancel() } catch {}
      buf = Buffer.concat(chunks)
    }
    clearTimeout(timer)
    const kind = sniff(buf)
    const kbps = total && durationSec ? Math.round((total * 8) / durationSec / 1000) : 0
    return {
      ok: res.status >= 200 && res.status < 400 && kind !== 'unknown',
      status: res.status,
      contentType: ct,
      totalBytes: total,
      kind,
      kbps,
      ms: Date.now() - t,
      finalHost: new URL(res.url).host,
      err: '',
    }
  } catch (e) {
    clearTimeout(timer)
    return { ok: false, status: 0, contentType: '', totalBytes: 0, kind: '', kbps: 0, ms: Date.now() - t, finalHost: '', err: String(e?.message || e).slice(0, 120) }
  }
}

if (process.argv[2]) {
  console.log(await verify(process.argv[2], Number(process.argv[3] || 240)))
}
