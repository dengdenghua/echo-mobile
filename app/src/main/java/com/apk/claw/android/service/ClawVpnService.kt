package com.apk.claw.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.apk.claw.android.R
import com.apk.claw.android.utils.XLog
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * VPN 隧道引擎 —— 建立 tun 接口,把设备 TCP 流量转发到用户指定的 SOCKS5 代理服务器。
 *
 * 协议:SOCKS5(RFC 1928 / RFC 1929)—— 最通用的代理协议,几乎所有代理服务商都支持。
 * 不做 Shadowsocks/V2Ray 等私有协议,保持引擎极简,社区可自行在 SOCKS5 之上套一层。
 *
 * 实现:
 *  - TUN 读线程单线程收 IP 包,按 (srcIp,srcPort) 分发给 NAT 条目
 *  - 每条 TCP 连接独占一个 SocketChannel + 双向转发线程
 *  - 正确维护 SEQ/ACK、TCP 状态机(SYN_SYNACK_ESTABLISHED/FIN_WAIT/CLOSED)
 *  - 计算 IP/TCP 校验和
 *  - protect() 避免 SOCKS5 连接本身被路由回 TUN(死循环)
 *  - UDP 不处理(DNS 经 8.8.8.8 可能不通;可后续加 dnstun 或在 establish 时加允许旁路 DNS)
 */
class ClawVpnService : VpnService() {

