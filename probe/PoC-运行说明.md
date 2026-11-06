# PoC 运行说明：端内落雪音源直连

> 一个独立于主播放链路的垂直切片，用来在真机上验证四件事：
> ① QuickJS 能否在电视上跑通社区脚本；② 自建搜索+匹配的命中率；③ 脚本级联能否稳定拿到 FLAC；④ ExoPlayer 能否直接播解析出的直链。

---

## 1. 新增了什么

```
app/app/src/main/java/com/applemusicktv/luoxue/
├── LxModels.kt          Candidate / AudioStream / ResolveAttempt / ResolveResult
├── PlatformSearch.kt    163 / QQ / 酷狗 搜索（OkHttp 直连，无需登录）
├── TrackMatcher.kt      繁简转换、艺人别名、时长兜底、打分
├── StreamVerifier.kt    Range 64KB 探测真实容器与码率（识别"假无损"）
├── LxScriptEngine.kt    QuickJS 宿主 + lx 协议 shim（request/timers/utils/crypto）
├── AudioResolver.kt     搜索→匹配→脚本级联→校验 的编排
└── LxPocActivity.kt     独立入口：四个样本曲目，一键解析并播放
app/app/src/main/assets/luoxue/   随包附带 3 个脚本（S14 / S3 / S13）
```

主 App 的 `PlayerViewModel` / `MusicRepository` **一行没改**，PoC 完全走新链路。

---

## 2. 构建

```bash
cd app
JAVA_HOME=/c/devtools/jdk-17.0.2 ./gradlew :app:assembleDebug --no-daemon
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 本机内存紧张时已把 `gradle.properties` 调到 `-Xmx1280m` + `in-process` 编译；
> 如果你的构建机内存充足，可以改回 `-Xmx4g` 并去掉 `kotlin.compiler.execution.strategy`，
> 编译会快不少。

---

## 3. 运行

电视/盒子开启 ADB 后：

```bash
adb connect <TV_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 方式一：桌面图标「LX PoC」打开，遥控器点四个样本之一
# 方式二：命令行直触发，日志更干净（推荐）
adb shell am start -n com.applemusic.lavande/com.applemusicktv.luoxue.LxPocActivity \
  --es auto "晴天|周杰伦|叶惠美|269"

adb logcat -s LxPoc:*
```

`auto` 参数的格式：`标题|艺人|专辑|时长秒`，例如：

```bash
adb shell am start -n com.applemusic.lavande/com.applemusicktv.luoxue.LxPocActivity --es auto "Anti-Hero|Taylor Swift|Midnights|201"
```

---

## 4. 预期日志与判定

正常一次成功解析：

```
I LxPoc: ENGINE 洛雪音源14.js ... sources=[wy, tx, kw, kg, mg]
I LxPoc: ENGINE 洛雪音源3.js  ... sources=[tx, wy, kw, kg, mg]
I LxPoc: READY engines=3
I LxPoc: PLAY Anti-Hero / Taylor Swift
I LxPoc: DONE attempts=3 total=1200ms stream=AudioStream(url=..., kind=flac, kbps=5193, ...)
```

判定标准：

| 观察点 | 通过条件 |
|---|---|
| `READY engines=N` | N ≥ 1（脚本能在 QuickJS 里跑起来） |
| `DONE ... stream=AudioStream` | 非 null，且 `kind=flac` |
| 实际出声 | ExoPlayer 能播（电视有声音） |
| 耗时 | `total` 在 0.2–4 s 之间属正常；>10 s 说明在反复换源 |

失败时的读法：

- `MISS`：搜索没命中或所有源都失败 → 看界面上的尝试列表（`✗ resolve/wy/…`）定位是哪个环节
- `resolver not ready`：脚本全没初始化 → 检查 assets 是否被打包、QuickJS 是否加载失败
- 界面停在"初始化中…"：QuickJS runtime 创建失败（看 `adb logcat | grep -i quickjs`）

---

## 5. 已知限制（PoC 阶段刻意不做的）

| 项 | 现状 | 上线前要补 |
|---|---|---|
| 酷我(kw)/咪咕(mg) | 搜索层只接了 163/QQ/酷狗 | 酷我需要 csrf/cookie，咪咕无公开搜索 |
| 直链过期 | 解析结果不缓存，每次现解析 | URL TTL ≈ 21 分钟，需"报错重解析" |
| 匹配失败兜底 | 只做了"艺人+时长"第二遍 | 别名表要按实际失败样本扩充 |
| 音频焦点/MediaSession | PoC 用裸 ExoPlayer | 接主链路的 `PlayerViewModel` 时统一处理 |
| 脚本热更新 | 脚本从 assets 复制到 filesDir | 加"在线导入 + 版本号" |
| 线程 | 每脚本一个 JS 线程 + 4 线程 HTTP 池 | 3 个脚本常驻约 3×runtime 内存，电视上需实测 |

---

## 6. 下一步（P1）

PoC 通过后，按这个顺序接回主链路：

1. `MusicRepository.streamUrl()` → `AudioResolver.resolve()`（同步转异步，6 处调用点）
2. `PlayerViewModel.playQueueItem` 加"解析中/失败换源/直链过期重连"三个状态
3. `prefetchSong` 改为"预解析 URL"（跨淡要提前 5 秒拿到可用直链）
4. 删 DRM 三条分支与页面（`media/widevine/*`、MV、电台）
5. 设置页换成"音源管理/音质优先/命中率诊断"
