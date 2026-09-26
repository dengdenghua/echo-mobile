package com.apk.claw.android.agent

import android.util.Log
import com.apk.claw.android.octopus_mobile.GoalVerifier
import com.apk.claw.android.channel.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

/**
 * 任务队列 —— 支持优先级调度和后台任务.
 *
 * 替代 TaskOrchestrator 中的单任务锁模型：
 *  - 高优先级任务可以抢占正在执行的低优先级任务
 *  - 后台任务（如下载文件）不阻塞前台任务
 *  - 被抢占的任务可以暂停并稍后恢复
 */
class TaskQueue {

    data class QueuedTask(
        val id: String,
        val task: String,
        val channel: Channel,
        val messageId: String,
        val priority: TaskPriority = TaskPriority.NORMAL,
        val isBackground: Boolean = false,  // 后台任务不阻塞队列
        val createdAt: Long = System.currentTimeMillis(),
        var status: TaskStatus = TaskStatus.QUEUED,
        var goalVerification: GoalVerifier.Verdict = GoalVerifier.unverified("尚未核验"),
        /** 任务来源是否受信任。false（默认）= 不可信来源（聊天渠道/LAN 控制台），高危工具走来源闸门拦截；
         *  true = 受信任来源（内部系统触发），不施加来源闸门。安全默认 false。 */
        val trusted: Boolean = false,
    ) : Comparable<QueuedTask> {
        override fun compareTo(other: QueuedTask): Int {
            // 优先级高的先执行；同优先级按创建时间排序
            val priorityCmp = other.priority.level.compareTo(this.priority.level)
            return if (priorityCmp != 0) priorityCmp else this.createdAt.compareTo(other.createdAt)
        }
    }

    enum class TaskPriority(val level: Int) {
        LOW(0),
        NORMAL(1),
        HIGH(2),
        URGENT(3)
    }

    enum class TaskStatus {
        QUEUED,       // 排队中
        RUNNING,      // 执行中
        PAUSED,       // 被暂停（被高优先级任务抢占）
        COMPLETED,    // 执行已结束，目标核验见 goalVerification
        FAILED,       // 失败
        CANCELLED     // 被取消
    }

    companion object {
        private const val TAG = "TaskQueue"
        private const val MAX_QUEUE_SIZE = 10
    }

    private val queue = PriorityBlockingQueue<QueuedTask>()
    // 被暂停的任务：用 ConcurrentHashMap 保证多线程安全（TaskOrchestrator 的抢占/恢复/取消可能并发触发）
    private val pausedTasks = ConcurrentHashMap<String, QueuedTask>()

    /** 入队一个任务 */
    fun enqueue(task: QueuedTask): Boolean {
        // 使用 putIfAbsent 保证原子性：若任务已存在（重复入队）则拒绝
        if (queue.size >= MAX_QUEUE_SIZE) {
            Log.w(TAG, "Queue is full, rejecting task: ${task.id}")
            return false
        }
        return queue.offer(task).also { ok ->
            if (ok) Log.i(TAG, "Task enqueued: id=${task.id}, priority=${task.priority}, queueSize=${queue.size}")
        }
    }

    /** 取出下一个要执行的任务（阻塞直到有任务） */
    fun dequeue(): QueuedTask? = queue.poll()

    /** 查看队首任务但不移除 */
    fun peek(): QueuedTask? = queue.peek()

    /** 当前队列大小 */
    fun size(): Int = queue.size

    /** 队列是否为空 */
    fun isEmpty(): Boolean = queue.isEmpty()

    /** 是否有后台任务在队列中 */
    fun hasBackgroundTasks(): Boolean = queue.any { it.isBackground }

    /** 暂停一个任务（被高优先级抢占时） */
    fun pauseTask(taskId: String): QueuedTask? {
        // PriorityBlockingQueue 的 removeIf 是线程安全的，原子地查找+移除
        var paused: QueuedTask? = null
        queue.removeIf { it.id == taskId && run { paused = it.copy(status = TaskStatus.PAUSED); true } }
        return paused?.also {
            // putIfAbsent 避免覆盖已存在的暂停任务
            pausedTasks.putIfAbsent(taskId, it)
            Log.i(TAG, "Task paused: $taskId")
        }
    }

    /** 暂停当前正在运行的任务（当前任务不在 queue 中）。 */
    fun pauseRunningTask(task: QueuedTask): QueuedTask {
        val paused = task.copy(status = TaskStatus.PAUSED)
        pausedTasks[task.id] = paused
        Log.i(TAG, "Running task paused: ${task.id}")
        return paused
    }

    /** 恢复一个被暂停的任务 */
    fun resumeTask(taskId: String): Boolean {
        val task = pausedTasks.remove(taskId) ?: return false
        val resumed = task.copy(status = TaskStatus.QUEUED)
        queue.add(resumed)
        Log.i(TAG, "Task resumed: $taskId")
        return true
    }

    /**
     * 当主队列空闲时，将暂停的任务批量恢复到队列中。
     * 返回恢复的任务数量（0 表示没有暂停任务）。
     */
    fun resumePausedTasksWhenIdle(): Int {
        if (queue.isNotEmpty() || pausedTasks.isEmpty()) return 0
        var count = 0
        val iter = pausedTasks.entries.iterator()
        while (iter.hasNext()) {
            val (_, task) = iter.next()
            iter.remove()
            queue.add(task.copy(status = TaskStatus.QUEUED))
            count++
            Log.i(TAG, "Auto-resumed paused task: ${task.id}")
        }
        return count
    }

    /** 是否有暂停任务等待恢复 */
    fun hasPausedTasks(): Boolean = pausedTasks.isNotEmpty()

    /** 取消一个任务 */
    fun cancelTask(taskId: String): Boolean {
        val removed = queue.removeIf { it.id == taskId }
        val pausedRemoved = pausedTasks.remove(taskId) != null
        return removed || pausedRemoved
    }

    /** 获取所有排队中的任务 */
    fun getQueuedTasks(): List<QueuedTask> = queue.toList().sorted()

    /** 获取所有被暂停的任务 */
    fun getPausedTasks(): List<QueuedTask> = pausedTasks.values.toList()

    /** 判断新任务是否应该抢占当前任务 */
    fun shouldPreempt(newTask: QueuedTask, currentTask: QueuedTask?): Boolean {
        if (currentTask == null) return false
        if (currentTask.isBackground) return false  // 后台任务不抢占
        if (newTask.isBackground) return false      // 后台任务不抢占前台
        return newTask.priority.level > currentTask.priority.level
    }
}
