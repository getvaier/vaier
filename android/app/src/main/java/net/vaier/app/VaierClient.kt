package net.vaier.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** How the ask to join went. */
sealed interface JoinOutcome {
    data class Waiting(val answer: JoinAnswer) : JoinOutcome
    data class Turned(val reason: String) : JoinOutcome
}

/** What came back on this phone's own stream — or why nothing did. */
sealed interface Verdict {
    /** Approved, carrying the configuration Vaier made for this phone. */
    data class Approved(val payload: String) : Verdict

    data object Refused : Verdict

    /** Vaier has no such request any more: it ran out, or it was turned away earlier. */
    data object Gone : Verdict

    /** The stream dropped before anything was decided. Nothing has changed; try again. */
    data object Lost : Verdict
}

/**
 * The only place this app talks to Vaier. Every route it uses is anonymous, because a phone that has
 * not been let in yet has no session and is never asked to sign in for one.
 */
class VaierClient {

    suspend fun askToJoin(address: String, deviceName: String, publicKey: String): JoinOutcome =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = post("https://$address/vpn/enrolments",
                    JoinProtocol.joinRequest(deviceName, publicKey))
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK ->
                        JoinOutcome.Waiting(JoinProtocol.readJoinAnswer(bodyOf(connection)))
                    HttpURLConnection.HTTP_BAD_REQUEST ->
                        JoinOutcome.Turned("Vaier would not take that name. Try a different one.")
                    HttpURLConnection.HTTP_CONFLICT ->
                        JoinOutcome.Turned("Too many phones are waiting to join right now. Try again in a few minutes.")
                    TOO_MANY_REQUESTS ->
                        JoinOutcome.Turned("That was too many tries at once. Wait a minute, then try again.")
                    else ->
                        JoinOutcome.Turned("Vaier could not take this request. Try again in a moment.")
                }
            } catch (e: IOException) {
                JoinOutcome.Turned(UNREACHABLE)
            } catch (e: EnrolmentException) {
                JoinOutcome.Turned(e.message.orEmpty())
            } finally {
                connection?.disconnect()
            }
        }

    /**
     * Holds this phone's own stream open until Vaier decides, the connection drops, or the caller is
     * cancelled. A blocking read never notices cancellation on its own, so closing the socket from
     * the completion handler is what actually ends it when the screen goes away.
     */
    suspend fun awaitVerdict(pending: PendingJoin): Verdict = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        val closeOnCancel = coroutineContext.job.invokeOnCompletion { connection?.disconnect() }
        try {
            val ticket = Uri.encode(pending.ticket)
            connection = (URL("https://${pending.address}/vpn/enrolments/$ticket/events")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MILLIS
                // An idle stream is normal here — the operator may take minutes. The timeout is
                // long enough not to churn, and short enough that a socket which died quietly is
                // noticed and the phone gets to look at its own deadline again.
                readTimeout = IDLE_MILLIS
                setRequestProperty("Accept", "text/event-stream")
            }
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    connection.inputStream.bufferedReader().use(::verdictIn)
                HttpURLConnection.HTTP_GONE -> Verdict.Gone
                else -> Verdict.Lost
            }
        } catch (e: IOException) {
            Verdict.Lost
        } finally {
            closeOnCancel.dispose()
            connection?.disconnect()
        }
    }

    /** 404 is a removal too: whatever this phone was, Vaier does not have it any more. */
    suspend fun leave(address: String, publicKey: String, presharedKey: String): LeaveOutcome =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = post("https://$address/vpn/peers/leave",
                    JoinProtocol.leaveRequest(publicKey, presharedKey))
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_NO_CONTENT, HttpURLConnection.HTTP_NOT_FOUND -> LeaveOutcome.REMOVED
                    else -> LeaveOutcome.UNREACHABLE
                }
            } catch (e: IOException) {
                LeaveOutcome.UNREACHABLE
            } finally {
                connection?.disconnect()
            }
        }

    /**
     * Asks whether this phone is still one of Vaier's devices. Changes nothing there, and must be sent
     * over ordinary internet: a removed phone's tunnel is a black hole, so an answer down it never comes.
     */
    suspend fun standing(address: String, publicKey: String, presharedKey: String): Standing =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                connection = post("https://$address/vpn/peers/standing",
                    JoinProtocol.standingRequest(publicKey, presharedKey))
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_NO_CONTENT -> Standing.MEMBER
                    HttpURLConnection.HTTP_NOT_FOUND -> Standing.REMOVED
                    else -> Standing.UNREACHABLE
                }
            } catch (e: IOException) {
                Standing.UNREACHABLE
            } finally {
                connection?.disconnect()
            }
        }

    /** Says, through the tunnel, that this phone has just connected. A few tries: the first can race the tunnel. */
    fun hello(publicKey: String, presharedKey: String) {
        repeat(HELLO_ATTEMPTS) { attempt ->
            if (tell("hello", JoinProtocol.helloRequest(publicKey, presharedKey), HELLO_TIMEOUT_MILLIS)) return
            if (attempt < HELLO_ATTEMPTS - 1) Thread.sleep(RETRY_MILLIS)
        }
    }

    /** Says, through the tunnel, that this phone is about to disconnect. One brief try. */
    fun goodbye(publicKey: String, presharedKey: String) {
        tell("goodbye", JoinProtocol.goodbyeRequest(publicKey, presharedKey), GOODBYE_TIMEOUT_MILLIS)
    }

    // True once Vaier answered at all. Never pooled: a connection opened through an earlier tunnel is dead.
    private fun tell(route: String, body: String, timeoutMillis: Int): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = post("${TunnelDoor.ADDRESS}/vpn/peers/$route", body, timeoutMillis, keepAlive = false)
            connection.responseCode
            true
        } catch (e: IOException) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun verdictIn(reader: BufferedReader): Verdict {
        val events = EventStream()
        while (true) {
            val line = reader.readLine() ?: return Verdict.Lost
            val event = events.accept(line) ?: continue
            when (event.name) {
                "approved" -> return Verdict.Approved(event.data)
                "refused" -> return Verdict.Refused
            }
        }
    }

    private fun post(url: String, body: String, timeoutMillis: Int = TIMEOUT_MILLIS, keepAlive: Boolean = true): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            if (!keepAlive) setRequestProperty("Connection", "close")
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

    private fun bodyOf(connection: HttpURLConnection): String =
        connection.inputStream.bufferedReader().use(BufferedReader::readText)

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
        const val HELLO_TIMEOUT_MILLIS = 3_000
        const val HELLO_ATTEMPTS = 3
        const val RETRY_MILLIS = 1_000L
        const val GOODBYE_TIMEOUT_MILLIS = 2_000
        const val IDLE_MILLIS = 65_000
        const val TOO_MANY_REQUESTS = 429
        const val UNREACHABLE = "Vaier could not be reached. Check the connection and try again."
    }
}
