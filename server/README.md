# octopus 账号 + 计费 + 会员 + 大模型中转(服务端骨架)

App 侧(`com.apk.claw.android.account`)按这套契约调用。默认全 mock,本地直接跑通;
生产把 `MIMO_API_KEY` / 支付 / 短信 env 配上即可,**App 不用改**。

## 它做什么

- **账号**:手机号 + 验证码登录/注册(opaque token 鉴权,存 sqlite)。
- **付费体系(默认路径)**:`/v1/chat/completions` **中转**到平台 MiMo(共享 key 只在服务端),
  按返回的 token 用量**扣积分**;积分不足返回 402。
- **会员(BYO 解锁)**:买 `kind=membership` 的商品 → 当月 `membershipActive=true`。
  App 据此解锁"接自己的大模型"(BYO 走客户端直连、不经本中转、不扣积分)。
- **充值/订单/每日签到**:积分商品下单 + 查单到账;签到每日一次。

## API(App 的 AccountGateway 契约)

```
POST /auth/sms/send            {mobile}            -> {ok, ttlSeconds, devCode?}
POST /auth/sms/login           {mobile, code}      -> {token, userId, mobile, isNewUser, nickname}
GET  /account/profile              (Bearer)            -> {userId, mobile, nickname, avatar}
GET  /account/balance              (Bearer)            -> {credits, membershipActive, membershipExpireAt}
GET  /account/membership           (Bearer)            -> {active, expireAt, remainingDays, benefits, dailyFreeCredits, dailyFreeRemaining}
GET  /account/credits/transactions (Bearer)            -> {total, items:[{id,delta,balanceAfter,source,detail,refId,ts}]}
GET  /account/usage                (Bearer)            -> {total, summary, items:[{id,model,tokens_in,tokens_out,credits,ts}]}
POST /account/daily-claim          (Bearer)            -> {claimed, credits, balance}
GET  /billing/goods                (Bearer)            -> {items:[{id,title,credits,bonusCredits,priceFen,tag,kind}]}
POST /billing/estimate             (Bearer){model?,messages,maxTokens?} -> {model,tier,multiplier,promptTokensEstimated,maxTokens,worstCaseCredits,dailyFreeCredits,chargeableCredits,estimatedRmb}
POST /billing/orders           (Bearer){goodsId}   -> {orderNo, payUrl?, amountFen, credits}
GET  /billing/orders/{orderNo} (Bearer)            -> {orderNo, status, credits}
GET  /v1/models                                     -> 公开模型目录(带每模型积分倍率)
POST /v1/chat/completions      (Bearer) OpenAI体    -> 转发 MiMo + 按倍率扣积分(支持 stream)
POST /billing/webhook/{prov}                        -> 生产支付回调(骨架留桩 501)
GET  /healthz
GET  /admin                                         -> 管理后台单页(未启用时 404)
GET  /admin/api/*              (X-Admin-Token)       -> 后台 JSON 接口(见下)
GET  /remote/console                                -> 官网远程控制台单页
POST /remote/pair/start        (Bearer){deviceName} -> {code, ttlSeconds}
POST /remote/pair/claim        (Bearer){code,...}   -> {deviceId, deviceToken, deviceName}
GET  /remote/devices           (Bearer)             -> 已配对设备列表(含 online/revoked)
POST /remote/devices/{id}/revoke (Bearer)           -> 撤销设备授权
WS   /remote/device/ws?device_id=...&device_token=... -> 手机主动连接官网
WS   /remote/console/ws?device_id=...&token=JWT       -> 官网控制台连接设备
POST /device/register              (Bearer){deviceId?,deviceName?,pushToken?,osVersion?,appVersion?,deviceModel?} -> {deviceId, deviceToken, deviceName}
POST /device/heartbeat             (Bearer){deviceId,battery?,isCharging?,currentApp?,screenHash?} -> {ok, serverTs, ...}
POST /device/report                (Bearer){deviceId,type,payload} -> {ok}
GET  /device/{device_id}/status    (Bearer)            -> {deviceId, deviceName, createdAt, lastSeen, lastHeartbeatAt, batteryLevel, osVersion, appVersion, deviceModel, revoked}
GET  /downloads/shizuku/latest                      -> Shizuku 安装包元信息(公开)
GET  /downloads/shizuku.apk                         -> Shizuku APK 下载(公开,需服务器放置文件)
```

