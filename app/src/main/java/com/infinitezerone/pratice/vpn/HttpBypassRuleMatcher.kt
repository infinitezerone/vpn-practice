package com.infinitezerone.pratice.vpn

object HttpBypassRuleMatcher {
    fun findMatchingRule(destinationHost: String, bypassRules: List<String>): String? {
        if (bypassRules.isEmpty()) {
            return null
        }
        val host = destinationHost.trim().trimEnd('.').lowercase()
        if (host.isBlank()) {
            return null
        }
        return bypassRules.firstOrNull { rawRule ->
            val rule = rawRule.trim().trimEnd('.').lowercase()
            if (rule.isBlank()) {
                return@firstOrNull false
            }
            when {
                rule == "<local>" -> !host.contains('.')
                rule.startsWith("*.") -> {
                    val suffix = rule.removePrefix("*.")
                    host == suffix || host.endsWith(".$suffix")
                }
                rule.startsWith(".") -> {
                    val suffix = rule.removePrefix(".")
                    host == suffix || host.endsWith(".$suffix")
                }
                rule.contains('*') -> wildcardMatch(host, rule)
                else -> host == rule
            }
        }
    }

    private fun wildcardMatch(host: String, wildcardRule: String): Boolean {
        val regex = wildcardRule
            .split("*")
            .joinToString(separator = ".*") { part -> Regex.escape(part) }
            .let { "^$it$" }
            .toRegex(RegexOption.IGNORE_CASE)
        return regex.matches(host)
    }
}
