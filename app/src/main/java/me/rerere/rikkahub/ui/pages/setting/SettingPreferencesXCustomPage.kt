// [X-custom] RikkaHub-X 定制(merge 上游时保留): X 定制设置聚合页
// 收纳 RikkaHub-X 全部「有开关」的增强定制项(语音输入提示音/振动增强/压缩会话反馈)。
// 2026-09-10 由 SettingPreferencesGeneralPage 迁移而来,通用页不再混入 X 项。
package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.x.context.ContextUsageDialogStyle
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesXCustomPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.setting_page_preferences_x_custom))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_voice_input_settings)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_voice_input_haptic_boost_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_voice_input_haptic_boost_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableVoiceInputHapticBoost,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableVoiceInputHapticBoost = it))
                                }
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_voice_input_sound_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_voice_input_sound_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableVoiceInputSound,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableVoiceInputSound = it))
                                }
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_compress_feedback)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_compress_feedback_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_compress_feedback_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableCompressFeedback,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableCompressFeedback = it))
                                }
                            )
                        },
                    )
                }
            }

            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_context_usage_ring)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_context_usage_ring_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_context_usage_ring_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableContextUsageRing,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableContextUsageRing = it))
                                }
                            )
                        },
                    )
                    // 弹窗样式二选一:圆环关闭时该弹窗不可达,故仅在开启后展示
                    if (displaySetting.enableContextUsageRing) {
                        item(
                            headlineContent = { Text(stringResource(R.string.context_usage_dialog_style_title)) },
                            supportingContent = {
                                Column {
                                    Text(stringResource(R.string.context_usage_dialog_style_desc))
                                    Select(
                                        options = ContextUsageDialogStyle.entries,
                                        selectedOption = displaySetting.contextUsageDialogStyle,
                                        onOptionSelected = { style ->
                                            updateDisplaySetting(
                                                displaySetting.copy(contextUsageDialogStyle = style)
                                            )
                                        },
                                        modifier = Modifier
                                            .padding(top = 4.dp)
                                            .fillMaxWidth(),
                                        optionToString = { it.labelUI() },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 上下文弹窗样式的显示名(与 ContextUsageDialogStyle 一一对应)。 */
@Composable
private fun ContextUsageDialogStyle.labelUI(): String = when (this) {
    ContextUsageDialogStyle.CLASSIC -> stringResource(R.string.context_usage_dialog_style_classic)
    ContextUsageDialogStyle.CODEX -> stringResource(R.string.context_usage_dialog_style_codex)
}
