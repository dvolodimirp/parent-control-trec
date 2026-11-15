package ua.parentcontrol

import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A local, DNS-only "VPN". It does NOT route general traffic — only UDP port 53
 * (plain DNS) is captured, via narrow routes to the device's own current DNS
 * server IPs (never 0.0.0.0/0). Every other packet never enters this interface
 * at all and is handled by the OS exactly as if this service didn't exist. That
 * is a deliberate safety choice: a bug here can make DNS lookups fail (apps show
 * "can't connect"), but it cannot take down general connectivity, because general
 * traffic is never routed through it.
 *
 * KNOWN GAP: a resolver that ignores the system DNS server — most notably Chrome
 * with "Use secure DNS" (DNS-over-HTTPS) turned on by default — bypasses this
 * entirely, since its lookups never go to port 53 at all. Native apps (Instagram,
 * TikTok, etc.) normally do use the system resolver and are covered.
 *
 * I could not test this against a real network stack — there is no Android
 * device or emulator in the environment this was written in. Turning off
 * "мережевий фільтр" in the parent panel stops this service on the next sync.
 */
class NetGuardService : VpnService() {
    companion object {
        @Volatile var blockedDomains: Set<String> = emptySet()
        @Volatile private var running = false
        @Volatile var lastError: String? = null
        fun isRunning() = running
    }

