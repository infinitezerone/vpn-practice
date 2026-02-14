package com.infinitezerone.pratice.vpn

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Local SOCKS5 server that tunnels CONNECT requests through an HTTP proxy.
 * Supports SOCKS5 CONNECT only (TCP).
 */
class HttpConnectSocksBridge(
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val protectSocket: (Socket) -> Boolean,
    private val logger: (String) -> Unit
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start(): Int {
        if (running) {
            return serverSocket?.localPort ?: -1
        }
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        serverSocket = server
        running = true
        acceptThread = thread(
            name = "http-connect-socks-accept",
            isDaemon = true
        ) {
            acceptLoop(server)
        }
        return server.localPort
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
            // Ignored.
        } finally {
            serverSocket = null
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            thread(name = "http-connect-socks-client", isDaemon = true) {
                handleClient(client)
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { socksClient ->
            val clientInput = BufferedInputStream(socksClient.getInputStream())
            val clientOutput = BufferedOutputStream(socksClient.getOutputStream())

            if (!performSocksHandshake(clientInput, clientOutput)) {
                return
            }

            val destination = parseConnectRequest(clientInput, clientOutput) ?: return
            val upstream = Socket()
            try {
                if (!protectSocket(upstream)) {
                    sendSocksFailure(clientOutput, REP_GENERAL_FAILURE)
                    logger("HTTP bridge failed to protect upstream socket.")
                    return
                }
                upstream.connect(InetSocketAddress(upstreamHost, upstreamPort), CONNECT_TIMEOUT_MS)
                upstream.soTimeout = CONNECT_TIMEOUT_MS

                val upstreamInput = BufferedInputStream(upstream.getInputStream())
                val upstreamOutput = BufferedOutputStream(upstream.getOutputStream())
                val connectRequest = buildConnectRequest(destination.first, destination.second)
                upstreamOutput.write(connectRequest.toByteArray(StandardCharsets.ISO_8859_1))
                upstreamOutput.flush()

                if (!readConnectResponse(upstreamInput)) {
                    sendSocksFailure(clientOutput, REP_CONNECTION_REFUSED)
                    return
                }
                sendSocksSuccess(clientOutput)

                val uplink = thread(isDaemon = true) {
                    copyStream(clientInput, upstreamOutput)
                    try {
                        upstream.shutdownOutput()
                    } catch (_: Exception) {
                        // Ignored.
                    }
                }
                val downlink = thread(isDaemon = true) {
                    copyStream(upstreamInput, clientOutput)
                    try {
                        socksClient.shutdownOutput()
                    } catch (_: Exception) {
                        // Ignored.
                    }
                }
                uplink.join()
                downlink.join()
            } catch (_: Exception) {
                try {
                    sendSocksFailure(clientOutput, REP_GENERAL_FAILURE)
                } catch (_: Exception) {
                    // Ignored.
                }
            } finally {
                try {
                    upstream.close()
                } catch (_: Exception) {
                    // Ignored.
                }
            }
        }
    }

    private fun performSocksHandshake(
        input: BufferedInputStream,
        output: BufferedOutputStream
    ): Boolean {
        val version = input.read()
        if (version != SOCKS_VERSION) {
            return false
        }
        val methodCount = input.read()
        if (methodCount <= 0) {
            return false
        }
        input.skipFully(methodCount)
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), AUTH_NONE.toByte()))
        output.flush()
        return true
    }

    private fun parseConnectRequest(
        input: BufferedInputStream,
        output: BufferedOutputStream
    ): Pair<String, Int>? {
        val header = ByteArray(4)
        input.readExact(header)
        val version = header[0].toInt() and 0xFF
        val command = header[1].toInt() and 0xFF
        val atyp = header[3].toInt() and 0xFF
        if (version != SOCKS_VERSION || command != CMD_CONNECT) {
            sendSocksFailure(output, REP_COMMAND_NOT_SUPPORTED)
            return null
        }

        val host = when (atyp) {
            ATYP_IPV4 -> {
                val bytes = ByteArray(4)
                input.readExact(bytes)
                InetAddress.getByAddress(bytes).hostAddress ?: return null
            }
            ATYP_DOMAIN -> {
                val length = input.read()
                if (length <= 0) {
                    return null
                }
                val bytes = ByteArray(length)
                input.readExact(bytes)
                String(bytes, StandardCharsets.UTF_8)
            }
            ATYP_IPV6 -> {
                val bytes = ByteArray(16)
                input.readExact(bytes)
                InetAddress.getByAddress(bytes).hostAddress ?: return null
            }
            else -> {
                sendSocksFailure(output, REP_ADDRESS_TYPE_NOT_SUPPORTED)
                return null
            }
        }
        val portBytes = ByteArray(2)
        input.readExact(portBytes)
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        return host to port
    }

    private fun buildConnectRequest(host: String, port: Int): String {
        val authority = if (host.contains(':') && !host.startsWith("[")) {
            "[$host]:$port"
        } else {
            "$host:$port"
        }
        return buildString {
            append("CONNECT ").append(authority).append(" HTTP/1.1\r\n")
            append("Host: ").append(authority).append("\r\n")
            append("Proxy-Connection: Keep-Alive\r\n")
            append("\r\n")
        }
    }

    private fun readConnectResponse(input: BufferedInputStream): Boolean {
        val header = ByteArrayOutputStream()
        var state = 0
        while (header.size() < MAX_HTTP_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) {
                return false
            }
            header.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                else -> 0
            }
            if (state == 4) {
                break
            }
        }
        val response = header.toString(StandardCharsets.ISO_8859_1.name())
        val statusLine = response.lineSequence().firstOrNull() ?: return false
        return statusLine.startsWith("HTTP/1.1 200") || statusLine.startsWith("HTTP/1.0 200")
    }

    private fun sendSocksSuccess(output: BufferedOutputStream) {
        output.write(
            byteArrayOf(
                SOCKS_VERSION.toByte(),
                REP_SUCCESS.toByte(),
                0x00,
                ATYP_IPV4.toByte(),
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00
            )
        )
        output.flush()
    }

    private fun sendSocksFailure(output: BufferedOutputStream, code: Int) {
        output.write(
            byteArrayOf(
                SOCKS_VERSION.toByte(),
                code.toByte(),
                0x00,
                ATYP_IPV4.toByte(),
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00
            )
        )
        output.flush()
    }

    private fun copyStream(input: BufferedInputStream, output: BufferedOutputStream) {
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = try {
                input.read(buffer)
            } catch (_: Exception) {
                -1
            }
            if (count <= 0) {
                break
            }
            try {
                output.write(buffer, 0, count)
                output.flush()
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun BufferedInputStream.readExact(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) {
                throw EOFException("Unexpected EOF while reading SOCKS frame.")
            }
            offset += read
        }
    }

    private fun BufferedInputStream.skipFully(count: Int) {
        var remaining = count.toLong()
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                if (read() < 0) {
                    throw EOFException("Unexpected EOF while skipping bytes.")
                }
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private companion object {
        const val SOCKS_VERSION = 0x05
        const val AUTH_NONE = 0x00
        const val CMD_CONNECT = 0x01
        const val ATYP_IPV4 = 0x01
        const val ATYP_DOMAIN = 0x03
        const val ATYP_IPV6 = 0x04
        const val REP_SUCCESS = 0x00
        const val REP_GENERAL_FAILURE = 0x01
        const val REP_CONNECTION_REFUSED = 0x05
        const val REP_COMMAND_NOT_SUPPORTED = 0x07
        const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08
        const val CONNECT_TIMEOUT_MS = 5_000
        const val MAX_HTTP_HEADER_BYTES = 16 * 1024
    }
}
