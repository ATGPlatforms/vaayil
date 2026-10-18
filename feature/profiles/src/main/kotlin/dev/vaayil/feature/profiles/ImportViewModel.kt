package dev.vaayil.feature.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vaayil.core.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Domain-level import outcome (known-issue A1): parse-failure details are mapped to a plain
 * message at the :app boundary — this module never touches :core:config directly.
 */
sealed interface ImportOutcome {
    data class Success(val profile: Profile) : ImportOutcome
    data class Failure(val message: String?) : ImportOutcome
}

class ImportViewModel(private val import: suspend (String, String) -> ImportOutcome) : ViewModel() {
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun importText(text: String, name: String, onResult: (ImportOutcome) -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = try {
                withContext(Dispatchers.Default) { import(text.trim(), name.ifBlank { "Imported" }) }
            } catch (e: Exception) {
                ImportOutcome.Failure(null) // unmapped unexpected error → generic copy
            }
            _busy.value = false
            onResult(outcome)
        }
    }
}
