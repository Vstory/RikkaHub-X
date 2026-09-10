// [X-custom] RikkaHub-X 定制(merge 上游时保留): X 定制设置聚合页
// 收纳 RikkaHub-X 全部「有开关」的增强定制项(语音输入提示音/振动增强/压缩会话反馈)。
// 2026-09-10 由 SettingPreferencesGeneralPage 迁移而来,通用页不再混入 X 项。
package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import me.rerere.rikkahub.x.chat.GenerationAutosave
import me.rerere.rikkahub.x.context.ContextUsageDialogStyle
import me.rerere.rikkahub.x.context.ContextWindowRepository
import me.rerere.rikkahub.x.context.formatRefreshedAt
import me.rerere.rikkahub.x.context.formatTableUpdatedAt
import me.rerere.rikkahub.x.context.RefreshError
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingPreferencesXCustomPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var displaySetting by remember(settings) { mutableStateOf(settings.displaySetting) }

    fun updateDisplaySetting(setting: DisplaySetting) {
        displaySetting = setting
        vm.updateSettings(settings.copy(displaySetting = setting))
    }

    // 容量表状态(数据日期 / 拉取中 / 失败原因)。顺带确保仓库已初始化 —— 用户可能先来设置页
    // 而没进过会话,那样圆环的分母就永远不会被拉取。
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) { ContextWindowRepository.ensureLoaded(appContext) }
    val tableStatus by ContextWindowRepository.status.collectAsStateWithLifecycle()

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
                    // 容量表状态与手动更新:表是圆环的分母来源,故归在本分组内。
                    // 展示「数据日期」让人能判断自己拿到的表有多新;拉取失败时给出原因,
                    // 而不是默默什么都不发生。
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_context_usage_ring_refresh_now_title)) },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_context_usage_ring_refresh_now_desc))
                                // 两个时间,都精确到秒:
                                //   数据日期 = 表内声明的数据源时刻;更新时间 = 本机最后一次成功拉取的时刻。
                                // 「更新时间」跨重启保留(取自缓存文件时间戳),故隔很久进来看到的仍是
                                // 上次真正更新的时间,而不是本次打开设置页的时间。
                                val dataDate = tableStatus.tableUpdatedAt?.let {
                                    formatTableUpdatedAt(it) ?: it   // 解析失败就原样显示,好过不显示
                                }
                                val refreshedAt = tableStatus.lastRefreshedAtMillis?.let(::formatRefreshedAt)

                                Text(
                                    text = when {
                                        tableStatus.isRefreshing ->
                                            stringResource(R.string.setting_context_usage_ring_refreshing)

                                        dataDate == null && refreshedAt == null ->
                                            stringResource(R.string.setting_context_usage_ring_table_missing)

                                        else -> ""
                                    },
                                )
                                dataDate?.let {
                                    Text(
                                        stringResource(
                                            R.string.setting_context_usage_ring_table_updated_at, it,
                                        )
                                    )
                                }
                                refreshedAt?.let {
                                    Text(
                                        stringResource(
                                            R.string.setting_context_usage_ring_refreshed_at, it,
                                        )
                                    )
                                }
                                tableStatus.lastError?.let { error ->
                                    Text(
                                        text = when (error) {
                                            RefreshError.REJECTED ->
                                                stringResource(R.string.setting_context_usage_ring_error_rejected)

                                            RefreshError.UNREACHABLE ->
                                                stringResource(R.string.setting_context_usage_ring_error_unreachable)
                                        },
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            TextButton(
                                onClick = { ContextWindowRepository.refreshNow() },
                                enabled = !tableStatus.isRefreshing,
                            ) {
                                Text(stringResource(R.string.setting_context_usage_ring_refresh_action))
                            }
                        },
                    )
                }
            }
            item {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    title = { Text(stringResource(R.string.setting_page_generation_autosave)) },
                ) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_generation_autosave_title)) },
                        supportingContent = { Text(stringResource(R.string.setting_generation_autosave_desc)) },
                        trailingContent = {
                            Switch(
                                checked = displaySetting.enableGenerationAutosave,
                                onCheckedChange = {
                                    updateDisplaySetting(displaySetting.copy(enableGenerationAutosave = it))
                                }
                            )
                        },
                    )
                    // 间隔输入:开关关闭时该项不可达,故仅在开启后展示(同圆环的弹窗样式)
                    if (displaySetting.enableGenerationAutosave) {
                        item(
                            headlineContent = { Text(stringResource(R.string.setting_generation_autosave_interval_title)) },
                            supportingContent = {
                                Column {
                                    Text(
                                        stringResource(
                                            R.string.setting_generation_autosave_interval_desc,
                                            GenerationAutosave.intervalRangeDescription(),
                                        )
                                    )
                                    var intervalInput by remember(displaySetting.generationAutosaveIntervalSeconds) {
                                        mutableStateOf(displaySetting.generationAutosaveIntervalSeconds.toString())
                                    }
                                    var intervalFocused by remember { mutableStateOf(false) }

                                    // 失焦才提交:边输边存会把中间值(如想输 60 时的 "6")落库
                                    fun commitInterval() {
                                        val parsed = intervalInput.toIntOrNull()
                                        if (parsed == null) {
                                            intervalInput = displaySetting.generationAutosaveIntervalSeconds.toString()
                                            return
                                        }
                                        val clamped = GenerationAutosave.clampGenerationAutosaveInterval(parsed)
                                        intervalInput = clamped.toString()
                                        if (clamped != displaySetting.generationAutosaveIntervalSeconds) {
                                            updateDisplaySetting(
                                                displaySetting.copy(generationAutosaveIntervalSeconds = clamped)
                                            )
                                        }
                                    }

                                    OutlinedTextField(
                                        value = intervalInput,
                                        onValueChange = { input ->
                                            if (input.all(Char::isDigit) &&
                                                (input.isEmpty() || input.toIntOrNull() != null)
                                            ) {
                                                intervalInput = input
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .onFocusChanged { focusState ->
                                                if (intervalFocused && !focusState.isFocused) {
                                                    commitInterval()
                                                }
                                                intervalFocused = focusState.isFocused
                                            },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        suffix = {
                                            Text(stringResource(R.string.setting_generation_autosave_interval_unit))
                                        },
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
