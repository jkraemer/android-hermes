package net.jkraemer.hermes.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushEventTest {
    private fun parse(json: String) = pushEventFrom(json.toByteArray())

    @Test
    fun malformedPayloadsParseToNull() {
        assertNull(pushEventFrom(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00)))
        assertNull(parse("not json"))
        assertNull(parse("{}"))
        assertNull(parse("""[1]"""))
        assertNull(parse("""{"v":2,"type":"test"}"""))
        assertNull(parse("""{"type":"test"}"""))
        assertNull(parse("""{"v":1,"type":"mystery"}"""))
        assertNull(parse("""{"v":1,"type":"approval","session_id":"s","text":"rm"}"""))
        assertNull(parse("""{"v":1,"type":"cron","session_id":"s","job_name":"n","text":"t"}"""))
        assertNull(parse("""{"v":1,"type":"reply","text":"t"}"""))
    }

    @Test
    fun cronParses() {
        assertEquals(
            PushEvent.Cron("s1", "j1", "Backup", "done", failed = false),
            parse("""{"v":1,"type":"cron","session_id":"s1","job_id":"j1","job_name":"Backup","text":"done","extra":1}"""),
        )
        assertEquals(
            PushEvent.Cron("s1", "j1", "Backup", "[CRON_FAILURE]", failed = true),
            parse("""{"v":1,"type":"cron","session_id":"s1","job_id":"j1","job_name":"Backup","text":"[CRON_FAILURE]","failed":true}"""),
        )
    }

    @Test
    fun cronWithoutAJobNameIsNamedByItsJobId() {
        assertEquals(
            PushEvent.Cron("s1", "j1", "j1", "done", failed = false),
            parse("""{"v":1,"type":"cron","session_id":"s1","job_id":"j1","text":"done"}"""),
        )
    }

    @Test
    fun approvalClarifyResolvedReplyAndTestParse() {
        assertEquals(PushEvent.Approval("s", "r", "rm -rf x"), parse("""{"v":1,"type":"approval","session_id":"s","request_id":"r","text":"rm -rf x"}"""))
        assertEquals(PushEvent.Clarify("s", "r", "Which?"), parse("""{"v":1,"type":"clarify","session_id":"s","request_id":"r","text":"Which?"}"""))
        assertEquals(PushEvent.Resolved("s", "r"), parse("""{"v":1,"type":"resolved","session_id":"s","request_id":"r"}"""))
        assertEquals(PushEvent.Reply("s", "hi ✓"), parse("""{"v":1,"type":"reply","session_id":"s","text":"hi ✓"}"""))
        assertEquals(PushEvent.Test, parse("""{"v":1,"type":"test"}"""))
    }
}
