package org.tasks.service

import com.todoroo.astrid.repeats.RepeatTaskHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.tasks.DatabaseTest
import org.tasks.data.TaskSaver
import org.tasks.data.entity.Task

class TaskCompleterTest : DatabaseTest() {
    private val taskDao = db.taskDao()

    private val taskSaver = TaskSaver(
        taskDao = taskDao,
        refreshBroadcaster = mock(),
        notifier = mock(),
        locationService = mock(),
        timerPlugin = mock(),
        backgroundWork = mock(),
        caldavDao = db.caldavDao(),
    )

    private val repeatTaskHelper: RepeatTaskHelper = mock()

    private val completer = TaskCompleter(
        taskDao = taskDao,
        taskSaver = taskSaver,
        notifier = mock(),
        refreshBroadcaster = mock(),
        repeatTaskHelper = repeatTaskHelper,
        caldavDao = db.caldavDao(),
        calendarHelper = mock(),
        completionDao = db.completionDao(),
        soundPlayer = mock(),
    )

    private suspend fun newTask(
        title: String,
        parent: Long = 0,
        completedAt: Long = 0,
        recurrence: String? = null,
    ): Task {
        val task = Task(title = title, parent = parent, completionDate = completedAt, recurrence = recurrence)
        taskDao.createNew(task)
        return task
    }

    private suspend fun completionOf(task: Task): Long = taskDao.fetch(task.id)!!.completionDate

    @Test
    fun completingATaskCarriesDownIntoEverythingInsideIt() = runBlocking {
        val a = newTask("a")
        val b = newTask("b", parent = a.id)
        val c = newTask("c", parent = b.id)

        completer.setComplete(a, true)

        val stamp = completionOf(a)
        assertTrue(stamp > 0)
        assertEquals(stamp, completionOf(b))
        assertEquals(stamp, completionOf(c))
    }

    @Test
    fun completingLeavesWhatWasAlreadyFinishedWithItsOwnDate() = runBlocking {
        val a = newTask("a")
        val done = newTask("done", parent = a.id, completedAt = EARLIER)

        completer.setComplete(a, true)

        assertEquals(EARLIER, completionOf(done))
    }

    @Test
    fun unCompletingUndoesTheWholeSubtreeItsOwnCompletionFinished() = runBlocking {
        val a = newTask("a")
        val b = newTask("b", parent = a.id)
        val c = newTask("c", parent = b.id)
        completer.setComplete(a, true)

        completer.setComplete(taskDao.fetch(a.id)!!, false)

        assertFalse(taskDao.fetch(a.id)!!.isCompleted)
        assertFalse(taskDao.fetch(b.id)!!.isCompleted)
        assertFalse(taskDao.fetch(c.id)!!.isCompleted)
    }

    @Test
    fun unCompletingAParentResetsTheWholeChecklist() = runBlocking {
        val a = newTask("a")
        val cascaded = newTask("cascaded", parent = a.id)
        val ownAccount = newTask("ownAccount", parent = a.id, completedAt = EARLIER)
        completer.setComplete(a, true)

        completer.setComplete(taskDao.fetch(a.id)!!, false)

        assertFalse(taskDao.fetch(a.id)!!.isCompleted)
        assertFalse(taskDao.fetch(cascaded.id)!!.isCompleted)
        assertFalse(taskDao.fetch(ownAccount.id)!!.isCompleted)
    }

    @Test
    fun unCompletingReachesPastADescendantFinishedOnItsOwnAccount() = runBlocking {
        val a = newTask("a")
        val skipped = newTask("skipped", parent = a.id, completedAt = EARLIER)
        val underneath = newTask("underneath", parent = skipped.id)
        completer.setComplete(a, true)

        completer.setComplete(taskDao.fetch(a.id)!!, false)

        assertFalse(taskDao.fetch(skipped.id)!!.isCompleted)
        assertFalse(taskDao.fetch(underneath.id)!!.isCompleted)
    }

    @Test
    fun unCompletingSomethingInsideReopensEverythingAboveIt() = runBlocking {
        val a = newTask("a")
        val b = newTask("b", parent = a.id)
        val c = newTask("c", parent = b.id)
        completer.setComplete(a, true)

        completer.setComplete(taskDao.fetch(c.id)!!, false)

        assertFalse(taskDao.fetch(c.id)!!.isCompleted)
        assertFalse(taskDao.fetch(b.id)!!.isCompleted)
        assertFalse(taskDao.fetch(a.id)!!.isCompleted)
    }

    @Test
    fun unCompletingLeavesTasksOutsideTheSubtreeAlone() = runBlocking {
        val a = newTask("a")
        val b = newTask("b", parent = a.id)
        val elsewhere = newTask("elsewhere")
        completer.setComplete(a, true)
        completer.setComplete(elsewhere, true)
        val untouched = completionOf(elsewhere)

        completer.setComplete(taskDao.fetch(a.id)!!, false)

        assertFalse(taskDao.fetch(b.id)!!.isCompleted)
        assertEquals(untouched, completionOf(elsewhere))
    }

    @Test
    fun skippingARecurringTaskStampsSkippedAndAdvancesIt() = runBlocking {
        val task = newTask("Exercise", recurrence = "FREQ=DAILY")

        completer.setSkipped(task)

        assertEquals(Task.Resolution.SKIPPED.serialized, task.lastResolution)
        assertFalse("skip must never leave the task marked completed", task.isCompleted)
    }

    @Test
    fun skippingANonRecurringTaskIsANoOp() = runBlocking {
        val task = newTask("One-off errand")

        completer.setSkipped(task)

        verifyNoInteractions(repeatTaskHelper)
        assertNull(task.lastResolution)
    }

    @Test
    fun skippingAReadOnlyTaskIsANoOp() = runBlocking {
        val task = newTask("Read-only habit", recurrence = "FREQ=DAILY")
        task.readOnly = true

        completer.setSkipped(task)

        verifyNoInteractions(repeatTaskHelper)
        assertNull(task.lastResolution)
    }

    @Test
    fun completingARecurringTaskStampsCompletedBeforeItAdvances() = runBlocking {
        val recurring: RepeatTaskHelper = mock {
            onBlocking { handleRepeat(any()) } doReturn true
        }
        val completerWithSpy = TaskCompleter(
            taskDao = taskDao,
            taskSaver = taskSaver,
            notifier = mock(),
            refreshBroadcaster = mock(),
            repeatTaskHelper = recurring,
            caldavDao = db.caldavDao(),
            calendarHelper = mock(),
            completionDao = db.completionDao(),
            soundPlayer = mock(),
        )
        val task = newTask("Exercise", recurrence = "FREQ=DAILY")

        completerWithSpy.setComplete(task, true)

        val saved = taskDao.fetch(task.id)!!
        assertEquals(Task.Resolution.COMPLETED.serialized, saved.lastResolution)
    }

    companion object {
        private const val EARLIER = 1_700_000_000_000L
    }
}
