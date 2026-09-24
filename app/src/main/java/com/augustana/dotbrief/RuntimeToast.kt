package com.augustana.dotbrief

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 把一条运行期提示**直接弹到屏幕上**。
 *
 * ## 为什么从"设置页里的一段红字"改成 Toast
 *
 * 出问题的那条链路几乎没有可视反馈：生成失败时用户看到的只是**桌面点阵换了个颜色**，
 * 而原因被压在设置页最底下（原来是「权限与诊断 → 运行状态 → 最近一次提示」）。
 * 真机上踩过 —— 模型回 429 额度用尽，用户反复点了半天，唯一的那句线索没人会想到去翻。
 * 现在的规矩是：**失败当场说，而且要出现在用户当时正看着的那块屏上。**
 *
 * ## 两条使用约定
 *
 * 1. **同一次失败只说一次**。手动「刷新内容」那条路本来就会把结论送到设置页的
 *    Snackbar（见 `SettingsViewModel.updateBriefNow`），所以 `BriefUpdater` 只在
 *    `force = false`（定时任务）时才调这里 —— 否则同一条消息会连着响两遍。
 * 2. **Toast 是搭便车的提示，不是唯一的记录**。原文仍旧写进 `RuntimeKeys.LAST_ERROR`，
 *    logcat 里也有一份；Toast 只负责让它别藏起来。
 *
 * ⚠️ Android 12 起**后台应用弹不出 Toast**（系统会拦掉并打一条 warning）。
 * 所以定时更新（WorkManager）那一次不一定看得见 —— 那种情况靠桌面点阵 + 日志兜底。
 * 这条限制不值得为它去申请「在其他应用上层显示」权限。
 */
fun toastIssue(context: Context, message: String) {
    if (message.isBlank()) return
    val app = context.applicationContext
    // 调用方多在协程 / 工作线程里，而 Toast 必须有 Looper 的线程才能构造。
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }
}
