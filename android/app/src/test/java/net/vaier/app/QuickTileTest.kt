package net.vaier.app

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickTileTest {

    private data class Row(val member: Boolean, val up: Boolean, val needsConsent: Boolean, val tap: TileTap)

    @Test
    fun `a tap disconnects, connects, or opens the app when only the app can help`() {
        listOf(
            Row(member = true, up = true, needsConsent = false, tap = TileTap.DISCONNECT),
            Row(member = true, up = false, needsConsent = false, tap = TileTap.CONNECT),
            // Android asks for VPN consent from an activity, never from the shade.
            Row(member = true, up = false, needsConsent = true, tap = TileTap.OPEN_APP),
            // Nothing to connect yet: joining happens in the app.
            Row(member = false, up = false, needsConsent = false, tap = TileTap.OPEN_APP),
        ).forEach { row ->
            assertEquals(row.toString(), row.tap, QuickTile.onTap(row.member, row.up, row.needsConsent))
        }
    }
}
