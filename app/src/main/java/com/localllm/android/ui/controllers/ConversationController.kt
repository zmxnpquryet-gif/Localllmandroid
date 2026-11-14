package com.localllm.android.ui.controllers

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import com.localllm.android.R
import com.localllm.android.data.repository.ChatRepository
import com.localllm.android.document.DocumentIndex
import com.localllm.android.document.DocumentStats
import com.localllm.android.document.DocumentSummary
import com.localllm.android.i18n.LocalizedStringProvider
import com.localllm.android.model.ChatAttachment
import com.localllm.android.model.ChatMessage
import com.localllm.android.model.Conversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Manages conversation selection, unsaved in-memory drafts, pending attachments,
 * and per-conversation Document RAG indexing/selection.
 */
internal class ConversationController(
    application: Application,
    private val scope: CoroutineScope,
    val repository: ChatRepository,
    private val strings: LocalizedStringProvider,
    private val activeModelId: () -> String,
    private val languagePreference: () -> String,
    private val onStopGeneration: () -> Unit,
    private val postStatus: (String) -> Unit
) {
    val conversations: StateFlow<List<Conversation>> = repository.conversations
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private var pendingConversation: Conversation? = null
    private var messageCollectionJob: Job? = null

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _pendingAttachment = MutableStateFlow<ChatAttachment?>(null)
    val pendingAttachment: StateFlow<ChatAttachment?> = _pendingAttachment.asStateFlow()

    private val documentIndex = DocumentIndex(application)
    private val documentSelectionPrefs = application.getSharedPreferences(
        "document_selection",
        Context.MODE_PRIVATE
    )

    private val _documentStats = MutableStateFlow(DocumentStats.EMPTY)
    val documentStats: StateFlow<DocumentStats> = _documentStats.asStateFlow()

    private val _activeDocumentIds = MutableStateFlow<Set<String>>(emptySet())
    val activeDocumentIds: StateFlow<Set<String>> = _activeDocumentIds.asStateFlow()

    private val _documentLibrary = MutableStateFlow<List<DocumentSummary>>(emptyList())
    val documentLibrary: StateFlow<List<DocumentSummary>> = _documentLibrary.asStateFlow()

    fun initialize() {
        scope.launch {
            _documentStats.value = documentIndex.stats()
            _documentLibrary.value = documentIndex.documents()
        }
        scope.launch {
            repository.pruneEmptyConversations()
            try {
                val upgraded = repository.upgradeLegacyEncryption()
                if (upgraded > 0) Log.i("ConversationController", "Upgraded $upgraded legacy encrypted rows")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("ConversationController", "Legacy encryption upgrade failed: ${e.message}")
            }
            repository.conversations.collect { list ->
                if (_currentConversationId.value == null) {
                    if (list.isEmpty()) createNewConversation()
                    else selectConversation(list.first().id)
                }
            }
        }
    }

    fun createNewConversation() {
        onStopGeneration()
        setCurrentToPending(newPendingConversation())
    }

    fun ensureConversationId(): String {
        _currentConversationId.value?.let { return it }
        val pending = newPendingConversation()
        setCurrentToPending(pending)
        return pending.id
    }

    fun capturePendingDraft(convId: String): Conversation? =
        pendingConversation?.takeIf { it.id == convId }

    fun clearPendingDraft(convId: String) {
        if (pendingConversation?.id == convId) pendingConversation = null
    }

    fun selectConversation(conversationId: String) {
        pendingConversation?.let { pending ->
            if (pending.id == conversationId) {
                if (_currentConversationId.value == conversationId && messageCollectionJob != null) return
                onStopGeneration()
                setCurrentToPending(pending)
                return
            }
            pendingConversation = null
        }
        if (_currentConversationId.value == conversationId && messageCollectionJob != null) return
        onStopGeneration()
        _currentConversationId.value = conversationId
        _activeDocumentIds.value = loadDocumentSelection(conversationId)
        messageCollectionJob?.cancel()
        messageCollectionJob = scope.launch {
            repository.getMessagesForConversation(conversationId).collect { msgList ->
                _messages.value = msgList
            }
        }
    }

    fun deleteConversation(id: String) {
        onStopGeneration()
        if (pendingConversation?.id == id) pendingConversation = null
        scope.launch {
            repository.deleteConversation(id)
            if (_currentConversationId.value == id) {
                val remaining = conversations.value.filter { it.id != id }
                if (remaining.isNotEmpty()) selectConversation(remaining.first().id)
                else createNewConversation()
            }
        }
    }

    fun clearAllHistory() {
        onStopGeneration()
        pendingConversation = null
        scope.launch {
            repository.clearAll()
            _messages.value = emptyList()
            _currentConversationId.value = null
            createNewConversation()
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        pendingConversation?.let {
            if (it.id == id) {
                pendingConversation = it.copy(title = newTitle)
                return
            }
        }
        scope.launch {
            repository.renameConversation(id, newTitle)
        }
    }

    fun setPendingAttachment(attachment: ChatAttachment?) {
        _pendingAttachment.value = attachment
    }

    fun consumePendingAttachment(): ChatAttachment? {
        val attachment = _pendingAttachment.value
        _pendingAttachment.value = null
        return attachment
    }

    suspend fun buildDocumentContext(query: String): String? =
        runCatching { documentIndex.buildContext(query, _activeDocumentIds.value) }.getOrNull()

    fun importDocument(uri: Uri, displayName: String) {
        scope.launch {
            postStatus(strings.get(R.string.vm_doc_indexing, displayName))
            val result = runCatching {
                documentIndex.ingest(uri, displayName, languagePreference())
            }
            _documentStats.value = documentIndex.stats()
            _documentLibrary.value = documentIndex.documents()
            result.onSuccess { ingested ->
                if (ingested.chunkCount > 0) enableDocumentForCurrentChat(ingested.documentId)
            }
            postStatus(
                result.fold(
                    onSuccess = { ingested ->
                        if (ingested.chunkCount == 0) {
                            strings.get(R.string.vm_doc_empty, displayName)
                        } else {
                            strings.get(R.string.vm_doc_indexed, ingested.chunkCount, displayName)
                        }
                    },
                    onFailure = { t ->
                        strings.get(R.string.vm_doc_failed, displayName, t.localizedMessage ?: t.message ?: "")
                    }
                )
            )
        }
    }

    fun toggleDocumentSelection(documentId: String) {
        val conversationId = _currentConversationId.value ?: ensureConversationId()
        val current = _activeDocumentIds.value
        val updated = if (documentId in current) current - documentId else current + documentId
        _activeDocumentIds.value = updated
        persistDocumentSelection(conversationId, updated)
    }

    fun deleteIndexedDocument(documentId: String) {
        val name = _documentLibrary.value.firstOrNull { it.id == documentId }?.name ?: "document"
        scope.launch {
            runCatching { documentIndex.deleteDocument(documentId) }
            pruneDocumentFromSelections(documentId)
            _documentLibrary.value = documentIndex.documents()
            _documentStats.value = documentIndex.stats()
            postStatus(strings.get(R.string.vm_doc_deleted, name))
        }
    }

    fun clearDocumentIndex() {
        scope.launch {
            runCatching { documentIndex.clear() }
            documentSelectionPrefs.edit().clear().apply()
            _activeDocumentIds.value = emptySet()
            _documentLibrary.value = emptyList()
            _documentStats.value = documentIndex.stats()
            postStatus(strings.get(R.string.vm_doc_cleared))
        }
    }

    private fun newPendingConversation(): Conversation = Conversation(
        id = UUID.randomUUID().toString(),
        title = strings.get(R.string.vm_conversation_default_title),
        modelId = activeModelId()
    )

    private fun setCurrentToPending(pending: Conversation) {
        pendingConversation = pending
        _currentConversationId.value = pending.id
        _activeDocumentIds.value = emptySet()
        messageCollectionJob?.cancel()
        _messages.value = emptyList()
        messageCollectionJob = scope.launch {
            repository.getMessagesForConversation(pending.id).collect { msgList ->
                _messages.value = msgList
            }
        }
    }

    private fun enableDocumentForCurrentChat(documentId: String) {
        val conversationId = _currentConversationId.value ?: return
        val updated = _activeDocumentIds.value + documentId
        _activeDocumentIds.value = updated
        persistDocumentSelection(conversationId, updated)
    }

    private fun pruneDocumentFromSelections(documentId: String) {
        documentSelectionPrefs.all.forEach { (key, value) ->
            if (key.startsWith(SELECTION_KEY_PREFIX) && value is Set<*>) {
                val ids = value.mapNotNull { it as? String }.toSet()
                if (documentId in ids) {
                    documentSelectionPrefs.edit().putStringSet(key, ids - documentId).apply()
                }
            }
        }
        _activeDocumentIds.value = _activeDocumentIds.value - documentId
    }

    private fun selectionKey(conversationId: String) = "$SELECTION_KEY_PREFIX$conversationId"

    private fun loadDocumentSelection(conversationId: String): Set<String> =
        documentSelectionPrefs.getStringSet(selectionKey(conversationId), emptySet())
            ?.toSet()
            .orEmpty()

    private fun persistDocumentSelection(conversationId: String, documentIds: Set<String>) {
        documentSelectionPrefs.edit().putStringSet(selectionKey(conversationId), documentIds).apply()
    }

    private companion object {
        const val SELECTION_KEY_PREFIX = "doc_selection_"
    }
}