## 管理后台 /admin

打开 `https://你的域名/admin`,输入 `ADMIN_TOKEN` 即可:看用户/积分/订单/用量与成本,
手动加减积分、封禁/解封、赠送会员、导出用量 CSV;所有变更写 `admin_log` 审计表。

- **默认关闭**:不设 `ADMIN_TOKEN` 时 `/admin` 返回 404、`/admin/api/*` 返回 503,不增加攻击面。
- **启用**:`.env` 设 `ADMIN_TOKEN=$(openssl rand -hex 32)` 后重启。它是公网后台**唯一应用层防线**
  (能改积分/封号),务必强随机。
- **加固(强烈建议)**:① 在 nginx 给后台加 IP 白名单作第二道门 —— ② 或设 `ADMIN_IP_ALLOWLIST`
  (应用层,基于可信来源 IP)。两者都依赖 `TRUSTED_PROXIES`(nginx 反代=1)正确,否则限流/白名单
  可被伪造的 `X-Forwarded-For` 绕过。nginx 示例:

  ```nginx
  location /admin { allow 1.2.3.4; deny all; proxy_pass http://127.0.0.1:8081; }
  ```

鉴权用 **JWT(HS256)**:登录返回的 token 即 JWT,后续请求带 `Authorization: Bearer <token>`。

## 计费策略

- 公式:`ceil((输入+输出 tokens)/1000 × CREDITS_PER_1K_TOKENS × 模型 multiplier)`。
- 默认 `CREDITS_PER_1K_TOKENS=0.1`,在 DeepSeek-V4 成本下保持 40%–70% 模型层毛利,同时让用户感知价格接近主流 AI 工具。
- 默认 `FREE_DAILY_CREDITS=2`:每个自然日赠送 2 积分,调用 `/v1/chat/completions` 时优先抵扣,余额为 0 也能轻度体验。
- 模型倍率:极速 0.2× / 标准 0.45× / 高级 1.2×。
- `/billing/estimate` 可在调用前预估本次 worst-case 扣费。
- 中转 `/v1/chat/completions` 同时支持**非流式**和**流式 SSE 透传**(流式自动注入 `stream_options.include_usage`,在末尾抓 usage 后扣费)。

## 官网远程控制台 /remote/console

远程控制走“官网统一入口 + 手机主动连接官网”的模式,不再依赖手机公网 IP:

1. 用户登录官网后打开 `/remote/console`,粘贴/保存登录 JWT。
2. 点击“生成配对码”,服务端创建 5 分钟有效的一次性配对码。
3. 手机 App 在设置 → 设备控制 → 官网控制台里输入配对码,调用 `/remote/pair/claim` 认领。
4. App 保存 `deviceId/deviceToken`,并主动连接 `wss://你的域名/remote/device/ws`。
5. 官网控制台连接 `/remote/console/ws`,控制指令由服务端转发到手机在线 WebSocket。

首版 relay 使用 JSON 指令,例如:

```json
{"type":"control","action":"home"}
{"type":"control","action":"tap","x":300,"y":800}
{"type":"control","action":"text","text":"你好"}
```

生产部署 nginx 时记得开启 WebSocket Upgrade 透传,例如在反代 location 中加:

```nginx
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection "upgrade";
proxy_read_timeout 3600s;
```

## Shizuku 一键下载安装

App 内的 Shizuku 引导会请求 `GET /downloads/shizuku/latest`,再下载 `downloadUrl` 并拉起
Android 系统安装器。服务端不会自动从第三方下载 APK,需要部署时手动放置官方安装包:

```bash
mkdir -p /home/ubuntu/octopus-server/downloads
cp Shizuku.apk /home/ubuntu/octopus-server/downloads/shizuku.apk
```

可选 `.env`:

```bash
SHIZUKU_APK_PATH=/home/ubuntu/octopus-server/downloads/shizuku.apk
SHIZUKU_VERSION=13.6.0
SHIZUKU_SOURCE_URL=https://shizuku.rikka.app/download/
SHIZUKU_LICENSE_URL=https://github.com/RikkaApps/Shizuku
```

