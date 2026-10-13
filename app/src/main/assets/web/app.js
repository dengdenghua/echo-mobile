// ====== i18n ======
const i18n = {
  zh: {
    title: 'Echo Mobile 配置',
    subtitle: '在此页面配置 LLM 与钉钉、飞书、QQ、Discord、Telegram 的应用凭证',
    loading: '加载中...',
    llm_title: 'LLM 配置', llm_api_key: 'API Key', llm_base_url: 'Base URL', llm_model_name: '模型名称',
    ph_llm_api_key: '如 sk-xxx（留空表示不修改）',
    ph_llm_base_url: '如 https://api.openai.com/v1（留空使用默认）',
    ph_llm_model: '如 gpt-4o、gpt-4o-mini',
    llm_tip: 'API Key 仅保存在设备本地，可通过设置 → LLM 配置 在应用内修改',
    load_failed: '加载失败，请检查连接',
    dingtalk: '钉钉', feishu: '飞书',
    ph_dingtalk_id: '输入 Client ID', ph_dingtalk_secret: '输入 Client Secret',
    ph_feishu_id: '输入 App ID', ph_feishu_secret: '输入 App Secret',
    ph_qq_id: '输入 QQ 机器人 App ID', ph_qq_secret: '输入 App Secret',
    ph_discord_token: '输入 Discord Bot Token',
    discord_tip: '需在 Discord Developer Portal 开启 Message Content Intent',
    ph_telegram_token: '输入 Telegram Bot Token',
    telegram_tip: '通过 @BotFather 创建 Bot 并获取 Token',
    toggle_visibility: '显示/隐藏',
    save_btn: '保存配置', saving: '保存中...',
    save_success: '配置已保存，通道将自动重连',
    save_failed: '保存失败', unknown_error: '未知错误',
    err_required: '此字段不能为空', err_url: '请输入有效的 URL',
    nav_title: '快速入口', nav_console: '网页遥控台', nav_debug: '调试控制台',
    nav_remote: '远程桌面', nav_tip: '提示：远程访问需携带 token，URL 形如 .../console.html#token=xxx',
    open_console: '打开控制台',
  },
  en: {
    title: 'Echo Mobile Config',
    subtitle: 'Configure LLM and credentials for DingTalk, Feishu, QQ, Discord, Telegram',
    loading: 'Loading...',
    llm_title: 'LLM Config', llm_api_key: 'API Key', llm_base_url: 'Base URL', llm_model_name: 'Model Name',
    ph_llm_api_key: 'e.g. sk-xxx (leave empty to keep current)',
    ph_llm_base_url: 'e.g. https://api.openai.com/v1 (empty = default)',
    ph_llm_model: 'e.g. gpt-4o, gpt-4o-mini',
    llm_tip: 'API Key is stored locally only. You can also edit in Settings \u2192 LLM Config',
    load_failed: 'Failed to load, please check connection',
    dingtalk: 'DingTalk', feishu: 'Feishu (Lark)',
    ph_dingtalk_id: 'Enter Client ID', ph_dingtalk_secret: 'Enter Client Secret',
    ph_feishu_id: 'Enter App ID', ph_feishu_secret: 'Enter App Secret',
    ph_qq_id: 'Enter QQ Bot App ID', ph_qq_secret: 'Enter App Secret',
    ph_discord_token: 'Enter Discord Bot Token',
    discord_tip: 'Enable Message Content Intent in Discord Developer Portal',
    ph_telegram_token: 'Enter Telegram Bot Token',
    telegram_tip: 'Create a bot via @BotFather and get the token',
    toggle_visibility: 'Show/Hide',
    save_btn: 'Save', saving: 'Saving...',
    save_success: 'Saved. Channels will reconnect automatically.',
    save_failed: 'Save failed', unknown_error: 'Unknown error',
    err_required: 'This field is required', err_url: 'Please enter a valid URL',
    nav_title: 'Quick Access', nav_console: 'Web Console', nav_debug: 'Debug Console',
    nav_remote: 'Remote Desktop', nav_tip: 'Tip: Remote access requires a token, URL like .../console.html#token=xxx',
    open_console: 'Open Console',
  },
  ja: {
    title: 'Echo Mobile 設定',
    subtitle: 'LLM と DingTalk、Feishu、QQ、Discord、Telegram の資格情報を設定',
    loading: '読み込み中...',
    llm_title: 'LLM 設定', llm_api_key: 'API Key', llm_base_url: 'Base URL', llm_model_name: 'モデル名',
    ph_llm_api_key: '例: sk-xxx（空欄で現状維持）',
    ph_llm_base_url: '例: https://api.openai.com/v1（空欄でデフォルト）',
    ph_llm_model: '例: gpt-4o、gpt-4o-mini',
    llm_tip: 'API Key は端末内にのみ保存されます。設定 \u2192 LLM 設定 からも変更可能',
    load_failed: '読み込みに失敗しました。接続を確認してください',
    dingtalk: 'DingTalk', feishu: 'Feishu (Lark)',
    ph_dingtalk_id: 'Client ID を入力', ph_dingtalk_secret: 'Client Secret を入力',
    ph_feishu_id: 'App ID を入力', ph_feishu_secret: 'App Secret を入力',
    ph_qq_id: 'QQ ボット App ID を入力', ph_qq_secret: 'App Secret を入力',
    ph_discord_token: 'Discord Bot Token を入力',
    discord_tip: 'Discord Developer Portal で Message Content Intent を有効にしてください',
    ph_telegram_token: 'Telegram Bot Token を入力',
    telegram_tip: '@BotFather で Bot を作成して Token を取得してください',
    toggle_visibility: '表示/非表示',
    save_btn: '保存', saving: '保存中...',
    save_success: '設定を保存しました。チャネルは自動的に再接続されます。',
    save_failed: '保存に失敗しました', unknown_error: '不明なエラー',
    err_required: 'この項目は必須です', err_url: '有効な URL を入力してください',
    nav_title: 'クイックアクセス', nav_console: 'ウェブコンソール', nav_debug: 'デバッグコンソール',
    nav_remote: 'リモートデスクトップ', nav_tip: 'ヒント：リモートアクセスにはトークンが必要、URL は .../console.html#token=xxx',
    open_console: 'コンソールを開く',
  },
};

