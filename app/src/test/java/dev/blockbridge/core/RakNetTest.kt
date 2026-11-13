package dev.blockbridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RakNetTest {
    @Test fun pingRoundTrip() {
        val ping = RakNet.buildPing(1234L, 99L)
        assertTrue(RakNet.isPing(ping, ping.size))
        assertEquals(1234L, RakNet.pingTime(ping))
    }

    @Test fun pongRoundTrip() {
        val pong = RakNet.buildPong(5L, 7L, "MCPE;Hi;766;1.21.50;1;10;7;x;Survival;1;19132;19133;")
        val p = RakNet.parsePong(pong, pong.size)
        assertNotNull(p)
        p!!
        assertEquals(5L, p.time)
        assertEquals(7L, p.serverGuid)
        assertTrue(p.motd.startsWith("MCPE;Hi;"))
    }

    @Test fun rewriteSetsNameAndPort() {
        val out = RakNet.rewriteMotd("MCPE;Real;766;1.21.50;3;20;1;sub;Survival;1;19200;19201;", "Mine", 19132, 1L)
        val parts = out.split(';')
        assertEquals("Mine", parts[1])
        assertEquals("766", parts[2])
        assertEquals("19132", parts[10])
        assertEquals("19132", parts[11])
        assertTrue(out.endsWith(";"))
    }

    @Test fun rewritePadsShortMotd() {
        val parts = RakNet.rewriteMotd("MCPE;Old", null, 19132, 42L).split(';')
        assertEquals("Old", parts[1])
        assertEquals("42", parts[6])
        assertEquals("19132", parts[10])
    }
}
