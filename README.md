# AMusic-ATV（自用二次开发）

把 `lavande16/Apple-Music-For-Android-TV`（Android TV 端非官方 Apple Music 客户端）改造成
**「界面/元数据仍用 Apple，音频改由落雪（LX Music）音源提供」**。

- 分发范围：**纯自用**，不考虑上架与法务问题
- 目标形态：**无 PC**，音源脚本在端内执行
- 音质目标：FLAC / 母带优先，MP3 兜底
- 品牌：保留 Apple 名称与图标（自用）
- **免登录**：Apple 只当元数据库（浏览/搜索/专辑/歌单/榜单均匿名可用），
  播放走落雪，歌词走网易云/QQ。令牌（MUT）与试听片段都不再需要。

---

## 当前进度

| 阶段 | 状态 |
|---|---|
| 可行性分析（架构/接口/鉴权/版权/技术五维 + 模块处置清单） | ✅ 完成 → `落雪音源改造可行性分析.md` |
| P0 探针（15 个脚本实测、命中率、真实码率） | ✅ 完成 → `probe/P0-实测报告.md` |
| 垂直切片 PoC（QuickJS + 搜索匹配 + 直链播放） | ✅ 完成（模拟器实测命中 320kbps） |
| P1 接入主播放链路（播放/跨淡/过期重解析） | ✅ 完成 |
| P1.5 音源选型（44 条候选实测） | ✅ 完成 → `probe/音源跑测报告.md` |
| P1.6 脚本管理（导入/热重载/回滚） | ✅ 完成（设置页「落雪音源」卡片） |
| P1.7 免登录化（Apple 仅作元数据 / 去令牌 / 去试听 / 歌词换网易云·QQ） | ✅ 完成（模拟器实测通过） |
| P1.8 本地资料库（收藏 + QQ/网易云/酷狗歌单导入 + 隐藏登录入口） | ✅ 完成（模拟器实测通过） |
| P2 体验收尾（解析态提示、音质选择、歌单增量同步） | ⬜ 待做 |

### P0 关键结论（决定架构的三条）

1. 落雪自定义源协议**只有 `musicUrl`，没有搜索**。15 个脚本里只有 1 个能"只给歌名"就返回 URL，且**只出 128k MP3**。
2. **自己实现搜索层、把平台 ID 注入 `musicInfo` 后**，7 个脚本可出真 FLAC，最高 3534 kbps。
3. 完整链路 20 首真实曲目：**19/20 命中（95%），18/20 真 FLAC（90%）**，解析 p50 239 ms。

---

## 目录结构

```
app/                       Android 工程（从上游 android/ 复制而来，实际工作副本）
  app/src/main/java/com/applemusicktv/
    luoxue/                ★ 本次新增：端内落雪音源链路
      LxModels.kt            数据模型
      PlatformSearch.kt      163 / QQ / 酷狗 搜索（直连，无需登录）
      TrackMatcher.kt        繁简转换、艺人别名、时长兜底、打分
      StreamVerifier.kt      Range 探测真实容器/码率（识别"假无损"）
      LxScriptEngine.kt      QuickJS 宿主 + lx 协议 shim（request/timer/buffer/crypto）
      AudioResolver.kt       搜索→匹配→脚本级联→校验 编排
      LxPlayback.kt          单例：脚本生命周期 / 解析缓存 / 预取 / 重载
      LxScriptStore.kt       脚本仓库：assets 同步、导入、回滚（filesDir/luoxue）
      LxBench.kt             批量跑测器（--ez bench true）
      LxPocActivity.kt       独立入口（桌面图标 "LX PoC"）
    （其余为上游原有：ui/ data/ media/ widevine 等，P2 再清理）
  app/src/main/assets/luoxue/  随包脚本 kulao / hyw / fish / xinghai（4 条实测最优）
  app/src/test/java/com/applemusicktv/luoxue/TrackMatcherTest.kt

probe/                     P0 探针工具链（Node，可复跑）
  lxhost.mjs               LX 宿主模拟器（按官方协议实现 lx.request/utils/事件）
  search.mjs               三平台搜索 + 打分
  verify.mjs               直链校验（魔数 + 码率反算）
  triage.mjs / idtest.mjs / e2e2.mjs   三级实验脚本
  *.json / *.jsonl         实测结果明细

luoxue/音源/                用户提供的 15 个落雪脚本（原始文件）
key/                        上游关键源码摘录（分析用）
.workbuddy/memory/          过程记录（按日期）
*.md                        分析报告
```

---

## 构建与运行

环境：JDK 17（`C:\devtools\jdk-17.0.2`）、Android SDK（`C:\devtools\android-sdk`）。

