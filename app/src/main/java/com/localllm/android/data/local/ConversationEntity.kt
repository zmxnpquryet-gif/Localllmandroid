package com.localllm.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey
    val id: String,
    val titleEncrypted: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String,
    val systemPromptEncrypted: String = ""
)
