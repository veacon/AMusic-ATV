// 平台搜索层：Apple 侧只有歌名/歌手，这里补上平台曲目 ID
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

const norm = (s) => (s || '')
  .toLowerCase()
  .replace(/\(.*?\)|\[.*?\]|（.*?）|【.*?】/g, ' ')
  .replace(/[\s\-_/·,，.。'’"“”!！?？:：&+]/g, '')
  .replace(/(feat|ft|remaster|remastered|deluxe|edition|version|live|explicit|single|radio edit|mono|stereo)/g, '')

const pickArtist = (s) => norm(String(s).split(/[,&、/]/)[0])

export async function search163(q, limit = 8) {
  const u = `https://music.163.com/api/search/get/web?s=${encodeURIComponent(q)}&type=1&limit=${limit}&offset=0`
  const r = await fetch(u, { headers: { 'User-Agent': UA, Referer: 'https://music.163.com/' } })
  const j = await r.json().catch(() => null)
  return (j?.result?.songs || []).map((s) => ({
    source: 'wy',
    id: String(s.id),
    extra: { songmid: String(s.id), songId: String(s.id) },
    title: s.name,
    artist: (s.artists || []).map((a) => a.name).join(' / '),
    album: s.album?.name || '',
    durationSec: Math.round((s.duration || 0) / 1000),
    fee: s.fee,
  }))
}

export async function searchQQ(q, limit = 8) {
  const u = `https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${encodeURIComponent(q)}&format=json&n=${limit}&p=1`
  const r = await fetch(u, { headers: { 'User-Agent': UA, Referer: 'https://y.qq.com/' } })
  const txt = (await r.text()).replace(/^callback\(|\)$/g, '')
  const j = JSON.parse(txt)
  return (j?.data?.song?.list || []).map((s) => ({
    source: 'tx',
    id: s.songmid,
    extra: { songmid: s.songmid, strMediaMid: s.media_mid, mid: s.songmid },
    title: s.songname,
    artist: (s.singer || []).map((a) => a.name).join(' / '),
    album: s.albumname || '',
    durationSec: s.interval || 0,
    pay: s.pay?.payplay,
  }))
}

export async function searchKugou(q, limit = 8) {
  const u = `https://songsearch.kugou.com/song_search_v2?keyword=${encodeURIComponent(q)}&page=1&pagesize=${limit}`
  const r = await fetch(u, { headers: { 'User-Agent': UA, Referer: 'https://www.kugou.com/' } })
  const j = await r.json().catch(() => null)
  return (j?.data?.lists || []).map((s) => ({
    source: 'kg',
    id: s.FileHash,
    extra: { hash: s.FileHash, album_id: s.AlbumID, album_audio_id: s.AlbumAudioID },
    title: s.SongName.replace(/<[^>]+>/g, ''),
    artist: (s.SingerName || '').replace(/<[^>]+>/g, ''),
    album: s.AlbumName || '',
    durationSec: s.Duration || 0,
    sq: !!s.SQFileHash,
    hq: !!s.HQFileHash,
  }))
}

export function score(cand, target) {
  const nt = norm(target.title)
  const nc = norm(cand.title)
  let s = 0
  if (nt === nc) s += 55
  else if (nc.includes(nt) || nt.includes(nc)) s += 38
  else {
    // 字符二元组重叠
    const bg = (x) => new Set(Array.from({ length: Math.max(0, x.length - 1) }, (_, i) => x.slice(i, i + 2)))
    const A = bg(nt); const B = bg(nc)
    let hit = 0; for (const g of A) if (B.has(g)) hit++
    s += Math.round(30 * (A.size ? hit / A.size : 0))
  }
  const at = pickArtist(target.artist)
  const ac = pickArtist(cand.artist)
  if (at && ac) {
    if (at === ac) s += 25
    else if (ac.includes(at) || at.includes(ac)) s += 18
    else if (norm(target.artist).includes(norm(cand.artist))) s += 12
  }
  if (target.album && cand.album && norm(target.album) === norm(cand.album)) s += 8
  if (target.durationSec && cand.durationSec) {
    const d = Math.abs(target.durationSec - cand.durationSec)
    if (d <= 2) s += 12
    else if (d <= 5) s += 8
    else if (d <= 10) s += 3
    else if (d > 30) s -= 10
  }
  // 版本惩罚：live/remix/piano/cover 与目标不一致
  const penaltyWords = ['live', 'remix', 'piano', 'cover', 'karaoke', 'instrumental', 'dj', 'acoustic']
  for (const w of penaltyWords) {
    const inT = nt.includes(w); const inC = nc.includes(w)
    if (inC && !inT) s -= 14
  }
  return s
}

export async function searchAll(song) {
  const q = `${song.title} ${pickArtist(song.artist)}`
  const out = []
  for (const fn of [search163, searchQQ, searchKugou]) {
    try {
      const r = await fn(q)
      out.push(...r.map((c) => ({ ...c, score: score(c, song) })))
    } catch (e) { /* 平台失败忽略 */ }
  }
  return out.sort((a, b) => b.score - a.score)
}

if (process.argv[2]) {
  const song = { title: process.argv[2], artist: process.argv[3] || '', durationSec: Number(process.argv[4] || 0) }
  const r = await searchAll(song)
  for (const c of r.slice(0, 8)) console.log(c.score, c.source, c.id, '|', c.title, '|', c.artist, '|', c.album, '|', c.durationSec)
}
