package com.localllm.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile
        private var INSTANCE: ChatDatabase? = null

        fun getInstance(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "local_llm_encrypted_chat.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                // Explicit: schema is version 1 with no migrations yet. If the schema
                // ever bumps, old encrypted rows (unreadable under a new schema) are
                // dropped rather than crashing launch. Revisit with a real Migration
                // once exportSchema/versioning is introduced.
                INSTANCE = instance
                instance
            }
        }
    }
}