function detectLang() {
  const lang = (navigator.language || navigator.userLanguage || 'en').toLowerCase();
  if (lang.startsWith('zh')) return 'zh';
  if (lang.startsWith('ja')) return 'ja';
  return 'en';
}

const currentLang = detectLang();
const t = (key) => (i18n[currentLang] || i18n['en'])[key] || i18n['en'][key] || key;

function applyI18n() {
  document.documentElement.lang = currentLang === 'zh' ? 'zh-CN' : currentLang;
  document.title = t('title');
  document.querySelectorAll('[data-i18n]').forEach(el => {
    el.textContent = t(el.dataset.i18n);
  });
  document.querySelectorAll('[data-i18n-placeholder]').forEach(el => {
    el.placeholder = t(el.dataset.i18nPlaceholder);
  });
  document.querySelectorAll('[data-i18n-title]').forEach(el => {
    el.title = t(el.dataset.i18nTitle);
  });
}

// ====== Icons ======
const eyeOpen = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>';
const eyeClosed = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/><line x1="1" y1="1" x2="23" y2="23"/></svg>';

function togglePassword(inputId, btn) {
  const input = document.getElementById(inputId);
  if (input.type === 'password') {
    input.type = 'text';
    btn.innerHTML = eyeOpen;
  } else {
    input.type = 'password';
    btn.innerHTML = eyeClosed;
  }
}

// ====== Card Configuration ======
const cardConfigs = [
  {
    id: 'llm', type: 'llm',
    titleKey: 'llm_title',
    fields: [
      { id: 'llmApiKey', labelKey: 'llm_api_key', placeholderKey: 'ph_llm_api_key', type: 'password', toggle: true },
      { id: 'llmBaseUrl', labelKey: 'llm_base_url', placeholderKey: 'ph_llm_base_url', type: 'text', validate: 'url' },
      { id: 'llmModelName', labelKey: 'llm_model_name', placeholderKey: 'ph_llm_model', type: 'text' },
    ],
    tipKey: 'llm_tip',
  },
  {
    id: 'dingtalk', type: 'dingtalk',
    titleKey: 'dingtalk',
    fields: [
      { id: 'dingtalkAppKey', labelKey: 'dingtalk_id', labelFallback: 'Client ID', placeholderKey: 'ph_dingtalk_id', type: 'text' },
      { id: 'dingtalkAppSecret', labelKey: 'dingtalk_secret', labelFallback: 'Client Secret', placeholderKey: 'ph_dingtalk_secret', type: 'password', toggle: true },
    ],
  },
  {
    id: 'feishu', type: 'feishu',
    titleKey: 'feishu',
    fields: [
      { id: 'feishuAppId', labelKey: 'feishu_id', labelFallback: 'App ID', placeholderKey: 'ph_feishu_id', type: 'text' },
      { id: 'feishuAppSecret', labelKey: 'feishu_secret', labelFallback: 'App Secret', placeholderKey: 'ph_feishu_secret', type: 'password', toggle: true },
    ],
  },
  {
    id: 'qq', type: 'qq',
    titleKey: 'qq', titleFallback: 'QQ',
    fields: [
      { id: 'qqAppId', labelKey: 'qq_id', labelFallback: 'App ID', placeholderKey: 'ph_qq_id', type: 'text' },
      { id: 'qqAppSecret', labelKey: 'qq_secret', labelFallback: 'App Secret', placeholderKey: 'ph_qq_secret', type: 'password', toggle: true },
    ],
  },
  {
    id: 'discord', type: 'discord',
    titleKey: 'discord', titleFallback: 'Discord',
    fields: [
      { id: 'discordBotToken', labelKey: 'discord_token', labelFallback: 'Bot Token', placeholderKey: 'ph_discord_token', type: 'password', toggle: true },
    ],
    tipKey: 'discord_tip',
  },
  {
    id: 'telegram', type: 'telegram',
    titleKey: 'telegram', titleFallback: 'Telegram',
    fields: [
      { id: 'telegramBotToken', labelKey: 'telegram_token', labelFallback: 'Bot Token', placeholderKey: 'ph_telegram_token', type: 'password', toggle: true },
    ],
    tipKey: 'telegram_tip',
  },
];

