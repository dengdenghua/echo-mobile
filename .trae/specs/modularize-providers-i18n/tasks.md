# Tasks

> 当前已落地(前期已完成,无需再做):
> - LlmProvider 枚举扩展(14 值)— `AgentConfig.kt`
> - GeminiLlmClient / LlmClientFactory 14 路分发 — `llm/GeminiLlmClient.kt`、`llm/LlmClientFactory.kt`
> - LlmProviderPreset(14 预设)— `llm/LlmProviderPreset.kt`
> - KVUtils.getLlmProvider/setLlmProvider + KEY_LLM_PROVIDER
> - build.gradle.kts: langchain4j-google-ai-gemini 已加;libs.versions.toml 已加版本项
> - values/strings.xml 英文兜底已清洗;values-zh/strings.xml 中文已补齐

---

## Phase A — LLM Provider UI 改造

- [ ] Task 1: KVUtils 新增 per-provider 持久化 + 语言持久化
  - [ ] 新增 `getLlmApiKey(provider: LlmProvider)` / `setLlmApiKey(provider, value)`(key = `KEY_LLM_API_KEY_${provider.name}`)
  - [ ] 新增 `getLlmBaseUrl(provider)` / `setLlmBaseUrl(provider, value)`(key = `KEY_LLM_BASE_URL_${provider.name}`)
  - [ ] 新增 `getLlmModelName(provider)` / `setLlmModelName(provider, value)`(key = `KEY_LLM_MODEL_NAME_${provider.name}`)
  - [ ] 旧的无后缀方法 `getLlmApiKey()` / `setLlmApiKey(value)` 等改为转发到「当前 provider」的 per-provider 方法(读取 `getLlmProvider()` 后转发)
  - [ ] 新增 `KEY_APP_LANGUAGE` 常量 + `getAppLanguage(): String`(默认返回 "") + `setAppLanguage(tag: String)`
  - [ ] 新增 `migrateLegacyLlmConfigIfNeeded()`:若 `KEY_LLM_API_KEY_OPENAI` 不存在且旧 `KEY_LLM_API_KEY` 非空,则把旧值拷贝到 `*_OPENAI` 系列 key(不删旧 key)

- [ ] Task 2: LlmConfigActivity UI 改造 — Provider 选择器 + 每 provider 持久化 + 旧配置迁移
  - [ ] `activity_llm_config.xml`:在 tvTip 之前/之后插入 Provider 选择器行(标签 TextView + MaterialAutoCompleteTextView 或 Spinner),id `spProvider`
  - [ ] `LlmConfigActivity.onCreate`:
    - 调用 `KVUtils.migrateLegacyLlmConfigIfNeeded()`
    - 用 `LlmProviderPreset.presets` 填充选择器,默认选中 `KVUtils.getLlmProvider()`
    - 监听选择器变化:onProviderChanged(newProvider) → 加载该 provider 的 apiKey/baseUrl/modelName(若 per-provider slot 为空,则填入 preset 的 defaultBaseUrl/defaultModel)
    - 根据 `LlmProviderPreset.of(provider).requiresApiKey` 显示/隐藏 API Key 行;`requiresApiKey=false` 时跳过 apiKey 必填校验
  - [ ] `btnSave` 点击:写入 `setLlmApiKey(provider, ...)` / `setLlmBaseUrl(provider, ...)` / `setLlmModelName(provider, ...)` / `setLlmProvider(provider)`,其余逻辑(ApiKeyPool.syncPrimary、updateAgentConfig、initAgent、afterInit)保留
  - [ ] 视觉模型字段(visionApiKey/BaseUrl/ModelName)维持现有全局存储,不按 provider 拆分
  - [ ] 新增 strings:`provider_selector_label`、`provider_desc_<PROVIDER>`(14 条,可选)或在 UI 直接用 `LlmProviderPreset.description`

## Phase B — 多语言扩展

- [ ] Task 3: 新增 `app/src/main/res/values-ko/strings.xml`(韩语)
  - [ ] 翻译 values/strings.xml 中全部 `<string>` key 为韩语
  - [ ] `app_name` 保留为 `Octopus Mobile`
  - [ ] 包含 Phase A 新增的 key(provider_selector_label 等)
  - [ ] 文件以 `<resources>` 包裹,UTF-8 编码

- [ ] Task 4: 新增 `app/src/main/res/values-es/strings.xml`(西班牙语)
  - [ ] 同 Task 3,翻译为西班牙语

- [ ] Task 5: 新增 `app/src/main/res/values-pt/strings.xml`(葡萄牙语)
  - [ ] 同 Task 3,翻译为葡萄牙语(巴西葡语 pt-BR 兼容)

