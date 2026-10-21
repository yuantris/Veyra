package dev.veyra.browser.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * App-level event fan-out for normalized browser events.
 *
 * This is deliberately separate from each engine's event stream. An adapter/engine owner forwards
 * events here after normalization; consumers should still use pageState for durable current state.
 * Slow collectors cannot indefinitely block native callbacks: the bounded buffer drops the oldest
 * queued event when full. State transitions must therefore never depend on this stream being lossless.
 */
class BrowserEventHub(
    extraBufferCapacity: Int = DEFAULT_EXTRA_BUFFER_CAPACITY,
) {
    init {
        require(extraBufferCapacity > 0) { "Buffer capacity must be positive when using DROP_OLDEST." }
    }

    private val mutableEvents = MutableSharedFlow<BrowserEvent>(
        replay = 0,
        extraBufferCapacity = extraBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: Flow<BrowserEvent> = mutableEvents.asSharedFlow()

    /**
     * Publishes a normalized event without suspending a platform callback.
     * Returns false only if publication cannot be accepted by the flow implementation.
     */
    fun publish(event: BrowserEvent): Boolean = mutableEvents.tryEmit(event)

    private companion object {
        const val DEFAULT_EXTRA_BUFFER_CAPACITY = 64
    }
}
