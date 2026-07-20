# LLM Provider UI + 多语言扩展 Spec

## Why

前期已完成 LlmProvider 枚举扩展(3→14)、GeminiLlmClient、LlmClientFactory 14 路分发、LlmProviderPreset 14 预设、KVUtils.getLlmProvider 持久化,以及 values/strings.xml 英文兜底清洗 + values-zh 中文补齐。但用户侧 UI 仍是单 provider 配置,无法选择/切换 14 个 provider;同时 app 仅支持 en/zh/ja 三语,需扩展到 6 语(en/zh/ja/ko/es/pt)。本 spec 补齐剩余的用户侧改造,使整套 LLM Provider 与多语言能力一步到位、对用户可用。

## What Changes

### Phase A — LLM Provider UI 改造
- **LlmConfigActivity**:在页面顶部增加 Provider 选择器(Material AutoCompleteTextView 或 Spinner),展示 14 个 provider 的 `displayName`
- **每 provider 独立配置持久化**:apiKey/baseUrl/modelName 按 provider 维度分别存储(key 后缀 `_OPENAI`/`_GEMINI` 等),切换 provider 时自动加载该 provider 已保存配置
- **预设联动**:选择 provider 后,baseUrl 默认填入 `LlmProviderPreset.defaultBaseUrl`,modelName 默认填入 `LlmProviderPreset.defaultModel`,用户已自定义则保留自定义值
- **本地 provider 隐藏 API Key 输入**:OLLAMA/LMSTUDIO/LOCAL 的 `requiresApiKey=false`,隐藏 API Key 输入框与必填校验
- **旧配置向后兼容**:首次进入时,若 KVUtils 中已有旧的统一 apiKey/baseUrl/modelName,迁移为 OPENAI provider 的配置(只在 OPENAI slot 为空时迁移)
- **API Key 池保持不变**:池仍为全局共享(主 key 由当前 provider 的 apiKey 同步),不按 provider 拆分

### Phase B — 多语言扩展
- 新增 `values-ko/strings.xml`(韩语)、`values-es/strings.xml`(西班牙语)、`values-pt/strings.xml`(葡萄牙语)三个资源文件
- 翻译范围:values/strings.xml 中的全部 `<string>` key(英文兜底值),`app_name` 保留为 `Octopus Mobile`(品牌名)
- `app/build.gradle.kts` 的 `resourceConfigurations` 由 `setOf("en", "zh", "ja")` 扩展为 `setOf("en", "zh", "ja", "ko", "es", "pt")`
- 同时新增 Phase A 引入的新 key(provider_selector_label / provider_desc_* / language_*)到 6 个语言文件

### Phase C — 语言切换 UI
- **SettingsActivity**:在「通用」分组下新增「语言 / Language」菜单项,展示当前语言
- **语言选择对话框**:提供 7 个选项 — 跟随系统 / English / 简体中文 / 日本語 / 한국어 / Español / Português
- **应用语言**:使用 `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))` 即时切换,无需重启 Activity
- **持久化**:用户选择保存到 KVUtils(`KEY_APP_LANGUAGE`,值如 `""`(跟随系统)/`en`/`zh`/`ja`/`ko`/`es`/`pt`)
- **启动应用语言恢复**:在 `ClawApplication.onCreate` 中读取 KVUtils 中保存的语言 tag,若非空则调用 `AppCompatDelegate.setApplicationLocales` 应用

## Impact

### Affected code
- **新增**:
  - `app/src/main/res/values-ko/strings.xml`
  - `app/src/main/res/values-es/strings.xml`
  - `app/src/main/res/values-pt/strings.xml`
- **修改**:
  - `app/src/main/java/com/apk/claw/android/ui/settings/LlmConfigActivity.kt`(Provider 选择器 + 每 provider 持久化)
  - `app/src/main/res/layout/activity_llm_config.xml`(顶部加 Provider 选择器行)
  - `app/src/main/java/com/apk/claw/android/utils/KVUtils.kt`(新增 `getLlmApiKey(provider)` 等 per-provider 存取方法 + `KEY_APP_LANGUAGE` 存取)
  - `app/src/main/java/com/apk/claw/android/ui/settings/SettingsActivity.kt`(新增「语言」菜单项)
  - `app/src/main/java/com/apk/claw/android/ClawApplication.kt`(启动时恢复语言)
  - `app/src/main/res/values/strings.xml` + `values-zh` + `values-ja`(新增 provider_selector_label / language_* 等 key)
  - `app/build.gradle.kts`(resourceConfigurations 扩展)

### Out of scope(后续 spec 处理)
- Gradle 模块拆分(:core/:native/:tool/:channel/:agent/:app)— 风险大、不阻塞功能,留待独立 spec

## ADDED Requirements

### Requirement: Provider 选择器
The system SHALL provide a provider selector at the top of LlmConfigActivity, listing all 14 LlmProvider values with their `displayName`. When user selects a provider, the form SHALL load that provider's saved apiKey/baseUrl/modelName (or defaults from LlmProviderPreset if not yet customized).

#### Scenario: 用户首次进入选择 Gemini
- **WHEN** 用户首次进入 LlmConfigActivity,顶部 Provider 选择器默认选中 OPENAI
- **AND** 用户点开下拉,选择 "Google Gemini"
- **THEN** baseUrl 字段保持空(Gemini 无 defaultBaseUrl)
- **AND** modelName 字段自动填入 `gemini-2.0-flash`
- **AND** apiKey 字段为空(尚未配置过 Gemini)

