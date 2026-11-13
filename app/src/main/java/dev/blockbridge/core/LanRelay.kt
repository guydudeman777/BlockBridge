package dev.blockbridge.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Pretends to be a LAN game on [localPort]. Answers discovery pings with the
 * real server's info, then pipes every other UDP packet to the real server.
 * One upstream socket per console so the server sees each player apart.
 */
class LanRelay(
    private val remoteHost: String,
    private val remotePort: Int,
    private val displayName: String?,
    private val localPort: Int = 19132,
    private val listener: Listener = object : Listener {},
) {
    interface Listener {
        fun onStatus(online: Boolean, motd: String?) {}
        fun onPlayers(count: Int) {}
        fun onError(message: String) {}
        fun log(message: String) {}
    }

    private class Session(val client: InetSocketAddress, val upstream: DatagramSocket) {
        @Volatile var lastSeen = System.currentTimeMillis()
    }

    private val guid = Random.nextLong()
    private val sessions = ConcurrentHashMap<InetSocketAddress, Session>()
    private val threads = mutableListOf<Thread>()
    @Volatile private var running = false
    @Volatile private var remoteAddr: InetSocketAddress? = null
    @Volatile private var cachedMotd: String? = null
    private lateinit var lan: DatagramSocket

    fun start() {
        check(!running) { "already running" }
        val port = localPort // inside apply{}, `localPort` would mean the socket's own port
        lan = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(port))
        }
        running = true
        spawn("bb-lan") { lanLoop() }
        spawn("bb-ping") { pingLoop() }
        spawn("bb-reaper") { reaperLoop() }
        listener.log("Listening on ${lan.localSocketAddress} for $remoteHost:$remotePort")
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { lan.close() }
        sessions.values.forEach { runCatching { it.upstream.close() } }
        sessions.clear()
        threads.forEach { it.interrupt() }
        threads.clear()
        listener.onPlayers(0)
    }

    private fun spawn(name: String, body: () -> Unit) {
        val t = Thread({
            try { body() } catch (e: Exception) {
                if (running) listener.onError("$name: ${e.message}")
            }
        }, name).apply { isDaemon = true }
        threads += t
        t.start()
    }

    private fun lanLoop() {
        val buf = ByteArray(65535)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            pkt.setLength(buf.size)
            try { lan.receive(pkt) } catch (e: SocketException) {
                if (lan.isClosed) break
                listener.log("LAN recv: ${e.javaClass.simpleName} ${e.message}")
                continue
            }
            val from = pkt.socketAddress as InetSocketAddress
            val len = pkt.length

            if (RakNet.isPing(buf, len)) {
                val motd = cachedMotd?.let { RakNet.rewriteMotd(it, displayName, localPort, guid) }
                    ?: RakNet.fallbackMotd(displayName ?: remoteHost, localPort, guid)
                val pong = RakNet.buildPong(RakNet.pingTime(buf), guid, motd)
                try { lan.send(DatagramPacket(pong, pong.size, from)) } catch (e: Exception) {
                    listener.log("Pong send failed: ${e.message}")
                }
                continue
            }

            val target = remoteAddr ?: continue
            val session = sessions[from] ?: openSession(from, target) ?: continue
            session.lastSeen = System.currentTimeMillis()
            runCatching { session.upstream.send(DatagramPacket(buf, len, target)) }
        }
    }

    private fun openSession(client: InetSocketAddress, target: InetSocketAddress): Session? {
        val up = try {
            DatagramSocket().apply { connect(target) }
        } catch (e: Exception) {
            listener.onError("Cannot reach server: ${e.message}")
            return null
        }
        val s = Session(client, up)
        sessions[client] = s
        listener.log("Console joined from ${client.address.hostAddress}")
        listener.onPlayers(sessions.size)
        spawn("bb-up-${client.port}") { upstreamLoop(s) }
        return s
    }

    private fun upstreamLoop(s: Session) {
        val buf = ByteArray(65535)
        val pkt = DatagramPacket(buf, buf.size)
        while (running && !s.upstream.isClosed) {
            pkt.setLength(buf.size)
            try { s.upstream.receive(pkt) } catch (e: SocketException) {
                // Connected UDP sockets throw on ICMP "port unreachable"; keep going.
                if (s.upstream.isClosed) break
                continue
            }
            s.lastSeen = System.currentTimeMillis()
            runCatching { lan.send(DatagramPacket(buf, pkt.length, s.client)) }
        }
    }

    /** Closes sessions that went quiet (player left). */
    private fun reaperLoop() {
        while (running) {
            try { Thread.sleep(5_000) } catch (e: InterruptedException) { break }
            val now = System.currentTimeMillis()
            val dead = sessions.values.filter { now - it.lastSeen > 30_000 }
            dead.forEach {
                sessions.remove(it.client)
                runCatching { it.upstream.close() }
                listener.log("Console left: ${it.client.address.hostAddress}")
            }
            if (dead.isNotEmpty()) listener.onPlayers(sessions.size)
        }
    }

    /** Resolves the host and asks the real server for its MOTD every few seconds. */
    private fun pingLoop() {
        val sock = DatagramSocket().apply { soTimeout = 2_000 }
        val buf = ByteArray(4096)
        var wasOnline: Boolean? = null
        try {
            while (running) {
                var online = false
                try {
                    val addr = InetSocketAddress(InetAddress.getByName(remoteHost), remotePort)
                    remoteAddr = addr
                    val ping = RakNet.buildPing(System.currentTimeMillis(), guid)
                    sock.send(DatagramPacket(ping, ping.size, addr))
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    RakNet.parsePong(buf, pkt.length)?.let {
                        cachedMotd = it.motd
                        online = true
                    }
                } catch (e: SocketTimeoutException) {
                    // server did not answer
                } catch (e: Exception) {
                    if (running && wasOnline != false) listener.onError("Lookup failed: ${e.message}")
                }
                if (online != wasOnline) {
                    listener.onStatus(online, cachedMotd)
                    wasOnline = online
                }
                try { Thread.sleep(3_000) } catch (e: InterruptedException) { break }
            }
        } finally {
            sock.close()
        }
    }
}
