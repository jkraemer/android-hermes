package net.jkraemer.hermes.core.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import net.jkraemer.hermes.core.rpc.RpcFrame
import net.jkraemer.hermes.core.rpc.parseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

class ChatReducerTest {
    private val now = Instant.parse("2026-11-05T12:00:00Z")
    private val empty = ChatState(liveSessionId = "live")

    private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

    private fun event(type: String, payload: String = "{}", seq: Long? = null, session: String? = "live") =
        ChatInput.Event(RpcFrame.Event(type, session, seq, obj(payload)))

    private fun request(id: String, method: String, params: String) =
        ChatInput.Request(RpcFrame.ServerRequest(id, method, obj(params)), now)

    private fun run(state: ChatState, vararg inputs: ChatInput) = inputs.fold(state) { s, i -> reduce(s, i) }

    private fun approval(params: String = """{"request_id":"r1","command":"rm x"}""") =
        run(empty, request("srq-1", "approval", params)).items.single() as ChatItem.Approval

    @Test fun aTranscriptUserRowKeepsItsDisplayKind() {
        val messages = listOf(
            obj("""{"role":"user","text":"/attention-check","display_kind":"skill_invocation"}"""),
            obj("""{"role":"user","text":"[Note: model switched]","display_kind":"model_switch"}"""),
            obj("""{"role":"user","text":"thanks"}"""),
        )
        val state = reduce(empty, ChatInput.Transcript(messages, running = false))
        assertEquals(
            listOf(
                ChatItem.User("/attention-check", displayKind = "skill_invocation"),
                ChatItem.User("[Note: model switched]", displayKind = "model_switch"),
                ChatItem.User("thanks"),
            ),
            state.items,
        )
    }

    @Test fun transcriptMapsRoles() {
        val messages = listOf(
            obj("""{"role":"user","text":"hi","unknown":1}"""),
            obj("""{"role":"assistant","content":"hello"}"""),
            obj("""{"role":"tool","name":"ls","tool_call_id":"t1","text":"out"}"""),
            obj("""{"role":"tool","name":"cat","tool_call_id":"t2"}"""),
        )
        val state = reduce(empty, ChatInput.Transcript(messages, running = true))
        assertEquals(
            listOf(
                ChatItem.User("hi"),
                ChatItem.Assistant("hello", streaming = false),
                ChatItem.Tools(listOf(ToolCall("t1", "ls", null, true, false), ToolCall("t2", "cat", null, true, false))),
            ),
            state.items,
        )
        assertEquals(true, state.running)
    }

    @Test fun deltasAccumulateIntoStreamingAssistant() {
        val state = run(
            empty,
            ChatInput.UserSent("q"),
            event("message.start", seq = 1),
            event("message.delta", """{"text":"Hel"}""", seq = 2),
            event("message.delta", """{"text":"lo"}""", seq = 3),
        )
        assertEquals(listOf(ChatItem.User("q"), ChatItem.Assistant("Hello", streaming = true)), state.items)
        assertEquals(true, state.running)
    }

    @Test fun completeFinalisesText() {
        val state = run(
            empty,
            event("message.start"),
            event("message.delta", """{"text":"He"}"""),
            event("message.complete", """{"text":"Hello","status":"complete"}"""),
        )
        assertEquals(listOf(ChatItem.Assistant("Hello", streaming = false)), state.items)
        assertEquals(false, state.running)
    }

    @Test fun completeWithErrorAddsErrorItem() {
        val state = run(
            empty.copy(running = true),
            event("message.start"),
            event("message.complete", """{"text":"","error":"boom"}"""),
        )
        assertEquals(listOf(ChatItem.Assistant("", streaming = false), ChatItem.Error("boom")), state.items)
        assertEquals(false, state.running)
    }

    @Test fun errorEventAddsErrorItemAndEndsTheTurn() {
        val state = run(
            empty.copy(running = true),
            event("message.start"),
            event("error", """{"message":"provider down"}"""),
        )
        assertEquals(listOf<ChatItem>(ChatItem.Error("provider down")), state.items)
        assertEquals(false, state.running)
    }

    @Test fun consecutiveToolsShareOneItem() {
        val state = run(
            empty,
            event("tool.start", """{"tool_id":"a","name":"ls"}"""),
            event("tool.start", """{"tool_id":"b","name":"cat"}"""),
            event("message.start"),
            event("tool.start", """{"tool_id":"c","name":"rm"}"""),
        )
        assertEquals(3, state.items.size)
        assertEquals(listOf("a", "b"), (state.items[0] as ChatItem.Tools).calls.map { it.toolId })
        assertEquals(listOf("c"), (state.items[2] as ChatItem.Tools).calls.map { it.toolId })
    }

    @Test fun toolCompleteMarksDone() {
        val state = run(
            empty,
            event("tool.start", """{"tool_id":"a","name":"ls"}"""),
            event("tool.start", """{"tool_id":"b","name":"cat"}"""),
            event("tool.complete", """{"tool_id":"a","name":"ls","summary":"3 files"}"""),
        )
        assertEquals(
            ChatItem.Tools(listOf(ToolCall("a", "ls", "3 files", true, false), ToolCall("b", "cat", null, false, false))),
            state.items.single(),
        )
    }

