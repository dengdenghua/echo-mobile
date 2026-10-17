package com.apk.claw.android.octopus_mobile.workflow

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.apk.claw.android.TestClawApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The engine follows condition branches. PROMPT steps use an injected callback that records which
 * prompts ran, so no model, tool or network is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestClawApplication::class)
class WorkflowEngineBranchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun prompt(name: String, outputVar: String? = null) = WorkflowStep(
        id = "id-$name", type = WorkflowStep.StepType.PROMPT, name = name, content = name, outputVar = outputVar,
    )

    private fun condition(name: String, vararg branches: Pair<String, String>) = WorkflowStep(
        id = "id-$name", type = WorkflowStep.StepType.CONDITION, name = name, content = "",
        branches = linkedMapOf(*branches),
    )

    /** Runs [steps]; each prompt answers with [answers] (by prompt text, default "done"). */
    private fun run(
        steps: List<WorkflowStep>,
        answers: Map<String, String> = emptyMap(),
    ): Pair<WorkflowResult, List<String>> {
        val ran = mutableListOf<String>()
        val engine = WorkflowEngine(context, onPrompt = { p -> ran += p; answers[p] ?: "done" })
        val result = runBlocking { engine.execute(Workflow(name = "t", steps = steps)) }
        return result to ran
    }

    private val branching = listOf(
        prompt("Check", outputVar = "status"),
        condition("Route", "\${status} == \"ok\"" to "Report"),
        prompt("Repair"),
        prompt("Report"),
    )

    @Test
    fun `a branch that names a step by name jumps there`() {
        val (result, ran) = run(branching, mapOf("Check" to "ok"))
        assertTrue(result.error, result.success)
        assertEquals(listOf("Check", "Report"), ran)
    }

    @Test
    fun `when no branch holds the run goes on to the next step`() {
        val (result, ran) = run(branching, mapOf("Check" to "broken"))
        assertTrue(result.success)
        assertEquals(listOf("Check", "Repair", "Report"), ran)
    }

    @Test
    fun `a branch saved as a step id (what the editor stores) also jumps`() {
        val stored = WorkflowBranches.withStepIds(branching)
        assertEquals("id-Report", stored[1].branches.values.single())
        val (result, ran) = run(stored, mapOf("Check" to "ok"))
        assertTrue(result.success)
        assertEquals(listOf("Check", "Report"), ran)
    }

    @Test
    fun `a branch can go to a step number`() {
        val steps = branching.toMutableList().apply { this[1] = condition("Route", "\${status} == ok" to "#4") }
        val (_, ran) = run(steps, mapOf("Check" to "ok"))
        assertEquals(listOf("Check", "Report"), ran)
    }

    @Test
    fun `a branch to a missing step fails the run and says which target`() {
        val steps = branching.toMutableList().apply { this[1] = condition("Route", "\${status} == ok" to "Ghost") }
        val (result, ran) = run(steps, mapOf("Check" to "ok"))
        assertFalse(result.success)
        assertEquals(listOf("Check"), ran)
        val error = result.error.orEmpty()
        assertTrue(error, error.contains("Ghost") && error.contains("Route"))
        assertFalse(result.logs.last().success)
    }

    @Test
    fun `a backward branch that never exits is stopped by the loop guard`() {
        val steps = listOf(
            prompt("Poll", outputVar = "state"),
            condition("Again", "\${state} != ready" to "Poll"),
        )
        val (result, ran) = run(steps, mapOf("Poll" to "waiting"))
        assertFalse(result.success)
        assertEquals(WorkflowEngine.MAX_RUNS_PER_STEP, ran.size)
    }

    @Test
    fun `a backward branch loops until its condition stops holding`() {
        var polls = 0
        val engine = WorkflowEngine(context, onPrompt = { if (++polls >= 3) "ready" else "waiting" })
        val steps = listOf(
            prompt("Poll", outputVar = "state"),
            condition("Again", "\${state} != ready" to "Poll"),
        )
        val result = runBlocking { engine.execute(Workflow(name = "loop", steps = steps)) }
        assertTrue(result.error, result.success)
        assertEquals(3, polls)
        assertEquals("ready", result.finalOutput)
    }
}
