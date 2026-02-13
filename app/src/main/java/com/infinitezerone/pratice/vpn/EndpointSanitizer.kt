package com.infinitezerone.pratice.vpn

object EndpointSanitizer {
    fun sanitizeHost(host: String): String {
        val withoutScheme = host.substringAfter("://", host)
        return withoutScheme.substringAfterLast("@")
    }
}