    private var pfd: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    @Volatile private var stopping = false
    // Forwarding a query to the real DNS server blocks for up to 4s waiting for its reply.
    // Doing that on the same thread that reads new packets from the tun interface would stall
    // every other in-flight query behind it (a single page load easily fires off a dozen DNS
    // lookups at once) — this pool lets several be in flight together instead of serialising them.
    private val forwardPool = Executors.newFixedThreadPool(4)
    private val writeLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        val dnsServers = currentDnsServers()
        if (dnsServers.isEmpty()) {
            lastError = "не вдалось визначити IPv4 DNS-сервери пристрою (мережа IPv6-only?)"
            stopSelf(); return START_NOT_STICKY
        }
        val builder = Builder()
            .setSession("Parent Control filter")
            .addAddress("10.111.222.1", 32)
            .setMtu(1500)
        dnsServers.forEach { try { builder.addRoute(it, 32) } catch (_: Exception) {} }
        val established = try { builder.establish() } catch (ex: Exception) {
            lastError = "establish() впав: ${ex.message ?: ex.javaClass.simpleName}"; null
        }
        if (established == null) {
            if (lastError == null) lastError = "немає дозволу на VPN (не виконано пункт 4 у додатку)"
            stopSelf(); return START_NOT_STICKY
        }
        lastError = null
        pfd = established
        running = true; stopping = false
        worker = thread(start = true, name = "netguard") { runLoop(established) }
        return START_STICKY
    }

    private fun currentDnsServers(): List<String> = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        val net = cm?.activeNetwork
        cm?.getLinkProperties(net)?.dnsServers
            ?.filterIsInstance<Inet4Address>()
            ?.mapNotNull { it.hostAddress }
            ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun runLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        while (!stopping) {
            val buf = ByteArray(2000) // a fresh buffer per packet: handlePacket may hand it off to another thread
            val len = try { input.read(buf) } catch (_: Exception) { break }
            if (len <= 0) continue
            try { handlePacket(buf, len, output) } catch (_: Exception) { /* malformed/unexpected packet: drop it, never crash the loop */ }
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int, output: FileOutputStream) {
        if (len < 28) return // smaller than a minimal IPv4+UDP header
        val verIhl = buf[0].toInt() and 0xFF
        if ((verIhl shr 4) != 4) return // IPv4 only
        val ihl = (verIhl and 0x0F) * 4
        val protocol = buf[9].toInt() and 0xFF
        if (protocol != 17) return // UDP only — nothing else should ever arrive, given our narrow routes
        val srcIp = buf.copyOfRange(12, 16)
        val dstIp = buf.copyOfRange(16, 20)
        val udpOff = ihl
        if (len < udpOff + 8) return
        val srcPort = ((buf[udpOff].toInt() and 0xFF) shl 8) or (buf[udpOff + 1].toInt() and 0xFF)
        val dstPort = ((buf[udpOff + 2].toInt() and 0xFF) shl 8) or (buf[udpOff + 3].toInt() and 0xFF)
        if (dstPort != 53) return
        val dnsOff = udpOff + 8
        val dnsLen = len - dnsOff
        if (dnsLen < 12) return
        val dns = buf.copyOfRange(dnsOff, dnsOff + dnsLen)
        val qname = parseQName(dns) ?: return

        if (isBlocked(qname)) {
            // Fast path: no network involved, answer immediately on this same thread.
            writePacket(output, buildReplyPacket(srcIp, dstIp, srcPort, dstPort, buildNxDomain(dns)))
            return
        }
        val dstIpStr = InetAddress.getByAddress(dstIp).hostAddress ?: return
        // Slow path: dispatch to the pool so one real DNS lookup can never stall the others,
        // or stall us noticing the next packet at all.
        forwardPool.execute {
            val reply = forward(dns, dstIpStr) ?: return@execute
            writePacket(output, buildReplyPacket(srcIp, dstIp, srcPort, dstPort, reply))
        }
    }

    private fun writePacket(output: FileOutputStream, packet: ByteArray) {
        synchronized(writeLock) { try { output.write(packet) } catch (_: Exception) {} }
    }

    private fun isBlocked(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return blockedDomains.any { h == it || h.endsWith(".$it") }
    }

    private fun parseQName(dns: ByteArray): String? {
        var pos = 12
        val sb = StringBuilder()
        while (pos < dns.size) {
            val l = dns[pos].toInt() and 0xFF
            if (l == 0) { pos++; break }
            if (l and 0xC0 == 0xC0) return null // compressed name pointer — not expected in a client query, skip
            if (pos + 1 + l > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, pos + 1, l, Charsets.US_ASCII))
            pos += 1 + l
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /** NXDOMAIN reply built from the original query (same ID/question, just flipped to a response). */
    private fun buildNxDomain(q: ByteArray): ByteArray {
        val r = q.copyOf()
        r[2] = 0x81.toByte() // QR=1 (response), RD=1
        r[3] = 0x83.toByte() // RA=1, RCODE=3 (NXDOMAIN)
        return r
    }

    /** Sends the query to the real DNS server, bypassing this very VPN via protect(), and returns the raw reply. */
    private fun forward(query: ByteArray, serverIp: String): ByteArray? {
        val sock = DatagramSocket()
        return try {
            protect(sock)
            sock.soTimeout = 4000
            sock.send(DatagramPacket(query, query.size, InetAddress.getByName(serverIp), 53))
            val replyBuf = ByteArray(2000)
            val replyPacket = DatagramPacket(replyBuf, replyBuf.size)
            sock.receive(replyPacket)
            replyBuf.copyOfRange(0, replyPacket.length)
        } catch (_: Exception) { null } finally { sock.close() }
    }

    /** Builds the IPv4+UDP packet carrying `dns` back from dstIp:53 to srcIp:srcPort (roles swapped vs. the query). */
    private fun buildReplyPacket(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, dns: ByteArray): ByteArray {
        val udpLen = 8 + dns.size
        val totalLen = 20 + udpLen
        val b = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
        b.put(0x45.toByte()); b.put(0); b.putShort(totalLen.toShort())
        b.putShort(0); b.putShort(0x4000.toShort()) // id=0, flags=Don't Fragment
        b.put(64); b.put(17) // TTL, protocol=UDP
        b.putShort(0) // header checksum placeholder
        b.put(dstIp); b.put(srcIp) // this reply's source is the (fake) DNS server, i.e. the original destination
        b.putShort(53.toShort()); b.putShort(srcPort.toShort())
        b.putShort(udpLen.toShort()); b.putShort(0) // UDP checksum 0 = not computed, valid for IPv4
        b.put(dns)
        val bytes = b.array()
        val csum = ipChecksum(bytes)
        bytes[10] = (csum.toInt() shr 8).toByte(); bytes[11] = (csum.toInt() and 0xFF).toByte()
        return bytes
    }

    private fun ipChecksum(header: ByteArray): Short {
        var sum = 0L
        var i = 0
        while (i < 20) { sum += ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF); i += 2 }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toShort()
    }

    override fun onDestroy() {
        stopping = true; running = false
        try { pfd?.close() } catch (_: Exception) {}
        worker?.interrupt()
        forwardPool.shutdownNow()
        super.onDestroy()
    }
}
