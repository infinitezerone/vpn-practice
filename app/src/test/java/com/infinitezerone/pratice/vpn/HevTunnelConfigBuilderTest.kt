package com.infinitezerone.pratice.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HevTunnelConfigBuilderTest {
    @Test
    fun includesMapDnsBlockWhenProvided() {
        val config = HevTunnelConfigBuilder.build(
            host = "127.0.0.1",
            port = 1080,
            udpMode = "tcp",
            mapDnsConfig = MapDnsConfig(
                address = "198.18.0.2",
                port = 53,
                network = "100.64.0.0",
                netmask = "255.192.0.0",
                cacheSize = 4096
            )
        )

        assertTrue(config.contains("mapdns:"))
        assertTrue(config.contains("address: \"198.18.0.2\""))
    }

    @Test
    fun omitsMapDnsBlockWhenNotProvided() {
        val config = HevTunnelConfigBuilder.build(
            host = "127.0.0.1",
            port = 1080,
            udpMode = "udp",
            mapDnsConfig = null
        )

        assertFalse(config.contains("mapdns:"))
    }
}
