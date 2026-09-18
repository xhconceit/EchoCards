package com.orange.echocards.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.orange.echocards.data.CardEntity
import com.orange.echocards.data.DeckEntity
import com.orange.echocards.data.DeckProgressEntity
import com.orange.echocards.data.DeckSummary
import com.orange.echocards.data.EchoRepository
import com.orange.echocards.data.UserSettingsEntity
import com.orange.echocards.importdata.ImportedDeck
import com.orange.echocards.importdata.JsonDeckImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportPreview(
    val fileName: String,
    val deck: ImportedDeck,
    val duplicateId: String?,
    val sameName: Boolean,
)

@OptIn(ExperimentalCoroutinesApi::class)
class EchoViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = EchoRepository(application)
    val decks: StateFlow<List<DeckEntity>> = repository.decks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val loadError = MutableStateFlow<String?>(null)
    private val deckRefresh = MutableStateFlow(0)
    val deckSummaries: StateFlow<List<DeckSummary>> = deckRefresh.flatMapLatest {
        repository.deckSummaries.catch { error ->
            loadError.value = error.message ?: "读取失败"
            emit(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings: StateFlow<UserSettingsEntity?> = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val selectedDeckId = MutableStateFlow<String?>(null)
    val selectedDeck: StateFlow<DeckEntity?> = selectedDeckId.flatMapLatest { id ->
        if (id == null) flowOf(null) else repository.deck(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val cards: StateFlow<List<CardEntity>> = selectedDeckId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repository.cards(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val progress: StateFlow<DeckProgressEntity?> = selectedDeckId.flatMapLatest { id ->
        if (id == null) flowOf(null) else repository.progress(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val importPreview = MutableStateFlow<ImportPreview?>(null)
    val importBusy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)

    fun openDeck(id: String) { selectedDeckId.value = id }
    fun clearMessage() { message.value = null }
    fun closeImport() { importPreview.value = null }
    fun retryDeckLoad() {
        loadError.value = null
        deckRefresh.value += 1
    }

    suspend fun createDeck(title: String, description: String): Boolean = attempt {
        val id = repository.createDeck(title, description)
        selectedDeckId.value = id
    }

    suspend fun updateDeck(id: String, title: String, description: String): Boolean = attempt {
        repository.updateDeck(id, title, description)
    }

    suspend fun deleteDeck(id: String): Boolean = attempt {
        repository.deleteDeck(id)
        selectedDeckId.value = null
    }

    suspend fun saveCard(cardId: String?, title: String, content: String, speechText: String, memoryTip: String): Boolean = attempt {
        val deckId = selectedDeckId.value ?: error("没有选中卡组")
        repository.saveCard(deckId, cardId, title, content, speechText, memoryTip)
    }

    suspend fun deleteCard(cardId: String): Boolean = attempt {
        val deckId = selectedDeckId.value ?: error("没有选中卡组")
        repository.deleteCard(deckId, cardId)
    }

    fun moveCard(cardId: String, delta: Int) {
        val deckId = selectedDeckId.value ?: return
        viewModelScope.launch { attempt { repository.moveCard(deckId, cardId, delta) } }
    }

    fun saveSettings(mode: String, rate: Double, delay: Long) {
        viewModelScope.launch { attempt { repository.saveSettings(mode, rate, delay) } }
    }

    fun saveProgress(cardId: String?, mode: String) {
        val deckId = selectedDeckId.value ?: return
        viewModelScope.launch { attempt { repository.saveProgress(deckId, cardId, mode) } }
    }

    fun readImport(uri: Uri) {
        if (importBusy.value) return
        importBusy.value = true
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    JsonDeckImport.read(getApplication<Application>().contentResolver, uri)
                }
                val duplicate = repository.findDuplicate(imported)
                val sameName = decks.value.any { it.title == imported.title }
                importPreview.value = ImportPreview(uri.lastPathSegment?.substringAfterLast('/') ?: "JSON 文件", imported, duplicate?.id, sameName)
            } catch (error: Exception) {
                message.value = error.message ?: "无法读取文件"
            } finally {
                importBusy.value = false
            }
        }
    }

    suspend fun confirmImport(title: String): Boolean {
        val preview = importPreview.value ?: return false
        if (preview.duplicateId != null) {
            selectedDeckId.value = preview.duplicateId
            closeImport()
            return true
        }
        return attempt {
            val id = repository.importDeck(preview.deck, title)
            selectedDeckId.value = id
            closeImport()
        }
    }

    private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (error: Exception) {
        message.value = error.message ?: "操作失败，请重试"
        false
    }
}
