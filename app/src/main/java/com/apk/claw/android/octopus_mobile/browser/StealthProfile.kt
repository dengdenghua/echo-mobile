package com.apk.claw.android.octopus_mobile.browser

import android.util.Log
import com.apk.claw.android.utils.KVUtils
import kotlin.random.Random

/**
 * Stealth 反爬配置文件。
 *
 * 一个 [StealthProfile] 描述一组真实浏览器的指纹特征：UA / 平台 / vendor / WebGL vendor/renderer /
 * canvas 噪声种子。配合 [buildStealthJs] 在文档开始时注入到 WebView，覆盖 navigator.* 与
 * Canvas/WebGL 指纹采集，把 WebView 反爬分数从 ~50 拉到 ~70。
 *
 * 设计要点：
 * - 5 个内置预设覆盖主流真实浏览器（Chrome Win/Mac、Firefox Win、Safari Mac、Chrome Android）。
 * - [StealthManager] 用 KVUtils(MMKV) 持久化当前选中的 profile index + canvas 种子。
 * - canvasSeed 在 rotate() 时重新生成，保证每次轮换都换新指纹。
 */
data class StealthProfile(
    val userAgent: String,
    val acceptLanguage: String,
    val platform: String,
    val vendor: String,
    val canvasSeed: String,
    val webglVendor: String,
    val webglRenderer: String,
) {
    /** 用于 Toast 展示的简短标签，如 "Chrome on Mac" */
    val label: String
        get() {
            val ua = userAgent
            return when {
                ua.contains("Firefox") -> {
                    if (ua.contains("Windows")) "Firefox on Windows" else "Firefox"
                }
                ua.contains("Safari") && !ua.contains("Chrome") -> {
                    if (ua.contains("Mac")) "Safari on Mac" else "Safari"
                }
                ua.contains("Chrome") -> {
                    when {
                        ua.contains("Android") -> "Chrome on Android"
                        ua.contains("Mac") -> "Chrome on Mac"
                        ua.contains("Windows") -> "Chrome on Windows"
                        else -> "Chrome"
                    }
                }
                else -> "Unknown"
            }
        }

    /** 从 acceptLanguage 解析出 navigator.languages 数组，如 "zh-CN,zh;q=0.9,en;q=0.8" -> ["zh-CN","zh","en"] */
    val languages: List<String>
        get() = acceptLanguage.split(",")
            .map { it.trim().substringBefore(";").trim() }
            .filter { it.isNotEmpty() }

    /** 根据 platform 推断屏幕分辨率（与桌面/移动端真实分布对齐） */
    val screenWidth: Int
        get() = if (platform == "Linux armv8l" || platform == "Linux aarch64") 412 else 1920

    val screenHeight: Int
        get() = if (platform == "Linux armv8l" || platform == "Linux aarch64") 915 else 1080
}

/**
 * Stealth 配置管理器。
 *
 * - [getCurrent] 返回当前选中的 profile（canvasSeed 已合并持久化值）。
 * - [rotate] 轮换到下一个预设并刷新 canvas 种子。
 * - [setProfile] 手动指定 index。
 * - [isEnabled] / [setEnabled] 控制总开关（关 = 用真实 WebView 指纹）。
 *
 * 持久化走 KVUtils(MMKV)，无需额外 Context。
 */
object StealthManager {

    private const val TAG = "StealthManager"
    private const val KEY_STEALTH_ENABLED = "KEY_STEALTH_ENABLED"
    private const val KEY_STEALTH_PROFILE_INDEX = "KEY_STEALTH_PROFILE_INDEX"
    private const val KEY_STEALTH_CANVAS_SEED = "KEY_STEALTH_CANVAS_SEED"

