package net.vaier.app

import org.json.JSONException
import org.json.JSONObject

/** What Vaier answers when this phone asks to join: the code to show, and the ticket to wait on. */
data class JoinAnswer(val code: String, val ticket: String, val expiresInSeconds: Long)

/**
 * The bodies this app sends to Vaier and the one answer it reads back, built as text so they can be
 * checked without a socket. The private key is never part of any of them.
 */
object JoinProtocol {

    fun joinRequest(name: String, publicKey: String): String =
        JSONObject().put("name", name).put("publicKey", publicKey).toString()

    fun leaveRequest(publicKey: String, presharedKey: String): String = proof(publicKey, presharedKey)

    /** Asking whether this phone is still in Vaier proves who is asking exactly as leaving does. */
    fun standingRequest(publicKey: String, presharedKey: String): String = proof(publicKey, presharedKey)

    /** Hello and goodbye, said through the tunnel, prove who is saying them the same way. */
    fun helloRequest(publicKey: String, presharedKey: String): String = proof(publicKey, presharedKey)

    fun goodbyeRequest(publicKey: String, presharedKey: String): String = proof(publicKey, presharedKey)

    private fun proof(publicKey: String, presharedKey: String): String =
        JSONObject().put("publicKey", publicKey).put("presharedKey", presharedKey).toString()

    fun readJoinAnswer(body: String): JoinAnswer =
        try {
            val answer = JSONObject(body)
            JoinAnswer(
                code = answer.getString("code"),
                ticket = answer.getString("ticket"),
                expiresInSeconds = answer.getLong("expiresInSeconds"),
            )
        } catch (e: JSONException) {
            throw EnrolmentException("Vaier answered with something this phone could not read.")
        }
}
