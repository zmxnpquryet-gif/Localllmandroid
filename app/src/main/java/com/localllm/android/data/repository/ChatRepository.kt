package com.localllm.android.data.repository

import com.localllm.android.data.crypto.ChatCrypto
import com.localllm.android.data.local.ChatDao
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
                        title = ChatCrypto.decrypt(entity.titleEncrypted),
                        createdAt = entity.createdAt,
                        updatedAt = entity.updatedAt,
                        modelId = entity.modelId,
                        systemPrompt = ChatCrypto.decrypt(entity.systemPromptEncrypted)
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
                            content = ChatCrypto.decrypt(entity.contentEncrypted),
                            reasoning = entity.reasoningEncrypted?.let { ChatCrypto.decrypt(it) },
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
                val entity = ConversationEntity(
                    id = conversation.id,
                    titleEncrypted = ChatCrypto.encrypt(conversation.title),
                    createdAt = conversation.createdAt,
                    updatedAt = conversation.updatedAt,
                    modelId = conversation.modelId,
                    systemPromptEncrypted = ChatCrypto.encrypt(conversation.systemPrompt)
                )
                chatDao.insertConversation(entity)
            }
        }
    }

    suspend fun saveMessage(message: ChatMessage) {
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                val entity = MessageEntity(
                    id = message.id,
                    conversationId = message.conversationId,
                    role = message.role.name,
                    contentEncrypted = ChatCrypto.encrypt(message.content),
                    reasoningEncrypted = message.reasoning?.let { ChatCrypto.encrypt(it) },
                    timestamp = message.timestamp,
                    tps = message.tps,
                    promptSpeed = message.promptSpeed,
                    contextTokens = message.contextTokens,
                    attachedFilePath = message.attachment?.uriString,
                    attachedFileType = message.attachment?.mimeType
                )
                chatDao.insertMessageAndUpdateConversationTimestamp(entity, System.currentTimeMillis())
            }
        }
    }

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
                chatDao.updateConversationTitle(id, ChatCrypto.encrypt(newTitle), System.currentTimeMillis())
            }
        }
    }

    suspend fun clearAll() {
        writeMutex.withLock {
            withContext(Dispatchers.IO) { chatDao.clearAllConversations() }
        }
    }

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
