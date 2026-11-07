package com.applemusicktv.ui.screens

import com.applemusicktv.i18n.tr
import com.applemusicktv.R

import com.applemusicktv.ui.components.TouchSurface as Surface

import com.applemusicktv.ui.components.pageHorizontalInset
import com.applemusicktv.ui.components.ContentActionShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.applemusicktv.ui.navigation.ReturnLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.applemusicktv.ui.viewmodel.settingsUiState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.applemusicktv.ui.components.returnToTabOnUp
import com.applemusicktv.ui.components.menuInitialFocus
import com.applemusicktv.ui.viewmodel.DevMenuViewModel
import com.applemusicktv.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DevMenuScreen(
    playerVm: PlayerViewModel,
    onDataRefresh: () -> Unit = {},
    onReturnToTab: (() -> Unit)? = null,
    tabBarFocused: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val vm: DevMenuViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val update by vm.update.collectAsState()
    val pstate by remember(playerVm) {
        playerVm.state.map { it.settingsUiState() }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(playerVm.state.value.settingsUiState())
    var showDev by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var showSourceManager by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val english = com.applemusicktv.i18n.AppLanguage.code == "en"
    fun tx(zh: String, en: String) = if (english) en else zh
    LaunchedEffect(Unit) { vm.checkUpdates() }

    // Live network activity — polled from the shared NetworkLog buffer.
    var netLogs by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(showDev) {
        while (showDev) { netLogs = vm.networkLogs(); kotlinx.coroutines.delay(1000) }
    }

    LaunchedEffect(vm) {
        while (true) { vm.refreshCacheUsage(); kotlinx.coroutines.delay(2_000) }
    }

    val scrollState = rememberScrollState()
    val returning = com.applemusicktv.ui.navigation.LocalNavigationReturn.current?.restoring == true
    LaunchedEffect(tabBarFocused, returning) { if (tabBarFocused && !returning) scrollState.animateScrollTo(0) }

    Box(modifier.fillMaxSize().background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF101012)))) {
        Column(
            // 顶部/底部留白必须在 verticalScroll 之外：写在里面时它是"可滚动内容"的一部分，
            // 焦点滚动会把当前行顶到 y=0（导航条下面）或压到屏幕最底，焦点框看不见。
            modifier = Modifier.align(Alignment.TopCenter).fillMaxHeight()
                .fillMaxWidth().padding(top = 100.dp, bottom = 48.dp)
                .verticalScroll(scrollState)
                .padding(start = pageHorizontalInset, end = pageHorizontalInset),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr(R.string.msg_182), fontSize = 32.sp, fontWeight = FontWeight.Bold,
                                        color = com.applemusicktv.ui.theme.AppPalette.primary)
                Text(tr(R.string.msg_183), fontSize = 13.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
            }

            // 免登录模式：不再提供 Apple 登录入口，设置页只留本地/播放相关项。
            Box(Modifier.returnToTabOnUp(onReturnToTab)) {
                SettingsSection(tr(R.string.msg_186)) { LauncherIconSettings() }
            }
            LanguageSettingsSection()
            SettingsSection(tx("外观", "Appearance")) {
                val dark = com.applemusicktv.ui.theme.AppPalette.isDark
                Stepper(tx("界面模式", "Appearance"), if (dark) tx("深色", "Dark") else tx("浅色", "Light"),
                    tx("选择应用界面的显示模式", "Choose the app appearance"),
                    { com.applemusicktv.ui.theme.AppPalette.setDark(ctx, false) },
                    { com.applemusicktv.ui.theme.AppPalette.setDark(ctx, true) },
                    decEnabled = dark, incEnabled = !dark)
            }
            SettingsSection(tr(R.string.playback_section)) {
                Stepper(tr(R.string.msg_187), tr(R.string.msg_188).format(com.applemusicktv.i18n.AppLanguage.locale, state.crossfadeMs / 1000f),
                    tr(R.string.msg_189), { vm.setCrossfade(com.applemusicktv.data.CrossfadePreferences.step(state.crossfadeMs, -1)) },
                    { vm.setCrossfade(com.applemusicktv.data.CrossfadePreferences.step(state.crossfadeMs, 1)) })
                SettingDivider()
                val quality = state.audioQuality
                Stepper(
                    tx("音质", "Audio quality"),
                    tx(quality.label, quality.label),
                    tx(quality.hint, quality.hint),
                    { vm.stepAudioQuality(-1) },
                    { vm.stepAudioQuality(1) },
                    decEnabled = quality.ordinal > 0,
                    incEnabled = quality.ordinal < com.applemusicktv.data.AudioQuality.entries.lastIndex,
                )
                if (state.pcServerIp.isNotBlank()) {
                    SettingDivider()
                    Toggle(tr(R.string.msg_190), state.standaloneOn,
                        if (state.standaloneOn) tr(R.string.msg_191) else tr(R.string.msg_192)) {
                        vm.toggleStandalone()
                    }
                }
                SettingDivider()
                Toggle(tr(R.string.msg_193), pstate.volumeLeveling, tr(R.string.msg_194)) { playerVm.toggleVolumeLeveling() }
                SettingDivider()
                Toggle(tr(R.string.msg_195), pstate.backgroundPlayEnabled, tr(R.string.msg_196)) { playerVm.toggleBackgroundPlay() }
            }
            SettingsSection(tr(R.string.msg_197)) {
                Stepper(tr(R.string.msg_198), pstate.fluidQuality.label, tr(R.string.msg_199),
                    { playerVm.stepFluidQuality(-1) }, { playerVm.stepFluidQuality(1) })
                SettingDivider()
                Stepper(tr(R.string.msg_200), lyricsScaleLabel(pstate.lyricsScale), tr(R.string.msg_201),
                    { playerVm.stepLyricsScale(-1) }, { playerVm.stepLyricsScale(1) })
                SettingDivider()
                Toggle(tr(R.string.msg_202), pstate.motionArtworkEnabled, tr(R.string.msg_203)) { playerVm.toggleMotionArtwork() }
                SettingDivider()
                Toggle(tr(R.string.msg_204), pstate.artworkRounded, tr(R.string.msg_205)) { playerVm.toggleArtworkRounded() }

            }
            Text(tr(R.string.msg_206),
                color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)), fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 4.dp))

            SettingsSection(tr(R.string.msg_208)) {
                Toggle(tr(R.string.msg_211), pstate.avSyncAuto, tr(R.string.msg_212)) {
                    playerVm.setAvSyncAuto(!pstate.avSyncAuto)
                }
                SettingDivider()
                Stepper(tr(R.string.msg_213), tr(R.string.msg_214, state.lyricsOffsetMs), tr(R.string.msg_215),
                    { vm.setLyricsOffset(state.lyricsOffsetMs - 50) }, { vm.setLyricsOffset(state.lyricsOffsetMs + 50) })
                SettingDivider()
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr(R.string.msg_216), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                    Text(com.applemusicktv.i18n.AppLanguage.outputLabel(pstate.avOutputLabel) + if (pstate.avSyncAuto && pstate.avOnBluetooth) tr(R.string.msg_217) else "",
                        fontSize = 12.sp, lineHeight = 18.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                }
            }
            CacheSettingsSection(state.cacheCapacityGb, state.cacheUsedBytes, state.cacheBusy,
                state.cacheMessage, vm::setCacheCapacity, vm::clearCache)
            // 免登录后这里不再是「账号配置」，而是遥控器输入不便时的唯一入口：
            // 手机浏览器打开 :8080 可以导入歌单、改播放设置。
            SettingsSection(tr(R.string.msg_218)) { PhoneServerRow(state.webServerUrl, playerVm) }
            SettingsSection(tx("关于", "About")) {
                val details = when (update.phase) {
                    "checking" -> tx("正在检查更新…", "Checking for updates…")
                    "available" -> tx("发现新版本 ${update.info?.version}", "Update available: ${update.info?.version}")
                    "latest" -> tx("当前已是最新版本", "You're up to date")
                    "downloading" -> tx("正在下载 ${update.progress}%", "Downloading ${update.progress}%")
                    "ready", "permission" -> tx("安装包已下载，点击继续安装", "Download complete; select to install")
                    "incompatible" -> tx("新版本不支持当前系统", "The update requires a newer Android version")
                    "error", "downloadError", "installError" -> tx("检查或安装失败，点击重试", "Update failed; select to retry")
                    else -> tx("点击检查更新", "Select to check for updates")
                }
                ActionSettingRow(tx("当前版本 ${com.applemusicktv.BuildConfig.VERSION_NAME}", "Version ${com.applemusicktv.BuildConfig.VERSION_NAME}"), details) {
                    if (update.phase == "ready" || update.phase == "permission") vm.installUpdate()
                    else vm.checkUpdates(manual = true)
                }
            }
            SettingsSection(tr(R.string.msg_219)) {
                Toggle(tr(R.string.msg_220), showDev, tr(R.string.msg_221)) { showDev = !showDev }
            }
            if (showDev) {
                SettingsSection(tr(R.string.msg_222)) {
                    ActionSettingRow(tr(R.string.msg_223), tr(R.string.msg_224)) { vm.clearLogs() }
                    SettingDivider()
                    LazyColumn(Modifier.fillMaxWidth().height(260.dp).padding(20.dp), reverseLayout = true,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (netLogs.isEmpty() && state.logs.isEmpty()) item {
                            Text(tr(R.string.msg_225), fontSize = 12.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
                        }
                        items(netLogs.reversed()) { line -> Text(line, fontSize = 12.sp, lineHeight = 18.sp,
                            fontFamily = FontFamily.Monospace, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8))) }
                        items(state.logs.reversed()) { log -> Text(log.message, fontSize = 12.sp, lineHeight = 18.sp,
                            fontFamily = FontFamily.Monospace, color = when (log.level) {
                                "ERROR" -> Color(0xFFB22222); "WARN" -> Color(0xFF8A6500)
                                else -> com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8))
                            }) }
                    }
                }
                SettingsSection(tr(R.string.msg_226)) { BugReportRow(state.webServerUrl) }
                SettingsSection(tr(R.string.msg_227)) {
                    ActionSettingRow(tx("配置音源", "Configure sources"), tx("连通性检测、停用、删除内置音源", "Probe, disable, or delete built-in sources")) {
                        showSourceManager = true
                    }
                    SettingDivider()
                    ActionSettingRow(tr(R.string.msg_230), tr(R.string.msg_231)) {
                        playerVm.stopPlayback(); android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
            }
            // Always available below developer options, without enabling developer content.
            Column(Modifier.fillMaxWidth().background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)), RoundedCornerShape(14.dp))) {
                ActionSettingRow(tr(R.string.msg_232), tr(R.string.msg_233), danger = true) { confirmReset = true }
            }

        }
    }

    if (confirmReset) {
        ResetAppDialog(onDismiss = { confirmReset = false }) {
            val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.clearApplicationUserData()
        }
    }
    if (showSourceManager) {
        SourceManagerDialog(
            scripts = vm.sourceScripts(),
            onDismiss = { showSourceManager = false },
            onToggle = { file, disabled -> vm.setScriptDisabled(file, disabled) },
            onDelete = { file -> vm.deleteScript(file) },
            onProbe = { file, source -> vm.probeScript(file, source) },
        )
    }
    if (update.manualResult) {
        val title = when (update.phase) {
            "available" -> tx("发现新版本", "Update available")
            "latest" -> tx("当前已是最新版本", "You're up to date")
            "permission" -> tx("允许安装应用", "Allow app installation")
            "incompatible" -> tx("暂无法更新", "Update unavailable")
            else -> tx("更新失败", "Update failed")
        }
        val message = when (update.phase) {
            "available" -> "${update.info?.version}\n${update.info?.notes.orEmpty()}"
            "latest" -> tx("当前版本已是最新版本。", "You have the latest version.")
            "permission" -> tx("请允许从此来源安装，返回后点击「确定」以继续安装。", "Allow installation from this source, then return and select OK to continue installation.")
            "incompatible" -> tx("新版本需要更新的 Android 系统。", "The new version requires a newer Android version.")
            else -> updateFailureMessage(english)
        }
        com.applemusicktv.ui.components.ConfirmationDialog(title = title, message = message,
            confirmLabel = if (update.phase == "available") tx("更新", "Update") else tx("确定", "OK"),
            onDismiss = vm::dismissUpdateResult,
            onConfirm = { when (update.phase) { "available" -> vm.downloadUpdate(); "permission" -> { vm.dismissUpdateResult(); vm.installUpdate() }; else -> vm.dismissUpdateResult() } })
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LauncherIconSettings() {
    val context = LocalContext.current
    val manager = remember(context) { com.applemusicktv.data.LauncherIconManager(context) }
    var selected by remember { mutableStateOf(manager.current()) }
    val shape = RoundedCornerShape(30.dp)
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr(R.string.msg_234), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
            Text(tr(R.string.msg_235), fontSize = 12.sp, lineHeight = 18.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            com.applemusicktv.data.LauncherIcon.values().forEach { icon ->
                val chosen = selected == icon
                val selectionBorder = Border(androidx.compose.foundation.BorderStroke(2.dp,
                    if (chosen) com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFC7C7CC), Color(0xFF707078)) else Color.Transparent), shape = shape)
                val focusBorder = Border(androidx.compose.foundation.BorderStroke(2.dp,
                    com.applemusicktv.ui.theme.AppPalette.accent), shape = shape)
                Surface(onClick = {
                    runCatching { manager.select(icon) }.onSuccess { selected = manager.current() }
                        .onFailure { android.widget.Toast.makeText(context, tr(R.string.msg_236), android.widget.Toast.LENGTH_SHORT).show() }
                }, modifier = Modifier.size(96.dp).semantics {
                    contentDescription = tr(R.string.msg_237, icon.title)
                    this.selected = chosen
                },
                    shape = ClickableSurfaceDefaults.shape(shape),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.White, focusedContainerColor = Color.White),
                    border = ClickableSurfaceDefaults.border(border = selectionBorder, focusedBorder = focusBorder),
                    glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(icon.resource), null, Modifier.size(64.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ResetAppDialog(onDismiss: () -> Unit, onReset: () -> Unit) {
    com.applemusicktv.ui.components.ConfirmationDialog(
        title = tr(R.string.msg_238), message = tr(R.string.msg_239),
        confirmLabel = tr(R.string.msg_240), confirmColor = Color(0xFFB22222), onDismiss = onDismiss, onConfirm = onReset,
    )
}

@Composable
private fun PhoneServerRow(webServerUrl: String, playerVm: PlayerViewModel) {
    var enabled by remember { mutableStateOf(playerVm.webServerEnabled()) }
    var showQr by remember { mutableStateOf(false) }
    Column {
        Toggle(tr(R.string.msg_241), enabled, tr(R.string.msg_242)) {
            enabled = !enabled; playerVm.setWebServerEnabled(enabled)
        }
        if (enabled) {
            SettingDivider()
            ActionSettingRow(
                if (webServerUrl.isNotEmpty()) webServerUrl else tr(R.string.msg_243),
                if (webServerUrl.isNotEmpty()) tr(R.string.msg_244) else tr(R.string.msg_244),
                enabled = webServerUrl.isNotEmpty(),
                onClick = { if (webServerUrl.isNotEmpty()) showQr = true },
            )
        }
    }
    if (showQr && webServerUrl.isNotEmpty()) {
        QrDialog(url = webServerUrl, onDismiss = { showQr = false })
    }
}

@Composable
private fun QrDialog(url: String, onDismiss: () -> Unit) {
    com.applemusicktv.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(440.dp).clip(RoundedCornerShape(24.dp))
                .background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF2F2F7), Color(0xFF1C1C1E)))
                .padding(horizontal = 32.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            Text("手机扫码配置", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = com.applemusicktv.ui.theme.AppPalette.primary)
            Spacer(Modifier.height(6.dp))
            Text("用手机扫描二维码，在浏览器里导入歌单、调整播放设置",
                fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center,
                color = com.applemusicktv.ui.theme.AppPalette.secondary)
            Spacer(Modifier.height(24.dp))
            com.applemusicktv.ui.components.QrCode(content = url, size = 236.dp, cornerRadius = 18.dp, padding = 16.dp)
            Spacer(Modifier.height(20.dp))
            Text(url, fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                color = com.applemusicktv.ui.theme.AppPalette.secondary)
            Spacer(Modifier.height(26.dp))
            Surface(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = com.applemusicktv.ui.theme.AppPalette.accent,
                    focusedContainerColor = Color(0xFFFF4757),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
            ) {
                Text("关闭", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp))
            }
        }
    }
}

