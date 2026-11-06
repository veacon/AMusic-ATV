// 最小 LX Music 宿主模拟器：按官方自定义源协议(fx)提供 globalThis.lx
// 用途：在 Node 中加载音源脚本，调用 musicUrl / lyric / pic，验证可用性与耗时
import fs from 'node:fs'
import vm from 'node:vm'
import crypto from 'node:crypto'
import { Buffer } from 'node:buffer'

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

function parseMeta(code) {
  const head = code.slice(0, 4000)
  const g = (k) => (head.match(new RegExp('@' + k + '\\s+(.+)')) || [])[1]?.trim()
  return { name: g('name'), description: g('description'), version: g('version'), author: g('author'), homepage: g('homepage') }
}

function safeJson(t) { try { return JSON.parse(t) } catch { return t } }

export class LxHost {
  constructor({ timeout = 12000, verbose = false } = {}) {
    this.sources = {}
    this.meta = null
    this.handler = null
    this.logs = []
    this.timeout = timeout
    this.verbose = verbose
    this.inited = false
    this._initResolve = null
    this._initPromise = new Promise((r) => { this._initResolve = r })
  }

  _ctx(info) {
    const self = this
    const log = (...a) => { const s = a.map(x => typeof x === 'string' ? x : JSON.stringify(x)).join(' '); self.logs.push(s); if (self.verbose) console.log('   [script]', s) }

    const request = (url, options = {}, callback = () => {}) => {
      const ac = new AbortController()
      const ms = options.timeout || self.timeout
      const timer = setTimeout(() => ac.abort(), ms)
      const headers = { 'User-Agent': UA, ...(options.headers || {}) }
      let body = options.body
      if (options.form) {
        body = new URLSearchParams(options.form).toString()
        headers['Content-Type'] = 'application/x-www-form-urlencoded'
      } else if (body && typeof body === 'object' && !(body instanceof Uint8Array)) {
        body = JSON.stringify(body)
        if (!headers['Content-Type']) headers['Content-Type'] = 'application/json'
      }
      const method = (options.method || 'GET').toUpperCase()
      fetch(url, { method, headers, body: method === 'GET' ? undefined : body, signal: ac.signal, redirect: 'follow' })
        .then(async (res) => {
          const buf = Buffer.from(await res.arrayBuffer())
          const ct = res.headers.get('content-type') || ''
          const text = buf.toString('utf8')
          callback(null, {
            statusCode: res.status,
            statusMessage: res.statusText,
            headers: Object.fromEntries(res.headers.entries()),
            body: ct.includes('json') ? safeJson(text) : text,
            raw: buf,
            bytes: buf.length,
          })
        })
        .catch((err) => callback(err, null))
        .finally(() => clearTimeout(timer))
      return () => ac.abort()
    }

    const lx = {
      version: '2.0.0',
      env: 'desktop',
      currentScriptInfo: { ...info, rawScript: '' },
      EVENT_NAMES: { inited: 'inited', request: 'request', updateAlert: 'updateAlert' },
      request,
      on: (name, h) => { if (name === 'request') self.handler = h },
      send: (name, data) => {
        if (name === 'inited') {
          self.inited = true
          self.sources = data?.sources || {}
          self.meta = data?.meta || self.meta
          if (self._initResolve) { self._initResolve(); self._initResolve = null }
        }
      },
      utils: {
        buffer: {
          from: (data, enc) => Buffer.from(data, enc === 'utf-8' ? 'utf8' : enc),
          bufToString: (buf, enc) => Buffer.from(buf).toString(enc === 'utf-8' ? 'utf8' : enc),
        },
        crypto: {
          md5: (s) => crypto.createHash('md5').update(s).digest('hex'),
          randomBytes: (n) => crypto.randomBytes(n),
          aesEncrypt: (data, mode, key, iv) => {
            const k = Buffer.from(key)
            const algo = `aes-${k.length * 8}-${String(mode).toLowerCase().replace('-', '')}`
            const c = crypto.createCipheriv(algo, k, iv ? Buffer.from(iv) : null)
            return Buffer.concat([c.update(Buffer.from(data)), c.final()])
          },
          rsaEncrypt: (data) => Buffer.from(data),
        },
      },
    }

    return {
      lx,
      console: { log, info: log, warn: log, error: log, debug: log },
      Buffer,
      setTimeout, clearTimeout, setInterval, clearInterval,
      TextEncoder, TextDecoder, URL, URLSearchParams, AbortController,
      fetch, Promise, JSON, Math, Date, RegExp, Object, Array, String, Number, Boolean, Error, Uint8Array, ArrayBuffer,
    }
  }

  async load(file, initTimeout = 8000) {
    const code = fs.readFileSync(file, 'utf8')
    const info = parseMeta(code)
    const ctx = this._ctx(info)
    ctx.globalThis = ctx
    this.ctx = vm.createContext(ctx)
    try {
      vm.runInContext(code, this.ctx, { filename: file, timeout: 60000 })
    } catch (e) {
      // 有些脚本在顶层就抛错，仍可能已经注册；记录后继续等待 inited
      this.logs.push('[load error] ' + (e?.message || e))
    }
    if (!this.inited) {
      await Promise.race([this._initPromise, new Promise((r) => setTimeout(r, initTimeout))])
    }
    if (!this.inited) throw new Error('script did not send inited event within ' + initTimeout + 'ms' + (this.logs.length ? ' | ' + this.logs.slice(-2).join(' / ').slice(0, 160) : ''))
    return { meta: this.meta || info, sources: this.sources }
  }

  async musicUrl(source, quality, musicInfo) {
    if (!this.handler) throw new Error('no request handler registered')
    return await this.handler({ source, action: 'musicUrl', info: { type: quality, musicInfo } })
  }

  async lyric(source, musicInfo) {
    if (!this.handler) throw new Error('no request handler registered')
    return await this.handler({ source, action: 'lyric', info: { musicInfo } })
  }
}

export function buildMusicInfo(song) {
  // 只给 Apple 侧能拿到的字段：无平台 ID。故意包含 Apple 常见后缀以压测匹配。
  return {
    source: song.source || 'wy',
    name: song.title,
    songName: song.title,
    singer: song.artist,
    albumName: song.album,
    interval: song.durationSec ? `${Math.floor(song.durationSec / 60)}:${String(song.durationSec % 60).padStart(2, '0')}` : undefined,
    _types: {},
  }
}
