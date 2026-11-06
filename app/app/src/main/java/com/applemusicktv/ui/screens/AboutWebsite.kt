package com.applemusicktv.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

internal const val OFFICIAL_WEBSITE_URL = "https://applemusictv.pages.dev"

internal fun updateFailureMessage(english: Boolean): String = if (english)
    "Check your connection and try again. Some TV brands restrict app self-updates. Please visit the official website to manually download and install the update."
else
    "请检查网络或稍后重试。部分电视品牌限制应用自升级，请前往官网手动下载安装包以更新。"

internal fun officialWebsiteIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_WEBSITE_URL))
    .addCategory(Intent.CATEGORY_BROWSABLE)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

internal fun openOfficialWebsite(context: Context, english: Boolean) {
    try {
        context.startActivity(officialWebsiteIntent())
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, if (english) "No browser available. Visit applemusictv.pages.dev on another device." else "未找到浏览器，请在其他设备访问 applemusictv.pages.dev。", Toast.LENGTH_LONG).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, if (english) "Unable to open the browser. Visit applemusictv.pages.dev on another device." else "无法打开浏览器，请在其他设备访问 applemusictv.pages.dev。", Toast.LENGTH_LONG).show()
    }
}