@Composable
private fun BugReportRow(webServerUrl: String) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr(R.string.msg_245), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
        Text(tr(R.string.msg_246),
            fontSize = 12.sp, lineHeight = 18.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
        Text(if (webServerUrl.isNotEmpty()) "$webServerUrl/report" else tr(R.string.msg_247),
            fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
    }
}

/** Calm / Normal / Strong / Crazy for the beat-reaction multiplier. */
internal fun intensityLabel(f: Float): String = when {
    f < 0.8f -> tr(R.string.msg_248)
    f < 1.5f -> tr(R.string.msg_007)
    f < 2.5f -> tr(R.string.msg_249)
    else     -> tr(R.string.msg_250)
}

internal fun orbSpeedLabel(f: Float): String = when {
    f < 0.85f -> tr(R.string.msg_251)
    f < 1.3f  -> tr(R.string.msg_007)
    else      -> tr(R.string.msg_252)
}

internal fun lyricsScaleLabel(f: Float): String = when {
    f < 0.9f  -> tr(R.string.msg_253)
    f < 1.1f  -> tr(R.string.msg_254)
    f < 1.4f  -> tr(R.string.msg_007)
    f < 1.8f  -> tr(R.string.msg_255)
    else      -> tr(R.string.msg_256)
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(title)
        Column(Modifier.fillMaxWidth().background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)), RoundedCornerShape(14.dp)), content = content)
    }
}

