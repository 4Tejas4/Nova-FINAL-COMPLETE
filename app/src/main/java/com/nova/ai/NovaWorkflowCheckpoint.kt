package com.nova.ai

/** Serializable checkpoint for a long-running autonomous task. */
data class NovaWorkflowCheckpoint(
    val taskId: String,
    val request: String,
    val step: Int,
    val lastResult: String,
    val taskState: String,
    val actionHistory: List<String>,
    val startedAt: Long,
    val updatedAt: Long,
    val status: String
)
