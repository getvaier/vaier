package net.vaier.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinProtocolTest {

    private val publicKey = "eA1uZ0nOhTn2Yz0kQvVYcWkZ0m7l7pQ5rXcSbW8xTiE="
    private val presharedKey = "FpCyhws9cxwWoV4xELtfJvjJN+zQVRPISllRWgeopVE="

    @Test
    fun `the ask to join carries the name and the public half of the key`() {
        val body = JSONObject(JoinProtocol.joinRequest("Pixel 8", publicKey))

        assertEquals("Pixel 8", body.getString("name"))
        assertEquals(publicKey, body.getString("publicKey"))
        assertEquals(2, body.length())
    }

    @Test
    fun `the ask to join never carries the private half`() {
        assertTrue(!JoinProtocol.joinRequest("Pixel 8", publicKey).contains("privateKey", ignoreCase = true))
    }

    @Test
    fun `the answer hands back the code, the ticket and how long is left`() {
        val answer = JoinProtocol.readJoinAnswer(
            """{"code":"0421","ticket":"3f9c","expiresInSeconds":600}"""
        )

        assertEquals("0421", answer.code)
        assertEquals("3f9c", answer.ticket)
        assertEquals(600L, answer.expiresInSeconds)
    }

    @Test
    fun `an answer that is not from Vaier is refused with words a person can read`() {
        val refusal = refusalFrom { JoinProtocol.readJoinAnswer("not json at all") }

        assertTrue(refusal, refusal.isNotEmpty())
    }

    @Test
    fun `an answer missing the ticket is refused`() {
        val refusal = refusalFrom { JoinProtocol.readJoinAnswer("""{"code":"0421"}""") }

        assertTrue(refusal, refusal.isNotEmpty())
    }

    @Test
    fun `leaving presents this phone's own key and the shared secret from its configuration`() {
        val body = JSONObject(JoinProtocol.leaveRequest(publicKey, presharedKey))

        assertEquals(publicKey, body.getString("publicKey"))
        assertEquals(presharedKey, body.getString("presharedKey"))
        assertEquals(2, body.length())
    }

    @Test
    fun `asking about standing, saying hello and saying goodbye present exactly the same proof as leaving`() {
        val leaving = JoinProtocol.leaveRequest(publicKey, presharedKey)
        assertEquals(leaving, JoinProtocol.standingRequest(publicKey, presharedKey))
        assertEquals(leaving, JoinProtocol.helloRequest(publicKey, presharedKey))
        assertEquals(leaving, JoinProtocol.goodbyeRequest(publicKey, presharedKey))
    }

    @Test
    fun `asking about standing carries nothing but the two keys`() {
        val body = JSONObject(JoinProtocol.standingRequest(publicKey, presharedKey))

        assertEquals(publicKey, body.getString("publicKey"))
        assertEquals(presharedKey, body.getString("presharedKey"))
        assertEquals(2, body.length())
    }
}

private fun refusalFrom(block: () -> Unit): String =
    try {
        block()
        throw AssertionError("expected this to be refused")
    } catch (e: EnrolmentException) {
        e.message.orEmpty()
    }
