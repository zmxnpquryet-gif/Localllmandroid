package com.example.data.repository

import com.example.data.crypto.ChatCrypto
import com.example.data.local.ChatDao
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.model.ChatAttachment
import com.example.model.ChatMessage
import com.example.model.Conversation
import com.example.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ChatRepository(private val chatDao: ChatDao) {

    val conversations: Flow<List<Conversation>> = chatDao.getAllConversations()
        .map { list ->
            list.map { entity ->
                Conversation(
                    id = entity.id,
                    title = ChatCrypto.decrypt(entity.titleEncrypted).ifBlank { "대화" },
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
                    modelId = entity.modelId,
                    systemPrompt = ChatCrypto.decrypt(entity.systemPromptEncrypted)
                )
            }
        }.flowOn(Dispatchers.Default)

    fun getMessagesForConversation(conversationId: String): Flow<List<ChatMessage>> {
        return chatDao.getMessagesForConversation(conversationId)
            .map { list ->
                list.map { entity ->
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
            }.flowOn(Dispatchers.Default)
    }

    suspend fun saveConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
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

    suspend fun saveMessage(message: ChatMessage) = withContext(Dispatchers.IO) {
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
        chatDao.insertMessage(entity)

        // Update conversation timestamp
        val conv = chatDao.getConversationById(message.conversationId)
        if (conv != null) {
            chatDao.updateConversation(conv.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun deleteConversation(id: String) = withContext(Dispatchers.IO) {
        chatDao.deleteMessagesForConversation(id)
        chatDao.deleteConversationById(id)
    }

    suspend fun renameConversation(id: String, newTitle: String) = withContext(Dispatchers.IO) {
        val conv = chatDao.getConversationById(id)
        if (conv != null) {
            chatDao.updateConversation(
                conv.copy(
                    titleEncrypted = ChatCrypto.encrypt(newTitle),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        chatDao.clearAllConversations()
    }
}
