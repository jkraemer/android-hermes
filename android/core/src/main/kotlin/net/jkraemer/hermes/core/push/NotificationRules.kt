package net.jkraemer.hermes.core.push

/** When a cron job's report is worth a notification. */
sealed interface NotificationRule {
    data object Off : NotificationRule
    data object Always : NotificationRule

    /** Only when the report contains a match of the regular expression [pattern]. */
    data class Match(val pattern: String) : NotificationRule
}

/**
 * Whether a report with this [output] passes the rule. A [NotificationRule.Match] whose pattern does not compile
 * allows: a broken rule must not hide reports.
 */
fun NotificationRule.allows(output: String): Boolean = when (this) {
    NotificationRule.Off -> false
    NotificationRule.Always -> true
    is NotificationRule.Match -> runCatching { Regex(pattern) }.map { it.containsMatchIn(output) }.getOrDefault(true)
}

fun validPattern(pattern: String): Boolean = runCatching { Regex(pattern) }.isSuccess