    companion object {
        private const val TAG = "ClawVpnService"
        private const val VPN_MTU = 1500
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_DNS = "8.8.8.8"
        private const val VPN_NETMASK_PREFIX = 0 // 0.0.0.0/0
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1003

        // IP 协议
        private const val IPV4_VERSION = 4
        private const val IP_HEADER_MIN_LEN = 20
        private const val IP_PROTO_OFFSET = 9
        private const val IP_SRC_OFFSET = 12
        private const val IP_DST_OFFSET = 16
        private const val TCP_PROTO = 6
        private const val UDP_PROTO = 17

        // TCP 标志位
        private const val TCP_FIN = 0x01
        private const val TCP_SYN = 0x02
        private const val TCP_RST = 0x04
        private const val TCP_PSH = 0x08
        private const val TCP_ACK = 0x10

        // TCP 头默认长度(无选项)
        private const val TCP_HEADER_LEN = 20
        private const val IP_ADDR_LEN = 4

        // 每个 NAT 连接的读写 buffer 大小
        private const val BUF_SIZE = 16384
        // 连接空闲超时(ms),防止 NAT 泄漏
        private const val CONN_IDLE_TIMEOUT = 120_000L

        @Volatile
        var currentConfig: VpnConfig? = null
            private set

        @Volatile
        private var instance: ClawVpnService? = null

        fun start(ctx: Context, config: VpnConfig): Boolean {
            val i = Intent(ctx, ClawVpnService::class.java)
            i.putExtra("host", config.host)
            i.putExtra("port", config.port)
            i.putExtra("username", config.username ?: "")
            i.putExtra("password", config.password ?: "")
            return try {
                ctx.startService(i)
                true
            } catch (e: Exception) {
                XLog.e(TAG, "startService failed", e)
                false
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ClawVpnService::class.java))
        }
    }

    data class VpnConfig(
        val host: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
        val perAppPackages: Array<String>? = null,
    )

    // 4元组作为NAT key:用客户端(设备)侧的 srcIp+srcPort 标识一个流
    private data class FlowKey(val srcIp: Int, val srcPort: Int)

    private enum class TcpState { SYN_SENT, ESTABLISHED, FIN_WAIT, CLOSED }

    private inner class NatEntry(
        val key: FlowKey,
        val dstIp: ByteArray,
        val dstPort: Int,
        val channel: SocketChannel,
    ) {
        @Volatile var state: TcpState = TcpState.SYN_SENT
        // 客户端→代理方向累计已确认字节数(相对ISN)
        @Volatile var clientSeq: Int = 0
        // 代理→客户端方向累计已确认字节数
        @Volatile var proxySeq: Int = 0
        // 客户端的初始SEQ
        @Volatile var clientIsn: Int = 0
        // 我们给客户端的SYN+ACK用的ISN
        val ourIsn: Int = nextIsn.getAndAdd(0x1000)
        @Volatile var lastActive: Long = System.currentTimeMillis()
        var proxyThread: Thread? = null
        // 入站(设备app → TUN → SOCKS5)是否已关闭写
        @Volatile var clientClosedWrite = false
        // 出站(SOCKS5 → 回写TUN)是否已结束
        @Volatile var proxyClosed = false
    }

    private var tunInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var pumpThread: Thread? = null
    private var tunOut: FileOutputStream? = null
    // TUN写入串行化:所有回包都要通过同一个OutputStream写,用 synchronized 保护
    private val tunWriteLock = Any()
    private val natTable = ConcurrentHashMap<FlowKey, NatEntry>()
    // 生成初始SEQ号(简单递增)
    private val nextIsn = AtomicInteger(0x5A5A5A5A)

    // 代理配置(每次pumpLoop使用)
    private var proxyHost: String = ""
    private var proxyPort: Int = 0
    private var proxyUser: String? = null
    private var proxyPass: String? = null
    private val vpnIp = ipv4ToInt(parseIp(VPN_ADDRESS))

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val host = intent.getStringExtra("host") ?: return START_NOT_STICKY
        val port = intent.getIntExtra("port", 0)
        if (port <= 0) return START_NOT_STICKY
        val username = intent.getStringExtra("username")?.takeIf { it.isNotBlank() }
        val password = intent.getStringExtra("password")?.takeIf { it.isNotBlank() }

        proxyHost = host
        proxyPort = port
        proxyUser = username
        proxyPass = password
        currentConfig = VpnConfig(host, port, username, password)
        instance = this

        startForeground(NOTIFICATION_ID, buildNotification())
        establishTunnel()
        return START_STICKY
    }

    private fun establishTunnel() {
        val builder = Builder()
            .setMtu(VPN_MTU)
            .addAddress(VPN_ADDRESS, 32)
            .addRoute(VPN_ROUTE, VPN_NETMASK_PREFIX)
            .addDnsServer(VPN_DNS)
            .setSession("Echo VPN")
        // 允许旁路本应用自身流量(防自循环),包括VPN隧道建立时的SOCKS5 TCP连接
        // 但我们也会在每个SocketChannel上手动调用protect(),双重保险

        try {
            tunInterface = builder.establish()
        } catch (e: Exception) {
            XLog.e(TAG, "establish tun failed (用户可能未授权 VPN)", e)
            stopSelf()
            return
        }

        val pfd = tunInterface ?: return
        tunOut = FileOutputStream(pfd.fileDescriptor)

        running.set(true)
        pumpThread = Thread({ pumpLoop(FileInputStream(pfd.fileDescriptor)) }, "vpn-pump").apply {
            isDaemon = true
            start()
        }
        // NAT清理线程
        Thread({ cleanupLoop() }, "vpn-cleanup").apply { isDaemon = true; start() }
    }

    // TUN主读循环
    private fun pumpLoop(input: FileInputStream) {
        val buffer = ByteBuffer.allocate(VPN_MTU)
        while (running.get()) {
            buffer.clear()
            val length = try {
                input.read(buffer.array())
            } catch (e: IOException) {
                break
            }
            if (length <= 0) {
                if (!running.get()) break
                continue
            }
            val packet = buffer.array().copyOf(length)
            try {
                handleIpPacket(packet)
            } catch (e: Exception) {
                XLog.w(TAG, "handle packet error: ${e.message}")
            }
        }
    }

    // 定期清理空闲连接
    private fun cleanupLoop() {
        while (running.get()) {
            try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
            val now = System.currentTimeMillis()
            val it = natTable.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val entry = e.value
                if (entry.state == TcpState.CLOSED || now - entry.lastActive > CONN_IDLE_TIMEOUT) {
                    closeEntry(entry, true)
                    it.remove()
                }
            }
        }
    }

    private fun handleIpPacket(packet: ByteArray) {
        if (packet.size < IP_HEADER_MIN_LEN) return
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != IPV4_VERSION) return

        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < IP_HEADER_MIN_LEN || ihl >= packet.size) return

        val proto = packet[IP_PROTO_OFFSET].toInt() and 0xFF
        val totalLen = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        if (totalLen > packet.size) return

        val srcIp = readIpInt(packet, IP_SRC_OFFSET)
        val dstIp = readIpInt(packet, IP_DST_OFFSET)

        if (proto == TCP_PROTO) {
            handleTcp(packet, ihl, totalLen, srcIp, dstIp)
        } else if (proto == UDP_PROTO) {
            handleUdp(packet, ihl, totalLen, srcIp, dstIp)
        }
        // 其他协议(ICMP等)忽略:不构造ICMP回应,内核会超时处理
    }

    // ── UDP DNS 转发 ─────────────────────────────────────────
    // UDP NAT条目(DNS查询专用:无状态,一次查询一个socket)
    private data class UdpDnsKey(val srcIp: Int, val srcPort: Int, val dnsIp: Int, val dnsPort: Int)
    private val udpDnsCh = java.util.concurrent.ConcurrentHashMap<UdpDnsKey, Long>()

    private fun handleUdp(packet: ByteArray, ihl: Int, totalLen: Int, srcIp: Int, dstIp: Int) {
        val udpOffset = ihl
        if (udpOffset + 8 > totalLen) return
        val srcPort = ((packet[udpOffset].toInt() and 0xFF) shl 8) or (packet[udpOffset + 1].toInt() and 0xFF)
        val dstPort = ((packet[udpOffset + 2].toInt() and 0xFF) shl 8) or (packet[udpOffset + 3].toInt() and 0xFF)
        val udpLen = ((packet[udpOffset + 4].toInt() and 0xFF) shl 8) or (packet[udpOffset + 5].toInt() and 0xFF)
        val dnsStart = udpOffset + 8
        val dnsLen = udpLen - 8
        if (dnsStart + dnsLen > totalLen || dnsLen <= 0) return
        val dnsData = packet.copyOfRange(dnsStart, dnsStart + dnsLen)

        // 只转发DNS查询(目标端口53);其他UDP包丢弃(QUIC等会fallback到TCP)
        if (dstPort != 53) return

        val key = UdpDnsKey(srcIp, srcPort, dstIp, dstPort)
        // 简单去重:同一key 1秒内不重复发起
        val now = System.currentTimeMillis()
        val last = udpDnsCh.putIfAbsent(key, now)
        if (last != null && now - last < 1000) return
        udpDnsCh[key] = now

        // 异步通过SOCKS5 TCP转发DNS(RFC 7766:DNS over TCP = 2字节长度前缀+DNS报文)
        val dnsIp = intToIp(dstIp)
        Thread({
            try {
                val ch = SocketChannel.open()
                ch.configureBlocking(true)
                protect(ch.socket())
                ch.connect(InetSocketAddress(proxyHost, proxyPort))
                if (!socks5Handshake(ch, proxyUser, proxyPass)) { closeQuietly(ch); return@Thread }
                if (!socks5Connect(ch, dnsIp, dstPort)) { closeQuietly(ch); return@Thread }

                // 发送:2字节大端长度 + DNS报文
                val sendBuf = ByteBuffer.allocate(2 + dnsData.size)
                sendBuf.putShort(dnsData.size.toShort())
                sendBuf.put(dnsData)
                sendBuf.flip()
                writeAll(ch, sendBuf)

                // 读取响应:2字节长度 + DNS报文(带超时)
                ch.socket().soTimeout = 8000
                val lenBuf = ByteBuffer.allocate(2)
                readExact(ch, lenBuf)
                lenBuf.flip()
                val respLen = lenBuf.short.toInt() and 0xFFFF
                if (respLen <= 0 || respLen > 4096) { closeQuietly(ch); return@Thread }
                val respBuf = ByteBuffer.allocate(respLen)
                readExact(ch, respBuf)
                val respDns = respBuf.array().copyOf(respLen)

                // 构造UDP响应包写回TUN
                val udpResp = buildUdpPacket(dstIp, srcIp, dstPort, srcPort, respDns)
                writeTun(udpResp)
                closeQuietly(ch)
            } catch (e: Exception) {
                XLog.d(TAG, "DNS relay failed: ${e.message}")
            } finally {
                udpDnsCh.remove(key)
            }
        }, "vpn-dns-${ipIntToStr(dstIp)}:$dstPort").apply { isDaemon = true; start() }
    }

    private fun buildUdpPacket(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val ipTotalLen = IP_HEADER_MIN_LEN + udpLen
        val pkt = ByteArray(ipTotalLen)

        // IP header
        pkt[0] = 0x45.toByte()
        pkt[1] = 0
        writeInt16(pkt, 2, ipTotalLen)
        writeInt16(pkt, 4, 0)
        writeInt16(pkt, 6, 0x4000)
        pkt[8] = 64
        pkt[9] = UDP_PROTO.toByte()
        writeInt16(pkt, 10, 0)
        writeInt32(pkt, IP_SRC_OFFSET, srcIp)
        writeInt32(pkt, IP_DST_OFFSET, dstIp)
        val ipCksum = checksum(pkt, 0, IP_HEADER_MIN_LEN)
        writeInt16(pkt, 10, ipCksum)

        // UDP header
        val ub = IP_HEADER_MIN_LEN
        writeInt16(pkt, ub + 0, srcPort)
        writeInt16(pkt, ub + 2, dstPort)
        writeInt16(pkt, ub + 4, udpLen)
        writeInt16(pkt, ub + 6, 0) // UDP checksum(IPv4可选,填0表示不校验)
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, pkt, ub + 8, payload.size)
        }
        return pkt
    }

    private fun handleTcp(packet: ByteArray, ihl: Int, totalLen: Int, srcIp: Int, dstIp: Int) {
        val tcpOffset = ihl
        if (tcpOffset + TCP_HEADER_LEN > totalLen) return
        val srcPort = ((packet[tcpOffset].toInt() and 0xFF) shl 8) or (packet[tcpOffset + 1].toInt() and 0xFF)
        val dstPort = ((packet[tcpOffset + 2].toInt() and 0xFF) shl 8) or (packet[tcpOffset + 3].toInt() and 0xFF)
        val seq = readInt32(packet, tcpOffset + 4)
        val ackNum = readInt32(packet, tcpOffset + 8)
        val dataOffset = ((packet[tcpOffset + 12].toInt() ushr 4) and 0x0F) * 4
        val flags = packet[tcpOffset + 13].toInt() and 0xFF
        val payloadStart = tcpOffset + dataOffset
        val payloadEnd = totalLen
        val payloadLen = payloadEnd - payloadStart
        val payload = if (payloadLen > 0) packet.copyOfRange(payloadStart, payloadEnd) else ByteArray(0)

        val key = FlowKey(srcIp, srcPort)
        var entry = natTable[key]

        val isSyn = (flags and TCP_SYN) != 0 && (flags and TCP_ACK) == 0
        val isSynAck = (flags and TCP_SYN) != 0 && (flags and TCP_ACK) != 0
        val isFin = (flags and TCP_FIN) != 0
        val isRst = (flags and TCP_RST) != 0
        val isAck = (flags and TCP_ACK) != 0

        if (isSyn && entry == null) {
            // 新连接:异步建立SOCKS5连接
            val dstIpBytes = intToIp(dstIp)
            openOutboundConnection(key, dstIpBytes, dstPort, seq)
            return
        }

        if (entry == null) {
            // 无NAT条目,发RST重置
            if (isSyn) return // 可能在连接建立中
            sendRst(srcIp, dstIp, srcPort, dstPort, ackNum, seq)
            return
        }

        entry.lastActive = System.currentTimeMillis()

        if (isRst) {
            closeEntry(entry, true)
            natTable.remove(key)
            return
        }

        when (entry.state) {
            TcpState.SYN_SENT -> {
                // 我们已经回了SYN+ACK,等待客户端ACK
                if (isAck && !isSynAck) {
                    entry.state = TcpState.ESTABLISHED
                    entry.clientIsn = seq - 1 // client的SYN占一个SEQ
                    // 可能已有payload随第三个包(piggyback data)到达
                    if (payloadLen > 0) {
                        writeToProxy(entry, payload)
                    }
                    // 启动代理→TUN读线程
                    startProxyReader(entry)
                }
            }
            TcpState.ESTABLISHED, TcpState.FIN_WAIT -> {
                // 处理payload
                if (payloadLen > 0 && entry.state == TcpState.ESTABLISHED && !entry.clientClosedWrite) {
                    writeToProxy(entry, payload)
                }
                if (isFin) {
                    entry.clientClosedWrite = true
                    entry.state = TcpState.FIN_WAIT
                    // 关闭SOCKS5 socket的写方向
                    try { entry.channel.socket().shutdownOutput() } catch (_: Exception) {}
                    // 回FIN+ACK
                    val finAck = buildTcpPacket(
                        vpnIp, srcIp, dstPort, srcPort,
                        entry.ourIsn + 1 + entry.proxySeq, entry.clientIsn + 1 + entry.clientSeq,
                        TCP_FIN or TCP_ACK, ByteArray(0)
                    )
                    writeTun(finAck)
                }
            }
            TcpState.CLOSED -> {}
        }
    }

    private fun openOutboundConnection(key: FlowKey, dstIp: ByteArray, dstPort: Int, clientIsn: Int) {
        Thread({
            try {
                val channel = SocketChannel.open()
                channel.configureBlocking(true)
                // 关键:protect()防止这个socket本身的流量被路由回TUN(死循环)
                protect(channel.socket())
                channel.connect(InetSocketAddress(proxyHost, proxyPort))
                if (!channel.isConnected) {
                    sendRstToKey(key, clientIsn, dstPort)
                    return@Thread
                }
                if (!socks5Handshake(channel, proxyUser, proxyPass)) {
                    sendRstToKey(key, clientIsn, dstPort)
                    closeQuietly(channel)
                    return@Thread
                }
                if (!socks5Connect(channel, dstIp, dstPort)) {
                    sendRstToKey(key, clientIsn, dstPort)
                    closeQuietly(channel)
                    return@Thread
                }

                val entry = NatEntry(key, dstIp, dstPort, channel)
                entry.clientIsn = clientIsn
                natTable[key] = entry

                // 回 SYN+ACK
                val synAck = buildTcpPacket(
                    vpnIp, key.srcIp,
                    0, key.srcPort, // 端口稍后修正:srcPort应是目标端口(即原始dstPort,因为我们在TUN端模拟目标服务器)
                    entry.ourIsn, clientIsn + 1,
                    TCP_SYN or TCP_ACK, ByteArray(0)
                )
                // 修正:srcPort = 原始dstPort, dstPort = key.srcPort
                writeTcpSynAck(synAck, dstPort, key.srcPort)
                XLog.d(TAG, "TCP SYN+ACK sent for ${ipIntToStr(key.srcIp)}:${key.srcPort} -> ${ipBytesToStr(dstIp)}:$dstPort")
            } catch (e: Exception) {
                XLog.w(TAG, "outbound connect failed: ${e.message}")
                sendRstToKey(key, clientIsn, dstPort)
            }
        }, "vpn-out-${ipBytesToStr(dstIp)}:$dstPort").apply { isDaemon = true; start() }
    }

    private fun startProxyReader(entry: NatEntry) {
        val t = Thread({
            val buf = ByteBuffer.allocate(BUF_SIZE)
            try {
                while (running.get() && entry.state != TcpState.CLOSED) {
                    buf.clear()
                    val n = try { entry.channel.read(buf) } catch (e: IOException) { -1 }
                    if (n < 0) {
                        // 远端关闭
                        break
                    }
                    if (n == 0) continue
                    val data = buf.array().copyOf(n)
                    synchronized(entry) {
                        entry.proxySeq += n
                        entry.lastActive = System.currentTimeMillis()
                    }

                    // 回PSH+ACK包给客户端
                    val tcpPkt = buildTcpPacket(
                        vpnIp, entry.key.srcIp,
                        entry.dstPort, entry.key.srcPort,
                        entry.ourIsn + 1 + (entry.proxySeq - n), // SEQ=已发+1(减n是因为刚加完,我们需要发这段data的起始SEQ)
                        entry.clientIsn + 1 + entry.clientSeq,
                        TCP_PSH or TCP_ACK,
                        data
                    )
                    writeTun(tcpPkt)
                }
                // 发送FIN
                entry.proxyClosed = true
                if (entry.state != TcpState.CLOSED) {
                    val fin = buildTcpPacket(
                        vpnIp, entry.key.srcIp,
                        entry.dstPort, entry.key.srcPort,
                        entry.ourIsn + 1 + entry.proxySeq,
                        entry.clientIsn + 1 + entry.clientSeq,
                        TCP_FIN or TCP_ACK, ByteArray(0)
                    )
                    writeTun(fin)
                }
            } catch (e: Exception) {
                XLog.w(TAG, "proxy reader error: ${e.message}")
                // 出错发RST
                sendRst(vpnIp, entry.key.srcIp, entry.dstPort, entry.key.srcPort,
                    entry.clientIsn + 1 + entry.clientSeq, entry.ourIsn + 1 + entry.proxySeq)
            } finally {
                closeEntry(entry, true)
                natTable.remove(entry.key)
            }
        }, "vpn-in-${ipBytesToStr(entry.dstIp)}:${entry.dstPort}")
        entry.proxyThread = t
        t.isDaemon = true
        t.start()
    }

    private fun writeToProxy(entry: NatEntry, data: ByteArray) {
        try {
            val buf = ByteBuffer.wrap(data)
            while (buf.hasRemaining()) {
                entry.channel.write(buf)
            }
            synchronized(entry) {
                entry.clientSeq += data.size
                entry.lastActive = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            XLog.w(TAG, "write to proxy failed: ${e.message}")
            sendRst(vpnIp, entry.key.srcIp, entry.dstPort, entry.key.srcPort,
                entry.clientIsn + 1 + entry.clientSeq, entry.ourIsn + 1 + entry.proxySeq)
            closeEntry(entry, true)
            natTable.remove(entry.key)
        }
    }

    private fun closeEntry(entry: NatEntry, removeFromTable: Boolean = false) {
        entry.state = TcpState.CLOSED
        try { entry.channel.close() } catch (_: Exception) {}
        entry.proxyThread?.interrupt()
        if (removeFromTable) natTable.remove(entry.key)
    }

    // ── 包构造 ────────────────────────────────────────────────

    // 构造一个IP+TCP包:src/dst是IPv4整数,sport/dport是端口,flags是TCP标志位
    private fun buildTcpPacket(
        srcIp: Int, dstIp: Int,
        srcPort: Int, dstPort: Int,
        seq: Int, ack: Int,
        flags: Int,
        payload: ByteArray,
    ): ByteArray {
        val tcpDataLen = TCP_HEADER_LEN + payload.size
        val ipTotalLen = IP_HEADER_MIN_LEN + tcpDataLen
        val pkt = ByteArray(ipTotalLen)

        // IP header
        pkt[0] = (0x45).toByte() // IPv4, IHL=5
        pkt[1] = 0 // ToS
        writeInt16(pkt, 2, ipTotalLen)
        writeInt16(pkt, 4, 0) // ID
        writeInt16(pkt, 6, 0x4000) // Flags=Don't Fragment
        pkt[8] = 64 // TTL
        pkt[9] = TCP_PROTO.toByte()
        // 校验和先留0
        writeInt16(pkt, 10, 0)
        writeInt32(pkt, IP_SRC_OFFSET, srcIp)
        writeInt32(pkt, IP_DST_OFFSET, dstIp)
        val ipCksum = checksum(pkt, 0, IP_HEADER_MIN_LEN)
        writeInt16(pkt, 10, ipCksum)

        // TCP header
        val tcpBase = IP_HEADER_MIN_LEN
        writeInt16(pkt, tcpBase + 0, srcPort)
        writeInt16(pkt, tcpBase + 2, dstPort)
        writeInt32(pkt, tcpBase + 4, seq)
        writeInt32(pkt, tcpBase + 8, ack)
        pkt[tcpBase + 12] = (5 shl 4).toByte() // data offset = 5 (20 bytes)
        pkt[tcpBase + 13] = flags.toByte()
        writeInt16(pkt, tcpBase + 14, 65535) // window
        writeInt16(pkt, tcpBase + 16, 0) // checksum placeholder
        writeInt16(pkt, tcpBase + 18, 0) // urgent
        // payload
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, pkt, tcpBase + TCP_HEADER_LEN, payload.size)
        }
        // TCP checksum (with pseudo-header)
        val tcpCksum = tcpChecksum(pkt, tcpBase, tcpDataLen, srcIp, dstIp)
        writeInt16(pkt, tcpBase + 16, tcpCksum)

        return pkt
    }

    private fun writeTcpSynAck(pkt: ByteArray, srcPort: Int, dstPort: Int) {
        // 修正buildTcpPacket时端口位置错误——重写srcPort和dstPort,再重算校验和
        val tcpBase = IP_HEADER_MIN_LEN
        writeInt16(pkt, tcpBase + 0, srcPort)
        writeInt16(pkt, tcpBase + 2, dstPort)
        // 重算TCP校验和
        val srcIp = readInt32(pkt, IP_SRC_OFFSET)
        val dstIp = readInt32(pkt, IP_DST_OFFSET)
        writeInt16(pkt, tcpBase + 16, 0)
        val tcpLen = ((pkt[2].toInt() and 0xFF) shl 8 or (pkt[3].toInt() and 0xFF)) - IP_HEADER_MIN_LEN
        val cksum = tcpChecksum(pkt, tcpBase, tcpLen, srcIp, dstIp)
        writeInt16(pkt, tcpBase + 16, cksum)
        writeTun(pkt)
    }

    private fun writeTun(pkt: ByteArray) {
        synchronized(tunWriteLock) {
            try {
                tunOut?.write(pkt)
            } catch (e: IOException) {
                XLog.w(TAG, "write TUN failed: ${e.message}")
            }
        }
    }

    private fun sendRst(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int, ack: Int, seq: Int) {
        val rst = buildTcpPacket(srcIp, dstIp, srcPort, dstPort, seq, ack, TCP_RST or TCP_ACK, ByteArray(0))
        writeTun(rst)
    }

    private fun sendRstToKey(key: FlowKey, clientIsn: Int, dstPort: Int) {
        sendRst(vpnIp, key.srcIp, dstPort, key.srcPort, clientIsn + 1, nextIsn.getAndAdd(0x1000))
    }

    // ── 校验和 ────────────────────────────────────────────────

    private fun checksum(data: ByteArray, offset: Int, len: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + len
        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (data[i].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun tcpChecksum(pkt: ByteArray, tcpOffset: Int, tcpLen: Int, srcIp: Int, dstIp: Int): Int {
        // 伪头: src(4) + dst(4) + 0(1) + proto(1) + tcpLen(2) = 12 bytes
        val pseudo = ByteArray(12)
        writeInt32(pseudo, 0, srcIp)
        writeInt32(pseudo, 4, dstIp)
        pseudo[8] = 0
        pseudo[9] = TCP_PROTO.toByte()
        writeInt16(pseudo, 10, tcpLen)

        var sum = 0
        // pseudo header
        var i = 0
        while (i < 12 - 1) {
            sum += ((pseudo[i].toInt() and 0xFF) shl 8) or (pseudo[i + 1].toInt() and 0xFF)
            i += 2
        }
        // tcp segment
        i = tcpOffset
        val end = tcpOffset + tcpLen
        while (i < end - 1) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (pkt[i].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv() and 0xFFFF
    }

    // ── SOCKS5 协议 ──────────────────────────────────────────

    private fun socks5Handshake(channel: SocketChannel, username: String?, password: String?): Boolean {
        val buf = ByteBuffer.allocate(512)
        if (username != null && password != null) {
            buf.put(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        } else {
            buf.put(byteArrayOf(0x05, 0x01, 0x00))
        }
        buf.flip()
        writeAll(channel, buf)

        buf.clear()
        buf.limit(2)
        readExact(channel, buf)
        buf.flip()
        val ver = buf.get().toInt() and 0xFF
        val method = buf.get().toInt() and 0xFF
        if (ver != 0x05) return false
        if (method == 0xFF) return false

        if (method == 0x02 && username != null && password != null) {
            val ub = ByteBuffer.allocate(515)
            ub.put(0x01)
            ub.put(username.length.toByte())
            ub.put(username.toByteArray())
            ub.put(password.length.toByte())
            ub.put(password.toByteArray())
            ub.flip()
            writeAll(channel, ub)
            ub.clear()
            ub.limit(2)
            readExact(channel, ub)
            ub.flip()
            ub.get() // version
            return (ub.get().toInt() and 0xFF) == 0
        }
        return method == 0x00
    }

    private fun socks5Connect(channel: SocketChannel, dstIp: ByteArray, dstPort: Int): Boolean {
        val buf = ByteBuffer.allocate(10)
        buf.put(0x05)
        buf.put(0x01)
        buf.put(0x00)
        buf.put(0x01) // IPv4
        buf.put(dstIp)
        buf.put((dstPort ushr 8).toByte())
        buf.put(dstPort.toByte())
        buf.flip()
        writeAll(channel, buf)

        // 读取响应头: VER(1) + REP(1) + RSV(1) + ATYP(1)
        val hdr = ByteBuffer.allocate(4)
        readExact(channel, hdr)
        hdr.flip()
        val ver = hdr.get().toInt() and 0xFF
        val rep = hdr.get().toInt() and 0xFF
        hdr.get() // RSV
        val atyp = hdr.get().toInt() and 0xFF
        if (ver != 0x05) return false
        if (rep != 0x00) return false

        // 根据ATYP读取BND.ADDR + BND.PORT(必须读完,否则残留字节会混入后续数据)
        val addrLen = when (atyp) {
            0x01 -> 4  // IPv4
            0x04 -> 16 // IPv6
            0x03 -> {  // 域名:首字节为长度
                val lenBuf = ByteBuffer.allocate(1)
                readExact(channel, lenBuf)
                lenBuf.flip()
                lenBuf.get().toInt() and 0xFF
            }
            else -> 4
        }
        val skip = ByteBuffer.allocate(addrLen + 2) // ADDR + PORT(2 bytes)
        readExact(channel, skip)
        return true
    }

    private fun writeAll(channel: SocketChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) channel.write(buf)
    }

    private fun readExact(channel: SocketChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) {
            val n = channel.read(buf)
            if (n < 0) throw IOException("connection closed")
        }
    }

    // ── 工具函数 ──────────────────────────────────────────────

    private fun readIpInt(p: ByteArray, offset: Int): Int =
        ((p[offset].toInt() and 0xFF) shl 24) or
        ((p[offset + 1].toInt() and 0xFF) shl 16) or
        ((p[offset + 2].toInt() and 0xFF) shl 8) or
        (p[offset + 3].toInt() and 0xFF)

    private fun writeInt16(p: ByteArray, offset: Int, v: Int) {
        p[offset] = ((v ushr 8) and 0xFF).toByte()
        p[offset + 1] = (v and 0xFF).toByte()
    }

    private fun writeInt32(p: ByteArray, offset: Int, v: Int) {
        p[offset] = ((v ushr 24) and 0xFF).toByte()
        p[offset + 1] = ((v ushr 16) and 0xFF).toByte()
        p[offset + 2] = ((v ushr 8) and 0xFF).toByte()
        p[offset + 3] = (v and 0xFF).toByte()
    }

    private fun readInt32(p: ByteArray, offset: Int): Int = readIpInt(p, offset)

    private fun intToIp(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun parseIp(s: String): ByteArray {
        val parts = s.split(".")
        require(parts.size == 4)
        return byteArrayOf(
            parts[0].toInt().toByte(),
            parts[1].toInt().toByte(),
            parts[2].toInt().toByte(),
            parts[3].toInt().toByte(),
        )
    }

    private fun ipv4ToInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or
        ((b[1].toInt() and 0xFF) shl 16) or
        ((b[2].toInt() and 0xFF) shl 8) or
        (b[3].toInt() and 0xFF)

    private fun ipBytesToStr(b: ByteArray): String =
        "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}.${b[2].toInt() and 0xFF}.${b[3].toInt() and 0xFF}"

    private fun ipIntToStr(v: Int): String =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

    private fun closeQuietly(ch: SocketChannel) {
        try { ch.close() } catch (_: Exception) {}
    }

    // ── 通知 / 生命周期 ───────────────────────────────────────

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val config = currentConfig
        val text = if (config != null) "${config.host}:${config.port}" else "运行中"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("VPN → $text")
            .setSmallIcon(R.drawable.ic_vpn)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running.set(false)
        // 关闭所有NAT连接
        natTable.values.forEach { closeEntry(it) }
        natTable.clear()
        pumpThread?.interrupt()
        pumpThread = null
        try { tunOut?.close() } catch (_: Exception) {}
        tunOut = null
        tunInterface?.close()
        tunInterface = null
        currentConfig = null
        instance = null
        super.onDestroy()
    }

    fun stopVpn() {
        running.set(false)
        stopSelf()
    }
}