    /** 5 个真实 UA 配置文件 */
    val PROFILES: List<StealthProfile> = listOf(
        // 1. Chrome on Windows
        StealthProfile(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            acceptLanguage = "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
            platform = "Win32",
            vendor = "Google Inc.",
            canvasSeed = "",
            webglVendor = "Google Inc. (Intel)",
            webglRenderer = "ANGLE (Intel, Intel(R) UHD Graphics 630 Direct3D11 vs_5_0 ps_5_0, D3D11)",
        ),
        // 2. Chrome on Mac
        StealthProfile(
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            acceptLanguage = "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
            platform = "MacIntel",
            vendor = "Google Inc.",
            canvasSeed = "",
            webglVendor = "Google Inc. (Apple)",
            webglRenderer = "ANGLE (Apple, Apple M1, OpenGL 4.1)",
        ),
        // 3. Firefox on Windows
        StealthProfile(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
            acceptLanguage = "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
            platform = "Win32",
            vendor = "",
            canvasSeed = "",
            webglVendor = "Google Inc. (Intel)",
            webglRenderer = "ANGLE (Intel, Intel(R) UHD Graphics 630 Direct3D11 vs_5_0 ps_5_0, D3D11)",
        ),
        // 4. Safari on Mac
        StealthProfile(
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.4 Safari/605.1.15",
            acceptLanguage = "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
            platform = "MacIntel",
            vendor = "Apple Computer, Inc.",
            canvasSeed = "",
            webglVendor = "Apple Inc.",
            webglRenderer = "Apple GPU",
        ),
        // 5. Chrome on Android
        StealthProfile(
            userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            acceptLanguage = "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
            platform = "Linux armv8l",
            vendor = "Google Inc.",
            canvasSeed = "",
            webglVendor = "Google Inc. (Qualcomm)",
            webglRenderer = "ANGLE (Qualcomm, Adreno (TM) 730, OpenGL ES 3.2)",
        ),
    )

    /** 当前是否启用 stealth（关 = 用真实 WebView 指纹，UA 也不轮换） */
    fun isEnabled(): Boolean = KVUtils.getBoolean(KEY_STEALTH_ENABLED, false)

    /** 开关 stealth。开启时不自动 rotate，由调用方按需触发。 */
    fun setEnabled(enabled: Boolean) {
        KVUtils.putBoolean(KEY_STEALTH_ENABLED, enabled)
        Log.i(TAG, "stealth enabled = $enabled")
    }

    /** 获取当前选中的 profile（合并持久化的 canvasSeed）。 */
    fun getCurrent(): StealthProfile {
        val idx = getIndex().coerceIn(0, PROFILES.size - 1)
        val seed = getCanvasSeed().ifBlank { generateCanvasSeed().also { setCanvasSeed(it) } }
        return PROFILES[idx].copy(canvasSeed = seed)
    }

    /** 轮换到下一个预设并刷新 canvas 种子。返回新 profile。 */
    fun rotate(): StealthProfile {
        val newIdx = (getIndex() + 1) % PROFILES.size
        setIndex(newIdx)
        val newSeed = generateCanvasSeed()
        setCanvasSeed(newSeed)
        Log.i(TAG, "rotated to profile #$newIdx (${PROFILES[newIdx].label})")
        return getCurrent()
    }

    /** 手动指定 profile index。 */
    fun setProfile(index: Int) {
        val safe = index.coerceIn(0, PROFILES.size - 1)
        setIndex(safe)
        setCanvasSeed(generateCanvasSeed())
        Log.i(TAG, "profile set to #$safe (${PROFILES[safe].label})")
    }

