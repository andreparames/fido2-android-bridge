package com.fidobridge.client.notifications

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

interface ForegroundStateProvider {
    fun isForegrounded(): Boolean
}

class ProcessForegroundStateProvider : ForegroundStateProvider {
    override fun isForegrounded(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
}