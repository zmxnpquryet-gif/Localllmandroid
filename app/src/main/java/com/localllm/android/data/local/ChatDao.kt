package com.localllm.android.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversationById(id: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :conversationId")
    suspend fun updateConversationTimestamp(conversationId: String, updatedAt: Long)

    @Transaction
    suspend fun insertMessageAndUpdateConversationTimestamp(message: MessageEntity, updatedAt: Long) {
        insertMessage(message)
        updateConversationTimestamp(message.conversationId, updatedAt)
    }

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    @Query("DELETE FROM conversations")
    suspend fun clearAllConversations()

    /** Atomic rename: avoids the load-then-update read-modify-write race. */
    @Query("UPDATE conversations SET titleEncrypted = :titleEncrypted, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversationTitle(id: String, titleEncrypted: String, updatedAt: Long)

    /**
     * Deletes conversations that hold zero messages in a single statement, so no
     * read-then-delete TOCTOU window exists between selecting and deleting.
     * Returns the number of deleted rows.
     */
    @Query("DELETE FROM conversations WHERE id NOT IN (SELECT DISTINCT conversationId FROM messages)")
    suspend fun deleteEmptyConversations(): Int
}