```bash
cd app
JAVA_HOME=/c/devtools/jdk-17.0.2 ./gradlew :app:assembleDebug --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk

# PoC 入口
adb shell am start -n com.applemusic.lavande/com.applemusicktv.luoxue.LxPocActivity \
  --es auto "晴天|周杰伦|叶惠美|269"
adb logcat -s LxPoc:*
```

> 本机内存紧张，`gradle.properties` 已调到 `-Xmx1280m` + `kotlin.compiler.execution.strategy=in-process`。
> 内存充足的机器可改回 `-Xmx4g` 并去掉 in-process，编译快很多。
> 详见 `probe/PoC-运行说明.md`。

---

## 验证状态（如实记录）

- ✅ `assembleDebug` 通过，APK 30.6 MB（含 QuickJS arm64/v7a/x86/x86_64）
- ✅ P0 三组实验数据齐全（见 `probe/P0-实测报告.md`）
- ⚠️ `TrackMatcherTest` 4 条用例：首轮 3 过 1 败，失败项暴露了真实缺陷
  （同名不同曲被误判为命中）→ 已修 `TrackMatcher.score`（艺人完全不符且时长差 >5s 扣 20 分），
  **修复后未复跑**（本机内存不足，Gradle/Kotlin daemon 反复被杀）
- ⬜ 真机未验证：QuickJS 在电视上的启动/执行、ExoPlayer 播 FLAC

---

## 脚本管理（P1.6）

设置页（登录页提示的 `http://<设备IP>:8080`）新增「落雪音源」卡片：

- **开关**：临时回到 Apple 直链/代理，不卸载 APK。
- **脚本列表**：文件名 + `随包 / 已导入` + 是否加载成功 + 大小；导入的脚本可单独删除。
- **导入并热重载**：贴 URL 或粘全文，落盘到 `files/luoxue/` 后立即重建引擎，下一首生效。
- **重载脚本**：手改过 `files/luoxue/*.js`（adb push）后点一下即可，不必重启 App。
- **恢复随包**：清空本地脚本，回到 APK 内置的 4 条。

同步规则（`LxScriptStore`，manifest 记 `name -> assets 哈希`）：

| 本地状态 | 行为 |
|---|---|
| 文件不存在 | 从 assets 落盘 |
| 与 assets 一致，assets 已更新（装了新 APK） | **覆盖** ← 旧实现缺失，导致换脚本必须重装 |
| 内容与记录不符（用户导入/手改） | 保留，摘出托管名单，升级 APK 不覆盖 |
| assets 里已删掉的随包脚本 | 本地副本一并删除 |

## 免登录（P1.7）

目标是「不填令牌也能把现有功能用完」，结论是 **Apple 侧只有登录态相关的接口会 403**：

| 接口 | 无 MUT | 说明 |
|---|---|---|
| `amp-api.music.apple.com/v1/catalog/{sf}/search` | ✅ 200 | 搜索换到此域名（老域名 `api.music.apple.com` 无 MUT 直接 401；`amp-api-edge` 搜索返回空 results） |
| `amp-api-edge.music.apple.com/v1/catalog/{sf}/albums|playlists|charts` | ✅ 200 | 专辑/歌单/榜单/分类/Moods 全可用 |
| `amp-api.music.apple.com/v1/me/*` | ❌ 403 | 资料库、个人化推荐 |
| `catalog/{sf}/songs/{id}/lyrics` | ❌ 404 | 歌词改走网易云/QQ |

对应改动：

1. `DirectAppleApi.search` 换域名 → 不登录也能搜。
2. `isFullStream` 恒为 `true`：不再有「未登录→试听」降级，`previewUrl` 路径不再使用。
3. 落雪为**唯一音源**：未命中直接提示跳过，不回退 Apple DRM/代理直链（跨淡、恢复播放、错误重解析三处同步）。
4. 新增 `ThirdPartyLyrics`：`标题+艺人+时长` 在网易云、QQ 匹配（Dice 相似度打分，阈值 42），
   链路 Apple TTML（有 MUT 时）→ 网易云 → QQ → lrclib。
5. 登录页按钮文案改为「不登录，直接使用」。

实测（MuMu，未填 MUT）：首页出「心情 + 热门专辑排行」；搜索 `love` 出最佳结果；
《范特西》整盘可播（爱在西元前 tx 320kbps，8.5s）；歌词为网易云同步词（`netease hit score=100 lines=48`）。

## 本地资料库（P1.8）

Apple 的 `/v1/me/library/*` 在未登录时 403，所以资料库整块换成**本地存储**，
并顺势支持把外部歌单导入进来。

