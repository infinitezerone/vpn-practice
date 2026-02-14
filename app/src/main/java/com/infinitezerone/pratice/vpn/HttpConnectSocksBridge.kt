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
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Local SOCKS5 server that tunnels CONNECT requests through an HTTP proxy.
 * Supports SOCKS5 CONNECT only (TCP).
 */
class HttpConnectSocksBridge(
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val upstreamEndpointProvider: () -> InetSocketAddress?,
    private val destinationEndpointProvider: (String, Int) -> InetSocketAddress?,
    private val bypassVpnForSocket: (Socket) -> Boolean,
    private val allowDirectFallbackForNonHttpPorts: Boolean,
    private val logger: (String) -> Unit
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val connectionCounter = AtomicLong(0)

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
        val connectionId = connectionCounter.incrementAndGet()
        client.use { socksClient ->
            val clientInput = BufferedInputStream(socksClient.getInputStream())
            val clientOutput = BufferedOutputStream(socksClient.getOutputStream())

            if (!performSocksHandshake(clientInput, clientOutput)) {
                return
            }

            val destination = parseConnectRequest(clientInput, clientOutput) ?: return
            logger("HTTP bridge conn#$connectionId SOCKS CONNECT ${destination.first}:${destination.second}")
            if (isUnsupportedMappedDnsTls(destination)) {
                sendSocksFailure(clientOutput, REP_CONNECTION_REFUSED)
                logger("HTTP bridge conn#$connectionId rejected: mapped DNS over TLS is not supported")
                return
            }
            if (allowDirectFallbackForNonHttpPorts && !isLikelyHttpPort(destination.second)) {
                if (startDirectPassthrough(connectionId, destination, clientInput, clientOutput, socksClient)) {
                    return
                }
            }
            val upstream = Socket()
            try {
                if (!bypassVpnForSocket(upstream)) {
                    sendSocksFailure(clientOutput, REP_GENERAL_FAILURE)
                    logger("HTTP bridge conn#$connectionId failed: cannot bypass VPN for upstream socket")
                    return
                }
                val upstreamEndpoint = upstreamEndpointProvider()
                if (upstreamEndpoint == null) {
                    sendSocksFailure(clientOutput, REP_HOST_UNREACHABLE)
                    logger("HTTP bridge conn#$connectionId failed: cannot resolve upstream proxy host")
                    return
                }
                upstream.connect(upstreamEndpoint, CONNECT_TIMEOUT_MS)
                upstream.soTimeout = CONNECT_TIMEOUT_MS
                logger(
                    "HTTP bridge conn#$connectionId upstream ${upstreamEndpoint.hostString}:${upstreamEndpoint.port} connected"
                )

                val upstreamInput = BufferedInputStream(upstream.getInputStream())
                val upstreamOutput = BufferedOutputStream(upstream.getOutputStream())
                if (isUpstreamPassthroughDestination(destination, upstreamEndpoint)) {
                    logger("HTTP bridge conn#$connectionId passthrough mode for direct proxy destination")
                    sendSocksSuccess(clientOutput)
                    pipeBidirectional(
                        connectionId = connectionId,
                        clientInput = clientInput,
                        clientOutput = clientOutput,
                        upstreamInput = upstreamInput,
                        upstreamOutput = upstreamOutput,
                        upstream = upstream,
                        socksClient = socksClient
                    )
                    return
                }
                val connectRequest = buildConnectRequest(destination.first, destination.second)
                upstreamOutput.write(connectRequest.toByteArray(StandardCharsets.ISO_8859_1))
                upstreamOutput.flush()

                val connectResult = readConnectResponse(upstreamInput)
                if (!connectResult.first) {
                    sendSocksFailure(clientOutput, REP_CONNECTION_REFUSED)
                    logger(
                        "HTTP bridge conn#$connectionId CONNECT ${destination.first}:${destination.second} rejected: ${connectResult.second}"
                    )
                    return
                }
                logger(
                    "HTTP bridge conn#$connectionId CONNECT ${destination.first}:${destination.second} accepted: ${connectResult.second}"
                )
                sendSocksSuccess(clientOutput)
                pipeBidirectional(
                    connectionId = connectionId,
                    clientInput = clientInput,
                    clientOutput = clientOutput,
                    upstreamInput = upstreamInput,
                    upstreamOutput = upstreamOutput,
                    upstream = upstream,
                    socksClient = socksClient
                )
            } catch (e: Exception) {
                try {
                    sendSocksFailure(clientOutput, REP_GENERAL_FAILURE)
                } catch (_: Exception) {
                    // Ignored.
                }
                logger(
                    "HTTP bridge conn#$connectionId failed: ${e.javaClass.simpleName}" +
                        (e.message?.let { " ($it)" } ?: "")
                )
            } finally {
                try {
                    upstream.close()
                } catch (_: Exception) {
                    // Ignored.
                }
            }
        }
    }

    private fun pipeBidirectional(
        connectionId: Long,
        clientInput: BufferedInputStream,
        clientOutput: BufferedOutputStream,
        upstreamInput: BufferedInputStream,
        upstreamOutput: BufferedOutputStream,
        upstream: Socket,
        socksClient: Socket
    ) {
        val uplinkBytes = AtomicLong(0)
        val downlinkBytes = AtomicLong(0)
        val uplink = thread(isDaemon = true) {
            val copied = copyStream(clientInput, upstreamOutput)
            uplinkBytes.set(copied)
            try {
                upstream.shutdownOutput()
            } catch (_: Exception) {
                // Ignored.
            }
        }
        val downlink = thread(isDaemon = true) {
            val copied = copyStream(upstreamInput, clientOutput)
            downlinkBytes.set(copied)
            try {
                socksClient.shutdownOutput()
            } catch (_: Exception) {
                // Ignored.
            }
        }
        uplink.join()
        downlink.join()
        logger(
            "HTTP bridge conn#$connectionId closed up=${formatBytes(uplinkBytes.get())} down=${formatBytes(downlinkBytes.get())}"
        )
    }

    private fun startDirectPassthrough(
        connectionId: Long,
        destination: Pair<String, Int>,
        clientInput: BufferedInputStream,
        clientOutput: BufferedOutputStream,
        socksClient: Socket
    ): Boolean {
        val direct = Socket()
        try {
            if (!bypassVpnForSocket(direct)) {
                logger("HTTP bridge conn#$connectionId direct fallback skipped: cannot bypass VPN")
                return false
            }
            val endpoint = destinationEndpointProvider(destination.first, destination.second)
            if (endpoint == null) {
                logger("HTTP bridge conn#$connectionId direct fallback skipped: cannot resolve destination")
                return false
            }
            direct.connect(endpoint, CONNECT_TIMEOUT_MS)
            direct.soTimeout = CONNECT_TIMEOUT_MS
            logger(
                "HTTP bridge conn#$connectionId direct fallback connected ${endpoint.hostString}:${endpoint.port}"
            )
            val upstreamInput = BufferedInputStream(direct.getInputStream())
            val upstreamOutput = BufferedOutputStream(direct.getOutputStream())
            sendSocksSuccess(clientOutput)
            pipeBidirectional(
                connectionId = connectionId,
                clientInput = clientInput,
                clientOutput = clientOutput,
                upstreamInput = upstreamInput,
                upstreamOutput = upstreamOutput,
                upstream = direct,
                socksClient = socksClient
            )
            return true
        } catch (_: Exception) {
            logger("HTTP bridge conn#$connectionId direct fallback failed")
            return false
        } finally {
            try {
                direct.close()
            } catch (_: Exception) {
                // Ignored.
            }
        }
    }

    private fun isUpstreamPassthroughDestination(
        destination: Pair<String, Int>,
        upstreamEndpoint: InetSocketAddress
    ): Boolean {
        val (destinationHost, destinationPort) = destination
        if (destinationPort != upstreamPort) {
            return false
        }
        val normalizedDestination = destinationHost.trim().lowercase()
        val normalizedUpstreamHost = upstreamHost.trim().lowercase()
        if (normalizedDestination == normalizedUpstreamHost) {
            return true
        }
        val upstreamIp = upstreamEndpoint.address?.hostAddress?.trim()?.lowercase()
        return upstreamIp != null && normalizedDestination == upstreamIp
    }

    private fun isLikelyHttpPort(port: Int): Boolean {
        return port == 80 || port == 443 || port == 8080 || port == 8443
    }

    private fun isUnsupportedMappedDnsTls(destination: Pair<String, Int>): Boolean {
        val (host, port) = destination
        return host == "198.18.0.2" && port == 853
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

    private fun readConnectResponse(input: BufferedInputStream): Pair<Boolean, String> {
        val header = ByteArrayOutputStream()
        var state = 0
        while (header.size() < MAX_HTTP_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) {
                return false to "unexpected EOF from upstream proxy"
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
        val statusLine = response.lineSequence().firstOrNull() ?: return false to "missing HTTP status line"
        val ok = statusLine.startsWith("HTTP/1.1 200") || statusLine.startsWith("HTTP/1.0 200")
        return ok to statusLine
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

    private fun copyStream(input: BufferedInputStream, output: BufferedOutputStream): Long {
        val buffer = ByteArray(8 * 1024)
        var totalBytes = 0L
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
                totalBytes += count.toLong()
            } catch (_: Exception) {
                break
            }
        }
        return totalBytes
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
        const val REP_HOST_UNREACHABLE = 0x04
        const val REP_CONNECTION_REFUSED = 0x05
        const val REP_COMMAND_NOT_SUPPORTED = 0x07
        const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 0x08
        const val CONNECT_TIMEOUT_MS = 5_000
        const val MAX_HTTP_HEADER_BYTES = 16 * 1024
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L -> String.format("%.1fMB", bytes / (1024f * 1024f))
            bytes >= 1024L -> String.format("%.1fKB", bytes / 1024f)
            else -> "${bytes}B"
        }
    }
}