// ====== Render Cards ======
function renderCards() {
  const container = document.getElementById('content');
  cardConfigs.forEach(card => {
    const div = document.createElement('div');
    div.className = `card ${card.type}`;
    div.innerHTML = `
      <div class="card-title">
        <span class="dot"></span>
        <span data-i18n="${card.titleKey}">${card.titleFallback || ''}</span>
      </div>
      ${card.fields.map(f => `
        <div class="field">
          <label data-i18n="${f.labelKey}">${f.labelFallback || ''}</label>
          ${f.toggle ? `
            <div class="input-wrap">
              <input type="${f.type}" id="${f.id}" data-i18n-placeholder="${f.placeholderKey}" autocomplete="off"
                     ${f.validate === 'url' ? 'data-validate="url"' : ''}>
              <button class="toggle-eye" data-target="${f.id}" data-i18n-title="toggle_visibility">${eyeClosed}</button>
            </div>
          ` : `
            <input type="${f.type}" id="${f.id}" data-i18n-placeholder="${f.placeholderKey}" autocomplete="off"
                   ${f.validate === 'url' ? 'data-validate="url"' : ''}>
          `}
          <div class="field-error" id="${f.id}-error"></div>
        </div>
      `).join('')}
      ${card.tipKey ? `<p class="card-tip" data-i18n="${card.tipKey}"></p>` : ''}
    `;
    container.appendChild(div);
  });
  // 重排：把 saveBtn 移到末尾，再把 nav-card 移到 saveBtn 之前
  // 静态 HTML 顺序是 nav-card → saveBtn，appendChild 会把 cards 追加到末尾，
  // 这样最终 DOM 顺序就是 cards → nav-card → saveBtn，符合视觉预期。
  const saveBtn = document.getElementById('saveBtn');
  const navCard = container.querySelector('.nav-card');
  if (saveBtn) container.appendChild(saveBtn);
  if (navCard && saveBtn) container.insertBefore(navCard, saveBtn);
}

// ====== Skeleton Loading ======
function renderSkeleton() {
  const container = document.getElementById('loading');
  const cardCount = 6;
  let html = '';
  for (let i = 0; i < cardCount; i++) {
    const fieldCount = i === 0 ? 3 : (i >= 4 ? 1 : 2);
    html += `<div class="skeleton-card">
      <div class="skeleton-line title-line"></div>`;
    for (let j = 0; j < fieldCount; j++) {
      html += `<div class="skeleton-line input-line"></div>`;
    }
    html += `</div>`;
  }
  html += `<div class="skeleton-card"><div class="skeleton-line btn-line"></div></div>`;
  container.innerHTML = html;
}

// ====== Form Validation ======
function clearErrors() {
  document.querySelectorAll('.field-error').forEach(el => {
    el.textContent = '';
    el.classList.remove('visible');
  });
  document.querySelectorAll('.input-error').forEach(el => {
    el.classList.remove('input-error');
  });
}

function showFieldError(fieldId, message) {
  const errEl = document.getElementById(fieldId + '-error');
  const inputEl = document.getElementById(fieldId);
  if (errEl) {
    errEl.textContent = message;
    errEl.classList.add('visible');
  }
  if (inputEl) {
    inputEl.classList.add('input-error');
  }
}

function validateForm() {
  clearErrors();
  let valid = true;

  // Validate LLM API Key (non-empty, reasonable length)
  const apiKey = document.getElementById('llmApiKey');
  if (apiKey && apiKey.value.trim()) {
    const val = apiKey.value.trim();
    if (val.length < 8) {
      showFieldError('llmApiKey', t('err_required'));
      valid = false;
    }
  }

  // Validate Base URL format (must be valid URL if provided)
  const baseUrl = document.getElementById('llmBaseUrl');
  if (baseUrl && baseUrl.value.trim()) {
    const val = baseUrl.value.trim();
    try {
      const url = new URL(val);
      if (url.protocol !== 'http:' && url.protocol !== 'https:') {
        showFieldError('llmBaseUrl', t('err_url'));
        valid = false;
      }
    } catch {
      showFieldError('llmBaseUrl', t('err_url'));
      valid = false;
    }
  }

  return valid;
}

