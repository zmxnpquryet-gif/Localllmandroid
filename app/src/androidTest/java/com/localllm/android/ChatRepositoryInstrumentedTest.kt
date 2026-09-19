package com.localllm.android

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localllm.android.data.local.ChatDatabase
import com.localllm.android.data.local.ConversationEntity
import com.localllm.android.data.local.MessageEntity
import com.localllm.android.data.repository.ChatRepository
import com.localllm.android.model.ChatMessage
import com.localllm.android.model.Conversation
import com.localllm.android.model.MessageRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof that conversations are stored encrypted with the real Keystore and
 * that a corrupt row is skipped instead of crashing the whole Flow.
 */
@RunWith(AndroidJUnit4::class)
class ChatRepositoryInstrumentedTest {

    private lateinit var db: ChatDatabase
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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
    fun conversationAndMessageSurviveACryptoRoundTrip() = runBlocking {
        repo.saveConversation(
            Conversation(id = "c1", title = "비밀 대화", modelId = "m1", systemPrompt = "시스템")
        )
        repo.saveMessage(
            ChatMessage(conversationId = "c1", role = MessageRole.USER, content = "안녕 로컬 LLM")
        )

        val conversations = repo.conversations.first()
        assertEquals(1, conversations.size)
        assertEquals("비밀 대화", conversations.first().title)
        assertEquals("시스템", conversations.first().systemPrompt)

        val messages = repo.getMessagesForConversation("c1").first()
        assertEquals(1, messages.size)
        assertEquals("안녕 로컬 LLM", messages.first().content)
    }

    @Test
    fun storedCiphertextIsNotPlaintext() = runBlocking {
        repo.saveConversation(Conversation(id = "c1", title = "평문이면 안 됨", modelId = "", systemPrompt = ""))
        val raw = db.chatDao().getConversationById("c1")
        assertTrue(raw != null && raw.titleEncrypted.isNotBlank())
        assertTrue(
            "title must be encrypted at rest",
            raw!!.titleEncrypted != "평문이면 안 됨"
        )
    }

    @Test
    fun corruptConversationRowIsSkippedNotFatal() = runBlocking {
        repo.saveConversation(Conversation(id = "good", title = "정상", modelId = "", systemPrompt = ""))
        // A row whose ciphertext cannot possibly decrypt must not kill the Flow.
        db.chatDao().insertConversation(
            ConversationEntity(
                id = "bad",
                titleEncrypted = "!!!!not-base64!!!!",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                modelId = ""
            )
        )

        val visible = repo.conversations.first()
        assertEquals(1, visible.size)
        assertEquals("good", visible.first().id)
    }

    @Test
    fun corruptMessageRowIsSkippedNotFatal() = runBlocking {
        repo.saveConversation(Conversation(id = "c1", title = "t", modelId = "", systemPrompt = ""))
        repo.saveMessage(ChatMessage(conversationId = "c1", role = MessageRole.USER, content = "정상 메시지"))
        db.chatDao().insertMessage(
            MessageEntity(id = "bad", conversationId = "c1", role = "user", contentEncrypted = "!!!!broken!!!!")
        )

        val messages = repo.getMessagesForConversation("c1").first()
        assertEquals(1, messages.size)
        assertEquals("정상 메시지", messages.first().content)
    }

    @Test
    fun atomicPruneRemovesOnlyEmptyConversations() = runBlocking {
        repo.saveConversation(Conversation(id = "a", title = "A", modelId = "", systemPrompt = ""))
        repo.saveConversation(Conversation(id = "b", title = "B", modelId = "", systemPrompt = ""))
        repo.saveMessage(ChatMessage(conversationId = "a", role = MessageRole.USER, content = "hi"))

        assertEquals(1, repo.pruneEmptyConversations())
        assertTrue(db.chatDao().getConversationById("a") != null)
        assertNull(db.chatDao().getConversationById("b"))
    }
}
