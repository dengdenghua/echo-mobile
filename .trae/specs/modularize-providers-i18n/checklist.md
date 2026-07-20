# Checklist

## Phase A — LLM Provider UI 改造
- [x] KVUtils 含 `getLlmApiKey(provider: LlmProvider)` / `setLlmApiKey(provider, value)` 方法
- [x] KVUtils 含 `getLlmBaseUrl(provider)` / `setLlmBaseUrl(provider, value)` 方法
- [x] KVUtils 含 `getLlmModelName(provider)` / `setLlmModelName(provider, value)` 方法
- [x] KVUtils 旧的无参 `getLlmApiKey()` / `setLlmApiKey(value)` 转发到当前 provider 的 per-provider 方法
- [x] KVUtils 含 `KEY_APP_LANGUAGE` 常量 + `getAppLanguage()` / `setAppLanguage(tag)` 方法
- [x] KVUtils 含 `migrateLegacyLlmConfigIfNeeded()` 方法,实现「旧 key 迁移到 OPENAI slot,只在 OPENAI slot 为空时」逻辑
- [x] activity_llm_config.xml 含 `spProvider` id 的 Provider 选择器
- [x] LlmConfigActivity.onCreate 调用 `migrateLegacyLlmConfigIfNeeded()`
- [x] LlmConfigActivity 用 `LlmProviderPreset.presets` 填充选择器
- [x] LlmConfigActivity 监听 provider 变化并加载该 provider 的配置
- [x] LlmConfigActivity 根据 `requiresApiKey` 显示/隐藏 API Key 输入行
- [x] LlmConfigActivity `requiresApiKey=false` 时跳过 apiKey 必填校验
- [x] btnSave 点击时调用 per-provider 的 set 方法 + `setLlmProvider(provider)`
- [x] 视觉模型字段(visionApiKey/BaseUrl/ModelName)维持全局存储,未拆分

## Phase B — 多语言扩展
- [x] `app/src/main/res/values-ko/strings.xml` 存在,根 tag 为 `<resources>`,UTF-8 编码
- [x] `app/src/main/res/values-es/strings.xml` 存在,根 tag 为 `<resources>`,UTF-8 编码
- [x] `app/src/main/res/values-pt/strings.xml` 存在,根 tag 为 `<resources>`,UTF-8 编码
- [x] 三个新语言文件中 `app_name` 值均为 `Octopus Mobile`
- [x] 6 个 strings.xml 文件(en/zh/ja/ko/es/pt)的 `<string>` key 集合完全一致(1258 key,Task 11 补齐 zh 48 + ja 71 历史缺失)
- [x] `app/build.gradle.kts` 的 `resourceConfigurations` 含 `ko`、`es`、`pt`
- [x] values/、values-zh/、values-ja/ 含 Phase A 新增 key:provider_selector_label / language_menu_title / language_follow_system / language_dialog_title

## Phase C — 语言切换 UI
- [x] SettingsActivity 含「语言」菜单项,title 引用 `@string/language_menu_title`
- [x] 菜单项副标题展示当前语言名(跟随系统 / English / 简体中文 等)
- [x] SettingsActivity 含 `showLanguageDialog()` 方法
- [x] showLanguageDialog 提供 7 个选项(跟随系统 / English / 简体中文 / 日本語 / 한국어 / Español / Português)
- [x] 选中后调用 `KVUtils.setAppLanguage(tag)` + `AppCompatDelegate.setApplicationLocales(...)`
- [x] tag 为空时使用 `LocaleListCompat.getEmptyLocaleList()`
- [x] ClawApplication.onCreate 读取 `KVUtils.getAppLanguage()` 并在非空时调用 `setApplicationLocales`
- [x] ClawApplication 的语言恢复逻辑放在 super.onCreate() 之后

## Phase D — 跨阶段一致性
- [x] LlmClientFactory 仍分发 14 个 provider(Grep `LlmProvider.` 出现 14 次)
- [x] LlmProviderPreset.presets 仍为 14 项
- [x] GeminiLlmClient 仍存在且 supportsVision=true
- [x] 无 import 缺失或符号引用错误(静态扫描)
- [x] 未引入对未存在 string key 的引用(Grep `@string/` 与 6 个 strings.xml 比对)
