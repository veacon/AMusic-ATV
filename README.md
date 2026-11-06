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
| 垂直切片 PoC（QuickJS + 搜索匹配 + 直链播放） | ✅ 完成（模拟器实测命中 320kbps） |
| P1 接入主播放链路（播放/跨淡/过期重解析） | ✅ 完成 |
| P1.5 音源选型（44 条候选实测） | ✅ 完成 → `probe/音源跑测报告.md` |
| P1.6 脚本管理（导入/热重载/回滚） | ✅ 完成（设置页「落雪音源」卡片） |
| P2 体验收尾（解析态提示、音质选择、登录后主链路验证） | ⬜ 待做 |

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

## 下一步（P2）

1. **登录后主链路验证**：目前端到端验证都走 `LxPocActivity`，真机上「浏览→点歌→播放」还需一次带 token 的实测。
2. 播放器内「解析中 / 已换源」提示：现在解析要 3–20s，界面只有转圈，用户不知道在换源。
3. 音质优先设置：`AudioResolver.LADDER` 现在写死 `320k` 起，应暴露给设置页（FLAC 优先 / 省流 128k）。
4. 清理 DRM 三条分支（`media/widevine/*`、MV、电台）：主链路已不走 Apple 解密，留着只是编译负担。
5. 首曲提速：脚本预热已在 `Application.startMainServices()` 触发，但 PoC 入口没有；可把预热下沉到 Application.onCreate。

## 已知问题

- 酷我(kw)、咪咕(mg) 未覆盖（酷我搜索需 csrf/cookie，咪咕无公开搜索）。
- tx（QQ 音乐）直链多为 28–38kbps 试听片段，被 `StreamVerifier` 的 200kbps 门槛挡掉，实际覆盖靠 wy/kg。
- 直链 TTL ≈ 21 分钟，暂停久后 seek 会 403 → 已实现"报错重解析"（每首一次）。
- 跨语言曲名（Apple `Wayfarer` vs 平台《山海》）只能靠"艺人+时长"兜底。
- 部分脚本 resolve 后残留定时器，`evaluate` 等不到空闲 → 超时后 `interruptEvaluation()` 兜底。
- 构建环境：上游 `gradle/gradle-daemon-jvm.properties` 要求 JDK 21，本机只有 17，该文件已移除（JDK 17 可正常构建）。换机器需补 `app/local.properties` 的 `sdk.dir`。

---

## 上游

`https://github.com/lavande16/Apple-Music-For-Android-TV`（GPL-3.0）。
本仓库未包含上游的 `server/`（Bun 代理）与原始文档；需要时重新 clone 即可。
