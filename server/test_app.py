"""
Octopus 服务端 pytest 测试套件。

覆盖范围:
  1.  健康检查端点 (/healthz)
  2.  账号注册与登录(手机号验证码、邮箱验证码)
  3.  JWT 鉴权(有效 / 无效 / 过期 token、封禁令牌失效)
  4.  积分预扣与结算(并发安全、余额不足、正常扣费、白名单免费)
  5.  限流(同 IP / 同邮箱超限返回 429)
  6.  管理后台(未设 ADMIN_TOKEN 返回 503、设了 token 后可访问、IP 白名单)
  7.  CSV 公式注入防护
  8.  安全设计(mock auth 默认关闭、可信 IP 提取、封禁用户令牌失效)
  9.  额外:邀请返利、订单结算、每日签到、模型目录

运行: cd server && python -m pytest test_app.py -v
"""
import asyncio
import json
import math
import os
import sys
import tempfile
import threading
import time
from contextlib import closing

# ── 在导入 app 前注入测试环境变量(避免污染真实 octo.db) ──
_TMPDIR = tempfile.mkdtemp(prefix="octo_test_")
os.environ["OCTO_DB"] = os.path.join(_TMPDIR, "test.db")
os.environ["UPLOAD_DIR"] = os.path.join(_TMPDIR, "uploads")
os.environ["ALLOW_MOCK_AUTH"] = "1"          # 测试中放开 mock 邮箱登录
os.environ["ENABLE_COST_SNAPSHOTS"] = "0"     # 关后台成本快照定时任务(测试直接调 _take_cost_snapshot)
os.environ["ADMIN_TOKEN"] = "test-token"      # 启用管理后台
os.environ["JWT_SECRET"] = "test-secret-key"
os.environ["EMAIL_PROVIDER"] = "mock"
os.environ["EMAIL_MOCK_CODE"] = "123456"
os.environ["SMS_PROVIDER"] = "mock"           # mock=禁用(测安全设计);流程测试用 monkeypatch 放开
os.environ["SMS_MOCK_CODE"] = "123456"
os.environ["PAYMENT_PROVIDER"] = "mock"       # mock=查单即结算
os.environ["TRUSTED_PROXIES"] = "1"
os.environ["SIGNUP_BONUS"] = "100"
os.environ["DAILY_BONUS"] = "20"
os.environ["FREE_CAP"] = "3000"
os.environ["REFERRAL_REDEEMER_BONUS"] = "200"
os.environ["REFERRAL_INVITER_BONUS"] = "200"

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import app as app_module  # noqa: E402
from app import (  # noqa: E402
    GOODS_BY_ID,
    _csv_cell,
    _reconcile_usage,
    _reserve_credits,
    app,
    client_ip,
    db,
    init_db,
    jwt_decode,
    jwt_encode,
)

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from starlette.websockets import WebSocketDisconnect  # noqa: E402


# ─────────────────────────── fixtures ───────────────────────────
@pytest.fixture(autouse=True)
def clean_state():
    """每个测试前清空 DB 表 + 限流字典,保证测试独立可运行。"""
    init_db()
    with closing(db()) as c:
        for t in ("users", "sms_codes", "email_codes", "orders", "usage_log", "admin_log",
                  "idempotency_keys",
                  "credit_transactions", "device_reports", "remote_devices", "remote_pair_codes",
                  "registry_assets", "crash_reports",
                  "square_posts", "square_comments", "square_likes", "square_follows",
                  "square_favorites", "square_unlocks", "plugin_subscriptions", "config_kv",
                  "cost_snapshots", "pricing_proposals", "voice_sessions", "voice_prefs",
                  "video_tasks"):
            c.execute(f"DELETE FROM {t}")
        c.commit()
    app_module._rl.clear()
    app_module._config_cache.clear()
    # 生产模型目录已移除 agnes chat 模型,但部分旧测试仍依赖它;在每个测试里临时注入,避免改生产默认配置。
    _inject_agnes_chat_model()
    yield
    app_module._rl.clear()


@pytest.fixture
def client():
    """FastAPI TestClient(触发 startup → init_db)。"""
    with TestClient(app) as c:
        yield c


# ─────────────────────────── helpers ───────────────────────────
def _now_ms() -> int:
    return int(time.time() * 1000)


def _inject_agnes_chat_model():
    """把 agnes-2.0-flash 临时注入测试用模型目录,保持旧测试兼容。"""
    spec = {"provider": "agnes", "multiplier": 0.2, "display_name": "Agnes", "tier": "fast"}
    app_module.MODEL_SPEC.setdefault("agnes-2.0-flash", spec)
    if not any(m.get("id") == "agnes-2.0-flash" for m in app_module.MODELS):
        app_module.MODELS.append({
            "id": "agnes-2.0-flash",
            "display_name": "Agnes",
            "tier": "fast",
            "multiplier": 0.2,
            "provider": "agnes",
        })


def _email_register(client, email="alice@example.com"):
    """通过邮箱 mock 登录注册一个新用户,返回 (token, userId)。"""
    r = client.post("/auth/email/send", json={"email": email})
    assert r.status_code == 200, r.text
    r = client.post("/auth/email/login", json={"email": email, "code": "123456"})
    assert r.status_code == 200, r.text
    d = r.json()
    return d["token"], d["userId"]


def _insert_email_code(email, code="123456", ttl_ms=300_000):
    """直接往 DB 插入邮箱验证码(绕过 send 端点的限流,用于需要重复登录同一邮箱的测试)。"""
    with closing(db()) as c:
        c.execute(
            "INSERT INTO email_codes(email, code, expire_at) VALUES(?,?,?) "
            "ON CONFLICT(email) DO UPDATE SET code=excluded.code, expire_at=excluded.expire_at",
            (email, code, _now_ms() + ttl_ms),
        )
        c.commit()


def _make_token(sub: str, exp_offset: int = 3600, secret: str = "test-secret-key") -> str:
    """手工签发 JWT(可控制 exp)。"""
    return jwt_encode(
        {"sub": sub, "iat": int(time.time()), "exp": int(time.time()) + exp_offset},
        secret,
    )


class _MockRequest:
    """轻量 mock Request,用于直接测试 client_ip。"""

    def __init__(self, xff=None, client_host="127.0.0.1"):
        self.headers = {}
        if xff is not None:
            self.headers["x-forwarded-for"] = xff
        self.client = type("C", (), {"host": client_host})()


# ═══════════════════════════════════════════════════════════════════════
# 1. 健康检查端点
# ═══════════════════════════════════════════════════════════════════════
class TestHealthz:
    def test_healthz_ok(self, client):
        r = client.get("/healthz")
        assert r.status_code == 200
        body = r.json()
        assert body["status"] in ("ok", "degraded")
        assert "db" in body
        assert "uptime_seconds" in body

    def test_healthz_no_auth_required(self, client):
        """健康检查不需要鉴权(不带 Authorization header 也能访问)。"""
        r = client.get("/healthz")
        assert r.status_code == 200
        assert "status" in r.json()


class TestShizukuDownload:
    def test_shizuku_latest_unconfigured(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "SHIZUKU_APK_PATH", os.path.join(_TMPDIR, "missing-shizuku.apk"))
        r = client.get("/downloads/shizuku/latest")
        assert r.status_code == 200
        data = r.json()
        assert data["name"] == "Shizuku"
        assert data["available"] is False
        assert "downloadUrl" not in data

        r = client.get("/downloads/shizuku.apk")
        assert r.status_code == 404

    def test_shizuku_latest_and_apk_download(self, client, tmp_path, monkeypatch):
        apk = tmp_path / "shizuku.apk"
        payload = b"fake apk bytes for endpoint test"
        apk.write_bytes(payload)
        monkeypatch.setattr(app_module, "SHIZUKU_APK_PATH", str(apk))
        monkeypatch.setattr(app_module, "SHIZUKU_VERSION", "test-version")

        r = client.get("/downloads/shizuku/latest")
        assert r.status_code == 200
        data = r.json()
        assert data["available"] is True
        assert data["version"] == "test-version"
        assert data["sizeBytes"] == len(payload)
        assert data["sha256"] == app_module._file_sha256(str(apk))
        assert data["downloadUrl"].endswith("/downloads/shizuku.apk")

        r = client.get("/downloads/shizuku.apk")
        assert r.status_code == 200
        assert r.content == payload
        assert r.headers["content-type"].startswith("application/vnd.android.package-archive")


# ═══════════════════════════════════════════════════════════════════════
# 2. 账号注册与登录
# ═══════════════════════════════════════════════════════════════════════
class TestEmailAuth:
    def test_email_send_ok(self, client):
        r = client.post("/auth/email/send", json={"email": "bob@example.com"})
        assert r.status_code == 200
        data = r.json()
        assert data["ok"] is True
        assert data["ttlSeconds"] == 300
        assert data["devCode"] == "123456"  # mock 模式返回固定码

    def test_email_send_invalid_email(self, client):
        for bad in ["not-an-email", "a@b", "@example.com", "a@.com", ""]:
            r = client.post("/auth/email/send", json={"email": bad})
            assert r.status_code == 400, f"{bad!r} should be 400"

    def test_email_send_failure_removes_code(self, client, monkeypatch):
        email = "smtp-fail@example.com"

        def _boom(_email, _code):
            raise RuntimeError("smtp failed")

        monkeypatch.setattr(app_module, "EMAIL_PROVIDER", "smtp")
        monkeypatch.setattr(app_module, "send_email", _boom)
        r = client.post("/auth/email/send", json={"email": email})
        assert r.status_code == 502
        assert r.json()["detail"] == "email send failed"
        with closing(db()) as c:
            rec = c.execute("SELECT * FROM email_codes WHERE email = ?", (email,)).fetchone()
        assert rec is None

    def test_email_login_new_user(self, client):
        email = "new@example.com"
        client.post("/auth/email/send", json={"email": email})
        r = client.post("/auth/email/login", json={"email": email, "code": "123456"})
        assert r.status_code == 200
        d = r.json()
        assert d["isNewUser"] is True
        assert d["email"] == email
        assert d["token"]
        assert d["userId"].startswith("u_")
        # 新用户应得注册礼
        r2 = client.get("/account/balance", headers={"Authorization": f"Bearer {d['token']}"})
        assert r2.json()["credits"] == 100

    def test_email_login_existing_user(self, client):
        email = "exist@example.com"
        _email_register(client, email)
        # 第二次登录:直接插验证码绕过 send 限流
        _insert_email_code(email)
        r = client.post("/auth/email/login", json={"email": email, "code": "123456"})
        assert r.status_code == 200
        assert r.json()["isNewUser"] is False

    def test_email_login_wrong_code(self, client):
        email = "wrong@example.com"
        client.post("/auth/email/send", json={"email": email})
        r = client.post("/auth/email/login", json={"email": email, "code": "000000"})
        assert r.status_code == 400
        assert "验证码错误" in r.json()["detail"]

    def test_email_login_expired_code(self, client):
        email = "expired@example.com"
        client.post("/auth/email/send", json={"email": email})
        # 直接改 DB 让验证码过期
        with closing(db()) as c:
            c.execute("UPDATE email_codes SET expire_at = ? WHERE email = ?", (0, email))
            c.commit()
        r = client.post("/auth/email/login", json={"email": email, "code": "123456"})
        assert r.status_code == 400

    def test_email_login_banned_user(self, client):
        """封禁用户不能再签发新 token → 403。"""
        email = "banned@example.com"
        token, uid = _email_register(client, email)
        client.post(
            f"/admin/api/users/{uid}/ban",
            json={"banned": True, "reason": "test"},
            headers={"X-Admin-Token": "test-token"},
        )
        _insert_email_code(email)  # 绕过 send 限流
        r = client.post("/auth/email/login", json={"email": email, "code": "123456"})
        assert r.status_code == 403


class TestSmsAuth:
    def test_sms_send_disabled_in_mock(self, client):
        """SMS_PROVIDER=mock 时短信端点禁用(防薅羊毛)。"""
        r = client.post("/auth/sms/send", json={"mobile": "13800000001"})
        assert r.status_code == 403

    def test_sms_login_disabled_in_mock(self, client):
        r = client.post("/auth/sms/login", json={"mobile": "13800000001", "code": "123456"})
        assert r.status_code == 403

    def test_sms_send_and_login_flow(self, client, monkeypatch):
        """完整 SMS 登录流程(放开 SMS_PROVIDER 并 mock send_sms)。"""
        monkeypatch.setattr(app_module, "SMS_PROVIDER", "test")
        monkeypatch.setattr(app_module, "send_sms", lambda m, c: None)
        mobile = "13900000001"
        r = client.post("/auth/sms/send", json={"mobile": mobile})
        assert r.status_code == 200
        # 非 mock 模式验证码是随机的,从 DB 读出
        with closing(db()) as c:
            rec = c.execute("SELECT code FROM sms_codes WHERE mobile = ?", (mobile,)).fetchone()
        code = rec["code"]
        r = client.post("/auth/sms/login", json={"mobile": mobile, "code": code})
        assert r.status_code == 200
        d = r.json()
        assert d["isNewUser"] is True
        assert d["mobile"] == mobile
        assert d["token"]

    def test_sms_send_invalid_mobile(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "SMS_PROVIDER", "test")
        monkeypatch.setattr(app_module, "send_sms", lambda m, c: None)
        for bad in ["123", "abcdefghijk", "123456"]:
            r = client.post("/auth/sms/send", json={"mobile": bad})
            assert r.status_code == 400

    def test_sms_login_wrong_code(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "SMS_PROVIDER", "test")
        monkeypatch.setattr(app_module, "send_sms", lambda m, c: None)
        mobile = "13900000002"
        client.post("/auth/sms/send", json={"mobile": mobile})
        r = client.post("/auth/sms/login", json={"mobile": mobile, "code": "000000"})
        assert r.status_code == 400


