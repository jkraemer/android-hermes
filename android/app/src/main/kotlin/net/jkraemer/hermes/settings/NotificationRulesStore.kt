package net.jkraemer.hermes.settings

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.jkraemer.hermes.core.push.NotificationRule

/** When each cron job's reports are announced; kept on the phone, never sent to Hermes. A job without a rule is announced always. */
class NotificationRulesStore(private val prefs: SharedPreferences) {
    private val _rules = MutableStateFlow(read())

    /** The rules that were set, by job id. */
    val rules: StateFlow<Map<String, NotificationRule>> = _rules.asStateFlow()

    fun ruleFor(jobId: String): NotificationRule = _rules.value[jobId] ?: NotificationRule.Always

    fun set(jobId: String, rule: NotificationRule) {
        _rules.value = _rules.value + (jobId to rule)
        prefs.edit().putString(KEY_PREFIX + jobId, encode(rule)).apply()
    }

    /** A value that is not a rule counts as none, so the job is announced. */
    private fun read(): Map<String, NotificationRule> = buildMap {
        for ((key, value) in prefs.all) {
            if (!key.startsWith(KEY_PREFIX)) continue
            val rule = (value as? String)?.let(::decode) ?: continue
            put(key.removePrefix(KEY_PREFIX), rule)
        }
    }

    private fun encode(rule: NotificationRule): String = when (rule) {
        NotificationRule.Off -> OFF
        NotificationRule.Always -> ALWAYS
        is NotificationRule.Match -> MATCH_PREFIX + rule.pattern
    }

    private fun decode(stored: String): NotificationRule? = when {
        stored == OFF -> NotificationRule.Off
        stored == ALWAYS -> NotificationRule.Always
        stored.startsWith(MATCH_PREFIX) -> NotificationRule.Match(stored.removePrefix(MATCH_PREFIX))
        else -> null
    }

    private companion object {
        const val KEY_PREFIX = "notification_rule:"
        const val OFF = "off"
        const val ALWAYS = "always"
        const val MATCH_PREFIX = "match:"
    }
}