@Composable
private fun SettingDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(0.75.dp).background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE0E0E5), Color(0xFF414146))))
}

@Composable
private fun RowScope.SettingText(label: String, sub: String) {
    Column(Modifier.weight(1f).padding(end = 20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
        Text(sub, fontSize = 12.sp, lineHeight = 18.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
    }
}

@Composable
internal fun Stepper(label: String, value: String, sub: String, onDec: () -> Unit, onInc: () -> Unit, modifier: Modifier = Modifier, decEnabled: Boolean = true, incEnabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 84.dp).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SettingText(label, sub)
        Row(Modifier.width(190.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            StepBtn("−", onDec, decEnabled)
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF3C3C43), Color(0xFFE3E3E8)),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f))
            StepBtn("+", onInc, incEnabled)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun StepBtn(symbol: String, onClick: () -> Unit, enabled: Boolean = true) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp),
        shape = ClickableSurfaceDefaults.shape(ContentActionShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)), focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.accent,
            focusedContentColor = Color.White, contentColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF3C3C43), Color(0xFFE3E3E8))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None)) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text(symbol, fontSize = 20.sp) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Toggle(label: String, on: Boolean, sub: String, enabled: Boolean = true, onToggle: () -> Unit) {
    Surface(onClick = onToggle, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SettingText(label, sub)
            Box(Modifier.width(48.dp).height(28.dp)
                .background(if (on) Color(0xFFFA233B) else com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFD2D2D7), Color(0xFF55555C)), RoundedCornerShape(50)),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.padding(3.dp).size(22.dp).background(Color.White, RoundedCornerShape(50)))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 18.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)), fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun StatusChip(label: String, ok: Boolean, sub: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SettingText(label, sub.orEmpty())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(if (ok) Color(0xFF248A3D) else com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)), RoundedCornerShape(50)))
            Text(if (ok) tr(R.string.msg_257) else tr(R.string.msg_054), fontSize = 13.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionSettingRow(label: String, description: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D))),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    color = if (danger) Color(0xFFB22222) else com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                Text(description, fontSize = 12.sp, lineHeight = 18.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
            }
            Text("›", fontSize = 22.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionBtn(label: String, color: Color, small: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = ClickableSurfaceDefaults.shape(ContentActionShape),
        colors = ClickableSurfaceDefaults.colors(containerColor = color, focusedContainerColor = color.copy(alpha = 0.7f)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier.height(44.dp)) {
        Box(Modifier.padding(horizontal = if (small) 12.dp else 18.dp), Alignment.Center) {
            Text(label, fontSize = 14.sp, color = com.applemusicktv.ui.theme.AppPalette.contentOn(color), fontWeight = FontWeight.Medium)
        }
    }
}


internal fun cacheBytesLabel(bytes: Long): String = if (bytes < 1_000_000_000L)
    (if (com.applemusicktv.i18n.AppLanguage.code == "en") "%.0f MB" else "%.0fM").format(java.util.Locale.ROOT, bytes.coerceAtLeast(0) / 1_000_000.0)
else (if (com.applemusicktv.i18n.AppLanguage.code == "en") "%.2f GB" else "%.2fG").format(java.util.Locale.ROOT, bytes / 1_000_000_000.0)

@Composable
internal fun CacheSettingsSection(capacityGb: Int, usedBytes: Long, busy: Boolean, message: String,
    onCapacity: (Int) -> Unit, onClear: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    val progress = (usedBytes.toDouble() / (capacityGb * 1_000_000_000L)).toFloat().coerceIn(0f, 1f)
    SettingsSection(tr(R.string.msg_258)) {
        Stepper(tr(R.string.msg_259), if (com.applemusicktv.i18n.AppLanguage.code == "en") "$capacityGb GB" else "${capacityGb}G", tr(R.string.msg_260),
            { onCapacity((capacityGb - 1).coerceAtLeast(1)) },
            { onCapacity((capacityGb + 1).coerceAtMost(10)) },
            decEnabled = !busy && capacityGb > 1, incEnabled = !busy && capacityGb < 10)
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr(R.string.msg_261, cacheBytesLabel(usedBytes), capacityGb), fontSize = 12.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
                Text("${(progress * 100).toInt()}%", fontSize = 12.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
            }
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))
                .background(com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE0E0E5), Color(0xFF414146))).semantics {
                    contentDescription = tr(R.string.msg_262)
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                }) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(com.applemusicktv.ui.theme.AppPalette.accent))
            }
            if (message.isNotEmpty()) Text(com.applemusicktv.i18n.AppLanguage.message(message).orEmpty(), fontSize = 12.sp, lineHeight = 18.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
        }
        SettingDivider()
        ActionSettingRow(if (busy) tr(R.string.msg_263) else tr(R.string.msg_264), tr(R.string.msg_265), enabled = !busy, onClick = { confirmClear = true })
    }
    if (confirmClear) {
        ClearCacheDialog(onDismiss = { confirmClear = false }) {
            confirmClear = false
            if (!busy) onClear()
        }
    }
}