// ====== API ======
const channelFieldIds = ['dingtalkAppKey', 'dingtalkAppSecret', 'feishuAppId', 'feishuAppSecret', 'qqAppId', 'qqAppSecret', 'discordBotToken', 'telegramBotToken'];
const llmFieldIds = ['llmApiKey', 'llmBaseUrl', 'llmModelName'];
const sensitiveFieldIds = cardConfigs
  .flatMap(c => c.fields)
  .filter(f => f.type === 'password')
  .map(f => f.id);
let isSaving = false;

function maskLast4(value) {
  const v = String(value || '');
  if (v.length <= 4) return v;
  return '*'.repeat(v.length - 4) + v.slice(-4);
}

function applyMask() {
  sensitiveFieldIds.forEach(id => {
    const el = document.getElementById(id);
    if (el && el.value) {
      el.dataset.original = el.value;
      el.value = maskLast4(el.value);
    }
  });
}

function getFieldValue(id) {
  const el = document.getElementById(id);
  if (!el) return '';
  const trimmed = el.value.trim();
  if (!trimmed) {
    // 留空表示不修改：敏感字段保留原始值
    return el.dataset.original || '';
  }
  if (sensitiveFieldIds.includes(id) && el.dataset.original && trimmed === maskLast4(el.dataset.original)) {
    return el.dataset.original;
  }
  return trimmed;
}

async function load() {
  try {
    const [chanRes, llmRes] = await Promise.all([fetch('/api/channels'), fetch('/api/llm')]);
    const chanJson = await chanRes.json();
    const llmJson = await llmRes.json();
    if (chanJson.code === 0 && chanJson.data) {
      channelFieldIds.forEach(f => {
        const el = document.getElementById(f);
        if (el && chanJson.data[f]) el.value = chanJson.data[f];
      });
    }
    if (llmJson.code === 0 && llmJson.data) {
      llmFieldIds.forEach(f => {
        const el = document.getElementById(f);
        if (el && llmJson.data[f] !== undefined) el.value = llmJson.data[f] || '';
      });
    }
    applyMask();
    document.getElementById('loading').style.display = 'none';
    document.getElementById('content').style.display = 'block';
  } catch (e) {
    document.getElementById('loading').textContent = t('load_failed');
  }
}

async function save() {
  if (isSaving || !validateForm()) return;
  isSaving = true;

  const btn = document.getElementById('saveBtn');
  btn.disabled = true;
  btn.textContent = t('saving');
  try {
    const chanBody = {};
    channelFieldIds.forEach(f => {
      chanBody[f] = getFieldValue(f);
    });
    const llmBody = {};
    llmFieldIds.forEach(f => {
      llmBody[f] = getFieldValue(f);
    });
    const [chanRes, llmRes] = await Promise.all([
      fetch('/api/channels', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(chanBody) }),
      fetch('/api/llm', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(llmBody) })
    ]);
    const chanJson = await chanRes.json();
    const llmJson = await llmRes.json();
    if (chanJson.code === 0 && llmJson.code === 0) {
      showToast(t('save_success') + ' · <a class="toast-link" href="console.html" target="_blank" rel="noopener">' + t('open_console') + ' →</a>', 'success', 5000);
      setTimeout(load, 500);
    } else {
      const msg = chanJson.code !== 0 ? chanJson.message : llmJson.message;
      showToast(t('save_failed') + ': ' + (msg || t('unknown_error')), 'error');
    }
  } catch (e) {
    showToast(t('save_failed') + ': ' + e.message, 'error');
  } finally {
    isSaving = false;
    btn.disabled = false;
    btn.textContent = t('save_btn');
  }
}

function showToast(msg, type, duration) {
  const el = document.getElementById('toast');
  el.innerHTML = msg;
  el.className = 'toast ' + type;
  requestAnimationFrame(() => { el.classList.add('show'); });
  setTimeout(() => { el.classList.remove('show'); }, duration || 2500);
}

// ====== Init ======
renderSkeleton();
renderCards();
applyI18n();

// 绑定保存按钮（避免 HTML 内联 onclick，兼容 CSP）
document.getElementById('saveBtn').addEventListener('click', save);

// 绑定密码显隐切换（事件委托，避免内联 onclick）
document.getElementById('content').addEventListener('click', function (e) {
  const btn = e.target.closest('.toggle-eye');
  if (!btn) return;
  const inputId = btn.dataset.target;
  if (inputId) togglePassword(inputId, btn);
});

load();
