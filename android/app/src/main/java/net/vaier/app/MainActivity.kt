package net.vaier.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.vaier.app.ui.VaierApp

class MainActivity : ComponentActivity() {

    private lateinit var store: VaierStore
    private lateinit var tunnels: TunnelController
    private lateinit var vaier: VaierClient
    private lateinit var watchdog: StandingWatchdog
    private lateinit var connection: Connection

    private val membership = mutableStateOf<Membership?>(null)
    private val pending = mutableStateOf<PendingJoin?>(null)
    private val stampedAddress = mutableStateOf<String?>(null)
    private val status = mutableStateOf(TunnelStatus(up = false))
    private val notice = mutableStateOf<String?>(null)
    private val busy = mutableStateOf(false)

    private val vpnPermission = registerForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            bringUp()
        } else {
            notice.value = "Android did not allow the connection, so this phone is not connected."
        }
    }

    /** Refusing this costs the one message about being removed, and nothing else. */
    private val notifyPermission = registerForActivityResult(RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as VaierApplication
        store = app.store
        tunnels = app.tunnels
        vaier = app.vaier
        watchdog = app.watchdog
        connection = app.connection
        readStore()

        setContent {
            VaierApp(
                membership = membership.value,
                pending = pending.value,
                stampedAddress = stampedAddress.value,
                status = status.value,
                notice = notice.value,
                busy = busy.value,
                suggestedDeviceName = suggestedName(),
                onJoin = ::join,
                onApproveHere = ::openApproval,
                onCancelJoin = ::cancelJoin,
                onWait = ::waitToBeLetIn,
                onConnectedChange = ::setConnected,
                onLeave = ::leave,
                onRefresh = ::refreshStatus,
            )
        }
    }

    /**
     * The watchdog outlives this screen, so a removal it found while the app was away is already in the
     * store by now — reading it back is what puts the person on the setup screen. What it cannot do is
     * check a phone whose connection is off, because nothing is watching then; that ask happens here.
     */
    override fun onResume() {
        super.onResume()
        watchdog.onRemoved = { runOnUiThread(::speakOfRemoval) }
        readStore()
        speakOfRemoval()
        // The tile may have connected or disconnected while the app was away.
        refreshStatus()
        askAboutThisPhone()
    }

    override fun onPause() {
        super.onPause()
        watchdog.onRemoved = null
    }

    private fun askAboutThisPhone() {
        val current = membership.value ?: return
        lifecycleScope.launch {
            val connected = withContext(Dispatchers.IO) {
                runCatching { tunnels.status().up }.getOrDefault(true)
            }
            // A connection that is on is the watchdog's business, and it is the only one allowed to
            // interrupt it. Nothing to take down here, so the ask goes straight out.
            if (connected) return@launch watchdog.watch()

            val steps = StandingWatch.afterOpening(vaier.standing(
                current.address, store.publicKey.orEmpty(),
                EnrolmentPayload.presharedKeyIn(current.configText),
            ))
            if (steps.forget) {
                store.forget()
                removed(steps.notice)
            }
        }
    }

    /** Vaier let this phone go: back to the start, with words that say so. */
    private fun removed(reason: String?) {
        status.value = TunnelStatus(up = false)
        notice.value = reason
        readStore()
    }

    /** Says what the watchdog found while nobody was here to be told. Silent when it found nothing. */
    private fun speakOfRemoval() {
        removed(watchdog.takeNotice() ?: return)
    }

    private fun readStore() {
        membership.value = store.membership
        pending.value = store.pending
        stampedAddress.value =
            if (membership.value == null && pending.value == null) stamp() else null
    }

    private fun suggestedName() = Build.MODEL.orEmpty().ifBlank { "Phone" }

    /**
     * Vaier stamps its own host into the APK it serves, so a download already knows where it came
     * from and nobody has to type an address. Read once, then remembered.
     */
    private fun stamp(): String? =
        store.stampedAddress
            ?: StampedServer.of(this)
                ?.let(VaierAddress::normalise)
                ?.also(store::rememberStamp)

    // Joining: this phone mints a key, asks, shows a code, and waits on its own stream.

    private fun join(typedAddress: String, deviceName: String) {
        val address = VaierAddress.normalise(typedAddress)
        if (address == null) {
            notice.value = "That does not look like a Vaier address. Try something like vaier.example.com."
            return
        }
        val name = deviceName.trim().ifBlank { suggestedName() }
        notice.value = null
        busy.value = true

        lifecycleScope.launch {
            val publicKey = store.beginJoin(address, name)
            when (val outcome = vaier.askToJoin(address, name, publicKey)) {
                is JoinOutcome.Waiting -> store.awaitApproval(
                    outcome.answer.code,
                    outcome.answer.ticket,
                    PendingJoin.deadlineOf(System.currentTimeMillis(), outcome.answer.expiresInSeconds),
                )
                // Nothing to wait on, so the freshly minted key goes too: the next try mints another.
                is JoinOutcome.Turned -> {
                    store.forget()
                    notice.value = outcome.reason
                }
            }
            busy.value = false
            readStore()
        }
    }

    /**
     * Holds the waiting phone's own stream open until Vaier decides. The caller runs this only while
     * the waiting screen is on show, and cancels it when it is not.
     */
    private suspend fun waitToBeLetIn(waiting: PendingJoin) {
        while (true) {
            if (waiting.hasExpired(System.currentTimeMillis())) {
                giveUp("Nobody approved this phone in time. Try again.")
                return
            }
            when (val verdict = vaier.awaitVerdict(waiting)) {
                is Verdict.Approved -> return accept(verdict.payload, waiting)
                Verdict.Refused -> return giveUp("Whoever runs Vaier turned this phone away.")
                Verdict.Gone -> return giveUp("That code is no longer waiting. Ask to join again.")
                Verdict.Lost -> delay(RECONNECT_MILLIS)
            }
        }
    }

    private fun accept(payload: String, waiting: PendingJoin) {
        val privateKey = store.privateKey
            ?: return giveUp("This phone can no longer prove who it is. Ask to join again.")
        try {
            val enrolment = EnrolmentPayload.parse(payload, waiting.publicKey, privateKey)
            // Prove the tunnel library accepts it before we keep it, so a bad configuration is a
            // message on this screen rather than a failure the first time the switch is touched.
            tunnels.configOf(enrolment.configText)
            store.complete(enrolment)
            notice.value = null
        } catch (e: EnrolmentException) {
            return giveUp(e.message.orEmpty())
        } catch (e: Exception) {
            return giveUp("Vaier sent something this phone could not read. Ask to join again.")
        }
        readStore()
    }

    /** Back to the start, with words that say why. */
    private fun giveUp(reason: String) {
        store.forget()
        notice.value = reason
        readStore()
    }

    private fun cancelJoin() {
        store.forget()
        notice.value = null
        readStore()
    }

    /** For the person who runs Vaier themselves: approve this phone from this phone. */
    private fun openApproval(waiting: PendingJoin) {
        val url = Uri.parse("https://${waiting.address}/explorer.html")
            .buildUpon()
            .appendQueryParameter("approve", waiting.code)
            .build()
        try {
            CustomTabsIntent.Builder().build().launchUrl(this, url)
        } catch (e: ActivityNotFoundException) {
            notice.value = "This phone has no browser to open Vaier in."
        }
    }

    // The connection.

    private fun setConnected(connected: Boolean) {
        if (!connected) {
            takeDown()
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) vpnPermission.launch(consent) else bringUp()
    }

    private fun bringUp() = onTunnel("Vaier could not connect this phone.") {
        connection.connect()
        // Only now is there anything worth a notification later, and only now has the person shown
        // they want this connection. A refusal changes nothing about being connected.
        runOnUiThread(::askToNotify)
    }

    private fun takeDown() = onTunnel("Vaier could not disconnect this phone.") {
        connection.disconnect()
    }

    private fun askToNotify() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (granted == PackageManager.PERMISSION_GRANTED) return
        notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun onTunnel(failure: String, block: (Membership) -> Unit) {
        val current = membership.value ?: return
        lifecycleScope.launch {
            val problem = withContext(Dispatchers.IO) {
                runCatching { block(current) }.exceptionOrNull()
            }
            notice.value = problem?.let { failure }
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        if (membership.value == null) return
        lifecycleScope.launch {
            status.value = withContext(Dispatchers.IO) {
                runCatching { tunnels.status() }.getOrDefault(TunnelStatus(up = false))
            }
        }
    }

    /**
     * Leaving for real: Vaier drops this phone first, and only then does the phone forget. Forgetting
     * on the handset alone would leave a device standing in Vaier that nobody has any more.
     * [Leaving] owns the order and what a failure owes the phone afterwards.
     */
    private fun leave() {
        val current = membership.value ?: return
        val publicKey = store.publicKey.orEmpty()
        val wasConnected = status.value.up
        notice.value = null
        busy.value = true

        lifecycleScope.launch {
            watchdog.rest()
            if (wasConnected) quietly { tunnels.setDown(tunnels.configOf(current.configText)) }

            val outcome = vaier.leave(
                current.address, publicKey, EnrolmentPayload.presharedKeyIn(current.configText),
            )
            val steps = Leaving.after(outcome, wasConnected)

            if (steps.reconnect) {
                quietly { tunnels.setUp(tunnels.configOf(current.configText)) }
                watchdog.watch()
            }
            if (steps.forget) {
                store.forget()
                status.value = TunnelStatus(up = false)
            }
            notice.value = steps.notice
            busy.value = false
            readStore()
        }
    }

    /** The tunnel calls around leaving, where a failure changes nothing the person can act on. */
    private suspend fun quietly(block: () -> Unit) {
        withContext(Dispatchers.IO) { runCatching(block) }
    }

    private companion object {
        /** How long to leave a dropped stream alone before opening it again. */
        const val RECONNECT_MILLIS = 3_000L
    }
}
