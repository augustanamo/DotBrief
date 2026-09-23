package com.briefwidget.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.briefwidget.BriefWidgetApp
import com.briefwidget.ui.theme.BriefWidgetTheme

/**
 * 应用唯一入口：轻量配置中心。
 *
 * 这里同时也是桌面图标点进来的落地页——本应用没有"主页"，
 * 因为它的主界面就是桌面小组件本身，Activity 只负责配置。
 */
class SettingsActivity : ComponentActivity() {

    private val viewModel: SettingsViewModel by viewModels {
        SettingsViewModel.factory((application as BriefWidgetApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BriefWidgetTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()

                SettingsScreen(
                    state = state,
                    runtimeState = runtimeState,
                    onChange = viewModel::onChangeDraft,
                    onSave = viewModel::save,
                    onReset = viewModel::reset,
                    onAddFeed = viewModel::addFeed,
                    onRemoveFeed = viewModel::removeFeed,
                    onToggleFeed = viewModel::toggleFeed,
                    onPreviewSpeech = viewModel::previewSpeech,
                    onPlayBriefNow = viewModel::playBriefNow,
                    onStopBrief = viewModel::stopBrief,
                    onProbeLlm = viewModel::probeLlm,
                    onConsumeMessage = viewModel::consumeMessage,
                )
            }
        }
    }
}
