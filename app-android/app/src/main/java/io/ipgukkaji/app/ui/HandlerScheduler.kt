package io.ipgukkaji.app.ui

import android.os.Handler
import android.os.Looper
import io.ipgukkaji.app.domain.Cancellable
import io.ipgukkaji.app.domain.Scheduler

class HandlerScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : Scheduler {
    override fun schedule(delayMs: Long, task: () -> Unit): Cancellable {
        val r = Runnable { task() }
        handler.postDelayed(r, delayMs)
        return Cancellable { handler.removeCallbacks(r) }
    }
}
