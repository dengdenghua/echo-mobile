package com.apk.claw.android.octopus_mobile.workflow

/**
 * Condition expressions of a CONDITION step: `${status} == "ok"`, `${count} > 3`, `${reply} contains yes`.
 *
 * The operator is found in the expression as written, before `${var}` values are filled in, so a
 * value that happens to contain "==" or ">" does not change how the expression is read. Quoted
 * literals ("ok" or 'ok') are compared without their quotes. An expression without an operator
 * holds when its value is not empty, "false" or "0".
 */
object WorkflowCondition {
    /** Longer operators first, so ">=" is not read as ">". */
    private val OPERATORS = listOf("==", "!=", ">=", "<=", "contains", ">", "<")
    private val VARIABLE = WorkflowContext.VARIABLE

    fun holds(expression: String, ctx: WorkflowContext): Boolean {
        val masked = VARIABLE.replace(expression) { "_".repeat(it.value.length) }
        val op = OPERATORS.firstOrNull { masked.contains(it) }
        if (op == null) {
            val value = unquote(ctx.resolveTemplate(expression))
            return value.isNotEmpty() && value != "false" && value != "0"
        }
        val at = masked.indexOf(op)
        val left = unquote(ctx.resolveTemplate(expression.substring(0, at)))
        val right = unquote(ctx.resolveTemplate(expression.substring(at + op.length)))
        return compare(op, left, right)
    }

    private fun compare(op: String, left: String, right: String): Boolean = when (op) {
        "==" -> left == right
        "!=" -> left != right
        "contains" -> left.contains(right)
        else -> {
            val l = left.toDoubleOrNull() ?: 0.0
            val r = right.toDoubleOrNull() ?: 0.0
            when (op) {
                ">=" -> l >= r
                "<=" -> l <= r
                ">" -> l > r
                else -> l < r
            }
        }
    }

    private fun unquote(raw: String): String {
        val s = raw.trim()
        val quoted = s.length >= 2 && (s.first() == '"' || s.first() == '\'') && s.last() == s.first()
        return if (quoted) s.substring(1, s.length - 1) else s
    }
}
