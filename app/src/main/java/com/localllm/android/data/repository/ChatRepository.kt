package com.localllm.android.data.repository

import com.localllm.android.data.crypto.ChatCrypto
import com.localllm.android.data.local.ChatDao
import com.localllm.android.data.local.ConversationCiphertext
import com.localllm.android.data.local.MessageCiphertext
import com.localllm.android.data.local.ConversationEntity
import com.localllm.android.data.local.MessageEntity
import com.localllm.android.model.ChatAttachment
import com.localllm.android.model.ChatMessage
import com.localllm.android.model.Conversation
import com.localllm.android.model.MessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ChatRepository(private val chatDao: ChatDao) {

    /**
     * Serializes mutations so read-modify-write sequences issued from independent
     * UI actions (delete vs. draft persist vs. clear) cannot interleave.
     */
    private val writeMutex = Mutex()

    val conversations: Flow<List<Conversation>> = chatDao.getAllConversations()
        .map { list ->
            list.mapNotNull { entity ->
                decodeRow {
                    Conversation(
                        id = entity.id,
                        title = ChatCrypto.decrypt(entity.titleEncrypted, convAad(entity.id, "title")),
                        createdAt = entity.createdAt,
                        updatedAt = entity.updatedAt,
                        modelId = entity.modelId,
                        systemPrompt = ChatCrypto.decrypt(entity.systemPromptEncrypted, convAad(entity.id, "systemPrompt"))
                    )
                }
            }
        }.flowOn(Dispatchers.Default)

    fun getMessagesForConversation(conversationId: String): Flow<List<ChatMessage>> {
        return chatDao.getMessagesForConversation(conversationId)
            .map { list ->
                list.mapNotNull { entity ->
                    decodeRow {
                        val role = try {
                            MessageRole.valueOf(entity.role)
                        } catch (e: Exception) {
                            MessageRole.ASSISTANT
                        }
                        val attachment = if (!entity.attachedFilePath.isNullOrEmpty()) {
                            ChatAttachment(
                                uriString = entity.attachedFilePath,
                                mimeType = entity.attachedFileType ?: "application/octet-stream",
                                fileName = entity.attachedFilePath.substringAfterLast('/'),
                                isImage = entity.attachedFileType?.startsWith("image") == true
                            )
                        } else null

                        ChatMessage(
                            id = entity.id,
                            conversationId = entity.conversationId,
                            role = role,
                            content = ChatCrypto.decrypt(entity.contentEncrypted, msgAad(entity.id, entity.conversationId, "content")),
                            reasoning = entity.reasoningEncrypted?.let { ChatCrypto.decrypt(it, msgAad(entity.id, entity.conversationId, "reasoning")) },
                            timestamp = entity.timestamp,
                            tps = entity.tps,
                            promptSpeed = entity.promptSpeed,
                            contextTokens = entity.contextTokens,
                            attachment = attachment
                        )
                    }
                }
            }.flowOn(Dispatchers.Default)
    }

    suspend fun saveConversation(conversation: Conversation) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                chatDao.insertConversation(conversationEntity(conversation))
            }
        }
    }

    suspend fun saveMessage(message: ChatMessage) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val entity = messageEntity(message)
                insertMessageDroppingOrphans(entity)
            }
        }
    }

    /**
     * A racing conversation delete can leave the message without its parent row
     * (FOREIGN KEY failure). The user deleted that conversation anyway: drop the
     * write instead of letting a fire-and-forget save crash the app. Only that
     * constraint case is swallowed — any other persistence failure propagates.
     */
    private suspend fun insertMessageDroppingOrphans(entity: MessageEntity) {
        try {
            chatDao.insertMessageAndUpdateConversationTimestamp(entity, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isForeignKeyViolation(e)) throw e
            android.util.Log.w("ChatRepository", "saveMessage dropped (parent conversation gone): ${e.message}")
        }
    }

    /**
     * Only FOREIGN KEY failures (the benign parent-row-delete race) are dropped;
     * other constraint kinds (NOT NULL, UNIQUE) are bugs and propagate. Walks the
     * cause chain: Room/coroutine boundaries may wrap the SQLite exception.
     */
    private fun isForeignKeyViolation(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val name = t.javaClass.name
            if (t is android.database.sqlite.SQLiteConstraintException || name.endsWith("SQLiteConstraintException")) {
                return t.message?.contains("FOREIGN KEY", ignoreCase = true) == true
            }
            t = t.cause
        }
        return false
    }

    /**
     * Saves [message] and, when [draft] is non-null, its still-in-memory parent
     * conversation in one transaction. Callers capture the draft synchronously at
     * send time: whatever the user taps next (another conversation, delete), the
     * message always lands with its parent row present.
     */
    suspend fun saveConversationAndMessage(draft: Conversation?, message: ChatMessage) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val entity = messageEntity(message)
                if (draft != null) {
                    try {
                        chatDao.insertConversationAndMessage(
                            conversationEntity(draft),
                            entity,
                            System.currentTimeMillis()
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!isForeignKeyViolation(e)) throw e
                        android.util.Log.w("ChatRepository", "saveConversationAndMessage dropped (parent gone): ${e.message}")
                    }
                } else {
                    insertMessageDroppingOrphans(entity)
                }
            }
        }
    }

    private fun conversationEntity(conversation: Conversation) = ConversationEntity(
        id = conversation.id,
        titleEncrypted = ChatCrypto.encrypt(conversation.title, convAad(conversation.id, "title")),
        createdAt = conversation.createdAt,
        updatedAt = conversation.updatedAt,
        modelId = conversation.modelId,
        systemPromptEncrypted = ChatCrypto.encrypt(conversation.systemPrompt, convAad(conversation.id, "systemPrompt"))
    )

    private fun messageEntity(message: ChatMessage) = MessageEntity(
        id = message.id,
        conversationId = message.conversationId,
        role = message.role.name,
        contentEncrypted = ChatCrypto.encrypt(message.content, msgAad(message.id, message.conversationId, "content")),
        reasoningEncrypted = message.reasoning?.let { ChatCrypto.encrypt(it, msgAad(message.id, message.conversationId, "reasoning")) },
        timestamp = message.timestamp,
        tps = message.tps,
        promptSpeed = message.promptSpeed,
        contextTokens = message.contextTokens,
        attachedFilePath = message.attachment?.uriString,
        attachedFileType = message.attachment?.mimeType
    )

    suspend fun deleteConversation(id: String) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                chatDao.deleteMessagesForConversation(id)
                chatDao.deleteConversationById(id)
            }
        }
    }

    suspend fun renameConversation(id: String, newTitle: String) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                chatDao.updateConversationTitle(id, ChatCrypto.encrypt(newTitle, convAad(id, "title")), System.currentTimeMillis())
            }
        }
    }

    suspend fun clearAll() {
        writeMutex.withLock {
            withContext(Dispatchers.IO) { chatDao.clearAllConversations() }
        }
    }

    /**
     * Re-encrypts rows written before AAD binding (no `v2:` prefix) so they are bound to
     * their own row/column. Idempotent: rows already in the v2 format are skipped, and a
     * row that cannot be decrypted is left untouched rather than destroyed. Updates in
     * place, so message rows are never cascade-deleted. Returns the number of rows upgraded.
     *
     * Two-phase: every new ciphertext is computed first, and only then are they all
     * written through a single DAO transaction. A failure during computation writes
     * nothing; a failure during the write rolls everything back. Either way the next
     * launch retries the whole migration instead of continuing from a half state.
     */
    suspend fun upgradeLegacyEncryption(): Int = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val newConversations = mutableListOf<ConversationCiphertext>()
            val newMessages = mutableListOf<MessageCiphertext>()
            var skipped = 0
            for (c in chatDao.getAllConversationsOnce()) {
                if (!ChatCrypto.isLegacyFormat(c.titleEncrypted) && !ChatCrypto.isLegacyFormat(c.systemPromptEncrypted)) continue
                try {
                    val title = ChatCrypto.decrypt(c.titleEncrypted, convAad(c.id, "title"))
                    val prompt = ChatCrypto.decrypt(c.systemPromptEncrypted, convAad(c.id, "systemPrompt"))
                    newConversations.add(
                        ConversationCiphertext(
                            c.id,
                            ChatCrypto.encrypt(title, convAad(c.id, "title")),
                            ChatCrypto.encrypt(prompt, convAad(c.id, "systemPrompt"))
                        )
                    )
                } catch (_: SecurityException) {
                    // Unreadable row (key lost or tampered): keep it as is.
                    skipped++
                }
            }
            for (m in chatDao.getAllMessagesOnce()) {
                val reasoningLegacy = ChatCrypto.isLegacyFormat(m.reasoningEncrypted)
                if (!ChatCrypto.isLegacyFormat(m.contentEncrypted) && !reasoningLegacy) continue
                try {
                    val content = ChatCrypto.decrypt(m.contentEncrypted, msgAad(m.id, m.conversationId, "content"))
                    val reasoning = m.reasoningEncrypted?.let {
                        ChatCrypto.decrypt(it, msgAad(m.id, m.conversationId, "reasoning"))
                    }
                    newMessages.add(
                        MessageCiphertext(
                            m.id,
                            ChatCrypto.encrypt(content, msgAad(m.id, m.conversationId, "content")),
                            reasoning?.let { ChatCrypto.encrypt(it, msgAad(m.id, m.conversationId, "reasoning")) }
                        )
                    )
                } catch (_: SecurityException) {
                    // Unreadable row (key lost or tampered): keep it as is.
                    skipped++
                }
            }
            if (skipped > 0) {
                android.util.Log.w(
                    "ChatRepository",
                    "LEGACY_UPGRADE_SKIPPED: $skipped unreadable rows left untouched " +
                        "(Keystore key lost after a restore, or tampering). " +
                        "They stay invisible until the data is reset."
                )
            }
            chatDao.applyCiphertextUpgrade(newConversations, newMessages)
            newConversations.size + newMessages.size
        }
    }

    private fun convAad(conversationId: String, column: String) = "conversation:$conversationId:$column"

    private fun msgAad(messageId: String, conversationId: String, column: String) =
        "message:$messageId:$conversationId:$column"

    /** Deletes conversations that never received a message. Returns removed count. */
    suspend fun pruneEmptyConversations(): Int = writeMutex.withLock {
        withContext(Dispatchers.IO) { chatDao.deleteEmptyConversations() }
    }

    /**
     * A single corrupt or undecryptable row must not kill the whole Flow: the row is
     * skipped instead. Cancellation is never swallowed.
     */
    private inline fun <T> decodeRow(block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
}
