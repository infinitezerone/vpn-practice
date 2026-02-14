package com.infinitezerone.pratice.discovery

import android.content.Context
import android.content.ContextWrapper
import com.infinitezerone.pratice.config.ProxyProtocol
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanProxyScannerTest {
    @Test
    fun buildScanHostsReturnsIpv4Slash24Range() {
        val hosts = LanProxyScanner.buildScanHosts("192.168.1.37")

        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
    }

    @Test
    fun discoverReturnsFirstDetectedProxy() = runBlocking {
        val probeCalls = AtomicInteger(0)
        val scanner = LanProxyScanner(
            localIpv4Provider = { "10.0.2.15" },
            proxyProbe = { host, port, _ ->
                probeCalls.incrementAndGet()
                if (host == "10.0.2.2" && port == 7891) {
                    ProxyProtocol.Socks5
                } else {
                    null
                }
            }
        )

        val result = scanner.discover(
            context = FakeContext(),
            scanPorts = listOf(7890, 7891),
            parallelism = 8
        )

        requireNotNull(result)
        assertEquals("10.0.2.2", result.host)
        assertEquals(7891, result.port)
        assertEquals(ProxyProtocol.Socks5, result.protocol)
        assertEquals("10.0.2.15", result.localIp)
        assertTrue(probeCalls.get() > 0)
    }

    @Test
    fun discoverReturnsNullWithoutLocalIp() = runBlocking {
        val scanner = LanProxyScanner(
            localIpv4Provider = { null },
            proxyProbe = { _, _, _ -> ProxyProtocol.Http }
        )

        val result = scanner.discover(context = FakeContext())

        assertNull(result)
    }

    private class FakeContext : ContextWrapper(null)
}
