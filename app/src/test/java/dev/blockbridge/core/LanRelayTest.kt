package dev.blockbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

/** Fake server + fake console on loopback, relay in the middle. */
class LanRelayTest {
    @Test fun pingAndRelayRoundTrip() {
        val loop = InetAddress.getLoopbackAddress()
        val server = DatagramSocket(0, loop).apply { soTimeout = 5000 }
        val serverMotd = "MCPE;Real Server;766;1.21.50;2;20;5;sub;Survival;1;${server.localPort};19133;"

        // Fake server: answer pings, echo everything else with "echo:" prefix.
        thread(isDaemon = true) {
            val buf = ByteArray(2048)
            while (!server.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { server.receive(p) } catch (e: Exception) { if (server.isClosed) break else continue }
                val reply = if (RakNet.isPing(buf, p.length)) RakNet.buildPong(RakNet.pingTime(buf), 5L, serverMotd)
                else "echo:".toByteArray() + buf.copyOf(p.length)
                server.send(DatagramPacket(reply, reply.size, p.socketAddress))
            }
        }

        val localPort = DatagramSocket(0).use { it.localPort }
        val relay = LanRelay(loop.hostAddress!!, server.localPort, "My Share", localPort, object : LanRelay.Listener {
            override fun onError(message: String) = println("ERR $message")
            override fun log(message: String) = println("LOG $message")
            override fun onStatus(online: Boolean, motd: String?) = println("STATUS $online $motd")
        })
        relay.start()
        try {
            Thread.sleep(1500) // let the relay learn the server MOTD
            val console = DatagramSocket().apply { soTimeout = 3000 }
            val relayAddr = InetSocketAddress(loop, localPort)
            val buf = ByteArray(2048)

            val ping = RakNet.buildPing(777L, 1L)
            console.send(DatagramPacket(ping, ping.size, relayAddr))
            val pp = DatagramPacket(buf, buf.size).also { console.receive(it) }
            val pong = RakNet.parsePong(buf, pp.length)
            assertNotNull(pong)
            assertEquals(777L, pong!!.time)
            val parts = pong.motd.split(';')
            assertEquals("My Share", parts[1])
            assertEquals("766", parts[2])
            assertEquals(localPort.toString(), parts[10])

            val game = byteArrayOf(0x05, 1, 2, 3)
            console.send(DatagramPacket(game, game.size, relayAddr))
            val gp = DatagramPacket(buf, buf.size).also { console.receive(it) }
            val got = buf.copyOf(gp.length)
            assertTrue(got.contentEquals("echo:".toByteArray() + game))
            console.close()
        } finally {
            relay.stop()
            server.close()
        }
    }
}
