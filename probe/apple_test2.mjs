const UA='Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15'
const html=await (await fetch('https://music.apple.com/',{headers:{'User-Agent':UA}})).text()
const src=html.match(/src="([^"]*\/assets\/[^"]*\.js)"/)[1]
const js=await (await fetch('https://music.apple.com'+src,{headers:{'User-Agent':UA}})).text()
const bearer=js.match(/eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+/)[0]
for (const sf of ['cn','us']) {
  const u=new URL(`https://amp-api-edge.music.apple.com/v1/catalog/${sf}/search`)
  u.searchParams.set('term','Jay Chou'); u.searchParams.set('types','songs'); u.searchParams.set('limit','3')
  const r=await fetch(u,{headers:{'Authorization':'Bearer '+bearer,'Origin':'https://music.apple.com','User-Agent':UA}})
  const t=await r.text()
  console.log(sf,'status',r.status,'len',t.length)
  try{const j=JSON.parse(t); console.log(' keys',Object.keys(j)); console.log(' results',Object.keys(j.results||{})); console.log(' songs count',j.results?.songs?.data?.length)}catch(e){console.log(' not json',t.slice(0,200))}
}
