package com.raulshma.grack.feature.servers

import com.raulshma.grack.core.designsystem.WindowWidths
import com.raulshma.grack.core.model.NetworkPortInfo
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 8 "Forward this": the pure reduction from a listening-port row to the prefilled
 * Add-Tunnel draft. Wildcard listeners target the node's loopback interface (what the
 * service actually serves); concrete addresses pass through; the local port defaults to
 * the listening port. Also pins the single breakpoint source of truth (compile-level
 * guard against reintroduced dp literals).
 */
class TunnelDraftPolicyTest {

    private fun port(
        localAddress: String,
        port: Int = 8096,
        process: String = "jellyfin",
        protocol: String = "tcp",
    ) = NetworkPortInfo(
        protocol = protocol,
        localAddress = localAddress,
        port = port,
        processName = process,
    )

    @Test
    fun wildcardListenerTargetsLoopback() {
        val draft = tunnelDraftForPort(port(localAddress = "0.0.0.0"))
        assertEquals("127.0.0.1", draft.remoteHost)
        assertEquals("8096", draft.localPort)
        assertEquals("8096", draft.remotePort)
        assertEquals("jellyfin", draft.label)
    }

    @Test
    fun ipv6WildcardListenerTargetsLoopback() {
        assertEquals("127.0.0.1", tunnelDraftForPort(port(localAddress = "::")).remoteHost)
        assertEquals("127.0.0.1", tunnelDraftForPort(port(localAddress = "[::]")).remoteHost)
        assertEquals("127.0.0.1", tunnelDraftForPort(port(localAddress = "*")).remoteHost)
    }

    @Test
    fun blankAddressFallsBackToLoopback() {
        assertEquals("127.0.0.1", tunnelDraftForPort(port(localAddress = "  ")).remoteHost)
    }

    @Test
    fun concreteAddressIsPreserved() {
        val draft = tunnelDraftForPort(port(localAddress = "192.168.1.20", port = 8123))
        assertEquals("192.168.1.20", draft.remoteHost)
        assertEquals("8123", draft.localPort)
        assertEquals("8123", draft.remotePort)
    }

    @Test
    fun blankProcessNameFallsBackToPortLabel() {
        assertEquals("Port 443", tunnelDraftForPort(port(localAddress = "0.0.0.0", port = 443, process = "")).label)
    }

    @Test
    fun breakpointsHaveASingleSourceOfTruth() {
        // The rail (840dp) and split (780dp) thresholds live only in WindowWidths;
        // this pins the values so accidental drift is caught.
        assertEquals(840.dp, WindowWidths.navigationRail)
        assertEquals(780.dp, WindowWidths.twoPaneSplit)
    }
}
