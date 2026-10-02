package com.fidobridge.client.ui.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface RequestLog {
    val records: StateFlow<List<RequestRecord>>
    fun record(id: String, type: RequestType, rpId: String)
    fun markAccepted(id: String)
    fun markRejected(id: String)
    fun clear()
}

class InMemoryRequestLog(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) : RequestLog {

    private val _records = MutableStateFlow<List<RequestRecord>>(emptyList())
    override val records: StateFlow<List<RequestRecord>> = _records.asStateFlow()

    override fun record(id: String, type: RequestType, rpId: String) {
        _records.update { current ->
            if (current.any { it.id == id }) {
                current
            } else {
                val record = RequestRecord(
                    id = id,
                    type = type,
                    rpId = rpId,
                    timestamp = System.currentTimeMillis(),
                    outcome = RequestOutcome.PENDING
                )
                (listOf(record) + current).take(maxEntries)
            }
        }
    }

    override fun markAccepted(id: String) = updateOutcome(id, RequestOutcome.ACCEPTED)

    override fun markRejected(id: String) = updateOutcome(id, RequestOutcome.REJECTED)

    override fun clear() {
        _records.value = emptyList()
    }

    private fun updateOutcome(id: String, outcome: RequestOutcome) {
        _records.update { current ->
            current.map { if (it.id == id) it.copy(outcome = outcome) else it }
        }
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 50
    }
}

object NoOpRequestLog : RequestLog {
    override val records: StateFlow<List<RequestRecord>> = MutableStateFlow(emptyList())
    override fun record(id: String, type: RequestType, rpId: String) = Unit
    override fun markAccepted(id: String) = Unit
    override fun markRejected(id: String) = Unit
    override fun clear() = Unit
}