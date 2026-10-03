package com.localllm.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** New ciphertext for one conversation row (computed before any write happens). */
data class ConversationCiphertext(
    val id: String,
    val title: String,
    val systemPrompt: String
)

/** New ciphertext for one message row (computed before any write happens). */
data class MessageCiphertext(
    val id: String,
    val content: String,
    val reasoning: String?
)

@Dao
abstract class ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    abstract fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    abstract suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    abstract suspend fun deleteConversationById(id: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    abstract fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :conversationId")
    abstract suspend fun updateConversationTimestamp(conversationId: String, updatedAt: Long)

    @Transaction
    open suspend fun insertMessageAndUpdateConversationTimestamp(message: MessageEntity, updatedAt: Long) {
        insertMessage(message)
        updateConversationTimestamp(message.conversationId, updatedAt)
    }

    /**
     * Persists a draft conversation together with its first message atomically, so a
     * conversation switch or delete racing the first send can never leave a message
     * without its parent row (FOREIGN KEY failure).
     */
    @Transaction
    open suspend fun insertConversationAndMessage(
        conversation: ConversationEntity,
        message: MessageEntity,
        updatedAt: Long
    ) {
        insertConversation(conversation)
        insertMessage(message)
        updateConversationTimestamp(message.conversationId, updatedAt)
    }

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    abstract suspend fun deleteMessagesForConversation(conversationId: String)

    @Query("DELETE FROM conversations")
    abstract suspend fun clearAllConversations()

    /** Atomic rename: avoids the load-then-update read-modify-write race. */
    @Query("UPDATE conversations SET titleEncrypted = :titleEncrypted, updatedAt = :updatedAt WHERE id = :id")
    abstract suspend fun updateConversationTitle(id: String, titleEncrypted: String, updatedAt: Long)

    /**
     * Deletes conversations that hold zero messages in a single statement, so no
     * read-then-delete TOCTOU window exists between selecting and deleting.
     * Returns the number of deleted rows.
     */
    @Query("DELETE FROM conversations WHERE id NOT IN (SELECT DISTINCT conversationId FROM messages)")
    abstract suspend fun deleteEmptyConversations(): Int

    // ---- one-shot reads + in-place updates for upgrading legacy (pre-AAD) rows ----
    // UPDATE, never REPLACE: replacing a conversation row cascades into its messages.

    @Query("SELECT * FROM conversations")
    abstract suspend fun getAllConversationsOnce(): List<ConversationEntity>

    @Query("SELECT * FROM messages")
    abstract suspend fun getAllMessagesOnce(): List<MessageEntity>

    @Query("UPDATE conversations SET titleEncrypted = :title, systemPromptEncrypted = :systemPrompt WHERE id = :id")
    abstract suspend fun updateConversationCiphertext(id: String, title: String, systemPrompt: String)

    @Query("UPDATE messages SET contentEncrypted = :content, reasoningEncrypted = :reasoning WHERE id = :id")
    abstract suspend fun updateMessageCiphertext(id: String, content: String, reasoning: String?)

    /**
     * Applies a pre-computed re-encryption as ONE transaction. Callers compute every
     * new ciphertext first and only then call this, so a failure during computation
     * writes nothing, and a failure during the write rolls everything back. The next
     * launch retries the whole migration instead of continuing from a half state.
     */
    @Transaction
    open suspend fun applyCiphertextUpgrade(
        conversations: List<ConversationCiphertext>,
        messages: List<MessageCiphertext>
    ) {
        conversations.forEach { updateConversationCiphertext(it.id, it.title, it.systemPrompt) }
        messages.forEach { updateMessageCiphertext(it.id, it.content, it.reasoning) }
    }
}