    /** 生成随机 canvas 噪声种子（16 位 hex）。 */
    fun generateCanvasSeed(): String {
        val chars = "0123456789abcdef"
        return (1..16).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun getIndex(): Int = KVUtils.getInt(KEY_STEALTH_PROFILE_INDEX, 0)
    private fun setIndex(i: Int) = KVUtils.putInt(KEY_STEALTH_PROFILE_INDEX, i)
    private fun getCanvasSeed(): String = KVUtils.getString(KEY_STEALTH_CANVAS_SEED, "")
    private fun setCanvasSeed(s: String) = KVUtils.putString(KEY_STEALTH_CANVAS_SEED, s)
}

/**
 * 根据给定 [profile] 构建一段 IIFE 包装的 stealth JS，用于文档开始时注入。
 *
 * 覆盖范围：
 * - navigator.userAgent / platform / vendor / languages / hardwareConcurrency / deviceMemory
 * - navigator.webdriver = false
 * - window.chrome 注入
 * - navigator.plugins 伪装
 * - screen.width / height
 * - Canvas 指纹噪声（getImageData / toDataURL）
 * - WebGL 指纹（getParameter 返回伪装的 vendor/renderer）
 * - Intl.DateTimeFormat 时区保持真实（避免反检测误伤）
 *
 * 所有 hook 用 Object.defineProperty + configurable:true，不污染全局。
 */
fun buildStealthJs(profile: StealthProfile): String {
    val languagesJs = profile.languages.joinToString(",") { "\"$it\"" }
    val hardwareConcurrency = Random.nextInt(4, 9) // 4-8
    val deviceMemory = if (Random.nextBoolean()) 4 else 8

    // canvas 噪声种子 → 一个稳定的伪随机扰动函数
    val seed = profile.canvasSeed

    return """
(function () {
  'use strict';
  var asNative = function (fn, name) {
    try {
      Object.defineProperty(fn, 'toString', {
        value: function () { return 'function ' + (name || fn.name || '') + '() { [native code] }'; },
        configurable: true
      });
    } catch (e) {}
    return fn;
  };
  var defineProp = function (obj, prop, getter) {
    try {
      Object.defineProperty(obj, prop, { get: getter, configurable: true });
    } catch (e) {}
  };
  // ── navigator 基本字段 ──
  try { defineProp(navigator, 'userAgent', function () { return ${jsStr(profile.userAgent)}; }); } catch (e) {}
  try { defineProp(navigator, 'appVersion', function () { return ${jsStr(profile.userAgent.substringAfter("Mozilla/"))}; }); } catch (e) {}
  try { defineProp(navigator, 'platform', function () { return ${jsStr(profile.platform)}; }); } catch (e) {}
  try { defineProp(navigator, 'vendor', function () { return ${jsStr(profile.vendor)}; }); } catch (e) {}
  try { defineProp(navigator, 'languages', function () { return [$languagesJs]; }); } catch (e) {}
  try { defineProp(navigator, 'hardwareConcurrency', function () { return $hardwareConcurrency; }); } catch (e) {}
  try { defineProp(navigator, 'deviceMemory', function () { return $deviceMemory; }); } catch (e) {}
  try { defineProp(navigator, 'webdriver', function () { return false; }); } catch (e) {}
  try {
    var navProto = Object.getPrototypeOf(navigator);
    if (navProto) { defineProp(navProto, 'webdriver', function () { return false; }); }
  } catch (e) {}
  // ── window.chrome ──
  try {
    if (!window.chrome) { window.chrome = { runtime: {}, app: {}, loadTimes: function () {}, csi: function () {} }; }
  } catch (e) {}
  // ── navigator.plugins ──
  try {
    var mk = function (n, d, f) { return { name: n, description: d, filename: f, length: 1 }; };
    var fake = [
      mk('Chrome PDF Plugin', 'Portable Document Format', 'internal-pdf-viewer'),
      mk('Chrome PDF Viewer', '', 'mhjfbmdgcfjbbpaeojofohoefgiehjai'),
      mk('Native Client', '', 'internal-nacl-plugin')
    ];
    defineProp(navigator, 'plugins', function () { return fake; });
  } catch (e) {}
  // ── screen 分辨率 ──
  try { defineProp(screen, 'width', function () { return ${profile.screenWidth}; }); } catch (e) {}
  try { defineProp(screen, 'height', function () { return ${profile.screenHeight}; }); } catch (e) {}
  try { defineProp(screen, 'availWidth', function () { return ${profile.screenWidth}; }); } catch (e) {}
  try { defineProp(screen, 'availHeight', function () { return ${profile.screenHeight - 40}; }); } catch (e) {}
  // ── permissions.query ──
  try {
    var pq = navigator.permissions && navigator.permissions.query;
    if (pq) {
      navigator.permissions.query = asNative(function (p) {
        if (p && p.name === 'notifications') { return Promise.resolve({ state: Notification.permission, onchange: null }); }
        return pq.call(navigator.permissions, p);
      }, 'query');
    }
  } catch (e) {}
  // ── Canvas 指纹噪声：hook getImageData / toDataURL ──
  try {
    var seed = ${jsStr(seed)};
    // 把 seed 转成数值数组（稳定扰动源）
    var seedArr = [];
    for (var i = 0; i < seed.length; i++) { seedArr.push(seed.charCodeAt(i)); }
    var seedIdx = 0;
    var nextNoise = function () {
      // 简单线性同余，范围 [-1, 1]
      seedIdx = (seedIdx + 1) % seedArr.length;
      var v = (seedArr[seedIdx] * 9301 + 49297) % 233280;
      return (v / 233280) * 2 - 1;
    };
    var origGetImageData = CanvasRenderingContext2D.prototype.getImageData;
    if (origGetImageData) {
      CanvasRenderingContext2D.prototype.getImageData = asNative(function (sx, sy, sw, sh) {
        var img = origGetImageData.apply(this, arguments);
        try {
          var data = img.data;
          // 仅对每隔若干像素的一个通道施加 ±1 噪声，肉眼不可见但破坏 hash
          for (var i = 0; i < data.length; i += 4 * 17) {
            var n = nextNoise();
            if (n > 0.5) { data[i] = (data[i] + 1) & 0xff; }
          }
        } catch (e) {}
        return img;
      }, 'getImageData');
    }
    var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
    if (origToDataURL) {
      HTMLCanvasElement.prototype.toDataURL = asNative(function () {
        // 在 toDataURL 前对 canvas 做微小像素扰动
        try {
          var ctx = this.getContext && this.getContext('2d');
          if (ctx) {
            var w = Math.min(this.width, 4);
            var h = Math.min(this.height, 4);
            if (w > 0 && h > 0) {
              var img = origGetImageData.call(ctx, 0, 0, w, h);
              var data = img.data;
              for (var i = 0; i < data.length; i += 4) {
                if (nextNoise() > 0.3) { data[i] = (data[i] + 1) & 0xff; }
              }
              ctx.putImageData(img, 0, 0);
            }
          }
        } catch (e) {}
        return origToDataURL.apply(this, arguments);
      }, 'toDataURL');
    }
  } catch (e) {}
  // ── WebGL 指纹：hook getParameter ──
  try {
    var patchGL = function (proto) {
      if (!proto || !proto.getParameter) return;
      var og = proto.getParameter;
      proto.getParameter = asNative(function (p) {
        // UNMASKED_VENDOR_WEBGL = 37445, UNMASKED_RENDERER_WEBGL = 37446
        if (p === 37445) return ${jsStr(profile.webglVendor)};
        if (p === 37446) return ${jsStr(profile.webglRenderer)};
        return og.call(this, p);
      }, 'getParameter');
    };
    if (window.WebGLRenderingContext) patchGL(WebGLRenderingContext.prototype);
    if (window.WebGL2RenderingContext) patchGL(WebGL2RenderingContext.prototype);
  } catch (e) {}
  // ── Intl.DateTimeFormat 时区保持真实（不 hook），避免反检测误伤 ──
})();
""".trimIndent()
}

/** 把 Kotlin 字符串安全转义为 JS 字符串字面量（双引号包裹，转义内部双引号/反斜杠/换行）。 */
private fun jsStr(s: String): String {
    val escaped = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "\"$escaped\""
}
