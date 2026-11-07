/*!
 * @name 星海GD精简源 (qdy-mini)
 * @description 基于 qdy 的洛雪自定义源精简版，仅聚合 GD 音乐台(星海)主/备双链，去除需配置 key 的第三方后端
 * @version 1.0.0
 * @author workbuddy (derived from qdy by lerd)
 * @homepage https://github.com/pdone/lx-music-source
 */

// 洛雪自定义源运行时提供的全局对象
const { EVENT_NAMES, request, on, send } = globalThis.lx;

// ===== 配置 =====
const XINGHAI_MAIN_API = "https://music-api.gdstudio.xyz/api.php";   // 星海主：GD 音乐台
const XINGHAI_BACKUP_API = "https://music-dl.sayqz.com/api/";        // 星海备：免费回退

// 洛雪平台 -> 星海 source（ID 空间一致，可直接传）
const PLATFORM_TO_XINGHAI = { wy: "netease", tx: "tencent", kw: "kuwo", kg: "kugou", mg: "migu" };
// 星海备链平台映射（仅 3 个平台支持）
const PLATFORM_TO_XINGHAI_BACKUP = { wy: "netease", tx: "qq", kw: "kuwo" };

// 洛雪音质 -> 星海 br（数字码率）
const QUALITY_TO_BR = { "128k": "128", "192k": "192", "320k": "320", flac: "740", flac24bit: "999" };

const PLATFORM_NAMES = { wy: "网易云音乐", tx: "QQ音乐", kw: "酷我音乐", kg: "酷狗音乐", mg: "咪咕音乐" };
const PLATFORM_QUALITIES = {
  wy: ["128k", "192k", "320k", "flac", "flac24bit"],
  tx: ["128k", "192k", "320k", "flac", "flac24bit"],
  kw: ["128k", "192k", "320k", "flac", "flac24bit"],
  kg: ["128k", "192k", "320k", "flac", "flac24bit"],
  mg: ["128k", "192k", "320k", "flac", "flac24bit"]
};
// 音质降级优先级（请求的音质不可用时回退到最接近的）
const QUALITY_PRIORITY = ["flac24bit", "flac", "320k", "192k", "128k"];

// ===== HTTP 封装 =====
// 注意：洛雪 lx.request 回调为 (err, res)，res.body 可能是已解析对象，也可能是原始字符串
function httpRequest(url, options = { method: "GET" }) {
  return new Promise((resolve, reject) => {
    request(url, { timeout: 8000, ...options }, (err, res) => {
      if (err) return reject(new Error("请求错误: " + (err && err.message)));
      let body = res && res.body;
      if (typeof body === "string") {
        const t = body.trim();
        if (t.startsWith("{") || t.startsWith("[") || t.startsWith('"')) {
          try { body = JSON.parse(t); } catch (e) { /* 保持原始字符串 */ }
        }
      }
      resolve({ statusCode: (res && res.statusCode) || 0, headers: (res && res.headers) || {}, body });
    });
  });
}

// ===== 工具函数 =====
// 兼容各平台歌曲 ID 字段（洛雪统一经 songmid 传入，但不同平台可能落在不同字段）
function getHashOrMid(songInfo) {
  const v = songInfo && (songInfo.id || songInfo.songmid || songInfo.songId ||
    songInfo.hash || songInfo.rid || songInfo.mid ||
    songInfo.strMediaMid || songInfo.mediaId);
  return v == null ? "" : String(v);
}

function selectQuality(requested, available) {
  if (!available || !available.length) return requested;
  if (available.includes(requested)) return requested;
  for (const q of QUALITY_PRIORITY) if (available.includes(q)) return q;
  return available[0];
}

function validateUrl(url, name) {
  if (typeof url !== "string" || !/^https?:\/\//.test(url)) {
    throw new Error(name + ": 返回了无效 URL");
  }
  return url;
}

// ===== 取链实现 =====
async function xinghaiMainGetUrl(platform, songId, quality) {
  const source = PLATFORM_TO_XINGHAI[platform];
  if (!source) throw new Error("星海主不支持该平台");
  const br = QUALITY_TO_BR[quality] || "320";
  const url = XINGHAI_MAIN_API +
    "?types=url&source=" + encodeURIComponent(source) +
    "&id=" + encodeURIComponent(songId) +
    "&br=" + encodeURIComponent(br);
  const res = await httpRequest(url, { method: "GET", headers: { "User-Agent": "LX-Music-Mobile" } });
  const body = res.body;
  if (!body || typeof body !== "object" || !body.url) {
    throw new Error((body && body.message) || "星海主未返回可用URL");
  }
  return validateUrl(body.url, "星海主");
}

async function xinghaiBackupGetUrl(platform, songId, quality) {
  const source = PLATFORM_TO_XINGHAI_BACKUP[platform];
  if (!source) throw new Error("星海备不支持该平台");
  const url = XINGHAI_BACKUP_API +
    "?source=" + encodeURIComponent(source) +
    "&id=" + encodeURIComponent(songId) +
    "&type=url&br=" + encodeURIComponent(quality);
  const res = await httpRequest(url, { method: "GET", headers: { "User-Agent": "LX-Music-Mobile" } });
  const body = res.body;
  if (!body || typeof body !== "object" || !body.url) {
    throw new Error((body && body.message) || "星海备未返回可用URL");
  }
  return validateUrl(body.url, "星海备");
}

// ===== 回退链：主链失败自动试备链 =====
async function getUrlWithFallback(platform, songInfo, quality) {
  const songId = getHashOrMid(songInfo);
  if (!songId) throw new Error("缺少歌曲ID");
  const selected = selectQuality(quality || "128k", PLATFORM_QUALITIES[platform]);
  const errors = [];
  try {
    return await xinghaiMainGetUrl(platform, songId, selected);
  } catch (e) { errors.push("星海主: " + (e && e.message)); }
  try {
    return await xinghaiBackupGetUrl(platform, songId, selected);
  } catch (e) { errors.push("星海备: " + (e && e.message)); }
  throw new Error("所有源均失败: " + errors.join("; "));
}

// ===== 事件入口（洛雪回调这里）=====
on(EVENT_NAMES.request, ({ action, source, info }) => {
  if (action !== "musicUrl") return Promise.reject(new Error("action not support"));
  if (!info || !info.musicInfo) return Promise.reject(new Error("请求参数不完整"));
  return getUrlWithFallback(source, info.musicInfo, info.type || "128k");
});

// ===== 向洛雪注册音源 =====
const sourceConfig = {};
Object.keys(PLATFORM_TO_XINGHAI).forEach(platform => {
  sourceConfig[platform] = {
    name: PLATFORM_NAMES[platform],
    type: "music",
    actions: ["musicUrl"],
    qualitys: PLATFORM_QUALITIES[platform]
  };
});

send(EVENT_NAMES.inited, {
  openDevTools: false,
  sources: sourceConfig
});