    @Test fun toolCompleteWithErrorMarksFailed() {
        val state = run(
            empty,
            event("tool.start", """{"tool_id":"a","name":"ls"}"""),
            event("tool.start", """{"tool_id":"b","name":"cat"}"""),
            event("tool.start", """{"tool_id":"c","name":"rm"}"""),
            event("tool.complete", """{"tool_id":"a","error":"denied"}"""),
            event("tool.complete", """{"tool_id":"b","status":"error"}"""),
            event("tool.complete", """{"tool_id":"c","error":null,"status":"ok"}"""),
        )
        assertEquals(listOf(true, true, false), (state.items.single() as ChatItem.Tools).calls.map { it.failed })
    }

    @Test fun staleSeqIsIgnored() {
        val state = run(empty, event("message.start", seq = 5), event("message.delta", """{"text":"x"}""", seq = 5))
        assertEquals(listOf(ChatItem.Assistant("", streaming = true)), state.items)
        assertEquals(5, state.lastSeq)
        val later = run(state, event("message.delta", """{"text":"y"}""", seq = 6), event("message.delta", """{"text":"z"}""", seq = 4))
        assertEquals("y", (later.items.single() as ChatItem.Assistant).markdown)
        assertEquals(6, later.lastSeq)
    }

    @Test fun nullSeqIsAppliedWithoutAdvancing() {
        val state = run(empty.copy(lastSeq = 3), event("message.start", seq = null))
        assertEquals(1, state.items.size)
        assertEquals(3, state.lastSeq)
    }

    @Test fun otherSessionEventsAreIgnored() {
        val state = run(empty, event("message.start", seq = 9, session = "other"))
        assertEquals(empty, state)
    }

