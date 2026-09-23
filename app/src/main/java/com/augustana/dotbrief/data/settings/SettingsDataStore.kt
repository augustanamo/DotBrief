package com.augustana.dotbrief.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * DataStore 实例注册处。
 *
 * `preferencesDataStore` 是一个属性委托，必须是顶层 val，且**同一个文件名只能声明一次**，
 * 否则运行时会抛 `IllegalStateException: There are multiple DataStores active for the same file`。
 * 所以这里统一持有两个实例，全 App 共用：
 * - brief_settings：用户配置 + API Key
 * - brief_runtime ：运行期状态（小组件状态 / 最近简报 / 错误信息）
 */
private const val SETTINGS_STORE_NAME = "brief_settings"
private const val RUNTIME_STORE_NAME = "brief_runtime"

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = SETTINGS_STORE_NAME)

val Context.runtimeDataStore: DataStore<Preferences> by preferencesDataStore(name = RUNTIME_STORE_NAME)
