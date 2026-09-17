package com.nova.ai

import android.content.Context

/** Stage 59 public facade for discovery, lookup and truthful capability reporting. */
class NovaCapabilityDiscoveryEngine(private val context: Context) {
    fun discover(query: String = ""): List<NovaCapabilityRegistry.Capability> = NovaCapabilityRegistry.find(context, query)
    fun canHandle(query: String): Boolean = discover(query).isNotEmpty()
    fun report(query: String = ""): String = discover(query).joinToString("\n") { "${it.id}: ${it.description}" }.ifBlank { "No matching capability was discovered." }
    fun plannerSnapshot(): String = NovaCapabilityRegistry.snapshot(context)
}
