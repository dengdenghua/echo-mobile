package com.apk.claw.android.account

import com.apk.claw.android.utils.KVUtils

/**
 * Persisted account session: auth token + identity + a cached credits balance,
 * stored in MMKV via [KVUtils]. Keys are local to this feature (we use the
 * generic put/get rather than adding to KVUtils' shared key list to keep the
 * blast radius small).
 */
object AccountStore {
    private const val K_TOKEN = "ACCOUNT_TOKEN"
    private const val K_USER_ID = "ACCOUNT_USER_ID"
    private const val K_MOBILE = "ACCOUNT_MOBILE"
    private const val K_NICKNAME = "ACCOUNT_NICKNAME"
    private const val K_AVATAR = "ACCOUNT_AVATAR"
    private const val K_EMAIL = "ACCOUNT_EMAIL"
    private const val K_CREDITS = "ACCOUNT_CREDITS"
    private const val K_PAID_CREDITS = "ACCOUNT_PAID_CREDITS"
    private const val K_GIFT_CREDITS = "ACCOUNT_GIFT_CREDITS"
    private const val K_MEMBER_EXPIRE = "ACCOUNT_MEMBER_EXPIRE_AT"
    private const val K_LOCAL_MODE = "ACCOUNT_LOCAL_MODE"

    var token: String
        get() = KVUtils.getString(K_TOKEN, "")
        set(v) = run { KVUtils.putString(K_TOKEN, v) }

    var userId: String
        get() = KVUtils.getString(K_USER_ID, "")
        set(v) = run { KVUtils.putString(K_USER_ID, v) }

    var mobile: String
        get() = KVUtils.getString(K_MOBILE, "")
        set(v) = run { KVUtils.putString(K_MOBILE, v) }

    var nickname: String
        get() = KVUtils.getString(K_NICKNAME, "")
        set(v) = run { KVUtils.putString(K_NICKNAME, v) }

    var avatar: String
        get() = KVUtils.getString(K_AVATAR, "")
        set(v) = run { KVUtils.putString(K_AVATAR, v) }

    var email: String
        get() = KVUtils.getString(K_EMAIL, "")
        set(v) = run { KVUtils.putString(K_EMAIL, v) }

    /** Cached so the UI can show a balance offline; refreshed from the server. */
    var credits: Long
        get() = KVUtils.getString(K_CREDITS, "0").toLongOrNull() ?: 0L
        set(v) = run { KVUtils.putString(K_CREDITS, v.toString()) }

    var paidCredits: Long
        get() = KVUtils.getString(K_PAID_CREDITS, "0").toLongOrNull() ?: 0L
        set(v) = run { KVUtils.putString(K_PAID_CREDITS, v.toString()) }

    var giftCredits: Long
        get() = KVUtils.getString(K_GIFT_CREDITS, "0").toLongOrNull() ?: 0L
        set(v) = run { KVUtils.putString(K_GIFT_CREDITS, v.toString()) }

    /** Membership expiry, epoch millis; 0 = none. Source of truth is the server. */
    var memberExpireAt: Long
        get() = KVUtils.getString(K_MEMBER_EXPIRE, "0").toLongOrNull() ?: 0L
        set(v) = run { KVUtils.putString(K_MEMBER_EXPIRE, v.toString()) }

    val isLoggedIn: Boolean get() = token.isNotEmpty()

    /**
     * The user chose 「不登录使用」 at the sign-in gate, so the app opens in local mode on every
     * start until they sign in. Signing in clears it; signing out does not set it again (the
     * sign-in gate offers the choice once more).
     */
    var localMode: Boolean
        get() = KVUtils.getBoolean(K_LOCAL_MODE, false)
        set(v) = run { KVUtils.putBoolean(K_LOCAL_MODE, v) }

    /** Whether the user may use a BYO own-model (active monthly membership). */
    val byoUnlocked: Boolean get() = memberExpireAt > System.currentTimeMillis()

    fun saveLogin(r: LoginResult) {
        localMode = false
        token = r.token
        userId = r.userId
        mobile = r.mobile
        if (r.email.isNotEmpty()) email = r.email
        r.nickname?.takeIf { it.isNotEmpty() }?.let { nickname = it }
        r.avatar?.takeIf { it.isNotEmpty() }?.let { avatar = it }
    }

    fun clear() {
        token = ""
        userId = ""
        mobile = ""
        nickname = ""
        avatar = ""
        email = ""
        credits = 0
        paidCredits = 0
        giftCredits = 0
        memberExpireAt = 0
    }
}
