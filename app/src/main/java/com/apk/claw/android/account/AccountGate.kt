package com.apk.claw.android.account

/** How the app is being used right now. */
enum class AccessMode {
    /** Signed in to the Echo account server. */
    SIGNED_IN,

    /** 「不登录使用」: no account; local model, model settings and pairing with your own Echo. */
    LOCAL,

    /** Neither: the sign-in gate is shown on start. */
    SIGNED_OUT,
}

/** Where the splash screen sends the user. */
enum class Landing { LOGIN, MAIN, DESKTOP }

/**
 * Features that call the account server for the user and so need a signed-in account.
 * In local mode each of them shows 「登录后可用」 with a 去登录 action instead of failing.
 */
enum class AccountFeature(val requiresAccount: Boolean = true) {
    /** Credits, membership, top-ups, invites. */
    WALLET,

    /** Square posts: publish, comment, like, follow, get or subscribe to a paid app. */
    COMMUNITY,

    /** Pairing this phone with the web console (官网远程控制台) through the account. */
    REMOTE_CONSOLE,

    /** Live voice calls, which run on the account server's voice relay. */
    VOICE_CALL,

    /** Image and video generation, billed in account credits. */
    MEDIA_GENERATION,

    /** Browser bookmarks, history and tabs synced through the account. */
    CLOUD_SYNC,
}

/**
 * The one place that decides what a user without an account may do.
 *
 * Everything that does not talk to the account server (chat on a model the user configured,
 * model settings, pairing with their own Echo through `echo://` invitations, local skills and
 * tools, the update check) works the same in [AccessMode.LOCAL]. The features in
 * [AccountFeature] need a signed-in account.
 */
object AccountGate {

    fun accessMode(
        loggedIn: Boolean = AccountStore.isLoggedIn,
        localMode: Boolean = AccountStore.localMode,
    ): AccessMode = when {
        loggedIn -> AccessMode.SIGNED_IN
        localMode -> AccessMode.LOCAL
        else -> AccessMode.SIGNED_OUT
    }

    /** Signed out → sign-in gate; signed in or local mode → the app (or the TV desktop, if chosen). */
    fun landing(mode: AccessMode = accessMode(), desktopDefault: Boolean): Landing = when {
        mode == AccessMode.SIGNED_OUT -> Landing.LOGIN
        desktopDefault -> Landing.DESKTOP
        else -> Landing.MAIN
    }

    /** True when [feature] can be used now; false means show 「登录后可用」. */
    fun isAvailable(feature: AccountFeature, loggedIn: Boolean = AccountStore.isLoggedIn): Boolean =
        loggedIn || !feature.requiresAccount

    /** The 「不登录使用」 choice at the sign-in gate; it persists across restarts. */
    fun chooseLocalMode() {
        AccountStore.localMode = true
    }

    /** True when the user is in local mode (not signed in, and chose to go on without an account). */
    val isLocalMode: Boolean get() = accessMode() == AccessMode.LOCAL
}
