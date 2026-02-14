package com.infinitezerone.pratice.vpn

import com.infinitezerone.pratice.config.ProxyProtocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

object ProxyConnectivityChecker {
    fun testConnection(
        host: String,
        port: Int,
        protocol: ProxyProtocol,
        timeoutMs: Int = 3_000,
        protectSocket: ((Socket) -> Boolean)? = null,
        connectAddress: InetSocketAddress? = null
    ): String? {
        val safeHost = EndpointSanitizer.sanitizeHost(host)
        val targetAddress = connectAddress ?: InetSocketAddress(safeHost, port)
        return try {
            Socket().use { socket ->
                if (protectSocket != null && !protectSocket.invoke(socket)) {
                    return "Cannot protect proxy socket for $safeHost:$port."
                }
                socket.connect(targetAddress, timeoutMs)
                socket.soTimeout = timeoutMs
                val protocolError = when (protocol) {
                    ProxyProtocol.Socks5 -> verifySocks5(socket)
                    ProxyProtocol.Http -> verifyHttpProxy(socket)
                }
                if (protocolError != null) {
                    return protocolError
                }
            }
            null
        } catch (e: Exception) {
            "Cannot connect to proxy $safeHost:$port (${e.javaClass.simpleName})."
        }
    }

    private fun verifySocks5(socket: Socket): String? {
        val out = BufferedOutputStream(socket.getOutputStream())
        val input = BufferedInputStream(socket.getInputStream())
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val version = input.read()
        val method = input.read()
        return when {
            version < 0 || method < 0 -> "Proxy closed connection during SOCKS5 handshake."
            version == 0x05 && method == 0x00 -> null
            version == 0x05 && method == 0xFF -> "SOCKS5 proxy rejected no-auth method."
            version == 0x05 -> "SOCKS5 proxy requires unsupported auth method (0x${method.toString(16)})."
            version == 'H'.code -> "Proxy protocol mismatch: endpoint looks like HTTP, not SOCKS5."
            else -> "Proxy protocol mismatch: invalid SOCKS5 handshake response."
        }
    }

    private fun verifyHttpProxy(socket: Socket): String? {
        val out = BufferedOutputStream(socket.getOutputStream())
        val input = BufferedInputStream(socket.getInputStream())
        val request = "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"
        out.write(request.toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()
        val statusLine = readHttpStatusLine(input)
        val statusCode = statusLine
            .split(" ")
            .getOrNull(1)
            ?.toIntOrNull()
        return when {
            statusLine.startsWith("HTTP/1.1") || statusLine.startsWith("HTTP/1.0") -> {
                when (statusCode) {
                    407 -> "HTTP proxy requires authentication."
                    null -> "HTTP proxy returned malformed status line."
                    in 100..599 -> null
                    else -> "HTTP proxy returned invalid status code."
                }
            }
            statusLine.isBlank() -> "Proxy closed connection during HTTP CONNECT handshake."
            else -> "Proxy protocol mismatch: endpoint is not speaking HTTP proxy."
        }
    }

    private fun readHttpStatusLine(input: BufferedInputStream): String {
        val line = StringBuilder()
        while (line.length < 512) {
            val b = input.read()
            if (b < 0) {
                break
            }
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                line.append(b.toChar())
            }
        }
        return line.toString()
    }
}
