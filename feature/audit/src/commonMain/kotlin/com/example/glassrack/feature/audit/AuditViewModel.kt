package com.example.glassrack.feature.audit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.glassrack.core.database.GlassrackRepository
import com.example.glassrack.core.model.AuditCategory
import com.example.glassrack.core.model.AuditChainVerification
import com.example.glassrack.core.model.AuditEntry
import com.example.glassrack.core.model.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AuditFilterChip(val label: String, val category: AuditCategory?) {
    ALL("All", null),
    SSH("SSH", AuditCategory.SSH),
    CONTAINER("Containers", AuditCategory.CONTAINER),
    SERVICE("Services", AuditCategory.SERVICE),
    PROCESS("Processes", AuditCategory.PROCESS),
    SYSTEM("System", AuditCategory.SYSTEM),
    CONFIG("Config", AuditCategory.CONFIG),
    POWER("Power", AuditCategory.POWER),
}

data class AuditUiState(
    val entries: List<AuditEntry> = emptyList(),
    val totalEntryCount: Int = 0,
    val retentionDays: Int = 90,
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
        repository.preferences,
        _search,
        _filter,
        _verification,
        _inspected,
        _feedback,
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        val all = arr[0] as List<AuditEntry>
        val prefs = arr[1] as UserPreferences
        val query = arr[2] as String
        val filter = arr[3] as AuditFilterChip
        val ver = arr[4] as AuditChainVerification?
        val inspected = arr[5] as AuditEntry?
        val fb = arr[6] as String?

        val q = query.trim().lowercase()
        val filtered = all.filter { entry ->
            val catMatch = filter.category == null || entry.category == filter.category
            val textMatch = q.isEmpty() ||
                entry.actionTitle.lowercase().contains(q) ||
                entry.serverNameSnapshot.lowercase().contains(q) ||
                entry.commandSanitized.lowercase().contains(q) ||
                entry.category.name.lowercase().contains(q)
            catMatch && textMatch
        }

        AuditUiState(
            entries = filtered,
            totalEntryCount = all.size,
            retentionDays = prefs.auditRetentionDays,
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

    fun pruneOldEntries() {
        viewModelScope.launch {
            val days = uiState.value.retentionDays
            val removed = repository.pruneAuditLogs(days)
            _verification.value = repository.verifyAuditChain()
            _feedback.value = if (removed > 0) {
                "Pruned $removed audit entries older than $days days"
            } else {
                "No entries older than $days days to prune"
            }
        }
    }

    suspend fun buildExportContent(format: String): String = when (format) {
        "json" -> repository.exportAuditAsJson()
        "csv" -> repository.exportAuditAsCsv()
        else -> repository.exportAuditAsMarkdown()
    }
}
