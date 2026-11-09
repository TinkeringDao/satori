package com.satori.presentation.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a recorder's work runs that must outlive the recorder: freeing its live model as it closes
 * ([LiveModel.close]). A recorder closes in onCleared, on the main thread, with its viewModelScope
 * already cancelled.
 */
@Singleton
class RecorderCloseScope @Inject constructor() {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
