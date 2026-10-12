package com.apk.claw.android.agent

/** Execution-scoped output observer. Child stream readers must capture and forward [current] explicitly. */
internal object ToolOutputContext {
    private val sink = ThreadLocal<((String) -> Unit)?>()

    fun current(): ((String) -> Unit)? = sink.get()
    fun emit(text: String) { sink.get()?.invoke(text) }

    fun stderrSink(observer: ((String) -> Unit)?): (String) -> Unit {
        var started = false
        return { chunk ->
            observer?.invoke((if (!started) "\n--- stderr ---\n" else "") + chunk)
            started = true
        }
    }

    fun <T> withSink(observer: (String) -> Unit, block: () -> T): T {
        val previous = sink.get()
        sink.set(observer)
        return try { block() } finally {
            if (previous == null) sink.remove() else sink.set(previous)
        }
    }
}
