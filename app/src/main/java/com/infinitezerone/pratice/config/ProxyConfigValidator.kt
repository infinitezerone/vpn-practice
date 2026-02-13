package com.infinitezerone.pratice.config

object ProxyConfigValidator {
    fun validate(hostInput: String, portInput: String): String? {
        if (hostInput.isBlank()) {
            return "Proxy host is required."
        }

        val parsedPort = portInput.toIntOrNull()
        if (parsedPort == null) {
            return "Proxy port must be a number."
        }

        if (parsedPort !in 1..65535) {
            return "Proxy port must be between 1 and 65535."
        }

        return null
    }
}
