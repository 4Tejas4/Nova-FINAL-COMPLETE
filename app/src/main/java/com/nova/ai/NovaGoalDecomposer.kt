package com.nova.ai

/** Deterministic goal decomposition used before AI execution. It creates a small dependency-aware skeleton, not fake certainty. */
object NovaGoalDecomposer {
    data class SubGoal(val id: String, val title: String, val dependsOn: List<String> = emptyList(), val done: Boolean = false)
    data class GoalPlan(val original: String, val subGoals: List<SubGoal>)

    fun decompose(request: String): GoalPlan {
        val text = request.trim().replace(Regex("\\s+"), " ")
        val parts = text.split(Regex("\\s+(?:then|and then|after that|next)\\s+|\\s*[;]\\s*|\\s*,\\s*and\\s+", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotBlank() }.take(8)
        val chunks = if (parts.size > 1) parts else listOf(text)
        val goals = chunks.mapIndexed { i, p ->
            val id = "g${i + 1}"
            SubGoal(id, classify(p), if (i == 0) emptyList() else listOf("g$i"))
        }
        return GoalPlan(text, goals)
    }

    private fun classify(text: String): String {
        val t = text.lowercase()
        return when {
            t.contains("search") || t.contains("find") -> "Find or retrieve the requested information"
            t.contains("open") || t.contains("launch") -> "Open the requested destination"
            t.contains("send") || t.contains("message") || t.contains("call") -> "Prepare the requested communication action"
            t.contains("download") -> "Retrieve and save the requested file"
            t.contains("create") || t.contains("make") -> "Create the requested result"
            t.contains("set") || t.contains("schedule") || t.contains("remind") -> "Create or schedule the requested item"
            else -> "Complete the requested goal using available capabilities"
        }
    }

    fun prompt(plan: GoalPlan): String = buildString {
        append("Goal decomposition (planning aid):\n")
        plan.subGoals.forEach { append("${it.id}: ${it.title}; dependsOn=${it.dependsOn.joinToString(",")}; completed=${it.done}\n") }
        append("Nova must verify each sub-goal before moving to its dependents and may re-plan when evidence changes.")
    }
}
