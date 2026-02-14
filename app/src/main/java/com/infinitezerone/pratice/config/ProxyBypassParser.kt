package com.infinitezerone.pratice.config

object ProxyBypassParser {
    fun parse(rawInput: String): List<String> =
        rawInput.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun format(values: List<String>): String = values.joinToString(",")
}
