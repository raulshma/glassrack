package com.example.glassrack.feature.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.glassrack.core.database.GlassrackRepository
import com.example.glassrack.core.model.AuditCategory
import com.example.glassrack.core.model.AuditChainVerification
import com.example.glassrack.core.model.AuditEntry
import com.example.glassrack.core.model.AuditOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AuditFilterChip(val label: String) {
    ALL("All"),
    CONTAINERS("Containers"),
    SSH("SSH Cmds"),
    SERVICES("Services"),
    POWER("Power"),
    FAILED("Failed / Blocked"),
}

data class AuditUiState(
    val entries: List<AuditEntry> = emptyList(),
    val searchQuery: String = "",
    val selectedFilter: AuditFilterChip = AuditFilterChip.ALL,
    val verification: AuditChainVerification? = null,
    val inspectedEntry: AuditEntry? = null,
    val exportFeedback: String? = null,
)

class AuditViewModel(
    private val repository: GlassrackRepository,
) : ViewModel() {

    private val _search = MutableStateFlow("")
    private val _filter = MutableStateFlow(AuditFilterChip.ALL)
    private val _verification = MutableStateFlow<AuditChainVerification?>(null)
    private val _inspected = MutableStateFlow<AuditEntry?>(null)
    private val _feedback = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AuditUiState> = combine(
        repository.auditEntries,
        _search,
        _filter,
        _verification,
        _inspected,
        _feedback,
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val all = arr[0] as List<AuditEntry>
        val query = arr[1] as String
        val filter = arr[2] as AuditFilterChip
        val ver = arr[3] as AuditChainVerification?
        val inspected = arr[4] as AuditEntry?
        val fb = arr[5] as String?

        val q = query.trim().lowercase()
        val filtered = all.filter { entry ->
            val catMatch = when (filter) {
                AuditFilterChip.ALL -> true
                AuditFilterChip.CONTAINERS -> entry.category == AuditCategory.CONTAINER
                AuditFilterChip.SSH -> entry.category == AuditCategory.SSH
                AuditFilterChip.SERVICES -> entry.category == AuditCategory.SERVICE || entry.category == AuditCategory.PROCESS
                AuditFilterChip.POWER -> entry.category == AuditCategory.POWER
                AuditFilterChip.FAILED -> entry.outcome == AuditOutcome.FAILED || entry.outcome == AuditOutcome.BLOCKED || entry.outcome == AuditOutcome.TIMEOUT
            }
            val textMatch = q.isEmpty() ||
                entry.actionTitle.lowercase().contains(q) ||
                entry.serverNameSnapshot.lowercase().contains(q) ||
                entry.commandSanitized.lowercase().contains(q)
            catMatch && textMatch
        }

        AuditUiState(
            entries = filtered,
            searchQuery = query,
            selectedFilter = filter,
            verification = ver,
            inspectedEntry = inspected,
            exportFeedback = fb,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AuditUiState())

    init {
        verifyChain()
    }

    fun setSearch(q: String) { _search.value = q }
    fun setFilter(f: AuditFilterChip) { _filter.value = f }
    fun inspectEntry(entry: AuditEntry?) { _inspected.value = entry }
    fun setFeedback(msg: String?) { _feedback.value = msg }

    fun verifyChain() {
        viewModelScope.launch {
            _verification.value = repository.verifyAuditChain()
        }
    }

    suspend fun buildExportContent(format: String): String = when (format) {
        "json" -> repository.exportAuditAsJson()
        "csv" -> repository.exportAuditAsCsv()
        else -> repository.exportAuditAsMarkdown()
    }
}
