package com.infinitezerone.pratice.vpn

import com.infinitezerone.pratice.config.ProxyProtocol
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyProbeCoordinatorTest {
    @Test
    fun retriesUntilConnectionSucceeds() = runBlocking {
        val attemptCounter = AtomicInteger(0)
        val sleeps = mutableListOf<Long>()
        val coordinator = ProxyProbeCoordinator(
            logger = { },
            resolveAddress = { _, _ -> InetSocketAddress("127.0.0.1", 9027) },
            bypassVpnForSocket = { true },
            hasActiveTunnel = { false },
            testConnection = { _, _, _, _, _ ->
                val attempt = attemptCounter.incrementAndGet()
                if (attempt < 3) "error" else null
            },
            sleep = { ms -> sleeps += ms }
        )

        val error = coordinator.waitForProxyWithRetry("proxy.example", 9027, ProxyProtocol.Http)

        assertNull(error)
        assertEquals(3, attemptCounter.get())
        assertEquals(listOf(1_000L, 2_000L), sleeps)
    }

    @Test
    fun returnsResolveErrorAfterMaxRetries() = runBlocking {
        val sleeps = mutableListOf<Long>()
        var testConnectionCalls = 0
        val coordinator = ProxyProbeCoordinator(
            logger = { },
            resolveAddress = { _, _ -> null },
            bypassVpnForSocket = { true },
            hasActiveTunnel = { false },
            testConnection = { _, _, _, _, _ ->
                testConnectionCalls += 1
                null
            },
            sleep = { ms -> sleeps += ms }
        )

        val error = coordinator.waitForProxyWithRetry("proxy.example", 9027, ProxyProtocol.Http)

        assertTrue(error!!.contains("Cannot resolve proxy"))
        assertEquals(0, testConnectionCalls)
        assertEquals(listOf(1_000L, 2_000L), sleeps)
    }
}
