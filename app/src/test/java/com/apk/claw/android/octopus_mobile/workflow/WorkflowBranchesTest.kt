package com.apk.claw.android.octopus_mobile.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Branch targets (id / name / #N) and condition expressions, pure JVM. */
class WorkflowBranchesTest {

    private fun step(id: String, name: String, type: WorkflowStep.StepType = WorkflowStep.StepType.PROMPT) =
        WorkflowStep(id = id, type = type, name = name, content = "")

    private val steps = listOf(
        step("id-a", "Fetch"),
        step("id-b", "Check", WorkflowStep.StepType.CONDITION),
        step("id-c", "Send report"),
        step("id-d", "Done"),
    )

    @Test
    fun `a target resolves by id, by name, case-insensitively, and by step number`() {
        assertEquals(3, WorkflowBranches.resolve(steps, "id-d"))
        assertEquals(2, WorkflowBranches.resolve(steps, "Send report"))
        assertEquals(2, WorkflowBranches.resolve(steps, "  send REPORT "))
        assertEquals(0, WorkflowBranches.resolve(steps, "#1"))
        assertEquals(3, WorkflowBranches.resolve(steps, "4"))
    }

    @Test
    fun `an unknown target or an out-of-range number resolves to nothing`() {
        assertNull(WorkflowBranches.resolve(steps, "Nope"))
        assertNull(WorkflowBranches.resolve(steps, "#9"))
        assertNull(WorkflowBranches.resolve(steps, "#0"))
        assertNull(WorkflowBranches.resolve(steps, ""))
    }

    @Test
    fun `saving stores step ids, and the editor shows them as names again`() {
        val typed = steps.toMutableList()
        typed[1] = typed[1].copy(
            branches = linkedMapOf("\${s} == ok" to "Done", "\${s} == retry" to "#1", "x" to "Ghost"),
        )

        val stored = WorkflowBranches.withStepIds(typed)[1].branches
        assertEquals(listOf("id-d", "id-a", "Ghost"), stored.values.toList())
        // Order of the branches is kept (first match wins).
        assertEquals(listOf("\${s} == ok", "\${s} == retry", "x"), stored.keys.toList())

        val shown = WorkflowBranches.format(typed, stored)
        assertEquals("\${s} == ok => Done\n\${s} == retry => Fetch\nx => Ghost", shown)
    }

    @Test
    fun `a renamed step keeps its branch because the stored target is its id`() {
        val renamed = steps.map { if (it.id == "id-d") it.copy(name = "Finish") else it }
        assertEquals(3, WorkflowBranches.resolve(renamed, "id-d"))
        assertEquals("Finish", WorkflowBranches.label(renamed, "id-d"))
    }

    @Test
    fun `a duplicate or unusable name is shown as its step number`() {
        val dup = listOf(step("1", "Same"), step("2", "Same"), step("3", "a => b"))
        assertEquals("#2", WorkflowBranches.label(dup, "2"))
        assertEquals("#3", WorkflowBranches.label(dup, "3"))
        assertEquals("#2 Same", WorkflowBranches.describe(dup, 1))
    }

    @Test
    fun `parse reads one branch per line and skips incomplete lines`() {
        val parsed = WorkflowBranches.parse("\${a} == 1 => Done\nhalf a line\n => nothing\n\${b} > 2 =>  Send report ")
        assertEquals(linkedMapOf("\${a} == 1" to "Done", "\${b} > 2" to "Send report"), parsed)
    }

    private fun ctx(vararg vars: Pair<String, String>) = WorkflowContext().apply { variables.putAll(vars) }

    @Test
    fun `quoted literals compare without their quotes`() {
        assertTrue(WorkflowCondition.holds("\${status} == \"ok\"", ctx("status" to "ok")))
        assertTrue(WorkflowCondition.holds("\${status} == 'ok'", ctx("status" to "ok")))
        assertFalse(WorkflowCondition.holds("\${status} == \"ok\"", ctx("status" to "fail")))
        assertTrue(WorkflowCondition.holds("\${status} != \"ok\"", ctx("status" to "fail")))
    }

    @Test
    fun `the operator is read from the expression, not from a variable's value`() {
        // The value contains "==" and ">"; the expression's operator is still "contains".
        assertTrue(WorkflowCondition.holds("\${reply} contains yes", ctx("reply" to "a == b > c, yes")))
    }

    @Test
    fun `numeric comparisons including greater-or-equal`() {
        assertTrue(WorkflowCondition.holds("\${n} > 3", ctx("n" to "4")))
        assertTrue(WorkflowCondition.holds("\${n} >= 4", ctx("n" to "4")))
        assertTrue(WorkflowCondition.holds("\${n} <= 4", ctx("n" to "4")))
        assertFalse(WorkflowCondition.holds("\${n} < 4", ctx("n" to "4")))
    }

    @Test
    fun `an expression without an operator is true unless empty, false or 0`() {
        assertTrue(WorkflowCondition.holds("\${flag}", ctx("flag" to "yes")))
        assertFalse(WorkflowCondition.holds("\${flag}", ctx("flag" to "false")))
        assertFalse(WorkflowCondition.holds("\${flag}", ctx("flag" to "0")))
    }
}