@Composable
internal fun ClearCacheDialog(onDismiss: () -> Unit, onClear: () -> Unit) {
    com.applemusicktv.ui.components.ConfirmationDialog(
        title = tr(R.string.msg_266), message = tr(R.string.msg_267),
        confirmLabel = tr(R.string.msg_268), onDismiss = onDismiss, onConfirm = onClear,
    )
}

@Composable
internal fun LanguageSettingsSection() {
    SettingsSection(tr(R.string.msg_310)) {
        val choice = com.applemusicktv.i18n.AppLanguage.choice
        val choices = com.applemusicktv.i18n.LanguageChoice.entries
        val label = when (choice) {
            com.applemusicktv.i18n.LanguageChoice.System -> tr(R.string.msg_311)
            com.applemusicktv.i18n.LanguageChoice.Chinese -> "简体中文"
            com.applemusicktv.i18n.LanguageChoice.English -> "English"
        }
        fun changeLanguage(direction: Int) {
            com.applemusicktv.i18n.AppLanguage.select(choices[(choice.ordinal + direction + choices.size) % choices.size])
        }
        Stepper(tr(R.string.msg_313), label, tr(R.string.msg_314),
            { changeLanguage(-1) }, { changeLanguage(1) })
    }
}

/**
 * 开发者模式「配置音源」二级菜单：列出内置音源，支持连通性检测、停用/启用、删除。
 * 用 MotionDialog 弹出，与 App 其余弹窗风格一致；列表项用方向键上下移动、确认执行。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SourceManagerDialog(
    scripts: List<com.applemusicktv.luoxue.LxPlayback.ScriptView>,
    onDismiss: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onProbe: suspend (String, String) -> com.applemusicktv.luoxue.LxPlayback.SourceProbe,
) {
    val scope = rememberCoroutineScope()
    var probingFile by remember { mutableStateOf<String?>(null) }
    var probeResult by remember { mutableStateOf<String?>(null) }
    val initialFocus = remember { FocusRequester() }

    com.applemusicktv.ui.components.MotionDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.menuInitialFocus(initialFocus).width(680.dp)
                .background(
                    com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFF5F5F7), Color(0xFF222225)),
                    RoundedCornerShape(20.dp),
                ).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(tx_dialog("配置音源", "Configure sources"), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                Text("${scripts.size} 个音源", fontSize = 12.sp,
                    color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(scripts, key = { it.file }) { s ->
                    SourceManagerRow(
                        script = s,
                        probing = probingFile == s.file,
                        probeResult = if (probingFile == s.file) probeResult else null,
                        onProbe = { src ->
                            if (probingFile != null) return@SourceManagerRow
                            probingFile = s.file
                            probeResult = null
                            scope.launch {
                                val r = onProbe(s.file, src)
                                probeResult = if (r.ok) "连通 ${r.ms}ms" else "失败 ${r.detail}"
                                probingFile = null
                            }
                        },
                        onToggle = { onToggle(s.file, !s.disabled) },
                        onDelete = { onDelete(s.file) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Surface(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End).focusRequester(initialFocus),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFE8E8ED), Color(0xFF38383D)),
                    focusedContainerColor = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFD9D9DE), Color(0xFF4A4A50)),
                ),
            ) {
                Text(tx_dialog("关闭", "Close"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)),
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
            }
        }
    }
}

private fun tx_dialog(zh: String, en: String) =
    if (com.applemusicktv.i18n.AppLanguage.code == "en") en else zh

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SourceManagerRow(
    script: com.applemusicktv.luoxue.LxPlayback.ScriptView,
    probing: Boolean,
    probeResult: String?,
    onProbe: (String) -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val display = script.displayName.ifBlank {
        script.file.removeSuffix(".js").removePrefix("lx-")
    }
    val sources = script.sources.takeIf { it.isNotEmpty() }?.joinToString("/") ?: "—"
    Column(
        Modifier.fillMaxWidth()
            .background(
                com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFFFFFFFF), Color(0xFF2A2A2E)),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(display, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF1D1D1F), Color(0xFFF5F5F7)))
                    if (script.disabled) {
                        Text("已停用", fontSize = 11.sp, color = Color(0xFFB22222))
                    } else if (script.loaded) {
                        Text("已加载", fontSize = 11.sp, color = Color(0xFF2E9E44))
                    }
                }
                Text("源 $sources", fontSize = 11.sp,
                    color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF8E8E93), Color(0xFFA3A3AA)))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionBtn(tx_dialog("检测", "Probe"), Color(0xFF3B7DD8), small = true) {
                    script.sources.firstOrNull()?.let(onProbe)
                }
                ActionBtn(tx_dialog(if (script.disabled) "启用" else "停用", if (script.disabled) "Enable" else "Disable"),
                    if (script.disabled) Color(0xFF2E9E44) else Color(0xFF8A6500), small = true, onClick = onToggle)
                ActionBtn(tx_dialog("删除", "Delete"), Color(0xFFB22222), small = true, onClick = onDelete)
            }
            if (probeResult != null) {
                Text(probeResult, fontSize = 11.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
            } else if (probing) {
                Text(tx_dialog("检测中…", "Probing…"), fontSize = 11.sp, color = com.applemusicktv.ui.theme.AppPalette.adaptive(Color(0xFF6E6E73), Color(0xFFB1B1B8)))
            }
    }
}
