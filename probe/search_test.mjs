const UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
async function t(name, url, headers={}) {
  try {
    const r = await fetch(url, { headers: { 'User-Agent': UA, ...headers } })
    const txt = await r.text()
    let j=null; try{ j=JSON.parse(txt) }catch{}
    console.log(`\n### ${name} status=${r.status} len=${txt.length} json=${!!j}`)
    if (j) console.log(JSON.stringify(j).slice(0, 500))
    else console.log(txt.slice(0, 200).replace(/\s+/g,' '))
  } catch(e) { console.log(`\n### ${name} ERR ${e.message}`) }
}
await t('163-web-search','https://music.163.com/api/search/get/web?s=%E6%99%B4%E5%A4%A9&type=1&limit=3&offset=0',{Referer:'https://music.163.com/'})
await t('qq-search','https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=%E6%99%B4%E5%A4%A9&format=json&n=3&p=1',{Referer:'https://y.qq.com/'})
await t('kugou-search','https://songsearch.kugou.com/song_search_v2?keyword=%E6%99%B4%E5%A4%A9&page=1&pagesize=3',{Referer:'https://www.kugou.com/'})
await t('kuwo-search','https://www.kuwo.cn/api/www/search/searchMusicBykeyWord?key=%E6%99%B4%E5%A4%A9&rn=3&pn=1&httpsStatus=1',{Referer:'https://www.kuwo.cn/'})