注意:分发第三方 APK 前应保留来源和开源许可信息,并定期用官方版本更新该文件。

## App 在线更新(OTA)

App 启动时和「设置 → 版本号」处会请求公开的 `GET /app/latest`,有更高的 versionCode 就弹「发现新版本」,
点「立即更新」下载 `/app/latest.apk` 并拉起系统安装器。服务端不构建、不签名 APK,只分发运维放好的包:

```bash
cp OctopusMobile_v1.1.0_vc15_universal.apk /home/ubuntu/octopus-server/downloads/octopus-app.apk
```

`.env`:

```bash
APP_UPDATE_APK_PATH=/home/ubuntu/octopus-server/downloads/octopus-app.apk
APP_UPDATE_VERSION_CODE=15
APP_UPDATE_VERSION_NAME=1.1.0
APP_UPDATE_NOTES_FILE=/home/ubuntu/octopus-server/downloads/notes.txt      # 多行说明,每次请求现读
APP_UPDATE_SIGNING_CERT_SHA256=<Release 说明里的签名证书 SHA-256>
APP_UPDATE_KEY_CHANGE_NOTES_FILE=/home/ubuntu/octopus-server/downloads/key-change-notes.txt
```

**换签名(1.1.0)**:1.0.0(versionCode 9–14)的签名密钥已遗失,1.1.0 起用新密钥签名。Android 不允许把
不同签名的包覆盖安装到旧版上,旧版点「立即更新」只会得到「应用未安装」。配了
`APP_UPDATE_KEY_CHANGE_NOTES(_FILE)` 后,签名对不上的客户端(1.0.0 不带 `cert` 参数;1.1.0 起带自身签名指纹)
拿到的是这段换签名说明而不是常规说明,且 `force` 恒为 false。说明文案见仓库
`docs/release-notes/ota/`。1.0.0 的更新弹窗不能滚动,说明控制在 12 行以内。

## 在你的服务器上跑(已实测该机:2 vCPU / 1GB / Python 3.12)

```bash
cd ~/octopus-mobile/server          # 把本目录拷到服务器
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env                 # 按需填 MIMO_* 等
set -a; . ./.env; set +a
uvicorn app:app --host 127.0.0.1 --port 8081
```

放在你现有的 nginx(80/443)后面反代到 `127.0.0.1:8081`,再把 App 的
`AccountConfig.baseUrl` 指到 `https://你的域名`。⚠️ 1GB 内存且已跑着一个 Next.js,
正式上量前建议把实例升到 2–4GB,并考虑把中转单独拆一台。

## 已补(参考 Molili)

- **流式 SSE 透传** + 末尾抓 usage 扣费。
- **模型目录 + 每模型倍率定价**(`/v1/models`,`MODELS_JSON` 可覆盖)。
- **JWT(HS256)会话**(stdlib 实现,无 PyJWT 依赖)。

## 还没做(留给生产)

- **真支付**:`PAYMENT_PROVIDER=wechat/alipay` 的收银台 + `/billing/webhook` 验签。
- **真短信**:`SMS_PROVIDER` 接阿里云/腾讯云,实现 `send_sms()`。
- **JWT 吊销**:无状态 JWT 改密码/登出无法立即失效;需要的话加一张吊销表或短期 token+refresh。
- **限流/并发**:按用户限流。⚠️ **当前部署必须是单 worker**(`uvicorn app:app`,默认单进程)——
  限流表 `_rl`(app.py:492 的进程内 dict)与远程中继 `RemoteRelayHub`(app.py:570,进程内)都是**内存态**,
  一旦加 gunicorn 多 worker / 多机,每个 worker 各持一份状态,**限流会按 worker 数被放大、中继会跨 worker 丢失**。
  要横向扩展**先把这两处状态外置到 Redis**(限流用 Redis 计数、中继用 Redis pub/sub 或消息队列),再开多 worker。
- **流式扣费兜底**:若上游不返回 usage,当前不扣费(只 log);可补 token 估算。
