package net.jkraemer.hermes.core.options

import net.jkraemer.hermes.core.chat.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionOptionsTest {
    @Test
    fun parsesLiveInfo() {
        val info = obj("""{"model":"a","provider":"p","reasoning_effort":"high","fast":true,"yolo":true}""")

        assertEquals(SessionOptions("a", "p", "high", fast = true, yolo = true), sessionOptionsFrom(info))
    }

    @Test
    fun lazyInfoWithoutModelIsNull() {
        assertNull(sessionOptionsFrom(obj("""{"lazy":true,"provider":"p"}""")))
        assertNull(sessionOptionsFrom(obj("""{"model":" ","provider":"p"}""")))
    }

    @Test
    fun unknownFieldsIgnoredAndMissingOnesDefault() {
        val info = obj("""{"model":"a","usage":{"total":3},"service_tier":"priority"}""")

        assertEquals(SessionOptions("a", "", "", fast = false, yolo = false), sessionOptionsFrom(info))
    }
}
