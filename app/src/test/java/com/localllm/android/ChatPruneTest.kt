package com.localllm.android

import androidx.room.Room
import com.localllm.android.data.local.ChatDatabase
import com.localllm.android.data.repository.ChatRepository
import com.localllm.android.model.ChatMessage
import com.localllm.android.model.Conversation
import com.localllm.android.model.MessageRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatPruneTest {

    private lateinit var db: ChatDatabase
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ChatRepository(db.chatDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `prune removes only conversations without messages`() = runBlocking {
        repo.saveConversation(Conversation(id = "a", title = "A", modelId = "", systemPrompt = ""))
        repo.saveConversation(Conversation(id = "b", title = "B", modelId = "", systemPrompt = ""))
        repo.saveMessage(ChatMessage(conversationId = "a", role = MessageRole.USER, content = "hi"))

        val removed = repo.pruneEmptyConversations()

        assertEquals(1, removed)
        assertNotNull(db.chatDao().getConversationById("a"))
        assertNull(db.chatDao().getConversationById("b"))
        Unit
    }

    @Test
    fun `prune on empty database removes nothing`() = runBlocking {
        assertEquals(0, repo.pruneEmptyConversations())
        Unit
    }
}