# ═══════════════════════════════════════════════════════════════════════
# 3. JWT 鉴权
# ═══════════════════════════════════════════════════════════════════════
class TestJwtAuth:
    def test_profile_with_valid_token(self, client):
        token, uid = _email_register(client, "jwt1@example.com")
        r = client.get("/account/profile", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["userId"] == uid

    def test_profile_without_token(self, client):
        r = client.get("/account/profile")
        assert r.status_code == 401

    def test_profile_with_malformed_token(self, client):
        r = client.get("/account/profile", headers={"Authorization": "Bearer not.a.jwt"})
        assert r.status_code == 401

    def test_profile_with_invalid_signature(self, client):
        """用错误 secret 签的 token → 401。"""
        bad = _make_token("u_x", secret="wrong-secret")
        r = client.get("/account/profile", headers={"Authorization": f"Bearer {bad}"})
        assert r.status_code == 401

    def test_profile_with_expired_token(self, client):
        """过期 token → 401。"""
        token, uid = _email_register(client, "jwt3@example.com")
        expired = _make_token(uid, exp_offset=-1)
        r = client.get("/account/profile", headers={"Authorization": f"Bearer {expired}"})
        assert r.status_code == 401

    def test_profile_with_unknown_user(self, client):
        """token 合法但 user 不在 DB → 401。"""
        token = _make_token("u_nonexistent")
        r = client.get("/account/profile", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 401
        assert "unknown user" in r.json()["detail"]

    def test_banned_user_token_invalidated(self, client):
        """封禁后,已签发的 token 立即失效 → 403。"""
        token, uid = _email_register(client, "jwt4@example.com")
        # 先确认能访问
        r = client.get("/account/profile", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        # 封禁
        client.post(
            f"/admin/api/users/{uid}/ban",
            json={"banned": True},
            headers={"X-Admin-Token": "test-token"},
        )
        # 再访问 → 403
        r = client.get("/account/profile", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 403

    def test_jwt_decode_roundtrip(self):
        """JWT 编码 → 解码 往返一致。"""
        claims = {"sub": "u_abc", "exp": int(time.time()) + 60}
        token = jwt_encode(claims, "secret")
        decoded = jwt_decode(token, "secret")
        assert decoded is not None
        assert decoded["sub"] == "u_abc"

    def test_jwt_decode_expired(self):
        """过期的 token 解码返回 None。"""
        token = jwt_encode({"sub": "u_x", "exp": int(time.time()) - 1}, "secret")
        assert jwt_decode(token, "secret") is None

    def test_jwt_decode_bad_sig(self):
        """签名错误解码返回 None。"""
        token = jwt_encode({"sub": "u_x", "exp": int(time.time()) + 60}, "secret")
        assert jwt_decode(token, "other-secret") is None


# ═══════════════════════════════════════════════════════════════════════
# 4. 积分预扣与结算
# ═══════════════════════════════════════════════════════════════════════
class TestBilling:
    def test_reserve_credits_success(self, client):
        """正常预扣:余额够 → 成功。"""
        token, uid = _email_register(client, "bill1@example.com")
        ok = _reserve_credits(uid, 50)
        assert ok is True
        with closing(db()) as c:
            bal = c.execute("SELECT credits FROM users WHERE user_id = ?", (uid,)).fetchone()["credits"]
        assert bal == 50  # 100 - 50

    def test_reserve_credits_insufficient(self, client):
        """余额不足 → 预扣失败,余额不变。"""
        token, uid = _email_register(client, "bill2@example.com")
        ok = _reserve_credits(uid, 200)  # 只有 100
        assert ok is False
        with closing(db()) as c:
            bal = c.execute("SELECT credits FROM users WHERE user_id = ?", (uid,)).fetchone()["credits"]
        assert bal == 100

    def test_reserve_credits_zero_hold(self, client):
        """hold=0 → 永远成功(白名单语义)。"""
        token, uid = _email_register(client, "bill3@example.com")
        ok = _reserve_credits(uid, 0)
        assert ok is True

    def test_reconcile_refund(self, client, monkeypatch):
        """预扣 50,实际扣 10 → 退 40。"""
        monkeypatch.setattr(app_module, "CREDITS_PER_1K_TOKENS", 1.0)
        token, uid = _email_register(client, "bill4@example.com")
        _reserve_credits(uid, 50)
        actual = _reconcile_usage(uid, "agnes-2.0-flash", 5000, 5000, 1.0, 50)
        # actual = ceil(10000/1000 * 1 * 1.0) = 10
        assert actual == 10
        with closing(db()) as c:
            bal = c.execute("SELECT credits FROM users WHERE user_id = ?", (uid,)).fetchone()["credits"]
        # 100 - 50(reserve) + 40(refund) = 90
        assert bal == 90

    def test_reconcile_zero_usage_full_refund(self, client):
        """上游报错(tin=tout=0)→ 全额退还预扣。"""
        token, uid = _email_register(client, "bill5@example.com")
        _reserve_credits(uid, 50)
        actual = _reconcile_usage(uid, "agnes-2.0-flash", 0, 0, 1.0, 50)
        assert actual == 0
        with closing(db()) as c:
            bal = c.execute("SELECT credits FROM users WHERE user_id = ?", (uid,)).fetchone()["credits"]
        assert bal == 100  # 全额退

    def test_reconcile_free_user(self, client):
        """白名单用户(free=True)→ actual 恒 0,仍记 usage_log。"""
        token, uid = _email_register(client, "bill6@example.com")
        actual = _reconcile_usage(uid, "agnes-2.0-flash", 1000, 1000, 1.0, 0, free=True)
        assert actual == 0
        with closing(db()) as c:
            n = c.execute("SELECT COUNT(*) n FROM usage_log WHERE user_id = ?", (uid,)).fetchone()["n"]
        assert n == 1  # 有用量记录

    def test_concurrent_reserve(self, client):
        """并发预扣:100 积分,5 个线程各扣 50 → 只有 2 个成功(原子 UPDATE 防超支)。"""
        token, uid = _email_register(client, "bill7@example.com")
        results = []

        def try_reserve():
            results.append(_reserve_credits(uid, 50))

        threads = [threading.Thread(target=try_reserve) for _ in range(5)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        assert sum(results) == 2  # 100 / 50 = 2
        with closing(db()) as c:
            bal = c.execute("SELECT credits FROM users WHERE user_id = ?", (uid,)).fetchone()["credits"]
        assert bal == 0

    def test_chat_insufficient_credits_402(self, client, monkeypatch):
        """余额不足 → POST /v1/chat/completions → 402。"""
        monkeypatch.setattr(app_module, "FREE_DAILY_CREDITS", 0)
        token, uid = _email_register(client, "bill8@example.com")
        # 清空积分
        with closing(db()) as c:
            c.execute("UPDATE users SET credits = 0 WHERE user_id = ?", (uid,))
            c.commit()
        # 配置假上游(只需通过 base/key 检查;402 在调上游前返回)
        monkeypatch.setitem(
            app_module.PROVIDERS, "agnes",
            {"base_url": "https://fake.example.com/v1", "api_key": "fake-key"},
        )
        r = client.post(
            "/v1/chat/completions",
            json={"model": "agnes-2.0-flash", "messages": [{"role": "user", "content": "hi"}]},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 402

    def test_chat_no_upstream_503(self, client):
        """上游未配置(base/key 空)→ 503。"""
        token, uid = _email_register(client, "bill9@example.com")
        r = client.post(
            "/v1/chat/completions",
            json={"model": "agnes-2.0-flash", "messages": [{"role": "user", "content": "hi"}]},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 503

    def test_chat_unauthorized(self, client):
        """未鉴权调中转 → 401。"""
        r = client.post(
            "/v1/chat/completions",
            json={"messages": [{"role": "user", "content": "hi"}]},
        )
        assert r.status_code == 401

    def test_daily_free_quota_covers_small_chat(self, client, monkeypatch):
        """每日免费额度优先抵扣,小请求不扣余额。"""
        monkeypatch.setattr(app_module, "FREE_DAILY_CREDITS", 5)
        token, uid = _email_register(client, "bill10@example.com")
        with closing(db()) as c:
            c.execute("UPDATE users SET credits = 0 WHERE user_id = ?", (uid,))
            c.commit()
        # 配置假上游,让请求走到结算(上游返回空 usage 会按 prompt 估算兜底)
        monkeypatch.setitem(
            app_module.PROVIDERS, "agnes",
            {"base_url": "https://fake.example.com/v1", "api_key": "fake-key"},
        )
        # 伪造上游返回 200 + 无 usage(测试里 httpx 会真的发请求到 fake.example.com,
        # 无法连接会抛异常并走 _settle(0,0),这样也能验证免费额度不扣余额)
        r = client.post(
            "/v1/chat/completions",
            json={"model": "agnes-2.0-flash", "messages": [{"role": "user", "content": "hi"}]},
            headers={"Authorization": f"Bearer {token}"},
        )
        # 上游连不上返回 502,但重点是余额没被扣
        with closing(db()) as c:
            bal = c.execute("SELECT credits FROM users WHERE user_id = ?", (uid,)).fetchone()["credits"]
        assert bal == 0

    def test_usage_reserve_rolls_back_free_buckets_on_insufficient_paid(self, client, monkeypatch):
        """赠送/每日额度已参与预留,但永久积分不足时应整体回滚。"""
        monkeypatch.setattr(app_module, "FREE_DAILY_CREDITS", 5)
        token, uid = _email_register(client, "bill11@example.com")
        with closing(db()) as c:
            c.execute(
                "UPDATE users SET credits = 0, gift_credits = 3, gift_month = ?, daily_free_used = 0, daily_free_date = ? "
                "WHERE user_id = ?",
                (app_module._this_month(), app_module._today_str(), uid),
            )
            c.commit()

        reserved = app_module._reserve_usage_credits(uid, 10, ref_id="test_hold")
        assert reserved is None
        with closing(db()) as c:
            row = c.execute(
                "SELECT credits, gift_credits, daily_free_used FROM users WHERE user_id = ?", (uid,)
            ).fetchone()
        assert row["credits"] == 0
        assert row["gift_credits"] == 3
        assert row["daily_free_used"] == 0

    def test_usage_reconcile_refunds_unused_gift_daily_and_paid(self, client, monkeypatch):
        """实际账单低于 worst-case 时,赠送/每日免费/永久积分都按未用量退回。"""
        monkeypatch.setattr(app_module, "FREE_DAILY_CREDITS", 5)
        monkeypatch.setattr(app_module, "CREDITS_PER_1K_TOKENS", 1.0)
        token, uid = _email_register(client, "bill12@example.com")
        with closing(db()) as c:
            c.execute(
                "UPDATE users SET credits = 10, gift_credits = 3, gift_month = ?, daily_free_used = 0, daily_free_date = ? "
                "WHERE user_id = ?",
                (app_module._this_month(), app_module._today_str(), uid),
            )
            c.commit()

        reserved = app_module._reserve_usage_credits(uid, 10, ref_id="test_hold")
        assert reserved == {"gift": 3, "daily": 5, "paid": 2}
        actual = _reconcile_usage(uid, "agnes-2.0-flash", 1000, 0, 1.0, 10, ref_id="test_hold", reserved=reserved)
        assert actual == 1
        with closing(db()) as c:
            row = c.execute(
                "SELECT credits, gift_credits, daily_free_used FROM users WHERE user_id = ?", (uid,)
            ).fetchone()
        assert row["credits"] == 10
        assert row["gift_credits"] == 2
        assert row["daily_free_used"] == 0


# ═══════════════════════════════════════════════════════════════════════
# 5. 限流
# ═══════════════════════════════════════════════════════════════════════
class TestRateLimit:
    def test_email_send_same_email_429(self, client):
        """同邮箱 60s 内只能发 1 次 → 第 2 次 429。"""
        email = "rl1@example.com"
        r1 = client.post("/auth/email/send", json={"email": email})
        assert r1.status_code == 200
        r2 = client.post("/auth/email/send", json={"email": email})
        assert r2.status_code == 429

    def test_email_send_ip_limit_429(self, client):
        """同 IP 每小时 10 条 → 第 11 条 429。"""
        for i in range(10):
            r = client.post("/auth/email/send", json={"email": f"ip{i}@example.com"})
            assert r.status_code == 200
        r = client.post("/auth/email/send", json={"email": "ip10@example.com"})
        assert r.status_code == 429

    def test_email_login_rate_limit(self, client):
        """同邮箱 10 分钟最多 10 次登录尝试 → 第 11 次 429。"""
        email = "rl2@example.com"
        for _ in range(10):
            r = client.post("/auth/email/login", json={"email": email, "code": "wrong"})
            assert r.status_code == 400  # 验证码错(还没到限流)
        r = client.post("/auth/email/login", json={"email": email, "code": "wrong"})
        assert r.status_code == 429

    def test_admin_rate_limit(self, client):
        """同 IP 每分钟 120 次管理请求 → 第 121 次 429。"""
        for _ in range(120):
            r = client.get("/admin/api/stats", headers={"X-Admin-Token": "test-token"})
            assert r.status_code == 200
        r = client.get("/admin/api/stats", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 429


# ═══════════════════════════════════════════════════════════════════════
# 6. 管理后台
# ═══════════════════════════════════════════════════════════════════════
class TestAdmin:
    def test_admin_503_without_token(self, client, monkeypatch):
        """未设 ADMIN_TOKEN → 503(默认安全关闭)。"""
        monkeypatch.setattr(app_module, "ADMIN_TOKEN", "")
        r = client.get("/admin/api/stats")
        assert r.status_code == 503

    def test_admin_page_404_without_token(self, client, monkeypatch):
        """未设 ADMIN_TOKEN 时 /admin 页面也不暴露 → 404。"""
        monkeypatch.setattr(app_module, "ADMIN_TOKEN", "")
        r = client.get("/admin")
        assert r.status_code == 404

    def test_admin_401_wrong_token(self, client):
        """口令错 → 401。"""
        r = client.get("/admin/api/stats", headers={"X-Admin-Token": "wrong"})
        assert r.status_code == 401

    def test_admin_stats_ok(self, client):
        """正确 token → 200 + 统计数据。"""
        r = client.get("/admin/api/stats", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200
        d = r.json()
        assert "users" in d
        assert "totalCredits" in d
        assert "calls" in d

    def test_admin_users_list(self, client):
        _email_register(client, "adm1@example.com")
        _email_register(client, "adm2@example.com")
        r = client.get("/admin/api/users", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200
        d = r.json()
        assert d["total"] == 2
        assert len(d["items"]) == 2

    def test_admin_users_search(self, client):
        _email_register(client, "search@example.com")
        _email_register(client, "other@example.com")
        r = client.get("/admin/api/users?q=search", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200
        assert r.json()["total"] == 1

    def test_admin_adjust_credits(self, client):
        token, uid = _email_register(client, "adm3@example.com")
        r = client.post(
            f"/admin/api/users/{uid}/credits",
            json={"delta": 500, "reason": "test"},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 200
        assert r.json()["balance"] == 600  # 100 + 500

    def test_admin_adjust_credits_negative_clamp(self, client):
        """负向调整会被 MAX(0,...) 截断到 0。"""
        token, uid = _email_register(client, "adm4@example.com")
        r = client.post(
            f"/admin/api/users/{uid}/credits",
            json={"delta": -10000, "reason": "test"},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 200
        assert r.json()["balance"] == 0

    def test_admin_adjust_credits_invalid_delta(self, client):
        token, uid = _email_register(client, "adm4b@example.com")
        r = client.post(
            f"/admin/api/users/{uid}/credits",
            json={"delta": "abc"},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 400

    def test_admin_membership_grant(self, client):
        token, uid = _email_register(client, "adm5@example.com")
        r = client.post(
            f"/admin/api/users/{uid}/membership",
            json={"days": 30, "reason": "test"},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 200
        d = r.json()
        assert d["memberActive"] is True
        assert d["memberExpireAt"] > 0

    def test_admin_ban_and_unban(self, client):
        token, uid = _email_register(client, "adm6@example.com")
        r = client.post(
            f"/admin/api/users/{uid}/ban",
            json={"banned": True, "reason": "test"},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 200
        assert r.json()["banned"] is True
        r = client.post(
            f"/admin/api/users/{uid}/ban",
            json={"banned": False, "reason": "test"},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 200
        assert r.json()["banned"] is False

    def test_admin_ban_requires_bool(self, client):
        """banned 必须显式 true/false,防漏字段静默解封。"""
        token, uid = _email_register(client, "adm7@example.com")
        r = client.post(
            f"/admin/api/users/{uid}/ban",
            json={"banned": "yes"},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 400

    def test_admin_logs(self, client):
        token, uid = _email_register(client, "adm8@example.com")
        client.post(
            f"/admin/api/users/{uid}/credits",
            json={"delta": 100, "reason": "log test"},
            headers={"X-Admin-Token": "test-token"},
        )
        r = client.get("/admin/api/logs", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200
        items = r.json()["items"]
        assert len(items) >= 1
        assert items[0]["action"] == "credits"

    def test_admin_user_not_found(self, client):
        r = client.post(
            "/admin/api/users/u_nope/credits",
            json={"delta": 100},
            headers={"X-Admin-Token": "test-token"},
        )
        assert r.status_code == 404


# ═══════════════════════════════════════════════════════════════════════
# 7. CSV 公式注入防护
# ═══════════════════════════════════════════════════════════════════════
class TestCsvInjection:
    def test_csv_cell_formula_equal(self):
        assert _csv_cell("=cmd|/c calc") == "'=cmd|/c calc"

    def test_csv_cell_formula_plus(self):
        assert _csv_cell("+1+1") == "'+1+1"

    def test_csv_cell_formula_minus(self):
        assert _csv_cell("-1+1") == "'-1+1"

    def test_csv_cell_formula_at(self):
        assert _csv_cell("@SUM(A1)") == "'@SUM(A1)"

    def test_csv_cell_normal(self):
        assert _csv_cell("normal") == "normal"

    def test_csv_cell_comma(self):
        assert _csv_cell("a,b") == '"a,b"'

    def test_csv_cell_quote(self):
        assert _csv_cell('say "hi"') == '"say ""hi"""'

    def test_csv_cell_none(self):
        assert _csv_cell(None) == ""

    def test_csv_export_with_injection(self, client):
        """导出 CSV 时,含公式注入字符的字段应被转义。"""
        token, uid = _email_register(client, "csv@example.com")
        with closing(db()) as c:
            c.execute(
                "INSERT INTO usage_log(user_id, model, tokens_in, tokens_out, credits, ts) "
                "VALUES(?,?,?,?,?,?)",
                (uid, '=HYPERLINK("http://evil")', 100, 50, 5, _now_ms()),
            )
            c.commit()
        r = client.get("/admin/api/usage.csv", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200
        text = r.text
        # 恶意 model 名应被转义(前面加 ')
        assert "'=HYPERLINK" in text
        # 逐行检查:数据行不应有单元格以 = 开头(应被 ' 转义)
        for line in text.split("\n"):
            if line.startswith("id,"):  # 表头跳过
                continue
            if not line.strip():
                continue
            # 简易检查:不应出现裸露的 = 开头单元格
            assert not line.startswith("="), f"unescaped formula at line: {line}"


# ═══════════════════════════════════════════════════════════════════════
# 8. 安全设计
# ═══════════════════════════════════════════════════════════════════════
class TestSecurity:
    def test_mock_auth_disabled_by_default(self, client, monkeypatch):
        """ALLOW_MOCK_AUTH=0 时邮箱 mock 登录禁用 → 403。"""
        monkeypatch.setattr(app_module, "ALLOW_MOCK_AUTH", False)
        r = client.post("/auth/email/send", json={"email": "sec1@example.com"})
        assert r.status_code == 403
        r = client.post("/auth/email/login", json={"email": "sec1@example.com", "code": "123456"})
        assert r.status_code == 403

    def test_sms_disabled_in_mock(self, client):
        """SMS_PROVIDER=mock 时短信端点禁用。"""
        assert client.post("/auth/sms/send", json={"mobile": "13800000000"}).status_code == 403
        assert client.post("/auth/sms/login", json={"mobile": "13800000000", "code": "123456"}).status_code == 403

    def test_client_ip_no_xff(self):
        """无 XFF → 用 socket IP。"""
        req = _MockRequest(xff=None, client_host="1.2.3.4")
        assert client_ip(req) == "1.2.3.4"

    def test_client_ip_with_trusted_proxy(self):
        """TRUSTED_PROXIES=1 → 取 XFF 右数第 1 个(不可伪造)。"""
        req = _MockRequest(xff="1.1.1.1, 2.2.2.2", client_host="3.3.3.3")
        assert client_ip(req) == "2.2.2.2"

    def test_client_ip_single_xff(self):
        """XFF 只有 1 段且 TRUSTED_PROXIES=1 → 取该段。"""
        req = _MockRequest(xff="1.1.1.1", client_host="2.2.2.2")
        assert client_ip(req) == "1.1.1.1"

    def test_client_ip_trusted_proxies_zero(self, monkeypatch):
        """TRUSTED_PROXIES=0 → 忽略 XFF,用 socket IP(防伪造)。"""
        monkeypatch.setattr(app_module, "TRUSTED_PROXIES", 0)
        req = _MockRequest(xff="1.1.1.1, 2.2.2.2", client_host="3.3.3.3")
        assert client_ip(req) == "3.3.3.3"

    def test_admin_ip_allowlist_blocked(self, client, monkeypatch):
        """设了 IP 白名单后,非白名单 IP → 403。"""
        monkeypatch.setattr(app_module, "ADMIN_IP_ALLOWLIST", ["10.0.0.1"])
        r = client.get("/admin/api/stats", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 403

    def test_admin_ip_allowlist_allowed(self, client, monkeypatch):
        """白名单 IP + 正确 token → 200。"""
        monkeypatch.setattr(app_module, "ADMIN_IP_ALLOWLIST", ["testclient"])
        r = client.get("/admin/api/stats", headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200

    def test_admin_empty_token_401(self, client):
        """空口令 → 401(不泄露任何信息)。"""
        r = client.get("/admin/api/stats", headers={"X-Admin-Token": ""})
        assert r.status_code == 401


# ═══════════════════════════════════════════════════════════════════════
# 9. 邀请返利
# ═══════════════════════════════════════════════════════════════════════
class TestInvite:
    def test_invite_info(self, client):
        token, uid = _email_register(client, "inv1@example.com")
        r = client.get("/invite/info", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["code"]  # 有邀请码
        assert d["invitedCount"] == 0
        assert d["redeemed"] is False

    def test_invite_redeem_ok(self, client):
        """新人填邀请码:新人得 200,邀请人也得 200。"""
        token_a, uid_a = _email_register(client, "inv2@example.com")
        token_b, uid_b = _email_register(client, "inv3@example.com")
        r = client.get("/invite/info", headers={"Authorization": f"Bearer {token_a}"})
        code = r.json()["code"]
        r = client.post("/invite/redeem", json={"code": code},
                        headers={"Authorization": f"Bearer {token_b}"})
        assert r.status_code == 200
        # B:100(注册) + 200(邀请) = 300
        assert r.json()["balance"] == 300
        # A:100(注册) + 200(邀请人奖励) = 300
        r = client.get("/account/balance", headers={"Authorization": f"Bearer {token_a}"})
        assert r.json()["credits"] == 300

    def test_invite_redeem_own_code(self, client):
        """不能使用自己的邀请码。"""
        token, uid = _email_register(client, "inv4@example.com")
        r = client.get("/invite/info", headers={"Authorization": f"Bearer {token}"})
        code = r.json()["code"]
        r = client.post("/invite/redeem", json={"code": code},
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 400
        assert "自己" in r.json()["detail"]

    def test_invite_redeem_twice_rejected(self, client):
        """同一新人只能用一次邀请码。"""
        token_a, _ = _email_register(client, "inv5@example.com")
        token_b, _ = _email_register(client, "inv6@example.com")
        r = client.get("/invite/info", headers={"Authorization": f"Bearer {token_a}"})
        code = r.json()["code"]
        # B 第一次使用 → 成功
        client.post("/invite/redeem", json={"code": code},
                    headers={"Authorization": f"Bearer {token_b}"})
        # B 第二次使用 → 拒绝
        r = client.post("/invite/redeem", json={"code": code},
                        headers={"Authorization": f"Bearer {token_b}"})
        assert r.status_code == 400

    def test_invite_redeem_invalid_code(self, client):
        token, uid = _email_register(client, "inv8@example.com")
        r = client.post("/invite/redeem", json={"code": "NOPE"},
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 400


# ═══════════════════════════════════════════════════════════════════════
# 10. 订单与结算
# ═══════════════════════════════════════════════════════════════════════
class TestOrders:
    def test_billing_estimate(self, client):
        """/billing/estimate 返回预估积分与人民币。"""
        token, uid = _email_register(client, "est1@example.com")
        r = client.post(
            "/billing/estimate",
            json={"model": "agnes-2.0-flash", "messages": [{"role": "user", "content": "hello"}]},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 200
        d = r.json()
        assert d["model"] == "agnes-2.0-flash"
        assert d["multiplier"] == 0.2
        assert d["worstCaseCredits"] >= 1
        assert "chargeableCredits" in d
        assert "estimatedRmb" in d

    def test_create_order(self, client):
        token, uid = _email_register(client, "ord1@example.com")
        r = client.post("/billing/orders", json={"goodsId": "sub_19"},
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["orderNo"]
        assert d["amountFen"] == 9900
        assert d["credits"] == 1500
        assert d["currency"] == "CNY"
        assert d["amountMinor"] == 9900

    def test_create_order_usd_uses_usd_pricing_and_credits(self, client):
        token, uid = _email_register(client, "ord1b@example.com")
        r = client.post("/billing/orders", json={"goodsId": "sub_99", "currency": "USD"},
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["currency"] == "USD"
        assert d["amountMinor"] == 6990
        assert d["credits"] == 8500

    def test_create_order_invalid_goods(self, client):
        token, uid = _email_register(client, "ord2@example.com")
        r = client.post("/billing/orders", json={"goodsId": "nope"},
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 400

    def test_query_order_mock_settle(self, client):
        """mock 支付:查单即结算 → PAID + 积分到账。"""
        token, uid = _email_register(client, "ord3@example.com")
        r = client.post("/billing/orders", json={"goodsId": "sub_19"},
                        headers={"Authorization": f"Bearer {token}"})
        order_no = r.json()["orderNo"]
        r = client.get(f"/billing/orders/{order_no}",
                       headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["status"] == "PAID"
        assert d["credits"] == 1500
        # 查余额:100(注册) + 1000(永久积分) + 500(月度赠送) = 1600
        r = client.get("/account/balance", headers={"Authorization": f"Bearer {token}"})
        assert r.json()["credits"] == 1600

    def test_query_order_mock_settle_usd_uses_usd_credit_benefits(self, client):
        token, uid = _email_register(client, "ord3b@example.com")
        r = client.post("/billing/orders", json={"goodsId": "sub_99", "currency": "USD"},
                        headers={"Authorization": f"Bearer {token}"})
        order_no = r.json()["orderNo"]
        r = client.get(f"/billing/orders/{order_no}",
                       headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["status"] == "PAID"
        assert d["credits"] == 8500
        r = client.get("/account/balance", headers={"Authorization": f"Bearer {token}"})
        assert r.json()["credits"] == 8600

    def test_query_order_not_found(self, client):
        token, uid = _email_register(client, "ord4@example.com")
        r = client.get("/billing/orders/ONOTEXIST",
                       headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 404

    def test_membership_order_extends_expire(self, client):
        """购买会员商品 → member_expire_at 延长。"""
        token, uid = _email_register(client, "ord5@example.com")
        r = client.post("/billing/orders", json={"goodsId": "sub_19"},
                        headers={"Authorization": f"Bearer {token}"})
        order_no = r.json()["orderNo"]
        client.get(f"/billing/orders/{order_no}",
                   headers={"Authorization": f"Bearer {token}"})
        r = client.get("/account/balance",
                       headers={"Authorization": f"Bearer {token}"})
        assert r.json()["membershipActive"] is True
        assert r.json()["membershipExpireAt"] > 0

    def test_billing_goods(self, client):
        """商品目录接口。"""
        token, uid = _email_register(client, "ord6@example.com")
        r = client.get("/billing/goods", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        items = r.json()["items"]
        assert len(items) == len(GOODS_BY_ID)
        assert any(g["id"] == "sub_19" for g in items)
        assert next(g for g in items if g["id"] == "sub_99")["priceUsdCents"] == 6990
        assert next(g for g in items if g["id"] == "sub_99")["usdCredits"] == 3500
        assert next(g for g in items if g["id"] == "sub_99")["usdBonusCredits"] == 5000

    def test_subscription_renew_rejects_non_mock_provider(self, client, monkeypatch):
        """真支付模式下,公开续费接口不能直接给用户加积分/顺延会员。"""
        token, uid = _email_register(client, "ord7@example.com")
        with closing(db()) as c:
            c.execute("UPDATE users SET sub_goods_id = ? WHERE user_id = ?", ("sub_19", uid))
            c.commit()
        monkeypatch.setattr(app_module, "PAYMENT_PROVIDER", "wechat")
        r = client.post("/billing/subscription/renew", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 403

    def test_create_order_pending_limit(self, client, monkeypatch):
        """未支付订单过多时拒绝继续创建,避免刷 PENDING 脏单。"""
        monkeypatch.setattr(app_module, "PENDING_ORDER_LIMIT", 1)
        token, uid = _email_register(client, "ord8@example.com")
        r = client.post("/billing/orders", json={"goodsId": "sub_19"},
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        r = client.post("/billing/orders", json={"goodsId": "sub_19"},
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 429

    def test_create_order_rejects_unconfigured_real_payment_without_order(self, client, monkeypatch):
        """真支付配置缺失时不创建订单。"""
        token, uid = _email_register(client, "ord9@example.com")
        monkeypatch.setattr(app_module, "PAYMENT_PROVIDER", "wechat")
        monkeypatch.delenv("WECHAT_APP_ID", raising=False)
        monkeypatch.delenv("WECHAT_PRIVATE_KEY", raising=False)
        r = client.post("/billing/orders", json={"goodsId": "sub_19"},
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 503
        with closing(db()) as c:
            n = c.execute("SELECT COUNT(*) n FROM orders WHERE user_id = ?", (uid,)).fetchone()["n"]
        assert n == 0


# ═══════════════════════════════════════════════════════════════════════
# 11. 每日签到 & 模型目录
# ═══════════════════════════════════════════════════════════════════════
class TestDailyClaim:
    def test_daily_claim_first_time(self, client):
        token, uid = _email_register(client, "dc1@example.com")
        r = client.post("/account/daily-claim",
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["claimed"] is True
        assert d["credits"] == 20
        assert d["balance"] == 120  # 100 + 20

    def test_daily_claim_twice_same_day(self, client):
        """同一天重复签到 → claimed=False。"""
        token, uid = _email_register(client, "dc2@example.com")
        client.post("/account/daily-claim",
                    headers={"Authorization": f"Bearer {token}"})
        r = client.post("/account/daily-claim",
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["claimed"] is False
        assert d["credits"] == 0


class TestModels:
    def test_list_models(self, client):
        r = client.get("/v1/models")
        assert r.status_code == 200
        d = r.json()
        assert d["object"] == "list"
        assert len(d["data"]) >= 2
        assert any(m["id"] == "agnes-2.0-flash" for m in d["data"])

    def test_list_models_public(self, client):
        """/v1/models 不需要鉴权。"""
        r = client.get("/v1/models")
        assert r.status_code == 200


# ═══════════════════════════════════════════════════════════════════════
# 12. 官网远程控制台配对
# ═══════════════════════════════════════════════════════════════════════
class TestRemotePairing:
    def test_pair_start_and_claim_device(self, client):
        token, uid = _email_register(client, "remote1@example.com")
        headers = {"Authorization": f"Bearer {token}"}

        r = client.post("/remote/pair/start", json={"deviceName": "测试手机"}, headers=headers)
        assert r.status_code == 200
        code = r.json()["code"]
        assert len(code) == 6
        assert r.json()["ttlSeconds"] == 300

        r = client.post(
            "/remote/pair/claim",
            json={"code": code, "deviceName": "测试手机", "deviceId": "dev_test_1"},
            headers=headers,
        )
        assert r.status_code == 200
        d = r.json()
        assert d["deviceId"] == "dev_test_1"
        assert d["deviceToken"].startswith("rt_")

        r = client.get("/remote/devices", headers=headers)
        assert r.status_code == 200
        items = r.json()["items"]
        assert len(items) == 1
        assert items[0]["deviceName"] == "测试手机"
        assert items[0]["online"] is False
        assert items[0]["revoked"] is False

    def test_pair_claim_requires_same_user(self, client):
        token_a, _ = _email_register(client, "remote2a@example.com")
        token_b, _ = _email_register(client, "remote2b@example.com")
        code = client.post(
            "/remote/pair/start",
            json={},
            headers={"Authorization": f"Bearer {token_a}"},
        ).json()["code"]

        r = client.post(
            "/remote/pair/claim",
            json={"code": code, "deviceName": "不该成功"},
            headers={"Authorization": f"Bearer {token_b}"},
        )
        assert r.status_code == 403

    def test_revoke_remote_device(self, client):
        token, uid = _email_register(client, "remote3@example.com")
        headers = {"Authorization": f"Bearer {token}"}
        code = client.post("/remote/pair/start", json={}, headers=headers).json()["code"]
        client.post(
            "/remote/pair/claim",
            json={"code": code, "deviceName": "待撤销", "deviceId": "dev_revoke_1"},
            headers=headers,
        )

        r = client.post("/remote/devices/dev_revoke_1/revoke", headers=headers)
        assert r.status_code == 200
        assert r.json()["ok"] is True

        r = client.get("/remote/devices", headers=headers)
        assert r.status_code == 200
        assert r.json()["items"][0]["revoked"] is True

    @staticmethod
    def _pair_device(client, email, device_id):
        token, uid = _email_register(client, email)
        headers = {"Authorization": f"Bearer {token}"}
        code = client.post("/remote/pair/start", json={}, headers=headers).json()["code"]
        pair = client.post("/remote/pair/claim", json={"code": code, "deviceId": device_id},
                           headers=headers).json()
        device_url = f"/remote/device/ws?device_id={device_id}&device_token={pair['deviceToken']}"
        console_url = f"/remote/console/ws?device_id={device_id}&token={token}"
        return token, uid, headers, device_url, console_url

    def test_revoke_closes_live_device_and_console(self, client):
        """撤销后服务端主动断开设备与控制台;无视 revoked 通知的客户端也无法继续中转。"""
        _, _, headers, device_url, console_url = self._pair_device(
            client, "remote-live1@example.com", "dev_live_revoke")
        with client.websocket_connect(device_url) as device:
            assert device.receive_json()["type"] == "hello"
            with client.websocket_connect(console_url) as console:
                assert console.receive_json()["online"] is True
                # 撤销前双向中转正常
                console.send_json({"type": "control", "action": "home", "id": "before"})
                assert device.receive_json()["id"] == "before"
                device.send_json({"type": "result", "id": "before", "success": True})
                assert console.receive_json()["id"] == "before"

                assert client.post("/remote/devices/dev_live_revoke/revoke", headers=headers).status_code == 200
                assert device.receive_json()["type"] == "revoked"
                console.send_json({"type": "control", "action": "home", "id": "after-revoke"})
                # 下一帧必须是关闭帧,而不是转发来的指令
                with pytest.raises(WebSocketDisconnect):
                    device.receive_json()
                with pytest.raises(WebSocketDisconnect):
                    console.receive_json()

    def test_ban_via_db_stops_forwarding(self, client):
        """直接改库封号(绕过 ban 端点):转发边界复核拦截,并断开设备与控制台。"""
        _, uid, _, device_url, console_url = self._pair_device(
            client, "remote-live2@example.com", "dev_live_ban_db")
        with client.websocket_connect(device_url) as device:
            assert device.receive_json()["type"] == "hello"
            with client.websocket_connect(console_url) as console:
                assert console.receive_json()["online"] is True
                with closing(db()) as c:
                    c.execute("UPDATE users SET banned=1 WHERE user_id=?", (uid,))
                    c.commit()
                console.send_json({"type": "control", "action": "back", "id": "after-ban"})
                with pytest.raises(WebSocketDisconnect):
                    device.receive_json()
                with pytest.raises(WebSocketDisconnect):
                    console.receive_json()

    def test_ban_via_db_stops_device_to_console(self, client):
        """直接改库封号后,设备侧上行消息也不再转发给控制台。"""
        _, uid, _, device_url, console_url = self._pair_device(
            client, "remote-live3@example.com", "dev_live_ban_up")
        with client.websocket_connect(device_url) as device:
            assert device.receive_json()["type"] == "hello"
            with client.websocket_connect(console_url) as console:
                assert console.receive_json()["online"] is True
                with closing(db()) as c:
                    c.execute("UPDATE users SET banned=1 WHERE user_id=?", (uid,))
                    c.commit()
                device.send_json({"type": "result", "id": "after-ban", "success": True})
                with pytest.raises(WebSocketDisconnect):
                    console.receive_json()
                with pytest.raises(WebSocketDisconnect):
                    device.receive_json()

    def test_admin_ban_closes_live_sockets(self, client):
        """管理员封号端点:即时断开该用户在线的设备与控制台。"""
        _, uid, _, device_url, console_url = self._pair_device(
            client, "remote-live4@example.com", "dev_live_ban_admin")
        with client.websocket_connect(device_url) as device:
            assert device.receive_json()["type"] == "hello"
            with client.websocket_connect(console_url) as console:
                assert console.receive_json()["online"] is True
                r = client.post(f"/admin/api/users/{uid}/ban", json={"banned": True, "reason": "t"},
                                headers={"X-Admin-Token": "test-token"})
                assert r.status_code == 200
                with pytest.raises(WebSocketDisconnect):
                    device.receive_json()
                with pytest.raises(WebSocketDisconnect):
                    console.receive_json()

    def test_remote_console_page_public_shell(self, client):
        r = client.get("/remote/console")
        assert r.status_code == 200
        assert "Octopus 远程控制台" in r.text


class TestVideoPollOwnership:
    def test_poll_other_users_video_is_404_without_upstream(self, client, monkeypatch):
        """B 轮询 A 提交的视频 id → 404 且不打上游;A 本人仍可 200。"""
        import httpx
        requests = []

        class FixtureUpstream:
            def __init__(self, *args, **kwargs):
                pass

            async def __aenter__(self):
                return self

            async def __aexit__(self, *args):
                pass

            async def post(self, url, *, headers, json):
                requests.append(("post", url))
                return httpx.Response(200, json={"video_id": "vid-private-a"})

            async def get(self, url, *, params, headers):
                requests.append(("get", params["video_id"]))
                return httpx.Response(200, json={"video_id": params["video_id"], "status": "completed"})

        monkeypatch.setattr(httpx, "AsyncClient", FixtureUpstream)
        monkeypatch.setattr(app_module, "_agnes_upstream", lambda: ("https://fixture.invalid/v1", "fixture-key"))
        token_a, _ = _email_register(client, "video-a@example.com")
        token_b, _ = _email_register(client, "video-b@example.com")
        r = client.post("/v1/video/generations", json={"prompt": "p"},
                        headers={"Authorization": f"Bearer {token_a}"})
        assert r.status_code == 200, r.text
        vid = r.json()["video_id"]

        r = client.get(f"/v1/video/generations/{vid}", headers={"Authorization": f"Bearer {token_b}"})
        assert r.status_code == 404
        assert not any(kind == "get" for kind, _ in requests)
        r = client.get("/v1/video/generations/never-submitted",
                       headers={"Authorization": f"Bearer {token_b}"})
        assert r.status_code == 404
        assert not any(kind == "get" for kind, _ in requests)

        r = client.get(f"/v1/video/generations/{vid}", headers={"Authorization": f"Bearer {token_a}"})
        assert r.status_code == 200
        assert r.json()["status"] == "completed"
        assert requests[-1] == ("get", vid)


# ═══════════════════════════════════════════════════════════════════════
# 13. 会员/积分/计费模型增强
# ═══════════════════════════════════════════════════════════════════════
class TestMembership:
    def test_membership_inactive_for_new_user(self, client):
        token, uid = _email_register(client, "mem1@example.com")
        r = client.get("/account/membership", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["active"] is False
        assert d["expireAt"] == 0
        assert d["remainingDays"] == 0
        assert any("解锁自有模型" in b for b in d["benefits"])

    def test_membership_active_after_order(self, client):
        token, uid = _email_register(client, "mem2@example.com")
        r = client.post("/billing/orders", json={"goodsId": "sub_19"},
                        headers={"Authorization": f"Bearer {token}"})
        order_no = r.json()["orderNo"]
        client.get(f"/billing/orders/{order_no}", headers={"Authorization": f"Bearer {token}"})
        r = client.get("/account/membership", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["active"] is True
        assert d["expireAt"] > 0
        assert d["remainingDays"] >= 29


class TestCreditLedger:
    def test_signup_recorded_in_ledger(self, client):
        token, uid = _email_register(client, "ledger1@example.com")
        r = client.get("/account/credits/transactions", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["total"] >= 1
        assert any(tx["source"] == "signup" and tx["delta"] == 100 for tx in d["items"])

    def test_daily_claim_recorded_in_ledger(self, client):
        token, uid = _email_register(client, "ledger2@example.com")
        client.post("/account/daily-claim", headers={"Authorization": f"Bearer {token}"})
        r = client.get("/account/credits/transactions", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        assert any(tx["source"] == "daily" and tx["delta"] == 20 for tx in r.json()["items"])

    def test_order_settle_recorded_in_ledger(self, client):
        token, uid = _email_register(client, "ledger3@example.com")
        r = client.post("/billing/orders", json={"goodsId": "sub_19"},
                        headers={"Authorization": f"Bearer {token}"})
        order_no = r.json()["orderNo"]
        client.get(f"/billing/orders/{order_no}", headers={"Authorization": f"Bearer {token}"})
        r = client.get("/account/credits/transactions", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        assert any(tx["source"] == "order" and tx["delta"] == 1000 for tx in r.json()["items"])

    def test_admin_adjust_recorded_in_ledger(self, client):
        token, uid = _email_register(client, "ledger4@example.com")
        client.post(
            f"/admin/api/users/{uid}/credits",
            json={"delta": 50, "reason": "ledger test"},
            headers={"X-Admin-Token": "test-token"},
        )
        r = client.get("/account/credits/transactions", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        assert any(tx["source"] == "admin_adjust" and tx["delta"] == 50 for tx in r.json()["items"])

    def test_credit_transactions_pagination(self, client):
        token, uid = _email_register(client, "ledger5@example.com")
        r = client.get("/account/credits/transactions?limit=1", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert len(d["items"]) == 1
        assert d["total"] >= 1

    def test_gift_and_daily_usage_recorded_in_ledger(self, client, monkeypatch):
        """月度赠送/每日免费额度的抵扣与退还都必须写入 credit_transactions,解决账本不平。"""
        monkeypatch.setattr(app_module, "FREE_DAILY_CREDITS", 5)
        monkeypatch.setattr(app_module, "CREDITS_PER_1K_TOKENS", 1.0)
        token, uid = _email_register(client, "ledger6@example.com")
        with closing(db()) as c:
            c.execute(
                "UPDATE users SET credits = 10, gift_credits = 3, gift_month = ?, daily_free_used = 0, daily_free_date = ? "
                "WHERE user_id = ?",
                (app_module._this_month(), app_module._today_str(), uid),
            )
            c.commit()

        reserved = app_module._reserve_usage_credits(uid, 10, ref_id="ledger_hold")
        assert reserved == {"gift": 3, "daily": 5, "paid": 2}
        # 实际只产生 1 积分成本,会触发 gift/daily/paid 全部退回
        actual = app_module._reconcile_usage(uid, "agnes-2.0-flash", 1000, 0, 1.0, 10,
                                              ref_id="ledger_hold", reserved=reserved)
        assert actual == 1

        r = client.get("/account/credits/transactions", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        items = r.json()["items"]
        sources = {tx["source"]: tx for tx in items}
        # 抵扣
        assert sources["gift_consume"]["delta"] == -3
        assert sources["daily_consume"]["delta"] == -5
        assert sources["usage_hold"]["delta"] == -2
        # 退还:实际只消耗 1 积分且优先走 gift,因此 gift 退 2,其余全额退。
        assert sources["gift_refund"]["delta"] == 2
        assert sources["daily_refund"]["delta"] == 5
        assert sources["usage_refund"]["delta"] == 2
        # 关键:balanceAfter 反映总可用额度,而不是仅永久积分
        assert sources["usage_hold"]["balanceAfter"] == 8  # 18-10


class TestAccountUsage:
    def test_account_usage_empty(self, client):
        token, uid = _email_register(client, "usage1@example.com")
        r = client.get("/account/usage", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["total"] == 0
        assert d["summary"]["calls"] == 0

    def test_account_usage_records(self, client):
        token, uid = _email_register(client, "usage2@example.com")
        # 直接写入 usage_log
        with closing(db()) as c:
            c.execute(
                "INSERT INTO usage_log(user_id, model, tokens_in, tokens_out, credits, ts) "
                "VALUES(?,?,?,?,?,?)",
                (uid, "agnes-2.0-flash", 100, 50, 5, _now_ms()),
            )
            c.commit()
        r = client.get("/account/usage", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["total"] == 1
        assert d["summary"]["calls"] == 1
        assert d["summary"]["credits"] == 5
        assert d["items"][0]["model"] == "agnes-2.0-flash"


# ═══════════════════════════════════════════════════════════════════════
# 14. App 协议对齐:设备注册/心跳/上报/状态
# ═══════════════════════════════════════════════════════════════════════
class TestDeviceProtocol:
    def test_device_register_new(self, client):
        token, uid = _email_register(client, "dev1@example.com")
        r = client.post(
            "/device/register",
            json={"deviceName": "测试机", "deviceModel": "Pixel 8", "osVersion": "14",
                  "appVersion": "1.2.3", "pushToken": "push_xxx"},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 200
        d = r.json()
        assert d["deviceId"].startswith("d_")
        assert d["deviceToken"].startswith("dt_")
        assert d["deviceName"] == "测试机"

    def test_device_heartbeat(self, client):
        token, uid = _email_register(client, "dev2@example.com")
        reg = client.post("/device/register", json={"deviceName": "测试机"},
                          headers={"Authorization": f"Bearer {token}"}).json()
        device_id = reg["deviceId"]
        r = client.post(
            "/device/heartbeat",
            json={"deviceId": device_id, "battery": 75, "isCharging": True,
                  "currentApp": "com.example.app", "screenHash": "abc123"},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 200
        d = r.json()
        assert d["ok"] is True
        assert d["battery"] == 75

    def test_device_heartbeat_requires_device_id(self, client):
        token, uid = _email_register(client, "dev3@example.com")
        r = client.post("/device/heartbeat", json={"battery": 50},
                        headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 400

    def test_device_heartbeat_other_users_device_fails(self, client):
        token_a, _ = _email_register(client, "dev4a@example.com")
        token_b, _ = _email_register(client, "dev4b@example.com")
        device_id = client.post("/device/register", json={},
                                headers={"Authorization": f"Bearer {token_a}"}).json()["deviceId"]
        r = client.post("/device/heartbeat", json={"deviceId": device_id, "battery": 50},
                        headers={"Authorization": f"Bearer {token_b}"})
        assert r.status_code == 404

    def test_device_report(self, client):
        token, uid = _email_register(client, "dev5@example.com")
        device_id = client.post("/device/register", json={"deviceName": "测试机"},
                                headers={"Authorization": f"Bearer {token}"}).json()["deviceId"]
        r = client.post(
            "/device/report",
            json={"deviceId": device_id, "type": "crash", "payload": {"reason": "oom"}},
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 200
        assert r.json()["ok"] is True

    def test_device_status(self, client):
        token, uid = _email_register(client, "dev6@example.com")
        device_id = client.post("/device/register", json={"deviceName": "测试机", "osVersion": "14"},
                                headers={"Authorization": f"Bearer {token}"}).json()["deviceId"]
        client.post("/device/heartbeat", json={"deviceId": device_id, "battery": 60},
                    headers={"Authorization": f"Bearer {token}"})
        r = client.get(f"/device/{device_id}/status", headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        d = r.json()
        assert d["deviceId"] == device_id
        assert d["deviceName"] == "测试机"
        assert d["osVersion"] == "14"
        assert d["batteryLevel"] == 60
        assert d["lastHeartbeatAt"] > 0


# ═══════════════════════════════════════════════════════════════════════
# 20. 小程序投稿广场(/square/publish → registry_assets → 管理员审核 → 公开)
# ═══════════════════════════════════════════════════════════════════════
class TestSquarePublish:
    def _payload(self, **overrides):
        p = {
            "id": "gen_1751234567890",
            "name": "每日相册整理",
            "description": "自动整理相册生成回忆视频",
            "type": "mini-app",
            "version": "1.0.0",
            "html": "<html><body>Hello Mini App</body></html>",
            "actions": ["run"],
            "allow_tools": ["fs_read"],
            "allow_hosts": [],
            "allow_device": ["camera"],
        }
        p.update(overrides)
        return p

    def test_requires_auth(self, client):
        r = client.post("/square/publish", json=self._payload())
        assert r.status_code == 401

    def test_publish_then_pending_not_public(self, client):
        token, _ = _email_register(client, "square1@example.com")
        r = client.post("/square/publish", json=self._payload(),
                         headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200, r.text
        d = r.json()
        assert d["success"] is True
        assert d["data"]["status"] == "pending"

        # pending 不进公开目录
        r = client.get("/square/assets?type=plugin&kind=mini-app")
        assert r.json()["total"] == 0

    def test_rejects_empty_html(self, client):
        token, _ = _email_register(client, "square2@example.com")
        r = client.post("/square/publish", json=self._payload(html=""),
                         headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 400

    def test_rejects_missing_name(self, client):
        token, _ = _email_register(client, "square3@example.com")
        r = client.post("/square/publish", json=self._payload(name=""),
                         headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 400

    def test_other_user_cannot_hijack_id(self, client):
        token1, _ = _email_register(client, "square4a@example.com")
        client.post("/square/publish", json=self._payload(),
                    headers={"Authorization": f"Bearer {token1}"})
        token2, _ = _email_register(client, "square4b@example.com")
        r = client.post("/square/publish", json=self._payload(name="抢占"),
                         headers={"Authorization": f"Bearer {token2}"})
        assert r.status_code == 403

    def test_admin_approve_then_public_download(self, client):
        """完整闭环:投稿 → pending → 管理员批准(此前这一步因缺 request 参数必 500,已修)→ 公开可下载。"""
        token, _ = _email_register(client, "square5@example.com")
        client.post("/square/publish", json=self._payload(),
                    headers={"Authorization": f"Bearer {token}"})

        r = client.post("/admin/api/plugins/gen_1751234567890/approve",
                         headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200, r.text

        r = client.get("/square/assets?type=plugin&kind=mini-app")
        assert r.json()["total"] == 1

        r = client.get("/square/assets/plugin/gen_1751234567890/download")
        assert r.status_code == 200
        assert r.json()["data"]["body"] == "<html><body>Hello Mini App</body></html>"

    def test_admin_reject_then_still_hidden(self, client):
        """reject 同样此前因缺 request 参数必 500,已修。"""
        token, _ = _email_register(client, "square6@example.com")
        client.post("/square/publish", json=self._payload(),
                    headers={"Authorization": f"Bearer {token}"})

        r = client.post("/admin/api/plugins/gen_1751234567890/reject",
                         json={"reason": "测试拒绝"},
                         headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200, r.text

        r = client.get("/square/assets?type=plugin&kind=mini-app")
        assert r.json()["total"] == 0

    def test_republish_resets_to_pending(self, client):
        """已批准的资产被作者再次投稿(如改了内容)→ 重置回 pending,需重新审核。"""
        token, _ = _email_register(client, "square7@example.com")
        client.post("/square/publish", json=self._payload(),
                    headers={"Authorization": f"Bearer {token}"})
        client.post("/admin/api/plugins/gen_1751234567890/approve",
                    headers={"X-Admin-Token": "test-token"})
        assert client.get("/square/assets?type=plugin&kind=mini-app").json()["total"] == 1

        client.post("/square/publish", json=self._payload(version="1.1.0"),
                    headers={"Authorization": f"Bearer {token}"})
        r = client.get("/square/assets?type=plugin&kind=mini-app")
        assert r.json()["total"] == 0


# ═══════════════════════════════════════════════════════════════════════
# 20b. /square/assets 排序:下载量排行 / 趋势(下载速度)/ 最新 + download_count 回传
# ═══════════════════════════════════════════════════════════════════════
class TestSquareAssetSort:
    def _seed(self, slug, dl, created_ms, updated_ms):
        """直插一个已审核 mini-app,指定累计下载量与上架/更新时间(毫秒)。"""
        with closing(db()) as c:
            c.execute(
                "INSERT OR REPLACE INTO registry_assets("
                "id, slug, type, kind, version, name, description, category, tags, platforms, mode, "
                "author_id, status, checksum, body, body_size, download_count, created_at, updated_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (f"plugin/{slug}", slug, "plugin", "mini-app", "1.0.0", slug, "", "",
                 "[]", '["mobile"]', "mini-app", "u_seed", "approved", "", "<html></html>", 13, dl,
                 created_ms, updated_ms),
            )
            c.commit()

    def test_download_count_returned_and_increments(self, client):
        now = _now_ms()
        self._seed("app_a", dl=0, created_ms=now, updated_ms=now)
        # 列表信封回传 download_count(初始 0)
        item = client.get("/square/assets?type=plugin&kind=mini-app").json()["data"][0]
        assert item["download_count"] == 0
        # 下载一次 → +1
        d = client.get("/square/assets/plugin/app_a/download").json()["data"]
        assert d["download_count"] == 1
        # 列表复查已 +1(同 IP 60s 内重复下载不再计数,防刷榜,故这里只下载一次)
        assert client.get("/square/assets?type=plugin&kind=mini-app").json()["data"][0]["download_count"] == 1

    def test_sort_downloads_trending_latest_distinct(self, client):
        now = _now_ms()
        # X:总量最高但上架很久、更新居中;Y:总量最低但刚上架、最近更新;Z:三项都居中。
        # 三种排序刻意造成两两不同,证明各自走了不同的 ORDER BY。
        self._seed("app_x", dl=100, created_ms=now - 1_000_000_000, updated_ms=now - 20_000)
        self._seed("app_y", dl=5,   created_ms=now - 5_000,          updated_ms=now - 10_000)
        self._seed("app_z", dl=40,  created_ms=now - 200_000,        updated_ms=now - 30_000)

        def slugs(sort):
            r = client.get(f"/square/assets?type=plugin&kind=mini-app&sort={sort}")
            assert r.status_code == 200, r.text
            return [a["slug"] for a in r.json()["data"]]

        assert slugs("downloads") == ["app_x", "app_z", "app_y"]   # 累计下载量降序
        assert slugs("trending") == ["app_y", "app_z", "app_x"]    # 下载速度(量/上架时长)降序
        assert slugs("") == ["app_y", "app_x", "app_z"]            # 默认:updated_at 降序


# ═══════════════════════════════════════════════════════════════════════
# 21. 小程序投稿自动审核(硬规则自动拒 / 代码扫描标风险 / 从不自动通过)
# ═══════════════════════════════════════════════════════════════════════
class TestSquareModeration:
    def _payload(self, **overrides):
        p = {
            "id": "gen_mod_test_001",
            "name": "正常小程序",
            "description": "一个正常的描述",
            "type": "mini-app",
            "version": "1.0.0",
            "html": "<html><body>Hello</body></html>",
            "actions": [],
            "allow_tools": [],
            "allow_hosts": [],
            "allow_device": [],
        }
        p.update(overrides)
        return p

    def test_clean_content_stays_pending_not_auto_rejected(self, client):
        """干净内容:不该被自动拒绝,仍进 pending 人工队列(qwen 未配置时静默跳过)。"""
        token, _ = _email_register(client, "mod1@example.com")
        r = client.post("/square/publish", json=self._payload(),
                         headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200, r.text
        assert r.json()["data"]["status"] == "pending"

        rows = client.get("/admin/api/plugins?status=pending",
                           headers={"X-Admin-Token": "test-token"}).json()["data"]
        row = next(x for x in rows if x["slug"] == "gen_mod_test_001")
        assert row["moderation_status"] == ""

    def test_banned_keyword_in_name_auto_rejects(self, client):
        """命中示例违禁词 → 直接 status='rejected',不进 pending 队列,不需要人工点一下。"""
        token, _ = _email_register(client, "mod2@example.com")
        r = client.post("/square/publish", json=self._payload(name="加我微信约炮"),
                         headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200, r.text
        assert r.json()["data"]["status"] == "rejected"
        assert "已拒绝" in r.json()["message"]

        # pending 队列里看不到它(自动拒绝跳过了人工审核这一步,但不代表绕过审核上线)
        pending = client.get("/admin/api/plugins?status=pending",
                              headers={"X-Admin-Token": "test-token"}).json()["data"]
        assert not any(x["slug"] == "gen_mod_test_001" for x in pending)

        rejected = client.get("/admin/api/plugins?status=rejected",
                               headers={"X-Admin-Token": "test-token"}).json()["data"]
        row = next(x for x in rejected if x["slug"] == "gen_mod_test_001")
        assert row["moderation_status"] == "auto_rejected"
        assert "违禁词" in row["moderation_reason"]

        # 自动拒绝的东西无论如何都不会出现在公开目录
        assert client.get("/square/assets?type=plugin&kind=mini-app").json()["total"] == 0

    def test_banned_keyword_in_description_auto_rejects(self, client):
        token, _ = _email_register(client, "mod3@example.com")
        r = client.post("/square/publish", json=self._payload(description="内部消息稳赚不赔,联系六合彩庄家"),
                         headers={"Authorization": f"Bearer {token}"})
        assert r.json()["data"]["status"] == "rejected"

    def test_eval_pattern_flags_but_still_pending_for_human_review(self, client):
        """代码扫描命中危险模式 → 只标记(flagged),不自动拒绝——仍进人工队列,不误伤。"""
        token, _ = _email_register(client, "mod4@example.com")
        r = client.post(
            "/square/publish",
            json=self._payload(html="<html><script>eval(userInput)</script></html>"),
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.status_code == 200, r.text
        assert r.json()["data"]["status"] == "pending"  # 没有被硬拒
        assert "标记待人工复核" in r.json()["message"]

        rows = client.get("/admin/api/plugins?status=pending",
                           headers={"X-Admin-Token": "test-token"}).json()["data"]
        row = next(x for x in rows if x["slug"] == "gen_mod_test_001")
        assert row["moderation_status"] == "flagged"
        assert "eval" in row["moderation_reason"]

    def test_undeclared_external_host_flags(self, client):
        """html 里引用了没在 allow_hosts 声明的外部域名 → 标记(声明与实际行为不一致)。"""
        token, _ = _email_register(client, "mod5@example.com")
        r = client.post(
            "/square/publish",
            json=self._payload(
                html='<html><script src="https://evil-tracker.example.com/x.js"></script></html>',
                allow_hosts=["api.octoapk.com"],
            ),
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.json()["data"]["status"] == "pending"
        rows = client.get("/admin/api/plugins?status=pending",
                           headers={"X-Admin-Token": "test-token"}).json()["data"]
        row = next(x for x in rows if x["slug"] == "gen_mod_test_001")
        assert row["moderation_status"] == "flagged"
        assert "evil-tracker.example.com" in row["moderation_reason"]

    def test_declared_host_does_not_flag(self, client):
        """引用的域名在 allow_hosts 里声明过 → 不应被标记为「未声明」。"""
        token, _ = _email_register(client, "mod6@example.com")
        r = client.post(
            "/square/publish",
            json=self._payload(
                html='<html><script src="https://api.octoapk.com/x.js"></script></html>',
                allow_hosts=["api.octoapk.com"],
            ),
            headers={"Authorization": f"Bearer {token}"},
        )
        assert r.json()["data"]["status"] == "pending"
        rows = client.get("/admin/api/plugins?status=pending",
                           headers={"X-Admin-Token": "test-token"}).json()["data"]
        row = next(x for x in rows if x["slug"] == "gen_mod_test_001")
        assert row["moderation_status"] == ""

    def test_qwen_not_configured_never_blocks_publish(self, client):
        """qwen 未配置(测试环境默认如此)→ _qwen_moderate 静默返回 None,不阻塞/不拖慢投稿。"""
        token, _ = _email_register(client, "mod7@example.com")
        assert app_module.PROVIDERS.get("qwen", {}).get("api_key", "") == ""  # 确认测试环境确实未配置
        r = client.post("/square/publish", json=self._payload(),
                         headers={"Authorization": f"Bearer {token}"})
        assert r.status_code == 200
        assert r.json()["data"]["status"] == "pending"

    def test_qwen_flagged_verdict_marks_pending_not_rejected(self, client, monkeypatch):
        """qwen 判定「可疑-」→ 只标记(flagged),不自动拒绝。mock _qwen_complete 直接跑真实判断分支。"""
        async def fake_qwen(messages, max_tokens=1400, timeout=90):
            return "可疑-描述含糊,疑似诱导下载"
        monkeypatch.setattr(app_module, "_qwen_complete", fake_qwen)

        token, _ = _email_register(client, "mod8@example.com")
        r = client.post("/square/publish", json=self._payload(),
                         headers={"Authorization": f"Bearer {token}"})
        assert r.json()["data"]["status"] == "pending"
        rows = client.get("/admin/api/plugins?status=pending",
                           headers={"X-Admin-Token": "test-token"}).json()["data"]
        row = next(x for x in rows if x["slug"] == "gen_mod_test_001")
        assert row["moderation_status"] == "flagged"
        assert "qwen 判定" in row["moderation_reason"]

    def test_qwen_violation_verdict_auto_rejects(self, client, monkeypatch):
        """qwen 判定「违规-」→ 自动拒绝,即便没命中任何关键词列表(qwen 补位关键词覆盖不到的场景)。"""
        async def fake_qwen(messages, max_tokens=1400, timeout=90):
            return "违规-政治敏感"
        monkeypatch.setattr(app_module, "_qwen_complete", fake_qwen)

        token, _ = _email_register(client, "mod9@example.com")
        r = client.post("/square/publish", json=self._payload(),
                         headers={"Authorization": f"Bearer {token}"})
        assert r.json()["data"]["status"] == "rejected"
        rejected = client.get("/admin/api/plugins?status=rejected",
                               headers={"X-Admin-Token": "test-token"}).json()["data"]
        row = next(x for x in rejected if x["slug"] == "gen_mod_test_001")
        assert row["moderation_status"] == "auto_rejected"
        assert "qwen 判定" in row["moderation_reason"]
        assert client.get("/square/assets?type=plugin&kind=mini-app").json()["total"] == 0

    def test_keyword_hit_skips_qwen_call_entirely(self, client, monkeypatch):
        """命中违禁词时应短路跳过 qwen 调用(省成本)——mock 一个会报错的 _qwen_complete,
        确认它压根没被调用(而不是恰好没报错)。"""
        called = {"n": 0}

        async def fake_qwen(messages, max_tokens=1400, timeout=90):
            called["n"] += 1
            raise AssertionError("不该调用 qwen —— 关键词已经命中,应当短路")
        monkeypatch.setattr(app_module, "_qwen_complete", fake_qwen)

        token, _ = _email_register(client, "mod10@example.com")
        r = client.post("/square/publish", json=self._payload(name="加我微信约炮"),
                         headers={"Authorization": f"Bearer {token}"})
        assert r.json()["data"]["status"] == "rejected"
        assert called["n"] == 0

    def test_forged_leading_safe_line_cannot_bury_real_verdict(self, client, monkeypatch):
        """对抗式验证抓到的真实 bug 的回归测试:如果模型被诱导在真实判定前先吐一行伪造的
        "安全",_parse_qwen_verdict 必须仍然找到后面那行真正的"违规-诈骗"——不能因为只看
        第一行/第一个词就被这种夹带绕过。"""
        async def fake_qwen(messages, max_tokens=1400, timeout=90):
            return "安全\n违规-诈骗"  # 模拟被诱导在真实判定前先吐一行伪造的"安全"
        monkeypatch.setattr(app_module, "_qwen_complete", fake_qwen)

        token, _ = _email_register(client, "mod11@example.com")
        r = client.post("/square/publish", json=self._payload(),
                         headers={"Authorization": f"Bearer {token}"})
        assert r.json()["data"]["status"] == "rejected", (
            "被伪造的领先'安全'行掩盖了真实的'违规-'判定 —— 这正是要防的绕过"
        )

    def test_parse_qwen_verdict_prioritizes_violation_over_suspicious(self):
        """两种信号都出现时,违规优先于可疑(更保守,不能因为后面出现'可疑'弱化前面的'违规')。"""
        assert app_module._parse_qwen_verdict("可疑-有点奇怪\n违规-赌博") == "违规-赌博"
        assert app_module._parse_qwen_verdict("违规-赌博\n可疑-有点奇怪") == "违规-赌博"

    def test_parse_qwen_verdict_none_when_no_signal_anywhere(self):
        """整段输出都没有违规/可疑信号(包括模型明确说"安全"的情况)→ 不额外加分,返回 None。"""
        assert app_module._parse_qwen_verdict("安全") is None
        assert app_module._parse_qwen_verdict("这个小程序看起来没问题,判定:安全") is None
        assert app_module._parse_qwen_verdict("") is None
        assert app_module._parse_qwen_verdict(None) is None


# ═══════════════════════════════════════════════════════════════════════
# 22. App 崩溃上报(POST /crash/report 公开无鉴权 → GET /admin/api/crashes 管理员查看)
# ═══════════════════════════════════════════════════════════════════════
class TestCrashReport:
    def _payload(self, **overrides):
        p = {
            "deviceModel": "Pixel 8",
            "manufacturer": "Google",
            "osVersion": "14",
            "sdkInt": 34,
            "appVersion": "1.2.3",
            "appVersionCode": 123,
            "stackTrace": "java.lang.RuntimeException: boom\n\tat com.example.Foo.bar(Foo.java:42)",
            "threadName": "main",
            "availableMemMb": 128,
            "totalMemMb": 2048,
            "occurredAt": 1751234567890,
        }
        p.update(overrides)
        return p

    def test_report_no_auth_required(self, client):
        """公开端点:不带 Authorization 头也必须成功——崩溃可能发生在登录前。"""
        r = client.post("/crash/report", json=self._payload())
        assert r.status_code == 200, r.text
        assert r.json() == {"ok": True}

    def test_report_full_payload_stored(self, client):
        r = client.post("/crash/report", json=self._payload())
        assert r.status_code == 200, r.text

        rows = client.get("/admin/api/crashes",
                           headers={"X-Admin-Token": "test-token"}).json()["items"]
        assert len(rows) == 1
        row = rows[0]
        assert row["device_model"] == "Pixel 8"
        assert row["manufacturer"] == "Google"
        assert row["os_version"] == "14"
        assert row["sdk_int"] == 34
        assert row["app_version"] == "1.2.3"
        assert row["app_version_code"] == 123
        assert "RuntimeException" in row["stack_trace"]
        assert row["thread_name"] == "main"
        assert row["available_mem_mb"] == 128
        assert row["total_mem_mb"] == 2048
        assert row["occurred_at"] == 1751234567890
        assert row["client_ip"]  # 管理端点暴露 IP
        assert row["created_at"] > 0

    def test_report_partial_payload_still_succeeds(self, client):
        """残缺 payload(大部分字段缺失)必须仍然 200 并落库,而不是 400——严格校验会丢失崩溃记录,
        违背这个功能存在的意义。"""
        r = client.post("/crash/report", json={"stackTrace": "NullPointerException"})
        assert r.status_code == 200, r.text
        assert r.json() == {"ok": True}

        rows = client.get("/admin/api/crashes",
                           headers={"X-Admin-Token": "test-token"}).json()["items"]
        assert len(rows) == 1
        row = rows[0]
        assert row["stack_trace"] == "NullPointerException"
        assert row["device_model"] == ""
        assert row["sdk_int"] == 0
        assert row["available_mem_mb"] == 0

    def test_report_empty_body_rejected(self, client):
        """完全空 body(不是"部分字段缺失"而是压根没内容)→ 拒绝,这是唯一该拒的情况之一。"""
        r = client.post("/crash/report", json={})
        assert r.status_code == 400

    def test_report_oversized_stack_trace_truncated_not_rejected(self, client):
        """stackTrace 超过 8000 字符 → 服务端截断保存,而不是整条 400 拒绝(纵深防御:不信任
        客户端已经截断过)。"""
        huge_trace = "x" * 20000
        r = client.post("/crash/report", json=self._payload(stackTrace=huge_trace))
        assert r.status_code == 200, r.text

        rows = client.get("/admin/api/crashes",
                           headers={"X-Admin-Token": "test-token"}).json()["items"]
        assert len(rows) == 1
        assert len(rows[0]["stack_trace"]) == 8000

    def test_report_oversized_body_rejected(self, client):
        """整个 JSON body 超过 ~32KB → 413 拒绝(防滥用刷爆磁盘/内存),不落库。"""
        r = client.post("/crash/report", json=self._payload(stackTrace="y" * 100_000))
        assert r.status_code == 413

        rows = client.get("/admin/api/crashes",
                           headers={"X-Admin-Token": "test-token"}).json()["items"]
        assert len(rows) == 0

    def test_report_rate_limited_after_threshold(self, client):
        """同 IP 每小时最多 20 条 → 第 21 条 429。"""
        for _ in range(20):
            r = client.post("/crash/report", json=self._payload())
            assert r.status_code == 200
        r = client.post("/crash/report", json=self._payload())
        assert r.status_code == 429

    def test_admin_crashes_requires_token_401(self, client):
        """无 token → 401(与其它 /admin/api/* 端点一致)。"""
        client.post("/crash/report", json=self._payload())
        r = client.get("/admin/api/crashes", headers={"X-Admin-Token": "wrong"})
        assert r.status_code == 401

    def test_admin_crashes_503_without_admin_token_configured(self, client, monkeypatch):
        """未设 ADMIN_TOKEN → 503(默认关闭),与其它 admin 端点一致。"""
        monkeypatch.setattr(app_module, "ADMIN_TOKEN", "")
        r = client.get("/admin/api/crashes")
        assert r.status_code == 503

    def test_admin_crashes_returns_newest_first(self, client):
        client.post("/crash/report", json=self._payload(stackTrace="first"))
        client.post("/crash/report", json=self._payload(stackTrace="second"))
        client.post("/crash/report", json=self._payload(stackTrace="third"))

        rows = client.get("/admin/api/crashes",
                           headers={"X-Admin-Token": "test-token"}).json()["items"]
        assert len(rows) == 3
        assert [r["stack_trace"] for r in rows] == ["third", "second", "first"]

    def test_admin_crashes_endpoint_uses_admin_guard_dependency(self):
        """不只测行为,直接检查路由声明本身确实挂了 Depends(admin_guard)——防止将来有人重构时
        不小心把依赖去掉但behavior 测试恰好没抓到。"""
        route = next(r for r in app_module.app.routes
                     if getattr(r, "path", "") == "/admin/api/crashes")
        dep_calls = [d.call for d in route.dependant.dependencies]
        assert app_module.admin_guard in dep_calls

    def test_public_report_endpoint_has_no_actor_dependency(self):
        """契约要求 POST /crash/report 不能用 Depends(actor)(会要求登录+已配对设备,
        丢失这个功能最想抓住的那批崩溃)——直接检查路由声明确认没有挂 actor 依赖。"""
        route = next(r for r in app_module.app.routes
                     if getattr(r, "path", "") == "/crash/report")
        dep_calls = [d.call for d in route.dependant.dependencies]
        assert app_module.actor not in dep_calls


# ═══════════════════════════════════════════════════════════════════════
# 23. App 在线更新(OTA)—— GET /app/latest + /app/latest.apk
# ═══════════════════════════════════════════════════════════════════════
class TestAppUpdate:
    def test_latest_unconfigured_looks_like_up_to_date(self, client, monkeypatch):
        """未配置版本号/APK 缺失 → versionCode=0,客户端 vc<=BuildConfig.VERSION_CODE 恒真,
        天然表现为「已最新」而不是报错——不需要客户端另外判断一个 available 标志位。"""
        monkeypatch.setattr(app_module, "APP_UPDATE_VERSION_CODE", 0)
        r = client.get("/app/latest")
        assert r.status_code == 200
        data = r.json()
        assert data["versionCode"] == 0
        assert data["url"] == ""

    def test_latest_configured_but_apk_missing_still_looks_up_to_date(self, client, monkeypatch, tmp_path):
        """版本号配了,但 APK 文件实际不存在(运维还没放包)→ 同样不能谎报「有更新」,
        否则客户端会去下载一个 404 的地址。"""
        monkeypatch.setattr(app_module, "APP_UPDATE_VERSION_CODE", 99)
        monkeypatch.setattr(app_module, "APP_UPDATE_APK_PATH", str(tmp_path / "missing.apk"))
        r = client.get("/app/latest")
        assert r.json()["versionCode"] == 0

    def test_latest_and_apk_download(self, client, monkeypatch, tmp_path):
        apk = tmp_path / "octopus-app.apk"
        payload = b"fake universal apk bytes for endpoint test"
        apk.write_bytes(payload)
        monkeypatch.setattr(app_module, "APP_UPDATE_APK_PATH", str(apk))
        monkeypatch.setattr(app_module, "APP_UPDATE_VERSION_CODE", 42)
        monkeypatch.setattr(app_module, "APP_UPDATE_VERSION_NAME", "0.0.8")
        monkeypatch.setattr(app_module, "APP_UPDATE_NOTES", "修了一堆东西")
        monkeypatch.setattr(app_module, "APP_UPDATE_FORCE", True)

        r = client.get("/app/latest")
        assert r.status_code == 200
        data = r.json()
        assert data["versionCode"] == 42
        assert data["versionName"] == "0.0.8"
        assert data["notes"] == "修了一堆东西"
        assert data["force"] is True
        assert data["url"].endswith("/app/latest.apk")

        r2 = client.get("/app/latest.apk")
        assert r2.status_code == 200
        assert r2.content == payload
        assert r2.headers["content-type"] == "application/vnd.android.package-archive"

    def test_download_404_when_apk_missing(self, client, monkeypatch, tmp_path):
        monkeypatch.setattr(app_module, "APP_UPDATE_APK_PATH", str(tmp_path / "missing.apk"))
        r = client.get("/app/latest.apk")
        assert r.status_code == 404

    def test_no_auth_required(self):
        """检查更新不该要求已登录——契约就是公开访问,直接核对路由声明没挂任何鉴权依赖。"""
        route = next(r for r in app_module.app.routes
                     if getattr(r, "path", "") == "/app/latest")
        dep_calls = [d.call for d in route.dependant.dependencies]
        assert app_module.actor not in dep_calls
        assert app_module.admin_guard not in dep_calls


# ═══════════════════════════════════════════════════════════════════════
# 24. 创作者分成(/plugin/pay 分账 · 幂等 · 脱敏 · 排行榜)
# ═══════════════════════════════════════════════════════════════════════
def _seed_asset(author_id, plugin_id="myapp", status="approved"):
    """造一个已审核、归 author_id 所有的 plugin 资产。"""
    with closing(db()) as c:
        now = _now_ms()
        c.execute(
            "INSERT OR REPLACE INTO registry_assets("
            "id, slug, type, kind, version, name, description, category, tags, platforms, mode, "
            "author_id, status, checksum, body, body_size, created_at, updated_at) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            (f"plugin/{plugin_id}", plugin_id, "plugin", "mini-app", "1.0.0", plugin_id, "", "",
             "[]", '["mobile"]', "mini-app", author_id, status, "", "", 0, now, now),
        )
        c.commit()


def _credits_of(user_id):
    with closing(db()) as c:
        row = c.execute("SELECT credits FROM users WHERE user_id=?", (user_id,)).fetchone()
        return int(row["credits"]) if row else 0


class TestCreatorRevenue:
    def test_split_pays_author_70pct_atomically(self, client):
        """买家扣 100、作者(另一账号)得 70,两条流水都写,余额一致。"""
        buyer_tok, buyer = _email_register(client, "buyer@example.com")
        _, author = _email_register(client, "author@example.com")
        _seed_asset(author, "myapp")
        author_before = _credits_of(author)

        r = client.post("/plugin/pay",
                        json={"plugin_id": "myapp", "item": "vip", "credits": 100},
                        headers={"Authorization": f"Bearer {buyer_tok}"})
        assert r.status_code == 200, r.text
        d = r.json()["data"]
        assert d["creator_earned"] == 70
        assert _credits_of(author) == author_before + 70
        assert _credits_of(buyer) == 100 - 100  # 注册奖励 100 - 花掉 100
        # 响应绝不含原始 author_id,只有脱敏 handle
        assert "author_id" not in d
        assert d["author"] and d["author"] != author

    def test_self_purchase_no_split(self, client):
        """自购(买家==作者):不分成,不写第二条流水。"""
        tok, uid = _email_register(client, "solo@example.com")
        _seed_asset(uid, "myapp")
        r = client.post("/plugin/pay",
                        json={"plugin_id": "myapp", "item": "x", "credits": 50},
                        headers={"Authorization": f"Bearer {tok}"})
        assert r.json()["data"]["creator_earned"] == 0

    def test_insufficient_balance_402_no_payout(self, client):
        """余额不足 → 402,且作者一分不进(整笔回滚)。"""
        buyer_tok, _ = _email_register(client, "poor@example.com")
        _, author = _email_register(client, "rich_author@example.com")
        _seed_asset(author, "myapp")
        author_before = _credits_of(author)
        r = client.post("/plugin/pay",
                        json={"plugin_id": "myapp", "item": "x", "credits": 999},
                        headers={"Authorization": f"Bearer {buyer_tok}"})
        assert r.status_code == 402
        assert _credits_of(author) == author_before  # 没被加钱

    def test_idempotency_key_blocks_double_charge(self, client):
        """同一 idempotency_key 重复请求:只扣一次、只分一次,第二次标 duplicate。"""
        buyer_tok, buyer = _email_register(client, "retry@example.com")
        _, author = _email_register(client, "retry_author@example.com")
        _seed_asset(author, "myapp")
        body = {"plugin_id": "myapp", "item": "vip", "credits": 40, "idempotency_key": "abc-123"}
        h = {"Authorization": f"Bearer {buyer_tok}"}

        r1 = client.post("/plugin/pay", json=body, headers=h)
        assert r1.status_code == 200
        assert r1.json()["data"].get("duplicate") in (None, False)
        bal_after_first = _credits_of(buyer)
        author_after_first = _credits_of(author)

        r2 = client.post("/plugin/pay", json=body, headers=h)  # 重试同 key
        assert r2.status_code == 200
        assert r2.json()["data"]["duplicate"] is True
        # 第二次不再扣款/不再分成
        assert _credits_of(buyer) == bal_after_first
        assert _credits_of(author) == author_after_first

    def test_rounding_one_credit_pays_zero(self, client):
        """取整:1 积分购买 → int(1*0.7)=0,作者拿 0(已知行为,锁死防回归)。"""
        buyer_tok, _ = _email_register(client, "penny@example.com")
        _, author = _email_register(client, "penny_author@example.com")
        _seed_asset(author, "myapp")
        before = _credits_of(author)
        r = client.post("/plugin/pay",
                        json={"plugin_id": "myapp", "item": "x", "credits": 1},
                        headers={"Authorization": f"Bearer {buyer_tok}"})
        assert r.json()["data"]["creator_earned"] == 0
        assert _credits_of(author) == before

    def test_ranking_masks_internal_user_id(self, client):
        """排行榜绝不下发原始 author_id:user_id 是哈希、nickname 是展示名。"""
        buyer_tok, buyer = _email_register(client, "rbuyer@example.com")
        _, author = _email_register(client, "rauthor@example.com")
        _seed_asset(author, "myapp")
        client.post("/plugin/pay",
                    json={"plugin_id": "myapp", "item": "vip", "credits": 100},
                    headers={"Authorization": f"Bearer {buyer_tok}"})
        r = client.get("/creator/ranking", headers={"Authorization": f"Bearer {buyer_tok}"})
        assert r.status_code == 200
        leaders = r.json()["data"]["leaders"]
        assert leaders, "应有至少一个上榜作者"
        top = leaders[0]
        assert top["user_id"] != author          # 不是原始账号 id
        assert len(top["user_id"]) == 12          # 是 12 位哈希
        assert "nickname" in top

    def test_dashboard_only_shows_own_assets(self, client):
        """看板只返回本人作品,不泄露他人。"""
        tok_a, a = _email_register(client, "da@example.com")
        _, b = _email_register(client, "db@example.com")
        _seed_asset(a, "app_a")
        _seed_asset(b, "app_b")
        r = client.get("/creator/dashboard", headers={"Authorization": f"Bearer {tok_a}"})
        ids = {x["id"] for x in r.json()["data"]["assets"]}
        assert "plugin/app_a" in ids
        assert "plugin/app_b" not in ids


# ═══════════════════════════════════════════════════════════════════════
# 广场图文帖:id 去斜杠回归测试
#
# 历史 bug:帖子 id 形如 "post/<hex>" 含斜杠,Starlette 单段路由 {post_id}([^/]+)
# 不匹配斜杠 → 详情/点赞/收藏/评论全部 404。根治为 id 用下划线前缀(post_<hex>)+
# 启动幂等迁移把历史 "post/x" 改写成 "post_x"。这里端到端锁死两点:
#   ① 新发帖 id 不含斜杠,且详情/点赞/收藏/评论子路由都可路由(200,非 404)。
#   ② 历史含斜杠 id 及其在评论/点赞/收藏表里的外键引用,被 init_db 迁移一致改写。
# ═══════════════════════════════════════════════════════════════════════
def _clear_square_tables():
    with closing(db()) as c:
        for t in ("square_posts", "square_comments", "square_likes", "square_favorites"):
            c.execute(f"DELETE FROM {t}")
        c.commit()


def _approve_post(post_id: str):
    """把帖子直接置为 approved(生产走人工审核队列;测试里直接改状态)。"""
    with closing(db()) as c:
        c.execute("UPDATE square_posts SET status='approved' WHERE id=?", (post_id,))
        c.commit()


class TestSquarePostIdNoSlash:
    def test_published_post_id_has_no_slash(self, client):
        _clear_square_tables()
        tok, _ = _email_register(client, "poster@example.com")
        r = client.post(
            "/square/posts/publish",
            json={"title": "去斜杠回归帖", "content": "正文内容", "images": [], "tag": "测试"},
            headers={"Authorization": f"Bearer {tok}"},
        )
        assert r.status_code == 200, r.text
        pid = r.json()["postId"]
        assert pid.startswith("post_"), pid
        assert "/" not in pid, f"帖子 id 不能含斜杠(会让单段路由 404):{pid}"

    def test_detail_like_favorite_comment_routable(self, client):
        """全链路:发帖 → 审核通过 → 详情/点赞/收藏/发评论/评论列表都可路由(200,非 404)。"""
        _clear_square_tables()
        tok, _ = _email_register(client, "poster2@example.com")
        pid = client.post(
            "/square/posts/publish",
            json={"title": "端到端帖", "content": "正文", "images": [], "tag": "教程"},
            headers={"Authorization": f"Bearer {tok}"},
        ).json()["postId"]
        _approve_post(pid)
        auth = {"Authorization": f"Bearer {tok}"}

        # 详情
        r = client.get(f"/square/posts/{pid}", headers=auth)
        assert r.status_code == 200, r.text
        assert r.json()["post"]["id"] == pid
        assert r.json()["post"]["title"] == "端到端帖"

        # 点赞
        r = client.post(f"/square/posts/{pid}/like", headers=auth)
        assert r.status_code == 200, r.text
        assert r.json()["liked"] is True

        # 收藏
        r = client.post(f"/square/posts/{pid}/favorite", headers=auth)
        assert r.status_code == 200, r.text
        assert r.json()["favorited"] is True

        # 发评论
        r = client.post(f"/square/posts/{pid}/comments",
                        json={"content": "第一条评论"}, headers=auth)
        assert r.status_code == 200, r.text

        # 评论列表
        r = client.get(f"/square/posts/{pid}/comments", headers=auth)
        assert r.status_code == 200, r.text
        assert any(cmt["content"] == "第一条评论" for cmt in r.json()["comments"])

        # 详情回读:liked/favorited/评论数已更新
        detail = client.get(f"/square/posts/{pid}", headers=auth).json()["post"]
        assert detail["liked"] is True and detail["favorited"] is True
        assert detail["commentsCount"] == 1

    def test_startup_migration_rewrites_legacy_slash_ids(self, client):
        """历史含斜杠 id 及其在评论/点赞/收藏表里的外键引用,被 init_db 幂等迁移改写。"""
        _clear_square_tables()
        _, uid = _email_register(client, "legacy@example.com")
        legacy_id = "post/deadbeefcafe0001"
        migrated_id = "post_deadbeefcafe0001"
        now = _now_ms()
        with closing(db()) as c:
            c.execute(
                "INSERT INTO square_posts(id, author_id, title, content, cover_url, images, tag,"
                " status, moderation_status, moderation_reason, likes_count, comments_count,"
                " favorites_count, created_at, updated_at)"
                " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (legacy_id, uid, "历史帖", "正文", "", "[]", "教程", "approved", "", "",
                 1, 1, 1, now, now),
            )
            c.execute("INSERT INTO square_comments(id, post_id, user_id, content, parent_id, created_at)"
                      " VALUES(?,?,?,?,?,?)", ("c_legacy1", legacy_id, uid, "老评论", "", now))
            c.execute("INSERT INTO square_likes(post_id, user_id, created_at) VALUES(?,?,?)",
                      (legacy_id, uid, now))
            c.execute("INSERT INTO square_favorites(user_id, post_id, created_at) VALUES(?,?,?)",
                      (uid, legacy_id, now))
            c.commit()

        # 触发启动迁移(幂等)
        init_db()

        with closing(db()) as c:
            assert c.execute("SELECT COUNT(*) FROM square_posts WHERE id LIKE 'post/%'").fetchone()[0] == 0
            assert c.execute("SELECT COUNT(*) FROM square_comments WHERE post_id LIKE 'post/%'").fetchone()[0] == 0
            assert c.execute("SELECT COUNT(*) FROM square_likes WHERE post_id LIKE 'post/%'").fetchone()[0] == 0
            assert c.execute("SELECT COUNT(*) FROM square_favorites WHERE post_id LIKE 'post/%'").fetchone()[0] == 0
            # id 被改写为下划线前缀,内容原样保留
            row = c.execute("SELECT id, title FROM square_posts WHERE id=?", (migrated_id,)).fetchone()
            assert row is not None and row["title"] == "历史帖"
            assert c.execute("SELECT COUNT(*) FROM square_comments WHERE post_id=?", (migrated_id,)).fetchone()[0] == 1
            assert c.execute("SELECT COUNT(*) FROM square_likes WHERE post_id=?", (migrated_id,)).fetchone()[0] == 1
            assert c.execute("SELECT COUNT(*) FROM square_favorites WHERE post_id=?", (migrated_id,)).fetchone()[0] == 1

        # 迁移后帖子按新 id 可路由(历史 bug 的核心症状:此前这里 404)
        r = client.get(f"/square/posts/{migrated_id}")
        assert r.status_code == 200, r.text
        assert r.json()["post"]["id"] == migrated_id

        # 幂等:再跑一次迁移不炸、不误伤
        init_db()
        with closing(db()) as c:
            assert c.execute("SELECT COUNT(*) FROM square_posts WHERE id=?", (migrated_id,)).fetchone()[0] == 1


# ══════════════════════════════════════════════════════════════════════════════
# 灵感广场:小红书式图文社区 + 可复刻应用 + 付费积分复刻(作者自定价 + 平台抽成 + 机审自动上架)
# ══════════════════════════════════════════════════════════════════════════════
class TestSquareAppPosts:
    """覆盖:机审干净自动上架、分类过滤、acquire 免费/付费/幂等/402/400/401、盗挂防护。"""

    @pytest.fixture(autouse=True)
    def _no_qwen(self, monkeypatch):
        """屏蔽外部 Qwen 审核 → 机审干净,发帖走自动上架路径(确定性,不依赖网络/密钥)。"""
        async def _clean(*_a, **_k):
            return None
        monkeypatch.setattr(app_module, "_qwen_moderate", _clean)

    @staticmethod
    def _auth(tok):
        return {"Authorization": f"Bearer {tok}"}

    @staticmethod
    def _set_credits(uid, credits):
        with closing(db()) as c:
            c.execute("UPDATE users SET credits=? WHERE user_id=?", (credits, uid))
            c.commit()

    @staticmethod
    def _insert_paid_post(author_id, price, app_ref="r1", app_kind="routine", status="approved"):
        pid = f"post_{app_ref}"
        now = _now_ms()
        with closing(db()) as c:
            c.execute(
                "INSERT INTO square_posts(id, author_id, title, content, cover_url, images, "
                "tag, status, reject_reason, moderation_status, moderation_reason, "
                "likes_count, comments_count, favorites_count, created_at, updated_at, "
                "app_ref, app_kind, price_credits, topic) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,0,0,0,?,?,?,?,?,?)",
                (pid, author_id, "付费例程", "", "", "[]", "", status, "", "", "",
                 now, now, app_ref, app_kind, price, "automation"),
            )
            c.commit()
        return pid

    def test_text_post_auto_approved_and_in_feed(self, client):
        tok, _uid = _email_register(client, "poster@x.com")
        r = client.post("/square/posts/publish", headers=self._auth(tok),
                        json={"title": "灵感一则", "content": "自动化真香", "topic": "automation"})
        assert r.status_code == 200, r.text
        j = r.json()
        assert j["status"] == "approved"          # 机审干净 → 自动上架
        pid = j["postId"]
        posts = client.get("/square/feed").json()["posts"]
        p = next((x for x in posts if x["id"] == pid), None)
        assert p is not None
        assert p["appRef"] == "" and p["priceCredits"] == 0 and p["topic"] == "automation"
        assert "owned" in p

    def test_topic_filter(self, client):
        tok, _uid = _email_register(client, "poster2@x.com")
        client.post("/square/posts/publish", headers=self._auth(tok),
                    json={"title": "AA", "content": "x", "topic": "automation"})
        client.post("/square/posts/publish", headers=self._auth(tok),
                    json={"title": "BB", "content": "y", "topic": "life"})
        auto = client.get("/square/feed", params={"topic": "automation"}).json()["posts"]
        life = client.get("/square/feed", params={"topic": "life"}).json()["posts"]
        assert any(p["title"] == "AA" for p in auto)
        assert all(p["title"] != "BB" for p in auto)
        assert any(p["title"] == "BB" for p in life)

    def test_acquire_text_post_returns_400(self, client):
        tok, _uid = _email_register(client, "u400@x.com")
        pid = client.post("/square/posts/publish", headers=self._auth(tok),
                          json={"title": "纯图文", "content": "无应用"}).json()["postId"]
        r = client.post(f"/square/posts/{pid}/acquire", headers=self._auth(tok))
        assert r.status_code == 400

    def test_paid_acquire_deducts_and_shares(self, client):
        _atok, author = _email_register(client, "author@x.com")
        self._set_credits(author, 0)
        btok, _buyer = _email_register(client, "buyer@x.com")
        self._set_credits(_buyer, 100)
        pid = self._insert_paid_post(author, 50)
        r = client.post(f"/square/posts/{pid}/acquire", headers=self._auth(btok))
        assert r.status_code == 200, r.text
        j = r.json()
        assert j["balance"] == 50 and j["owned"] is True
        assert j["creatorEarned"] == 35           # int(50 * 0.7)
        assert (j["app"] or {}).get("ref") == "r1"
        with closing(db()) as c:
            ac = c.execute("SELECT credits FROM users WHERE user_id=?", (author,)).fetchone()["credits"]
        assert ac == 35

    def test_acquire_is_idempotent(self, client):
        _atok, author = _email_register(client, "a2@x.com")
        btok, buyer = _email_register(client, "b2@x.com")
        self._set_credits(buyer, 100)
        pid = self._insert_paid_post(author, 30, app_ref="r2")
        client.post(f"/square/posts/{pid}/acquire", headers=self._auth(btok))
        j = client.post(f"/square/posts/{pid}/acquire", headers=self._auth(btok)).json()
        assert j["balance"] == 70                  # 只扣一次 30,不二次扣款
        assert j["creatorEarned"] == 0

    def test_acquire_insufficient_credits_402_no_charge(self, client):
        _atok, author = _email_register(client, "a3@x.com")
        btok, buyer = _email_register(client, "b3@x.com")
        self._set_credits(buyer, 10)
        pid = self._insert_paid_post(author, 50, app_ref="r3")
        r = client.post(f"/square/posts/{pid}/acquire", headers=self._auth(btok))
        assert r.status_code == 402
        with closing(db()) as c:
            bc = c.execute("SELECT credits FROM users WHERE user_id=?", (buyer,)).fetchone()["credits"]
            un = c.execute("SELECT 1 FROM square_unlocks WHERE user_id=? AND post_id=?",
                           (buyer, pid)).fetchone()
        assert bc == 10 and un is None             # 回滚干净:余额未动、无解锁记录

    def test_acquire_requires_auth(self, client):
        _atok, author = _email_register(client, "a4@x.com")
        pid = self._insert_paid_post(author, 50, app_ref="r4")
        assert client.post(f"/square/posts/{pid}/acquire").status_code == 401

    def test_publish_miniapp_ref_must_exist(self, client):
        tok, _uid = _email_register(client, "a5@x.com")
        r = client.post("/square/posts/publish", headers=self._auth(tok),
                        json={"title": "挂个不存在的应用", "content": "x",
                              "appRef": "nonexistent_app", "appKind": "mini-app", "priceCredits": 20})
        assert r.status_code == 400                # 关联的应用不存在


# ══════════════════════════════════════════════════════════════════════════════
# 插件按月积分订阅(手动续订:每次扣月费顺延 30 天;门控按 plugin_ref 查有效期)
# ══════════════════════════════════════════════════════════════════════════════
class TestSquareSubscription:
    """覆盖:首订扣款+分成、status 门控、续订顺延、余额不足 402、幂等、非订阅帖 400、订阅/一次性互斥。"""

    @pytest.fixture(autouse=True)
    def _no_qwen(self, monkeypatch):
        async def _clean(*_a, **_k):
            return None
        monkeypatch.setattr(app_module, "_qwen_moderate", _clean)

    @staticmethod
    def _auth(tok):
        return {"Authorization": f"Bearer {tok}"}

    @staticmethod
    def _set_credits(uid, credits):
        with closing(db()) as c:
            c.execute("UPDATE users SET credits=? WHERE user_id=?", (credits, uid))
            c.commit()

    @staticmethod
    def _insert_sub_post(author_id, monthly, app_ref="subapp"):
        pid = f"post_{app_ref}"
        now = _now_ms()
        with closing(db()) as c:
            c.execute(
                "INSERT INTO square_posts(id, author_id, title, content, cover_url, images, tag, status, "
                "reject_reason, moderation_status, moderation_reason, likes_count, comments_count, "
                "favorites_count, created_at, updated_at, app_ref, app_kind, price_credits, topic, sub_price_credits) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,0,0,0,?,?,?,?,?,?,?)",
                (pid, author_id, "订阅应用", "", "", "[]", "", "approved", "", "", "",
                 now, now, app_ref, "routine", 0, "efficiency", monthly),
            )
            c.commit()
        return pid

    @staticmethod
    def _sub_expire(uid, app_ref):
        with closing(db()) as c:
            r = c.execute("SELECT expire_at FROM plugin_subscriptions WHERE user_id=? AND plugin_ref=?",
                          (uid, app_ref)).fetchone()
        return int(r["expire_at"]) if r else 0

    def test_first_subscribe_deducts_and_shares(self, client):
        _atok, author = _email_register(client, "subauthor@x.com")
        self._set_credits(author, 0)
        btok, buyer = _email_register(client, "subbuyer@x.com")
        self._set_credits(buyer, 100)
        pid = self._insert_sub_post(author, 50)
        r = client.post(f"/square/posts/{pid}/subscribe", headers=self._auth(btok), json={})
        assert r.status_code == 200, r.text
        j = r.json()
        assert j["balance"] == 50 and j["creatorEarned"] == 35    # 100-50;int(50*0.7)
        assert (j["app"] or {}).get("ref") == "subapp"
        assert j["expireAt"] > _now_ms()
        with closing(db()) as c:
            ac = c.execute("SELECT credits FROM users WHERE user_id=?", (author,)).fetchone()["credits"]
        assert ac == 35

    def test_status_active_after_subscribe(self, client):
        _atok, author = _email_register(client, "s2a@x.com")
        btok, buyer = _email_register(client, "s2b@x.com")
        self._set_credits(buyer, 100)
        pid = self._insert_sub_post(author, 30, app_ref="s2app")
        client.post(f"/square/posts/{pid}/subscribe", headers=self._auth(btok), json={})
        j = client.get("/square/plugin/s2app/subscription", headers=self._auth(btok)).json()
        assert j["active"] is True and j["expireAt"] > _now_ms()

    def test_status_inactive_when_never_subscribed(self, client):
        btok, _b = _email_register(client, "s3@x.com")
        assert client.get("/square/plugin/whatever/subscription", headers=self._auth(btok)).json()["active"] is False

    def test_renew_extends_by_a_month(self, client):
        _atok, author = _email_register(client, "s4a@x.com")
        btok, buyer = _email_register(client, "s4b@x.com")
        self._set_credits(buyer, 100)
        pid = self._insert_sub_post(author, 30, app_ref="s4app")
        client.post(f"/square/posts/{pid}/subscribe", headers=self._auth(btok), json={})
        exp1 = self._sub_expire(buyer, "s4app")
        r = client.post(f"/square/posts/{pid}/subscribe", headers=self._auth(btok), json={})
        exp2 = self._sub_expire(buyer, "s4app")
        assert r.json()["balance"] == 40                          # 100-30-30
        assert exp2 - exp1 >= 29 * 24 * 3600 * 1000               # 顺延约一个月

    def test_insufficient_credits_402(self, client):
        _atok, author = _email_register(client, "s5a@x.com")
        btok, buyer = _email_register(client, "s5b@x.com")
        self._set_credits(buyer, 10)
        pid = self._insert_sub_post(author, 50, app_ref="s5app")
        r = client.post(f"/square/posts/{pid}/subscribe", headers=self._auth(btok), json={})
        assert r.status_code == 402
        assert self._sub_expire(buyer, "s5app") == 0              # 未产生订阅

    def test_idempotency_key_no_double_charge(self, client):
        _atok, author = _email_register(client, "s6a@x.com")
        btok, buyer = _email_register(client, "s6b@x.com")
        self._set_credits(buyer, 100)
        pid = self._insert_sub_post(author, 30, app_ref="s6app")
        client.post(f"/square/posts/{pid}/subscribe", headers=self._auth(btok), json={"idempotency_key": "k1"})
        j = client.post(f"/square/posts/{pid}/subscribe", headers=self._auth(btok), json={"idempotency_key": "k1"}).json()
        assert j.get("duplicate") is True
        with closing(db()) as c:
            bc = c.execute("SELECT credits FROM users WHERE user_id=?", (buyer,)).fetchone()["credits"]
        assert bc == 70                                           # 只扣一次 30

    def test_subscribe_non_subscription_post_400(self, client):
        _atok, author = _email_register(client, "s7a@x.com")
        btok, buyer = _email_register(client, "s7b@x.com")
        self._set_credits(buyer, 100)
        pid = self._insert_sub_post(author, 0, app_ref="s7app")   # sub_price=0 → 不是订阅帖
        assert client.post(f"/square/posts/{pid}/subscribe", headers=self._auth(btok), json={}).status_code == 400

    def test_publish_subscription_mutually_exclusive(self, client):
        tok, uid = _email_register(client, "s8@x.com")
        now = _now_ms()
        with closing(db()) as c:
            c.execute(
                "INSERT INTO registry_assets(id, slug, type, kind, version, name, description, category, tags, "
                "platforms, mode, author_id, status, reject_reason, moderation_status, moderation_reason, "
                "checksum, body, body_size, created_at, updated_at, author_earnings, download_count) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                ("plugin/s8app", "s8app", "plugin", "mini-app", "1.0.0", "S8", "", "", "[]",
                 '["mobile"]', "", uid, "approved", "", "", "", "sha256:x", "<html></html>", 13, now, now, 0, 0),
            )
            c.commit()
        r = client.post("/square/posts/publish", headers=self._auth(tok),
                        json={"title": "订阅应用帖", "content": "x", "appRef": "s8app", "appKind": "mini-app",
                              "priceCredits": 99, "subPriceCredits": 40})
        assert r.status_code == 200 and r.json()["status"] == "approved"
        pid = r.json()["postId"]
        with closing(db()) as c:
            row = c.execute("SELECT price_credits, sub_price_credits FROM square_posts WHERE id=?", (pid,)).fetchone()
        assert row["sub_price_credits"] == 40 and row["price_credits"] == 0    # 标了月价 → 一次性价清零


# ══════════════════════════════════════════════════════════════════════════════
# 动态定价/运营配置(config_kv:运维面板可改、即时生效免重部署;计费/奖励处 get_config 读)
# ══════════════════════════════════════════════════════════════════════════════
class TestDynamicConfig:
    """覆盖:表存在、get_config 回落默认、set/get 往返、admin 读/改、白名单拒绝、类型/负值校验、
    以及接线生效(改 daily_bonus → 每日签到即变、改 signup_bonus → 注册礼即变)。"""

    _ADMIN = {"X-Admin-Token": "test-token"}

    def test_table_exists(self, client):
        with closing(db()) as c:
            tbls = [r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")]
        assert "config_kv" in tbls

    def test_get_config_falls_back_to_default(self, client):
        # 无覆盖 → 回落代码默认
        assert app_module.get_config("daily_bonus", 20) == 20
        assert app_module.get_config("voice_credits_per_min", 5) == 5

    def test_set_get_roundtrip_and_typing(self, client):
        app_module.set_config("voice_credits_per_min", 8)
        assert app_module.get_config("voice_credits_per_min", 5) == 8       # int 强转
        app_module.set_config("nonexist_float", 3.5)
        assert app_module.get_config("nonexist_float", 0.0) == 3.5          # float 强转

    def test_admin_list_shows_keys(self, client):
        r = client.get("/admin/api/config", headers=self._ADMIN)
        assert r.status_code == 200
        keys = {i["key"] for i in r.json()["items"]}
        assert {"daily_bonus", "voice_credits_per_min", "voice_free_min_member"} <= keys

    def test_admin_list_requires_auth(self, client):
        assert client.get("/admin/api/config").status_code in (401, 403, 503)

    def test_admin_set_reflects(self, client):
        r = client.post("/admin/api/config", headers=self._ADMIN,
                        json={"key": "voice_credits_per_min", "value": 7})
        assert r.status_code == 200 and r.json()["value"] == 7
        got = client.get("/admin/api/config", headers=self._ADMIN).json()["items"]
        assert next(i for i in got if i["key"] == "voice_credits_per_min")["value"] == 7

    def test_admin_set_rejects_non_allowlisted(self, client):
        r = client.post("/admin/api/config", headers=self._ADMIN,
                        json={"key": "jwt_secret", "value": "hack"})
        assert r.status_code == 400

    def test_admin_set_rejects_negative(self, client):
        r = client.post("/admin/api/config", headers=self._ADMIN,
                        json={"key": "voice_credits_per_min", "value": -5})
        assert r.status_code == 400

    def test_wired_daily_bonus_takes_effect(self, client):
        # 改 daily_bonus=50 → 新用户签到到账 50(而非默认 20)
        app_module.set_config("daily_bonus", 50)
        tok, _uid = _email_register(client, "cfgdaily@x.com")
        r = client.post("/account/daily-claim", headers={"Authorization": f"Bearer {tok}"})
        assert r.status_code == 200 and r.json()["claimed"] is True
        assert r.json()["credits"] == 50

    def test_wired_signup_bonus_takes_effect(self, client):
        # 改 signup_bonus=300 → 新注册用户初始积分含 300 注册礼
        app_module.set_config("signup_bonus", 300)
        _tok, uid = _email_register(client, "cfgsignup@x.com")
        with closing(db()) as c:
            credits = c.execute("SELECT credits FROM users WHERE user_id=?", (uid,)).fetchone()["credits"]
        assert credits == 300


# ══════════════════════════════════════════════════════════════════════════════
# 定时真实成本核算(cost_snapshots:后台定时把成本/毛利快照存成时序,喂面板 + AI 顾问)
# ══════════════════════════════════════════════════════════════════════════════
class TestCostSnapshots:
    """覆盖:表存在、_take_cost_snapshot 落库+返回、admin 时序查询、手动触发、间隔可配、鉴权。"""

    _ADMIN = {"X-Admin-Token": "test-token"}

    def test_table_exists(self, client):
        with closing(db()) as c:
            tbls = [r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")]
        assert "cost_snapshots" in tbls

    def test_take_snapshot_inserts_and_returns(self, client):
        snap = app_module._take_cost_snapshot()
        assert "costFull" in snap and "costPerCreditFull" in snap and "revenue" in snap
        with closing(db()) as c:
            n = c.execute("SELECT COUNT(*) FROM cost_snapshots").fetchone()[0]
        assert n == 1

    def test_cost_trend_endpoint(self, client):
        app_module._take_cost_snapshot()
        app_module._take_cost_snapshot()
        r = client.get("/admin/api/cost-trend", headers=self._ADMIN)
        assert r.status_code == 200
        j = r.json()
        assert j["count"] == 2
        assert "cost_per_credit_full" in j["snapshots"][0]

    def test_cost_trend_requires_auth(self, client):
        assert client.get("/admin/api/cost-trend").status_code in (401, 403, 503)

    def test_manual_snapshot_endpoint(self, client):
        r = client.post("/admin/api/cost-trend/snapshot", headers=self._ADMIN)
        assert r.status_code == 200 and r.json()["ok"] is True
        assert "costFull" in r.json()["snapshot"]
        with closing(db()) as c:
            assert c.execute("SELECT COUNT(*) FROM cost_snapshots").fetchone()[0] == 1

    def test_interval_is_configurable(self, client):
        # 间隔在白名单里,可被动态配置改
        assert "cost_snapshot_interval_hours" in app_module.PRICING_KEYS
        app_module.set_config("cost_snapshot_interval_hours", 12)
        assert app_module.get_config("cost_snapshot_interval_hours", 6) == 12


# ══════════════════════════════════════════════════════════════════════════════
# AI 调价建议(提议→人工批准):AI 产结构化建议存 pending,admin 采纳才写 config_kv
# ══════════════════════════════════════════════════════════════════════════════
class TestPricingAdvisor:
    """覆盖:表存在、AI 提议落库(白名单/护栏/去重过滤)、列出、采纳生效、驳回不改、鉴权。"""

    _ADMIN = {"X-Admin-Token": "test-token"}

    @staticmethod
    def _mock_qwen(monkeypatch, arr):
        async def _fake(*_a, **_k):
            return json.dumps(arr, ensure_ascii=False)
        monkeypatch.setattr(app_module, "_qwen_complete", _fake)

    def test_table_exists(self, client):
        with closing(db()) as c:
            tbls = [r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")]
        assert "pricing_proposals" in tbls

    def test_advise_stores_valid_proposal(self, client, monkeypatch):
        self._mock_qwen(monkeypatch, [{"key": "voice_credits_per_min", "suggested": 8, "reason": "毛利偏薄"}])
        r = client.post("/admin/api/pricing/advise", headers=self._ADMIN)
        assert r.status_code == 200
        props = r.json()["proposals"]
        assert len(props) == 1 and props[0]["key"] == "voice_credits_per_min" and props[0]["suggested"] == 8
        with closing(db()) as c:
            row = c.execute("SELECT status FROM pricing_proposals").fetchone()
        assert row["status"] == "pending"     # 未自动生效

    def test_advise_filters_bad_key_and_guardrails(self, client, monkeypatch):
        self._mock_qwen(monkeypatch, [
            {"key": "jwt_secret", "suggested": 1, "reason": "非白名单"},          # 白名单外 → 丢
            {"key": "voice_credits_per_min", "suggested": -3, "reason": "负"},     # 负 → 丢
            {"key": "voice_credits_per_min", "suggested": 999999, "reason": "离谱"},  # 超护栏 → 夹到上限、仍入库
        ])
        r = client.post("/admin/api/pricing/advise", headers=self._ADMIN)
        props = r.json()["proposals"]
        assert len(props) == 1                                   # 只剩被夹住的那条
        assert props[0]["suggested"] <= max(5 * 10, 100)         # 夹在 [0,100] 内(默认5×10=50→上限100)

    def test_approve_applies_config(self, client, monkeypatch):
        self._mock_qwen(monkeypatch, [{"key": "daily_bonus", "suggested": 33, "reason": "x"}])
        pid = client.post("/admin/api/pricing/advise", headers=self._ADMIN).json()["proposals"][0]["id"]
        r = client.post(f"/admin/api/pricing/proposals/{pid}/approve", headers=self._ADMIN)
        assert r.status_code == 200 and r.json()["value"] == 33
        assert app_module.get_config("daily_bonus", 20) == 33     # 已写进 config_kv 生效
        # 重复采纳 → 400(已处理)
        assert client.post(f"/admin/api/pricing/proposals/{pid}/approve", headers=self._ADMIN).status_code == 400

    def test_reject_does_not_change_config(self, client, monkeypatch):
        self._mock_qwen(monkeypatch, [{"key": "voice_credits_per_min", "suggested": 9, "reason": "x"}])
        pid = client.post("/admin/api/pricing/advise", headers=self._ADMIN).json()["proposals"][0]["id"]
        assert client.post(f"/admin/api/pricing/proposals/{pid}/reject", headers=self._ADMIN).status_code == 200
        assert app_module.get_config("voice_credits_per_min", 5) == 5   # 未变
        with closing(db()) as c:
            assert c.execute("SELECT status FROM pricing_proposals WHERE id=?", (pid,)).fetchone()["status"] == "rejected"

    def test_list_and_auth(self, client, monkeypatch):
        self._mock_qwen(monkeypatch, [{"key": "daily_bonus", "suggested": 25, "reason": "x"}])
        client.post("/admin/api/pricing/advise", headers=self._ADMIN)
        r = client.get("/admin/api/pricing/proposals", headers=self._ADMIN)
        assert r.status_code == 200 and len(r.json()["proposals"]) == 1
        assert client.get("/admin/api/pricing/proposals").status_code in (401, 403, 503)


# ═══════════════════════════════════════════════════════════════════════
# 实时语音代理(/voice/realtime + /voice/quota + _VoiceMeter 计费)
# ═══════════════════════════════════════════════════════════════════════
class _FakeUpstream:
    """假的上游实时 WS:每收到一条 client 消息就回一条 audio.delta,便于测中继。"""

    def __init__(self):
        self.sent = []
        self._q = asyncio.Queue()
        self.closed = False

    async def send(self, msg):
        self.sent.append(msg)
        await self._q.put(json.dumps({"type": "response.audio.delta", "delta": "QUJD"}))

    async def recv(self):
        return await self._q.get()

    async def close(self):
        self.closed = True


class TestVoiceMeter:
    """计费状态机:免费分钟优先 → 扣积分 → 单次封顶。纯单元,不走 WS。"""

    def test_free_then_paid(self, client):
        _tok, uid = _email_register(client)
        app_module.set_config("voice_free_min_guest", 2)
        app_module.set_config("voice_credits_per_min", 5)
        app_module.set_config("voice_max_session_min", 30)
        with closing(db()) as c:
            bal0 = app_module._total_available(c, uid)
        m = app_module._VoiceMeter(uid, False, "vs_x")
        assert m.charge_minute() == (True, "free")   # 第1分钟免费
        assert m.charge_minute() == (True, "free")   # 第2分钟免费
        assert m.charge_minute() == (True, "paid")   # 第3分钟扣 5
        assert m.charge_minute() == (True, "paid")   # 第4分钟扣 5
        assert m.free_used == 2 and m.paid_used == 2 and m.credits == 10
        with closing(db()) as c:
            assert app_module._total_available(c, uid) == bal0 - 10

    def test_session_limit(self, client):
        _tok, uid = _email_register(client)
        app_module.set_config("voice_free_min_guest", 100)   # 全免费,只测封顶
        app_module.set_config("voice_max_session_min", 3)
        m = app_module._VoiceMeter(uid, False, "vs_y")
        assert m.charge_minute()[0] is True
        assert m.charge_minute()[0] is True
        assert m.charge_minute()[0] is True
        assert m.charge_minute() == (False, "session_limit")   # 第4分钟被封顶拒绝

    def test_insufficient_credits(self, client):
        _tok, uid = _email_register(client)
        app_module.set_config("voice_free_min_guest", 0)         # 无免费额度
        app_module.set_config("voice_credits_per_min", 10_000)   # 天价:签到礼付不起
        m = app_module._VoiceMeter(uid, False, "vs_z")
        assert m.charge_minute() == (False, "insufficient_credits")
        assert m.charged == 0 and m.credits == 0

    def test_free_minutes_daily_sum(self, client):
        """今日已用免费分钟从 voice_sessions 累计;新 meter 的剩余额度应扣掉它。"""
        _tok, uid = _email_register(client)
        app_module.set_config("voice_free_min_guest", 3)
        with closing(db()) as c:
            c.execute(
                "INSERT INTO voice_sessions(id,user_id,model,started_ms,free_minutes) VALUES(?,?,?,?,?)",
                ("vs_old", uid, "m", _now_ms(), 2),
            )
            c.commit()
        m = app_module._VoiceMeter(uid, False, "vs_new")
        assert m.free_remaining == 1   # 3 - 今日已用 2

    def test_member_gets_more_free(self, client):
        _tok, uid = _email_register(client)
        app_module.set_config("voice_free_min_member", 10)
        app_module.set_config("voice_free_min_guest", 1)
        guest = app_module._VoiceMeter(uid, False, "vs_g")
        member = app_module._VoiceMeter(uid, True, "vs_m")
        assert guest.free_remaining == 1
        assert member.free_remaining == 10


class TestVoiceQuota:
    def test_quota(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "QWEN_API_KEY", "test-key")
        tok, _uid = _email_register(client)
        app_module.set_config("voice_free_min_guest", 3)
        app_module.set_config("voice_credits_per_min", 5)
        r = client.get("/voice/quota", headers={"Authorization": f"Bearer {tok}"})
        assert r.status_code == 200, r.text
        d = r.json()
        assert d["enabled"] is True
        assert d["perMinCredits"] == 5
        assert d["freeMinutesDaily"] == 3
        assert d["freeMinutesRemaining"] == 3
        assert d["estimatedMinutes"] == 3 + d["creditsBalance"] // 5

    def test_quota_requires_auth(self, client):
        assert client.get("/voice/quota").status_code in (401, 403)


class TestVoiceRealtimeWS:
    def _fake_open(self, monkeypatch):
        created = []

        async def fake_open(model):
            up = _FakeUpstream()
            created.append(up)
            return up

        monkeypatch.setattr(app_module, "_open_upstream_voice_ws", fake_open)
        return created

    def test_relay_happy_path(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "QWEN_API_KEY", "test-key")
        created = self._fake_open(monkeypatch)
        tok, uid = _email_register(client)
        app_module.set_config("voice_free_min_guest", 5)
        with client.websocket_connect(f"/voice/realtime?token={tok}") as ws:
            sess = ws.receive_json()
            assert sess["type"] == "voice.session"
            assert sess["billedMode"] == "free"
            assert sess["sessionId"].startswith("vs_")
            # 服务端注入的 session.update 回显
            ev = ws.receive_json()
            assert ev["type"] == "response.audio.delta"
            ws.send_text(json.dumps({"type": "response.create"}))
            ev2 = ws.receive_json()
            assert ev2["type"] == "response.audio.delta"
        # 上游收到了注入的 session.update + 客户端的 response.create
        assert any("session.update" in s for s in created[0].sent)
        assert any("response.create" in s for s in created[0].sent)
        # 会话落库,首分钟已计
        with closing(db()) as c:
            row = c.execute("SELECT * FROM voice_sessions WHERE user_id=?", (uid,)).fetchone()
        assert row is not None and row["minutes"] == 1 and row["free_minutes"] == 1

    def test_auth_failure(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "QWEN_API_KEY", "test-key")
        self._fake_open(monkeypatch)
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/voice/realtime?token=bad-token") as ws:
                ws.receive_json()

    def test_insufficient_credits_at_connect(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "QWEN_API_KEY", "test-key")
        opened = self._fake_open(monkeypatch)
        tok, _uid = _email_register(client)
        app_module.set_config("voice_free_min_guest", 0)
        app_module.set_config("voice_credits_per_min", 10_000)
        with client.websocket_connect(f"/voice/realtime?token={tok}") as ws:
            err = ws.receive_json()
            assert err["type"] == "error"
            assert err["error"]["code"] == "insufficient_credits"
            with pytest.raises(WebSocketDisconnect):
                ws.receive_json()
        assert opened == []   # 付不起时根本不连上游,零成本

    def test_unavailable_when_no_key(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "QWEN_API_KEY", "")
        tok, _uid = _email_register(client)
        with client.websocket_connect(f"/voice/realtime?token={tok}") as ws:
            err = ws.receive_json()
            assert err["type"] == "error"
            assert err["error"]["code"] == "voice_unavailable"


class TestVoiceDemoPage:
    def test_demo_page_served(self, client):
        r = client.get("/voice/demo")
        assert r.status_code == 200
        assert "text/html" in r.headers["content-type"]
        assert "实时语音" in r.text
        assert r.headers.get("permissions-policy", "").startswith("microphone")

    def test_origin_same_host_ok(self):
        class _WS:
            def __init__(self, host):
                self.headers = {"host": host}
        # 同源放行(demo 页与 WS 同域)
        assert app_module._voice_origin_ok(_WS("api.octoapk.com"), "https://api.octoapk.com") is True
        assert app_module._voice_origin_ok(_WS("api.octoapk.com:443"), "https://api.octoapk.com:443") is True
        # 跨源且不在白名单 → 拒
        assert app_module._voice_origin_ok(_WS("api.octoapk.com"), "https://evil.example") is False


# ═══════════════════════════════════════════════════════════════════════
# 实时语音工具接管(Phase 3):VOICE_TOOLS / _execute_voice_tool / _handle_voice_tool_call
# ═══════════════════════════════════════════════════════════════════════
class _FakeUpTool:
    def __init__(self):
        self.sent = []

    async def send(self, m):
        self.sent.append(m)


class _FakeClientTool:
    def __init__(self):
        self.jsons = []

    async def send_json(self, o):
        self.jsons.append(o)


class TestVoiceTools:
    def test_tools_wellformed(self):
        assert len(app_module.VOICE_TOOLS) >= 1
        for t in app_module.VOICE_TOOLS:
            assert t["type"] == "function" and t["name"] and "parameters" in t

    def test_execute_credit_balance(self, client):
        _tok, uid = _email_register(client)
        with closing(db()) as c:
            bal = app_module._total_available(c, uid)
        assert app_module._execute_voice_tool("get_credit_balance", uid) == {"balance": bal}

    def test_execute_membership_guest(self, client):
        _tok, uid = _email_register(client)
        out = app_module._execute_voice_tool("get_membership_status", uid)
        assert out["active"] is False and out["remaining_days"] == 0

    def test_execute_voice_quota(self, client):
        _tok, uid = _email_register(client)
        app_module.set_config("voice_free_min_guest", 3)
        app_module.set_config("voice_credits_per_min", 5)
        out = app_module._execute_voice_tool("get_voice_quota", uid)
        assert out["free_minutes_remaining"] == 3 and out["credits_per_min"] == 5

    def test_execute_unknown_tool(self, client):
        _tok, uid = _email_register(client)
        assert app_module._execute_voice_tool("nope", uid) == {"error": "unknown_tool"}

    def test_handle_tool_call_feeds_upstream(self, client):
        _tok, uid = _email_register(client)
        up, cws = _FakeUpTool(), _FakeClientTool()
        ev = {"type": "response.function_call_arguments.done",
              "name": "get_credit_balance", "call_id": "c1", "arguments": "{}"}
        asyncio.run(app_module._handle_voice_tool_call(ev, up, cws, uid))
        # 通知客户端(UI)
        assert {"type": "voice.tool", "name": "get_credit_balance"} in cws.jsons
        # 喂回上游:function_call_output(带 call_id + 真实余额)+ response.create
        assert len(up.sent) == 2
        item = json.loads(up.sent[0])
        assert item["type"] == "conversation.item.create"
        assert item["item"]["type"] == "function_call_output"
        assert item["item"]["call_id"] == "c1"
        assert "balance" in item["item"]["output"]
        assert json.loads(up.sent[1])["type"] == "response.create"

    def test_handle_unsupported_tool(self, client):
        _tok, uid = _email_register(client)
        up, cws = _FakeUpTool(), _FakeClientTool()
        ev = {"type": "response.function_call_arguments.done",
              "name": "delete_everything", "call_id": "x", "arguments": "{}"}
        asyncio.run(app_module._handle_voice_tool_call(ev, up, cws, uid))
        # 未知工具 → 回 unsupported,不执行任何真实操作
        assert "unsupported_tool" in json.loads(up.sent[0])["item"]["output"]


class TestVoiceClientTools:
    def test_client_tools_declared(self):
        names = {t["name"] for t in app_module.VOICE_CLIENT_TOOLS}
        assert "open_url" in names and "get_battery_level" in names

    def test_relay_client_tool_roundtrip(self, client):
        ev = {"type": "response.function_call_arguments.done",
              "name": "get_battery_level", "call_id": "cc", "arguments": "{}"}
        up = _FakeUpTool()
        sent = []

        class CWS:
            async def send_json(self, o):
                sent.append(o)

        async def scenario():
            pending = {}
            task = asyncio.create_task(app_module._relay_client_tool(ev, up, CWS(), pending))
            for _ in range(100):
                await asyncio.sleep(0.01)
                if "cc" in pending and not pending["cc"].done():
                    break
            pending["cc"].set_result({"level": 88})
            await task
        asyncio.run(scenario())
        # 中继给客户端
        assert sent[0]["type"] == "voice.tool_call" and sent[0]["name"] == "get_battery_level"
        assert sent[0]["callId"] == "cc"
        # 客户端结果喂回上游
        item = json.loads(up.sent[0])
        assert item["item"]["type"] == "function_call_output"
        assert item["item"]["call_id"] == "cc" and "88" in item["item"]["output"]
        assert json.loads(up.sent[1])["type"] == "response.create"

    def test_relay_client_tool_timeout(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "VOICE_TOOL_TIMEOUT_S", 0.2)
        ev = {"type": "response.function_call_arguments.done",
              "name": "open_url", "call_id": "t1", "arguments": '{"url":"https://x.com"}'}
        up = _FakeUpTool()

        class CWS:
            async def send_json(self, o):
                pass  # 客户端从不回 → 超时兜底

        asyncio.run(app_module._relay_client_tool(ev, up, CWS(), {}))
        assert "client_timeout" in json.loads(up.sent[0])["item"]["output"]
        assert json.loads(up.sent[1])["type"] == "response.create"


# ═══════════════════════════════════════════════════════════════════════
# 语音个性化(Phase 4-A):/voice/prefs + session.update 应用 per-user 音色/人设
# ═══════════════════════════════════════════════════════════════════════
class TestVoicePrefs:
    def test_default(self, client):
        tok, _ = _email_register(client)
        d = client.get("/voice/prefs", headers={"Authorization": f"Bearer {tok}"}).json()
        assert d["voice"] == app_module.VOICE_REALTIME_VOICE
        assert "Ethan" in d["presets"] and d["hasCloned"] is False

    def test_set_and_get(self, client):
        tok, _ = _email_register(client)
        h = {"Authorization": f"Bearer {tok}"}
        assert client.post("/voice/prefs", json={"voice": "Dylan", "persona": "简短有力"}, headers=h).status_code == 200
        d = client.get("/voice/prefs", headers=h).json()
        assert d["voice"] == "Dylan" and d["persona"] == "简短有力"

    def test_reject_bad_voice(self, client):
        tok, _ = _email_register(client)
        r = client.post("/voice/prefs", json={"voice": "Cherry"}, headers={"Authorization": f"Bearer {tok}"})
        assert r.status_code == 400  # Cherry 3.5 生成会 400,不给选

    def test_cloned_requires_enrollment(self, client):
        tok, _ = _email_register(client)
        r = client.post("/voice/prefs", json={"voice": "cloned"}, headers={"Authorization": f"Bearer {tok}"})
        assert r.status_code == 400  # 没复刻不能选 cloned

    def test_persona_capped(self, client):
        tok, _ = _email_register(client)
        h = {"Authorization": f"Bearer {tok}"}
        client.post("/voice/prefs", json={"persona": "x" * 999}, headers=h)
        assert len(client.get("/voice/prefs", headers=h).json()["persona"]) == 500

    def test_session_config_applies_pref(self, client):
        _tok, uid = _email_register(client)
        with closing(db()) as c:
            c.execute("INSERT INTO voice_prefs(user_id,voice,persona,updated_at) VALUES(?,?,?,?)",
                      (uid, "Ethan", "你是我的健身教练", _now_ms()))
            c.commit()
        voice, instr = app_module._voice_session_config(uid)
        assert voice == "Ethan"
        assert instr.startswith("你是我的健身教练")  # 人设前置
        assert "章鱼" in instr  # 基础指令仍在

    def test_cloned_voice_selected(self, client):
        _tok, uid = _email_register(client)
        with closing(db()) as c:
            c.execute("INSERT INTO voice_prefs(user_id,voice,cloned_voice,updated_at) VALUES(?,?,?,?)",
                      (uid, "cloned", "voice-abc123", _now_ms()))
            c.commit()
        voice, _instr = app_module._voice_session_config(uid)
        assert voice == "voice-abc123"  # 复刻音色 id 被选用


class TestVoicePrefsInSession:
    def test_ws_injects_pref_voice(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "QWEN_API_KEY", "test-key")
        created = []

        async def fake_open(model):
            up = _FakeUpstream()
            created.append(up)
            return up
        monkeypatch.setattr(app_module, "_open_upstream_voice_ws", fake_open)
        tok, _uid = _email_register(client)
        app_module.set_config("voice_free_min_guest", 5)
        client.post("/voice/prefs", json={"voice": "Ethan", "persona": "你是健身教练"},
                    headers={"Authorization": f"Bearer {tok}"})
        with client.websocket_connect(f"/voice/realtime?token={tok}") as ws:
            ws.receive_json()  # voice.session
            ws.receive_json()  # 注入回显
        injected = [s for s in created[0].sent if "session.update" in s]
        assert injected
        sess = json.loads(injected[0])["session"]
        assert sess["voice"] == "Ethan"
        assert "健身教练" in sess["instructions"]  # 人设注入(解码后中文)


class TestVoiceClone:
    def test_clone_success(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "_enroll_voice_id", lambda uid, data: "qwen-omni-vc-test-123")
        tok, _uid = _email_register(client)
        h = {"Authorization": f"Bearer {tok}"}
        r = client.post("/voice/clone", json={"audio": "AAAA"}, headers=h)
        assert r.status_code == 200 and r.json()["voiceId"] == "qwen-omni-vc-test-123"
        d = client.get("/voice/prefs", headers=h).json()
        assert d["voice"] == "cloned" and d["hasCloned"] is True

    def test_clone_applies_in_session(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "_enroll_voice_id", lambda uid, data: "qwen-omni-vc-xyz")
        tok, uid = _email_register(client)
        client.post("/voice/clone", json={"audio": "AAAA"}, headers={"Authorization": f"Bearer {tok}"})
        voice, _instr = app_module._voice_session_config(uid)
        assert voice == "qwen-omni-vc-xyz"  # 复刻音色进 session

    def test_clone_no_audio(self, client):
        tok, _ = _email_register(client)
        assert client.post("/voice/clone", json={}, headers={"Authorization": f"Bearer {tok}"}).status_code == 400

    def test_clone_enroll_failure(self, client, monkeypatch):
        monkeypatch.setattr(app_module, "_enroll_voice_id", lambda uid, data: "")
        tok, _ = _email_register(client)
        r = client.post("/voice/clone", json={"audio": "AAAA"}, headers={"Authorization": f"Bearer {tok}"})
        assert r.status_code == 502

    def test_clone_too_large(self, client):
        tok, _ = _email_register(client)
        r = client.post("/voice/clone", json={"audio": "A" * 14_000_001}, headers={"Authorization": f"Bearer {tok}"})
        assert r.status_code == 413


class TestLogoutRevocation:
    @staticmethod
    def _hdr(tok):
        return {"Authorization": f"Bearer {tok}"}

    def test_logout_invalidates_old_token_new_login_works(self, client):
        tok, uid = _email_register(client, "logout1@example.com")
        assert client.get("/account/profile", headers=self._hdr(tok)).status_code == 200
        assert client.post("/auth/logout", headers=self._hdr(tok)).status_code == 200
        assert client.get("/account/profile", headers=self._hdr(tok)).status_code == 401
        assert client.post("/auth/logout", headers=self._hdr(tok)).status_code == 401
        _insert_email_code("logout1@example.com")
        r = client.post("/auth/email/login", json={"email": "logout1@example.com", "code": "123456"})
        assert r.status_code == 200
        assert client.get("/account/profile", headers=self._hdr(r.json()["token"])).status_code == 200

    def test_pre_change_token_without_claim_still_works(self, client):
        _, uid = _email_register(client, "logout2@example.com")
        legacy = _make_token(uid)  # 无 tv 声明
        assert client.get("/account/profile", headers=self._hdr(legacy)).status_code == 200
        tok2 = _make_token(uid)
        client.post("/auth/logout", headers=self._hdr(legacy))
        assert client.get("/account/profile", headers=self._hdr(tok2)).status_code == 401

    def test_ban_invalidates_token(self, client):
        tok, uid = _email_register(client, "logout3@example.com")
        r = client.post(f"/admin/api/users/{uid}/ban", json={"banned": True, "reason": "t"},
                        headers={"X-Admin-Token": "test-token"})
        assert r.status_code == 200
        client.post(f"/admin/api/users/{uid}/ban", json={"banned": False, "reason": "t"},
                    headers={"X-Admin-Token": "test-token"})
        assert client.get("/account/profile", headers=self._hdr(tok)).status_code == 401  # 解封后旧令牌不复活

    def test_logout_rejects_console_ws_handshake_and_closes_live(self, client):
        tok, uid, headers, device_url, console_url = TestRemotePairing._pair_device(
            client, "logout4@example.com", "dev_logout_ws")
        with client.websocket_connect(device_url) as device:
            assert device.receive_json()["type"] == "hello"
            with client.websocket_connect(console_url) as console:
                assert console.receive_json()["online"] is True
                assert client.post("/auth/logout", headers=headers).status_code == 200
                with pytest.raises(WebSocketDisconnect):
                    device.receive_json()
                with pytest.raises(WebSocketDisconnect):
                    console.receive_json()
        with client.websocket_connect(console_url) as console:  # 旧令牌握手被拒
            with pytest.raises(WebSocketDisconnect):
                console.receive_json()

    def test_console_per_message_recheck_honors_version(self, client):
        tok, uid, headers, device_url, console_url = TestRemotePairing._pair_device(
            client, "logout5@example.com", "dev_logout_msg")
        with client.websocket_connect(device_url) as device:
            assert device.receive_json()["type"] == "hello"
            with client.websocket_connect(console_url) as console:
                assert console.receive_json()["online"] is True
                with closing(db()) as c:  # 绕过端点直接换代
                    c.execute("UPDATE users SET token_version = token_version + 1 WHERE user_id=?", (uid,))
                    c.commit()
                console.send_json({"type": "control", "action": "home", "id": "x"})
                with pytest.raises(WebSocketDisconnect):
                    console.receive_json()
