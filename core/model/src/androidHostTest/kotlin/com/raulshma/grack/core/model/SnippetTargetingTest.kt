package com.raulshma.grack.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Broadcast targeting (Phase 1C item 5): snippets without tags reach every node, tagged
 * snippets reach the nodes carrying any of their tags — previously zero nodes, because
 * node tags were not editable.
 */
class SnippetTargetingTest {

    private val docker = Server(id = "srv-1", name = "node-a", primaryHost = "10.0.0.5", tags = listOf("docker", "nas"))
    private val rpi = Server(id = "srv-2", name = "node-b", primaryHost = "10.0.0.6", tags = listOf("raspberrypi"))
    private val bare = Server(id = "srv-3", name = "node-c", primaryHost = "10.0.0.7")

    private val servers = listOf(docker, rpi, bare)

    @Test
    fun emptyTargetTagsTargetEveryNode() {
        assertEquals(servers, snippetTargetServers(servers, emptyList()))
    }

    @Test
    fun nodesWithAnyTargetTagMatch() {
        val targets = snippetTargetServers(servers, listOf("docker", "raspberrypi"))
        assertEquals(listOf(docker, rpi), targets)
    }

    @Test
    fun unknownTagsTargetZeroNodesInsteadOfEverything() {
        assertTrue(snippetTargetServers(servers, listOf("nonexistent")).isEmpty())
    }

    @Test
    fun nodesWithoutTagsNeverMatchTaggedRunbooks() {
        assertTrue(snippetTargetServers(listOf(bare), listOf("docker")).isEmpty())
        assertEquals(listOf(bare), snippetTargetServers(listOf(bare), emptyList()))
    }
}
