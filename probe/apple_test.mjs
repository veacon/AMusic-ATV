const UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15'
const html=await (await fetch('https://music.apple.com/',{headers:{'User-Agent':UA}})).text()
const src=html.match(/src="([^"]*\/assets\/[^"]*\.js)"/)[1]
const js=await (await fetch('https://music.apple.com'+src,{headers:{'User-Agent':UA}})).text()
const bearer=js.match(/eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+/)[0]
const u=new URL('https://amp-api-edge.music.apple.com/v1/catalog/cn/search')
u.searchParams.set('term','周杰伦 晴天'); u.searchParams.set('types','songs'); u.searchParams.set('limit','3')
const r=await fetch(u,{headers:{'Authorization':'Bearer '+bearer,'Origin':'https://music.apple.com','Referer':'https://music.apple.com/','User-Agent':UA}})
console.log('status',r.status)
const j=await r.json().catch(e=>null)
const songs=j?.results?.songs?.data||[]
for(const s of songs) console.log(s.id, '|', s.attributes.name, '|', s.attributes.artistName, '|', s.attributes.albumName, '|', s.attributes.durationInMillis, '| isrc', s.attributes.isrc)