- [ ] Task 6: 补齐 Phase A 新增 key 到 values/ + values-zh/ + values-ja/
  - [ ] `provider_selector_label` → en: "Provider" / zh: "模型提供商" / ja: "プロバイダー"
  - [ ] `language_menu_title` → en: "Language" / zh: "语言" / ja: "言語"
  - [ ] `language_follow_system` → en: "Follow system" / zh: "跟随系统" / ja: "システムに従う"
  - [ ] `language_dialog_title` → en: "Select language" / zh: "选择语言" / ja: "言語を選択"

- [ ] Task 7: 更新 `app/build.gradle.kts` 的 `resourceConfigurations`
  - [ ] 由 `setOf("en", "zh", "ja")` 改为 `setOf("en", "zh", "ja", "ko", "es", "pt")`
  - [ ] 更新上方注释,说明新增 ko/es/pt 的原因

## Phase C — 语言切换 UI

- [ ] Task 8: SettingsActivity 新增「语言」菜单项
  - [ ] 在「通用」分组下新增 MenuItem,title=`@string/language_menu_title`,副标题展示当前语言名(跟随系统 / English / 简体中文 等)
  - [ ] 点击触发 `showLanguageDialog()`
  - [ ] `showLanguageDialog()`:AlertDialog 单选,7 个选项(跟随系统 / English / 简体中文 / 日本語 / 한국어 / Español / Português),默认选中当前 KVUtils.getAppLanguage() 对应项
  - [ ] 选中后:`KVUtils.setAppLanguage(tag)` + `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))`,tag 为空时传 `LocaleListCompat.getEmptyLocaleList()`
  - [ ] 更新菜单项副标题展示新语言名

- [ ] Task 9: ClawApplication 启动恢复语言
  - [ ] 在 `ClawApplication.onCreate` 中读取 `KVUtils.getAppLanguage()`
  - [ ] 若非空,调用 `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))`
  - [ ] 放在 super.onCreate() 之后、其他初始化之前

## Phase D — 验证

- [x] Task 10: 端到端验证(静态)
  - [x] Grep 验证 LlmClientFactory 仍分发 14 个 provider(✅)
  - [x] Grep 验证 LlmConfigActivity 引用 LlmProviderPreset 与 spProvider(✅)
  - [x] Grep 验证 KVUtils 含 `KEY_LLM_API_KEY_` / `KEY_APP_LANGUAGE`(✅)
  - [x] Grep 验证 SettingsActivity 含 `showLanguageDialog` / `setApplicationLocales`(✅)
  - [x] Grep 验证 ClawApplication 含 `setApplicationLocales` 与 `getAppLanguage`(✅)
  - [x] 验证 values-ko / values-es / values-pt 文件存在且根 tag 为 `<resources>`(✅)
  - [x] 验证 6 个 strings.xml 文件含相同 key 集合(✅ Task 11 补齐后 6 文件均为 1258 key,集合完全一致)
  - [x] 验证 app/build.gradle.kts resourceConfigurations 含 ko/es/pt(✅)

## Phase E — 历史遗留修复(由 Task 10 验证发现)

- [x] Task 11: 补齐 values-zh / values-ja 历史缺失 key(使 6 文件 key 集合完全一致)
  - [x] values-zh:补齐缺失的 48 个 key(browser_*/voice_* 系列,翻译为中文)
  - [x] values-ja:补齐缺失的 71 个 key(48 共享 + 23 独有 agent_record_*/userscript_*/wakeword_*,翻译为日文)
  - [x] 重新验证 6 文件 key 集合一致性(Python 校验 ALL EQUAL: True,1258 key 全对齐)

# Task Dependencies
- Task 2 depends on Task 1(KVUtils per-provider 方法先存在)
- Task 3/4/5 可与 Task 1/2 并行(只翻译现有 key + Phase A 新 key,Phase A 新 key 由 Task 6 统一补齐到 en/zh/ja,ko/es/pt 在 Task 3/4/5 中直接包含)
- Task 6 depends on Task 1/2(知道 Phase A 引入了哪些新 key)
- Task 7 独立,可与 Task 1-6 并行
- Task 8 depends on Task 1(getAppLanguage/setAppLanguage)+ Task 6(language_* strings)
- Task 9 depends on Task 1(getAppLanguage)
- Task 10 depends on all above

# 并行执行建议
- 第一批(并行):Task 1 + Task 3 + Task 4 + Task 5 + Task 7
- 第二批(并行):Task 2 + Task 6 + Task 8 + Task 9
- 第三批:Task 10 验证
