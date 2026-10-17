package com.apk.claw.android.octopus_mobile.workflow

/**
 * Where a condition branch goes.
 *
 * People write a branch target as the step's name (the editor shows names, never ids), while the
 * engine runs steps by position. The editor stores targets as step ids so that renaming a step
 * keeps its branches; workflows saved before that stored the typed name, and imported JSON may use
 * either. [resolve] therefore accepts a step id, a step name, or a 1-based step number ("#3" or "3").
 */
object WorkflowBranches {
    private const val ARROW = "=>"
    private val STEP_NUMBER = Regex("^#?(\\d+)$")

    /** Index of the step that [target] names, or null when no step matches. */
    fun resolve(steps: List<WorkflowStep>, target: String): Int? {
        val t = target.trim()
        if (t.isEmpty()) return null
        val matchers = sequenceOf<(WorkflowStep) -> Boolean>(
            { it.id == t },
            { it.name.trim() == t },
            { it.name.trim().equals(t, ignoreCase = true) },
        )
        val byIdOrName = matchers.map { steps.indexOfFirst(it) }.firstOrNull { it >= 0 }
        return byIdOrName ?: stepNumber(t)?.takeIf { it in steps.indices }
    }

    /** "#3 Send report": how a step is named in hints and run logs. */
    fun describe(steps: List<WorkflowStep>, index: Int): String {
        val name = steps.getOrNull(index)?.name?.trim().orEmpty()
        return if (name.isEmpty()) "#${index + 1}" else "#${index + 1} $name"
    }

    /**
     * What the editor shows for a stored target: the step's name, or "#N" when that name is empty,
     * not unique or would not survive the "expression => target" line format. Unknown targets are
     * shown as stored so the user can see and fix them.
     */
    fun label(steps: List<WorkflowStep>, target: String): String {
        val index = resolve(steps, target) ?: return target.trim()
        val name = steps[index].name.trim()
        return if (namesOnlyItself(steps, index, name)) name else "#${index + 1}"
    }

    /** True when [name] can stand for step [index] in an editor line and resolves back to it. */
    private fun namesOnlyItself(steps: List<WorkflowStep>, index: Int, name: String): Boolean {
        val fitsLine = name.isNotEmpty() && !name.contains(ARROW) && !name.contains('\n')
        return fitsLine && steps.count { it.name.trim() == name } == 1 && resolve(steps, name) == index
    }

    /** Editor text for a step's branches, one "expression => target" per line. */
    fun format(steps: List<WorkflowStep>, branches: Map<String, String>): String =
        branches.entries.joinToString("\n") { (expr, target) -> "$expr $ARROW ${label(steps, target)}" }

    /** Parses editor text; lines without "=>" or with an empty side are ignored. Order is kept. */
    fun parse(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        text.lines().forEach { line ->
            val arrow = line.lastIndexOf(ARROW)
            if (arrow > 0) {
                val expr = line.substring(0, arrow).trim()
                val target = line.substring(arrow + ARROW.length).trim()
                if (expr.isNotEmpty() && target.isNotEmpty()) result[expr] = target
            }
        }
        return result
    }

    /**
     * Stored form: every target that names a step becomes that step's id. Targets that name no step
     * are kept as typed, and the run reports them instead of silently going on.
     */
    fun withStepIds(steps: List<WorkflowStep>): List<WorkflowStep> = steps.map { step ->
        if (step.branches.isEmpty()) {
            step
        } else {
            val stored = step.branches.mapValues { (_, target) ->
                resolve(steps, target)?.let { steps[it].id } ?: target.trim()
            }
            step.copy(branches = stored)
        }
    }

    private fun stepNumber(target: String): Int? =
        STEP_NUMBER.matchEntire(target)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
}
