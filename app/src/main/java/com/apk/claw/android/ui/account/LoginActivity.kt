package com.apk.claw.android.ui.account

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.apk.claw.android.R
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.activity.addCallback
import com.apk.claw.android.account.AccountErrorText
import com.apk.claw.android.account.AccountGate
import com.apk.claw.android.account.AccountRepository
import com.apk.claw.android.account.Landing
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.ui.compose.MainActivity
import com.apk.claw.android.ui.desktop.DesktopActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.KButton
import kotlinx.coroutines.launch

/**
 * Email-code sign-in / sign-up. Talks only to [AccountRepository].
 *
 * Three ways in:
 *  - [gateIntent]: the start-up gate (signed out, no local mode yet). No back; offers 「不登录使用」.
 *  - [signInIntent]: 去登录 from a 「登录后可用」 prompt. Returns to that screen once signed in.
 *  - plain intent (Settings account row): opens the wallet once signed in.
 */
class LoginActivity : BaseActivity() {

    private var countdown: CountDownTimer? = null
    private var mode = "email" // 先只用邮箱登录;手机号 tab 暂隐藏("phone" 仍可用,服务端端点保留)

    companion object {
        /** true=从启动闸进入:隐藏返回、提供「不登录使用」,登录成功后进主界面。 */
        const val EXTRA_GATE = "gate"

        /** true=从「登录后可用」的「去登录」进入:登录成功后直接回到原页面。 */
        const val EXTRA_RETURN = "return_after_sign_in"

        /** The start-up gate. Callers add their own task flags. */
        fun gateIntent(context: Context): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_GATE, true)

