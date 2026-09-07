package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey
    val id: String,
    val conversationId: String,
    val role: String, // "user", "assistant", "system"
    val contentEncrypted: String,
    val reasoningEncrypted: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val tps: Float = 0f,
    val promptSpeed: Float = 0f,
    val contextTokens: Int = 0,
    val attachedFilePath: String? = null,
    val attachedFileType: String? = null // "image", "file"
)
