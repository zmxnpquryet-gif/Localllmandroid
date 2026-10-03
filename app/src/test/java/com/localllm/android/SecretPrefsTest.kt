package com.localllm.android

import android.content.Context
import androidx.room.Room
import com.localllm.android.data.crypto.ChatCrypto
import com.localllm.android.data.crypto.SecretPrefs
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretPrefsTest {

    private lateinit var db: ChatDatabase
    private lateinit var repo: ChatRepository

    @Before
    fun setUp() {
        TestCrypto.install()
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ChatRepository(db.chatDao())
    }

    @After
    fun tearDown() {
        db.close()
        TestCrypto.uninstall()
    }

    private fun prefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("secret_prefs_test", Context.MODE_PRIVATE)
        .also { it.edit().clear().apply() }

    // ---------------- SecretPrefs ----------------

    @Test
    fun `secret round trips and only ciphertext is stored`() {
        val prefs = prefs()
        val secrets = SecretPrefs(prefs)

        assertTrue(secrets.put(SecretPrefs.HF_TOKEN, "hf_abcdef123456"))
        assertEquals("hf_abcdef123456", secrets.get(SecretPrefs.HF_TOKEN))

        assertNull(prefs.getString("hf_token", null))
        val stored = prefs.getString("hf_token_enc", null)
        assertNotNull(stored)
        assertFalse(stored!!.contains("hf_abcdef123456"))
    }

    @Test
    fun `plaintext token from an older build is migrated and removed`() {
        val prefs = prefs()
        prefs.edit().putString("hf_token", "hf_legacyplain").apply()

        assertEquals("hf_legacyplain", SecretPrefs(prefs).get(SecretPrefs.HF_TOKEN))

        assertNull("plaintext copy must be gone", prefs.getString("hf_token", null))
        assertNotNull(prefs.getString("hf_token_enc", null))
        assertEquals("hf_legacyplain", SecretPrefs(prefs).get(SecretPrefs.HF_TOKEN))
    }

    @Test
    fun `a secret copied under another name does not decrypt`() {
        val prefs = prefs()
        val secrets = SecretPrefs(prefs)
        secrets.put(SecretPrefs.API_SERVER_KEY, "sk-local-secret")
        // An attacker with prefs access moves the API key ciphertext into the HF token slot.
        prefs.edit().putString("hf_token_enc", prefs.getString("api_server_key_enc", null)).apply()

        assertNull(secrets.get(SecretPrefs.HF_TOKEN))
        assertEquals("sk-local-secret", secrets.get(SecretPrefs.API_SERVER_KEY))
    }

    @Test
    fun `undecryptable secret reads as absent and the broken entry is dropped`() {
        val prefs = prefs()
        prefs.edit().putString("hf_token_enc", "v2:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").apply()
        val secrets = SecretPrefs(prefs)

        assertNull(secrets.get(SecretPrefs.HF_TOKEN))
        assertNull(prefs.getString("hf_token_enc", null))
    }

    @Test
    fun `broken ciphertext with a surviving plaintext falls back and re-encrypts atomically`() {
        val prefs = prefs()
        // Crashed migration: both slots populated, then the ciphertext broke (key loss).
        prefs.edit()
            .putString("hf_token", "hf_coexisting")
            .putString("hf_token_enc", "v2:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
            .apply()

        assertEquals("hf_coexisting", SecretPrefs(prefs).get(SecretPrefs.HF_TOKEN))

        // One commit wrote the fresh ciphertext and removed the plaintext together.
        assertNull("plaintext must be gone after recovery", prefs.getString("hf_token", null))
        val recovered = prefs.getString("hf_token_enc", null)
        assertNotNull(recovered)
        assertEquals("hf_coexisting", SecretPrefs(prefs).get(SecretPrefs.HF_TOKEN))
    }

    @Test
    fun `stale plaintext left by a crashed migration is cleaned on a good read`() {
        val prefs = prefs()
        val secrets = SecretPrefs(prefs)
        secrets.put(SecretPrefs.HF_TOKEN, "hf_fresh")
        // Simulate the crash window: plaintext reappears next to a valid ciphertext.
        prefs.edit().putString("hf_token", "hf_stale").apply()

        assertEquals("hf_fresh", secrets.get(SecretPrefs.HF_TOKEN))
        assertNull("stale plaintext must not linger", prefs.getString("hf_token", null))
    }

    @Test
    fun `blank value clears the secret`() {
        val prefs = prefs()
        val secrets = SecretPrefs(prefs)
        secrets.put(SecretPrefs.HF_TOKEN, "hf_x")
        secrets.put(SecretPrefs.HF_TOKEN, "  ")

        assertNull(secrets.get(SecretPrefs.HF_TOKEN))
        assertNull(prefs.getString("hf_token_enc", null))
    }

    // ---------------- Repository AAD + legacy upgrade ----------------

    @Test
    fun `repository rows round trip with row bound encryption`() = runBlocking {
        repo.saveConversation(Conversation(id = "c1", title = "제목", modelId = "", systemPrompt = "sys"))
        repo.saveMessage(ChatMessage(id = "m1", conversationId = "c1", role = MessageRole.USER, content = "안녕", reasoning = "생각"))

        assertEquals("제목", repo.conversations.first().single().title)
        val msg = repo.getMessagesForConversation("c1").first().single()
        assertEquals("안녕", msg.content)
        assertEquals("생각", msg.reasoning)
        Unit
    }

    @Test
    fun `message ciphertext swapped between rows is skipped, not shown`() = runBlocking {
        repo.saveConversation(Conversation(id = "c1", title = "t", modelId = "", systemPrompt = ""))
        repo.saveMessage(ChatMessage(id = "m1", conversationId = "c1", role = MessageRole.USER, content = "first"))
        repo.saveMessage(ChatMessage(id = "m2", conversationId = "c1", role = MessageRole.USER, content = "second"))

        val rows = db.chatDao().getAllMessagesOnce().associateBy { it.id }
        // Put m2's ciphertext into m1's row.
        db.chatDao().updateMessageCiphertext("m1", rows.getValue("m2").contentEncrypted, null)

        val visible = repo.getMessagesForConversation("c1").first()
        assertEquals(listOf("second"), visible.map { it.content })
        Unit
    }

    @Test
    fun `legacy rows are upgraded in place without losing messages`() = runBlocking {
        // Rows as an older build wrote them: Base64 body, no v2 prefix, no AAD.
        fun legacy(plain: String) = TestCrypto.legacyPayload(plain)
        db.chatDao().insertConversation(
            ConversationEntity(
                id = "old", titleEncrypted = legacy("옛 대화"), createdAt = 1, updatedAt = 1,
                modelId = "", systemPromptEncrypted = legacy("old system")
            )
        )
        db.chatDao().insertMessage(
            MessageEntity(
                id = "om1", conversationId = "old", role = "USER",
                contentEncrypted = legacy("옛 메시지"), reasoningEncrypted = legacy("옛 추론")
            )
        )
        assertTrue(ChatCrypto.isLegacyFormat(db.chatDao().getAllMessagesOnce().single().contentEncrypted))

        val upgraded = repo.upgradeLegacyEncryption()

        assertEquals(2, upgraded)
        val msgRow = db.chatDao().getAllMessagesOnce().single()
        assertFalse(ChatCrypto.isLegacyFormat(msgRow.contentEncrypted))
        assertFalse(ChatCrypto.isLegacyFormat(msgRow.reasoningEncrypted))
        assertFalse(ChatCrypto.isLegacyFormat(db.chatDao().getAllConversationsOnce().single().titleEncrypted))

        // Still readable through the normal path, and the message survived the upgrade.
        assertEquals("옛 대화", repo.conversations.first().single().title)
        val msg = repo.getMessagesForConversation("old").first().single()
        assertEquals("옛 메시지", msg.content)
        assertEquals("옛 추론", msg.reasoning)

        // Idempotent.
        assertEquals(0, repo.upgradeLegacyEncryption())
        Unit
    }

    /** Forwards everything to [delegate] unless overridden: failure injection for DAO tests. */
    private open inner class ForwardingDao(
        private val delegate: com.localllm.android.data.local.ChatDao
    ) : com.localllm.android.data.local.ChatDao() {
        override fun getAllConversations() = delegate.getAllConversations()
        override suspend fun getConversationById(id: String) = delegate.getConversationById(id)
        override suspend fun insertConversation(conversation: ConversationEntity) =
            delegate.insertConversation(conversation)
        override suspend fun deleteConversationById(id: String) = delegate.deleteConversationById(id)
        override fun getMessagesForConversation(conversationId: String) =
            delegate.getMessagesForConversation(conversationId)
        override suspend fun insertMessage(message: MessageEntity) = delegate.insertMessage(message)
        override suspend fun updateConversationTimestamp(conversationId: String, updatedAt: Long) =
            delegate.updateConversationTimestamp(conversationId, updatedAt)
        override suspend fun deleteMessagesForConversation(conversationId: String) =
            delegate.deleteMessagesForConversation(conversationId)
        override suspend fun clearAllConversations() = delegate.clearAllConversations()
        override suspend fun updateConversationTitle(id: String, titleEncrypted: String, updatedAt: Long) =
            delegate.updateConversationTitle(id, titleEncrypted, updatedAt)
        override suspend fun deleteEmptyConversations(): Int = delegate.deleteEmptyConversations()
        override suspend fun getAllConversationsOnce() = delegate.getAllConversationsOnce()
        override suspend fun getAllMessagesOnce() = delegate.getAllMessagesOnce()
        override suspend fun updateConversationCiphertext(id: String, title: String, systemPrompt: String) =
            delegate.updateConversationCiphertext(id, title, systemPrompt)
        override suspend fun updateMessageCiphertext(id: String, content: String, reasoning: String?) =
            delegate.updateMessageCiphertext(id, content, reasoning)
    }

    @Test
    fun `mid-migration failure writes nothing and a retry completes`() = runBlocking {
        fun legacy(plain: String) = TestCrypto.legacyPayload(plain)
        db.chatDao().insertConversation(
            ConversationEntity(
                id = "old", titleEncrypted = legacy("t"), createdAt = 1, updatedAt = 1,
                modelId = "", systemPromptEncrypted = legacy("s")
            )
        )
        db.chatDao().insertMessage(
            MessageEntity(
                id = "om1", conversationId = "old", role = "USER",
                contentEncrypted = legacy("c"), reasoningEncrypted = null
            )
        )

        // Fail once during the read phase with a non-SecurityException (passes through).
        var failReads = true
        val flaky = object : ForwardingDao(db.chatDao()) {
            override suspend fun getAllMessagesOnce(): List<MessageEntity> {
                if (failReads) {
                    failReads = false
                    throw java.io.IOException("injected mid-migration failure")
                }
                return super.getAllMessagesOnce()
            }
        }
        try {
            ChatRepository(flaky).upgradeLegacyEncryption()
            org.junit.Assert.fail("mid-migration failure must propagate")
        } catch (_: java.io.IOException) {
            // Expected.
        }

        // Compute-then-write: the failed run wrote nothing, and a retry finishes it all.
        assertTrue(ChatCrypto.isLegacyFormat(db.chatDao().getAllConversationsOnce().single().titleEncrypted))
        assertTrue(ChatCrypto.isLegacyFormat(db.chatDao().getAllMessagesOnce().single().contentEncrypted))
        assertEquals(2, repo.upgradeLegacyEncryption())
        Unit
    }

    @Test
    fun `unreadable legacy rows are left untouched by the upgrade`() = runBlocking {
        db.chatDao().insertConversation(
            ConversationEntity(
                id = "bad", titleEncrypted = "bm90LXJlYWxseS1jaXBoZXJ0ZXh0LW5vdC1yZWFsbHk=", createdAt = 1, updatedAt = 1,
                modelId = "", systemPromptEncrypted = ""
            )
        )
        val before = db.chatDao().getAllConversationsOnce().single()

        assertEquals(0, repo.upgradeLegacyEncryption())
        assertEquals(before, db.chatDao().getAllConversationsOnce().single())
        Unit
    }
}
