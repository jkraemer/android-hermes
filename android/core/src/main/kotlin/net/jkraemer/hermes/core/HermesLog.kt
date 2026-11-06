package net.jkraemer.hermes.core

/**
 * Where the core reports what the connection and the chat are doing, so a failure on a device can
 * be traced. Lines never contain tickets, cookies or the text of messages and commands.
 */
fun interface HermesLog {
    fun log(message: String)

    companion object {
        val None = HermesLog { }
    }
}