        /** 去登录 from a feature that needs an account; finishes back to the caller after sign-in. */
        fun signInIntent(context: Context): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_RETURN, true).apply {
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val gated = intent.getBooleanExtra(EXTRA_GATE, false)
        // 强制登录：从启动闸进入时隐藏返回、并拦截系统返回，必须登录/注册才能进入主界面。
        findViewById<android.widget.ImageView>(R.id.ivBack).apply {
            visibility = if (gated) android.view.View.GONE else android.view.View.VISIBLE
            setOnClickListener { finish() }
        }
        onBackPressedDispatcher.addCallback(this) { if (!gated) finish() }

        // 「不登录使用」:只在启动闸上出现。选择会持久化,下次启动直接进本地模式,之后可在设置里登录。
        findViewById<TextView>(R.id.btnUseLocal).apply {
            visibility = if (gated) View.VISIBLE else View.GONE
            setOnClickListener {
                AccountGate.chooseLocalMode()
                openApp()
            }
        }
        findViewById<TextView>(R.id.tvLocalHint).visibility = if (gated) View.VISIBLE else View.GONE

        val etAccount = findViewById<EditText>(R.id.etMobile)
        val etCode = findViewById<EditText>(R.id.etCode)
        val btnSendCode = findViewById<KButton>(R.id.btnSendCode)
        val btnLogin = findViewById<KButton>(R.id.btnLogin)
        val tabPhone = findViewById<android.widget.TextView>(R.id.tvTabPhone)
        val tabEmail = findViewById<android.widget.TextView>(R.id.tvTabEmail)
        tabPhone.visibility = android.view.View.GONE // 先不用手机号登录,只留邮箱

        fun applyMode() {
            val phone = mode == "phone"
            tabPhone.setTextColor(getColor(if (phone) R.color.colorBrandPrimary else R.color.colorTextSecondary))
            tabEmail.setTextColor(getColor(if (phone) R.color.colorTextSecondary else R.color.colorBrandPrimary))
            etAccount.hint = getString(if (phone) R.string.account_mobile_hint else R.string.account_email_hint)
            etAccount.inputType = if (phone) {
                android.text.InputType.TYPE_CLASS_PHONE
            } else {
                android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            etAccount.setText("")
            etCode.setText("")
        }
        tabPhone.setOnClickListener { mode = "phone"; applyMode() }
        tabEmail.setOnClickListener { mode = "email"; applyMode() }
        applyMode()

        btnSendCode.setOnClickListener {
            val acct = etAccount.text.toString().trim()
            if (!isValidAccount(acct)) {
                toast(invalidAccountMsg())
                return@setOnClickListener
            }
            // 立即给反馈：点下即变「发送中…」并置灰，避免网络往返期间按钮毫无变化、看着像“没倒计时/没反应”。
            btnSendCode.isEnabled = false
            btnSendCode.alpha = 0.6f
            btnSendCode.text = getString(R.string.account_sending)
            lifecycleScope.launch {
                val res = if (mode == "phone") AccountRepository.sendSmsCode(acct)
                else AccountRepository.sendEmailCode(acct)
                res.onSuccess { r ->
                    startCountdown(btnSendCode)
                    val code = r.devCode
                    if (!code.isNullOrEmpty()) {
                        etCode.setText(code)
                        toast(getString(R.string.account_mock_code_filled, code))
                    } else {
                        toast(getString(R.string.account_code_sent))
                    }
                }.onFailure {
                    btnSendCode.isEnabled = true
                    btnSendCode.alpha = 1f
                    btnSendCode.text = getString(R.string.account_send_code)
                    toast(errorText(it, R.string.account_code_send_failed))
                }
            }
        }

        btnLogin.setOnClickListener {
            val acct = etAccount.text.toString().trim()
            val code = etCode.text.toString().trim()
            if (!isValidAccount(acct)) {
                toast(invalidAccountMsg())
                return@setOnClickListener
            }
            if (code.isEmpty()) {
                toast(getString(R.string.account_invalid_code))
                return@setOnClickListener
            }
            btnLogin.isEnabled = false
            lifecycleScope.launch {
                val r = if (mode == "phone") AccountRepository.login(acct, code)
                else AccountRepository.loginEmail(acct, code)
                btnLogin.isEnabled = true
                r.onSuccess {
                    toast(getString(R.string.account_login_success))
                    when {
                        intent.getBooleanExtra(EXTRA_GATE, false) -> openApp()
                        intent.getBooleanExtra(EXTRA_RETURN, false) -> finish()
                        else -> {
                            startActivity(Intent(this@LoginActivity, AccountActivity::class.java))
                            finish()
                        }
                    }
                }.onFailure {
                    toast(errorText(it, R.string.account_login_failed))
                }
            }
        }
    }

    /** Leave the gate for the app: the TV desktop if the user made it the default, otherwise chat. */
    private fun openApp() {
        val target = when (AccountGate.landing(desktopDefault = KVUtils.isDesktopModeDefault())) {
            Landing.DESKTOP -> DesktopActivity::class.java
            Landing.MAIN, Landing.LOGIN -> MainActivity::class.java
        }
        val i = Intent(this, target)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(i)
        finish()
    }

    private fun isValidAccount(s: String): Boolean =
        if (mode == "phone") isValidMobile(s)
        else s.contains("@") && s.substringAfterLast("@").contains(".")

    private fun invalidAccountMsg(): String =
        getString(if (mode == "phone") R.string.account_invalid_mobile else R.string.account_invalid_email)

    private fun startCountdown(btn: KButton) {
        countdown?.cancel()
        btn.isEnabled = false
        btn.alpha = 0.6f
        countdown = object : CountDownTimer(60_000, 1_000) {
            override fun onTick(msUntilFinished: Long) {
                btn.text = getString(R.string.account_resend_in, (msUntilFinished / 1000).toInt())
            }

            override fun onFinish() {
                btn.isEnabled = true
                btn.alpha = 1f
                btn.text = getString(R.string.account_send_code)
            }
        }.start()
    }

    private fun isValidMobile(m: String): Boolean = m.length == 11 && m.all { it.isDigit() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    /** Account failures in the user's language; raw network exceptions never reach the toast. */
    private fun errorText(error: Throwable, fallback: Int): String = AccountErrorText.of(
        error,
        fallback = getString(fallback),
        network = getString(R.string.account_error_network),
    ) { code -> getString(R.string.account_error_server, code) }

    override fun onDestroy() {
        countdown?.cancel()
        super.onDestroy()
    }
}
