# AMusic-ATV（自用二次开发）

把 `lavande16/Apple-Music-For-Android-TV`（Android TV 端非官方 Apple Music 客户端）改造成
**「界面/元数据仍用 Apple，音频改由落雪（LX Music）音源提供」**。

- 分发范围：**纯自用**，不考虑上架与法务问题
- 目标形态：**无 PC**，音源脚本在端内执行
- 音质目标：FLAC / 母带优先，MP3 兜底
- 品牌：保留 Apple 名称与图标（自用）

---

## 当前进度

| 阶段 | 状态 |
|---|---|
| 可行性分析（架构/接口/鉴权/版权/技术五维 + 模块处置清单） | ✅ 完成 → `落雪音源改造可行性分析.md` |
| P0 探针（15 个脚本实测、命中率、真实码率） | ✅ 完成 → `probe/P0-实测报告.md` |
| 垂直切片 PoC（QuickJS + 搜索匹配 + 直链播放） | ✅ 代码完成，**APK 编译通过**，真机待验证 |
| P1 接入主播放链路 | ⬜ 未开始 |

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
      LxPocActivity.kt       独立入口（桌面图标 "LX PoC"）
    （其余为上游原有：ui/ data/ media/ widevine 等，P1 再清理）
  app/src/main/assets/luoxue/  随包脚本 S14 / S3 / S13
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

## 下一步（P1）

1. `MusicRepository.streamUrl()` → `AudioResolver.resolve()`（同步转异步，6 处调用点）
2. `PlayerViewModel.playQueueItem` 增加：解析中 / 换源 / 直链过期重连
3. `prefetchSong` 改为预解析（跨淡需提前 5s 拿到直链）
4. 删除 DRM 三条分支与页面（`media/widevine/*`、MV、电台、解密相关测试）
5. 设置页改为「音源管理 / 音质优先 / 命中率诊断」

## 已知问题

- 酷我(kw)、咪咕(mg) 未覆盖（酷我搜索需 csrf/cookie，咪咕无公开搜索）
- 直链 TTL ≈ 21 分钟，暂停久后 seek 会 403，需"报错重解析"
- 跨语言曲名（Apple `Wayfarer` vs 平台《山海》）只能靠"艺人+时长"兜底
- 脚本随时失效（15 个里 6 个已死），必须支持在线导入与热更新

---

## 上游

`https://github.com/lavande16/Apple-Music-For-Android-TV`（GPL-3.0）。
本仓库未包含上游的 `server/`（Bun 代理）与原始文档；需要时重新 clone 即可。