- **存储**：`data/local/LocalLibraryStore`，单个 `filesDir/local_library.json`（Moshi 全量读写，
  写入走单线程执行器 + 临时文件 rename）。没上 Room —— 几百首的量级不值得引入 ksp 编译负担。
- **数据**：收藏曲目（`favorites`）+ 本地歌单（`LocalPlaylist`：名称/封面/来源/源链接/曲目）。
- **四个分类**：歌曲 = 收藏；播放列表 = 自建 + 导入；专辑/艺人 = 由**带 Apple id 的收藏**聚合
  （导入曲目没有 albumId，点开也没有详情页，所以不参与聚合）。
- **导入**（`data/local/PlaylistImporter`，参考 GoMusic / music-api-server / Superheroff/musicapi 的做法）：

| 平台 | 接口 | 实测 |
|---|---|---|
| QQ 音乐 | `c.y.qq.com/v8/fcg-bin/fcg_v8_playlist_cp.fcg?id=` | 30/30 首，字段 `name/singer/album/interval` |
| 网易云 | `music.163.com/api/v6/playlist/detail?id=&n=1000` | 200/200 首，含封面与 `dt` |
| 酷狗 | `mobilecdn.kugou.com/api/v3/special/song?specialid=` | 22/22 首；detail 接口常返回空，歌单名改从分享页 `<title>` 取 |
| 文本 | 每行 `歌名 - 歌手[- 专辑]` | 用于从别处粘列表 |

- **入口**：资料库「播放列表」第一格是「＋ 导入歌单」；设置页（网页端 8080）也有一个导入表单，
  在电脑/手机上粘长链接比遥控器输入舒服得多。接口：`POST /playlist-import`（`url` 或 `text`）。
- **播放**：导入的 Song 用 `imp-<平台>-<id>` 做本地 id，播放时交给落雪按**标题+艺人+时长**搜索，
  不依赖 Apple 曲库；歌词照旧走网易云/QQ。
- **收藏**：播放页星标、长按菜单「收藏到资料库」、`AddToDialog` 的 Library 行，全部落到本地。

实测（MuMu，未登录）：三个平台全部导入成功；《千千阙歌》tx 320kbps 13.2s 命中 + 网易云歌词 67 行；
文本导入的 Yesterday 修复后 wy 5.2s 命中；收藏后重启仍在。

## 下一步（P2）

1. 播放器内「解析中 / 已换源」提示：现在解析要 3–20s，界面只有转圈，用户不知道在换源。
2. 音质优先设置：`AudioResolver.LADDER` 现在写死 `320k` 起，应暴露给设置页（FLAC 优先 / 省流 128k）。
3. 清理 DRM 分支（`media/widevine/*`、MV、电台）：主链路已不走 Apple 解密，留着只是编译负担。
4. 歌单增量同步：现在「重新导入」是整盘覆盖，可以按 `sourceUrl` 做 diff 并保留本地排序。
5. 导入歌曲补元数据：文本导入没有时长/封面，可用 Apple catalog 搜索回填。

## 已知问题

- **免登录模式下不可用**：个人化推荐（首页改出榜单）、电台与 MV（Widevine 需令牌）。
  这些是 Apple 服务端限制，只有登录后才恢复；资料库已改为本地，不受影响。
- 酷我(kw)、咪咕(mg) 未覆盖（酷我搜索需 csrf/cookie，咪咕无公开搜索）。
- tx（QQ 音乐）直链多为 28–38kbps 试听片段，被 `StreamVerifier` 的 200kbps 门槛挡掉，实际覆盖靠 wy/kg。
- 码率未知时按体积兜底（≥1.8MB 视为完整曲目）：脚本不返 bitrate 的情况原来会被 0kbps 误杀。
- 酷狗歌单无封面（detail 接口不给 `imgurl`），卡片显示音符占位。
- 文本导入的歌曲没有时长，落雪只能按标题+艺人匹配，命中率低于平台导入。
- 直链 TTL ≈ 21 分钟，暂停久后 seek 会 403 → 已实现"报错重解析"（每首一次）。
- 跨语言曲名（Apple `Wayfarer` vs 平台《山海》）只能靠"艺人+时长"兜底。
- 部分脚本 resolve 后残留定时器，`evaluate` 等不到空闲 → 超时后 `interruptEvaluation()` 兜底。
- 构建环境：上游 `gradle/gradle-daemon-jvm.properties` 要求 JDK 21，本机只有 17，该文件已移除（JDK 17 可正常构建）。换机器需补 `app/local.properties` 的 `sdk.dir`。

---

## 上游

`https://github.com/lavande16/Apple-Music-For-Android-TV`（GPL-3.0）。
本仓库未包含上游的 `server/`（Bun 代理）与原始文档；需要时重新 clone 即可。