    @Test fun approvalUsesOfferedChoices() {
        val item = approval("""{"request_id":"r1","command":"c","choices":["once","deny","bogus"]}""")
        assertEquals(listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY), item.choices)
        assertEquals("srq-1", item.requestId)
        assertEquals("r1", item.approvalId)
        assertEquals(CardStatus.Open, item.status)
    }

    @Test fun smartDeniedOffersOnceAndDeny() {
        assertEquals(
            listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY),
            approval("""{"request_id":"r1","command":"c","smart_denied":true}""").choices,
        )
        assertEquals(
            listOf(ApprovalChoice.ONCE, ApprovalChoice.DENY),
            approval("""{"request_id":"r1","command":"c","allow_session":false}""").choices,
        )
    }

    @Test fun allowPermanentFalseDropsAlways() {
        assertEquals(
            listOf(ApprovalChoice.ONCE, ApprovalChoice.SESSION, ApprovalChoice.DENY),
            approval("""{"request_id":"r1","command":"c","allow_permanent":false}""").choices,
        )
        assertEquals(ApprovalChoice.entries.toList(), approval().choices)
    }

    @Test fun approvalDeadlineDefaultsTo300s() {
        assertEquals(now.plusSeconds(300), approval().deadline)
        assertEquals(now.plusSeconds(60), approval("""{"request_id":"r1","command":"c","timeout_s":60}""").deadline)
    }

    @Test fun approvalDeadlineIgnoresOutOfRangeTimeouts() {
        for (bad in listOf("1e30", "0", "-5", "86401")) {
            assertEquals(bad, now.plusSeconds(300), approval("""{"request_id":"r1","command":"c","timeout_s":$bad}""").deadline)
        }
        assertEquals(now.plusSeconds(86_400), approval("""{"request_id":"r1","command":"c","timeout_s":86400}""").deadline)
    }

    @Test fun requestCancelMarksApprovalExpired() {
        val state = run(
            empty,
            request("srq-1", "approval", """{"request_id":"r1","command":"c"}"""),
            event("request.cancel", """{"id":"srq-1","method":"approval","reason":"timeout"}"""),
        )
        assertEquals(CardStatus.Expired, (state.items.single() as ChatItem.Approval).status)
    }

    @Test fun requestCancelWithOtherReasonMarksCancelled() {
        val state = run(
            empty,
            request("srq-1", "clarify", """{"session_id":"live","questions":[]}"""),
            event("request.cancel", """{"id":"srq-1","method":"clarify","reason":"interrupted"}"""),
        )
        assertEquals(CardStatus.Cancelled("interrupted"), (state.items.single() as ChatItem.Clarify).status)
    }

    @Test fun approvalCancelledMarksCancelled() {
        val state = run(
            empty,
            request("srq-1", "approval", """{"request_id":"r1","command":"c"}"""),
            request("srq-2", "approval", """{"request_id":"r2","command":"d"}"""),
            event("approval.cancelled", """{"request_ids":["r1"],"reason":"resolved elsewhere"}"""),
        )
        assertEquals(
            listOf(CardStatus.Cancelled("resolved elsewhere"), CardStatus.Open),
            state.items.map { (it as ChatItem.Approval).status },
        )
    }

    @Test fun answeredMarksCard() {
        val state = run(
            empty,
            request("srq-1", "approval", """{"request_id":"r1","command":"c"}"""),
            ChatInput.Answered("srq-1", "Allowed once"),
            event("request.cancel", """{"id":"srq-1","reason":"timeout"}"""),
        )
        assertEquals(CardStatus.Answered("Allowed once"), (state.items.single() as ChatItem.Approval).status)
    }

    @Test fun openRequestsSettleOnlyTheCardsKnownWhenTheCallWasSent() {
        val state = run(
            empty,
            request("srq-1", "approval", """{"request_id":"r1","command":"c"}"""),
            request("srq-2", "clarify", """{"questions":[{"qid":"q1","question":"Which?"}]}"""),
            ChatInput.Answered("srq-2", "Answered"),
            request("srq-3", "approval", """{"request_id":"r3","command":"c"}"""),
            request("srq-4", "clarify", """{"questions":[{"qid":"q1","question":"Which?"}]}"""),
            request("srq-5", "approval", """{"request_id":"r5","command":"c"}"""),
            ChatInput.Answered("srq-5", "Allowed once"),
            ChatInput.OpenRequests(ids = setOf("srq-2", "srq-5"), known = setOf("srq-1", "srq-2")),
        )
        assertEquals(
            listOf(
                CardStatus.Cancelled("answered elsewhere"),
                CardStatus.Open,
                CardStatus.Open,
                CardStatus.Open,
                CardStatus.Answered("Allowed once"),
            ),
            state.items.map { (it as? ChatItem.Approval)?.status ?: (it as ChatItem.Clarify).status },
        )
    }

    @Test fun redeliveredApprovalIsNotDuplicated() {
        val req = request("srq-1", "approval", """{"request_id":"r1","command":"c"}""")
        val once = run(empty, req)
        val twice = reduce(once, req)
        assertEquals(1, twice.items.size)
    }

    @Test fun clarifyRequestAddsCard() {
        val state = run(
            empty,
            request(
                "srq-9", "clarify",
                """{"session_id":"live","questions":[{"qid":"q1","question":"Which?","choices":["a","b"],"multi_select":true},{"qid":"q2","question":"Why?"}]}""",
            ),
        )
        assertEquals(
            ChatItem.Clarify(
                "srq-9",
                listOf(ClarifyQuestion("q1", "Which?", listOf("a", "b"), true), ClarifyQuestion("q2", "Why?", emptyList(), false)),
                CardStatus.Open,
            ),
            state.items.single(),
        )
    }

    @Test fun unsupportedRequestLeavesStateUnchanged() {
        assertSame(empty, reduce(empty, request("srq-1", "sudo", "{}")))
    }

    @Test fun unknownEventLeavesStateUnchanged() {
        assertEquals(empty, reduce(empty, event("session.info", """{"x":1}""")))
        assertEquals(empty.copy(lastSeq = 4), reduce(empty, event("session.info", seq = 4)))
    }

    @Test fun globalApprovalCancelledWithBlankSessionIdReachesTheCard() {
        val global = parseFrame(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"approval.cancelled","session_id":"","payload":{"request_ids":["r1"],"reason":"timeout"}}}""",
        ) as RpcFrame.Event
        val state = run(
            empty,
            request("srq-1", "approval", """{"request_id":"r1","command":"c"}"""),
            ChatInput.Event(global),
        )
        assertEquals(CardStatus.Expired, (state.items.single() as ChatItem.Approval).status)
    }

    @Test fun completeDropsEmptyStreamingItemLeftByToolOnlyTurn() {
        val state = run(
            empty,
            ChatInput.UserSent("q"),
            event("message.start"),
            event("tool.start", """{"tool_id":"t1","name":"ls"}"""),
            event("tool.complete", """{"tool_id":"t1"}"""),
            event("message.complete", """{"text":"done"}"""),
        )
        assertEquals(
            listOf(
                ChatItem.User("q"),
                ChatItem.Tools(listOf(ToolCall("t1", "ls", null, true, false))),
                ChatItem.Assistant("done", streaming = false),
            ),
            state.items,
        )
    }

    @Test fun completeWithoutTextDropsEmptyStreamingItem() {
        val state = run(empty, event("message.start"), event("message.complete"))
        assertEquals(emptyList<ChatItem>(), state.items)
        assertEquals(false, state.running)
    }

    @Test fun errorDropsEmptyStreamingItem() {
        val state = run(empty, event("message.start"), event("error", """{"message":"boom"}"""))
        assertEquals(listOf<ChatItem>(ChatItem.Error("boom")), state.items)
    }

    @Test fun messageStartReusesEmptyStreamingItem() {
        val state = run(
            empty,
            event("message.start"),
            event("tool.start", """{"tool_id":"t1","name":"ls"}"""),
            event("message.start"),
            event("message.delta", """{"text":"hi"}"""),
        )
        assertEquals(1, state.items.count { it is ChatItem.Assistant })
        assertEquals(ChatItem.Assistant("hi", streaming = true), state.items.last())
    }
}