#### Scenario: 切换回已配置的 provider
- **WHEN** 用户曾为 DeepSeek 配置过 apiKey=sk-xxx、modelName=deepseek-chat
- **AND** 用户从 OPENAI 切换回 DeepSeek
- **THEN** apiKey/baseUrl/modelName 字段自动恢复为 DeepSeek 已保存的值

### Requirement: 本地 provider 隐藏 API Key
The system SHALL hide API Key input row and skip API Key required validation when the selected provider's `requiresApiKey` is false (OLLAMA / LMSTUDIO / LOCAL).

#### Scenario: 选择 Ollama
- **WHEN** 用户选择 Ollama provider(`requiresApiKey=false`)
- **THEN** API Key 输入行隐藏
- **AND** 点击保存时,不再校验 apiKey 非空

### Requirement: 每 provider 独立配置持久化
The system SHALL persist apiKey/baseUrl/modelName per provider in KVUtils with provider-suffixed keys. The active provider itself is persisted via `KEY_LLM_PROVIDER`.

#### Scenario: 保存 DeepSeek 配置
- **WHEN** 用户在 DeepSeek slot 下填入 apiKey=sk-abc, baseUrl=默认, modelName=deepseek-chat,点击保存
- **THEN** KVUtils 写入 `KEY_LLM_API_KEY_DEEPSEEK=sk-abc`、`KEY_LLM_BASE_URL_DEEPSEEK=https://api.deepseek.com/v1`、`KEY_LLM_MODEL_NAME_DEEPSEEK=deepseek-chat`、`KEY_LLM_PROVIDER=DEEPSEEK`
- **AND** AgentConfig 使用 DeepSeek 的配置重新初始化

### Requirement: 旧配置向后兼容
The system SHALL migrate legacy single-config (KEY_LLM_API_KEY / KEY_LLM_BASE_URL / KEY_LLM_MODEL_NAME without provider suffix) to OPENAI slot on first launch, only when OPENAI slot is empty.

#### Scenario: 老用户首次升级
- **GIVEN** KVUtils 中存在旧的 `KEY_LLM_API_KEY=sk-legacy` 但 `KEY_LLM_API_KEY_OPENAI` 不存在
- **WHEN** 用户进入 LlmConfigActivity
- **THEN** 一次性迁移:`KEY_LLM_API_KEY_OPENAI=sk-legacy`、`KEY_LLM_BASE_URL_OPENAI=<旧值>`、`KEY_LLM_MODEL_NAME_OPENAI=<旧值>`
- **AND** `KEY_LLM_PROVIDER=OPENAI`(若未设置)
- **AND** 旧 key 保留不删(避免回滚风险)

### Requirement: 多语言资源
The system SHALL provide string resources for 6 locales: en(default) / zh / ja / ko / es / pt. All `<string>` keys defined in values/strings.xml SHALL have corresponding translations in values-zh / values-ja / values-ko / values-es / values-pt.

#### Scenario: 韩语用户启动
- **WHEN** 系统语言为韩语,用户启动 app
- **THEN** 所有 UI 文本从 values-ko/strings.xml 加载
- **AND** `app_name` 显示为 `Octopus Mobile`(品牌名不翻译)

### Requirement: 语言切换 UI
The system SHALL provide a "Language" menu item in SettingsActivity under the "General" group. Clicking it shows a dialog with 7 options: Follow system / English / 简体中文 / 日本語 / 한국어 / Español / Português. Selecting an option applies the locale immediately via AppCompatDelegate.setApplicationLocales and persists the choice.

#### Scenario: 用户从中文切换到英语
- **WHEN** 用户在 SettingsActivity 点击「语言」→ 选择 "English"
- **THEN** AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) 被调用
- **AND** 所有 Activity 立即重建并切换为英文显示
- **AND** KVUtils 保存 `KEY_APP_LANGUAGE=en`

#### Scenario: 跟随系统
- **WHEN** 用户选择「跟随系统」
- **THEN** AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList()) 被调用
- **AND** KVUtils 保存 `KEY_APP_LANGUAGE=""`(空字符串)

### Requirement: 启动恢复语言
The system SHALL read the persisted language tag from KVUtils in ClawApplication.onCreate and apply it via AppCompatDelegate.setApplicationLocales before any Activity is created.

#### Scenario: 重启后恢复
- **GIVEN** 用户上次选择了 Español,KVUtils 中 `KEY_APP_LANGUAGE=es`
- **WHEN** 用户杀进程后重新启动 app
- **THEN** ClawApplication.onCreate 调用 setApplicationLocales(es)
- **AND** 首个 Activity 直接以西语显示,无英文闪烁

## MODIFIED Requirements

### Requirement: KVUtils LLM 配置存取
原 `getLlmApiKey()` / `setLlmApiKey(value)` 保留作为「当前 provider」的便捷方法(内部读取 `KEY_LLM_PROVIDER` 后转发到 per-provider 方法)。新增 per-provider 重载:
- `getLlmApiKey(provider: LlmProvider): String`
- `setLlmApiKey(provider: LlmProvider, value: String)`
- `getLlmBaseUrl(provider: LlmProvider): String`
- `setLlmBaseUrl(provider: LlmProvider, value: String)`
- `getLlmModelName(provider: LlmProvider): String`
- `setLlmModelName(provider: LlmProvider, value: String)`
- `getAppLanguage(): String`(返回 "" 表示跟随系统)
- `setAppLanguage(tag: String)`

## REMOVED Requirements

(无移除项 — 全部为增量改造,旧 API 保持向后兼容)
