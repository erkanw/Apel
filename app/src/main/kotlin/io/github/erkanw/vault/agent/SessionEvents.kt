package io.github.erkanw.vault.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A session changed outside the chat on screen (an automation started or finished a turn in it): the chat reads its
 * list and, if that session is open, its messages again.
 */
object SessionEvents {
    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    fun changed(sessionId: String) {
        _changes.tryEmit(sessionId)
    }
}
