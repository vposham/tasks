package org.tasks.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.tasks.analytics.Firebase
import org.tasks.data.dao.NotificationDao
import org.tasks.injection.ApplicationScope
import org.tasks.service.TaskCompleter
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class SkipTaskReceiver : BroadcastReceiver() {
    @Inject lateinit var notificationDao: NotificationDao
    @Inject lateinit var taskCompleter: TaskCompleter
    @Inject @ApplicationScope lateinit var scope: CoroutineScope
    @Inject lateinit var firebase: Firebase

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(TASK_ID, 0)
        Timber.i("Skipping %s", taskId)
        scope.launch {
            if (!notificationDao.hasNotification(taskId)) {
                Timber.e("No notification found for $taskId")
                return@launch
            }
            taskCompleter.setSkipped(taskId)
            firebase.completeTask("notification_skip")
        }
    }

    companion object {
        const val TASK_ID = "id"
    }
}
