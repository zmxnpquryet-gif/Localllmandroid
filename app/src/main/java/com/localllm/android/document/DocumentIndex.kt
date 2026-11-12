package com.localllm.android.document

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * End-to-end document RAG: extract, chunk, embed, persist, retrieve.
 *
 * Retrieval is automatic: [buildContext] ranks every indexed chunk against the
 * user's message and returns the best excerpts, which the chat prompt includes as
 * untrusted reference data. There is no vector database; on-device corpora are small
 * enough that a linear cosine scan is instant and avoids another dependency.
 */
class DocumentIndex(context: Context) {

    private val extractor = DocumentTextExtractor(context.applicationContext)
    private val store = DocumentStore(context.applicationContext)
    private val embedder = HashedTextEmbedder()

    data class IngestResult(
        val documentName: String,
        val chunkCount: Int,
        val documentId: String
    )

    suspend fun ingest(
        uri: Uri,
        displayName: String,
        languagePreference: String = "system"
    ): IngestResult = withContext(Dispatchers.IO) {
        val text = extractor.extract(uri, displayName, languagePreference)
        val chunks = DocumentChunker.chunk(text)
        val docId = documentId(uri, displayName)
        if (chunks.isEmpty()) return@withContext IngestResult(displayName, 0, docId)

        store.replaceDocument(
            docId = docId,
            docName = displayName,
            chunks = chunks.map { DocumentStore.ChunkInput(it, embedder.embed(it)) }
        )
        IngestResult(displayName, chunks.size, docId)
    }

    /**
     * Returns a prompt-ready block of the most relevant excerpts, or null when the
     * index is empty or nothing clears the relevance floor. The floor keeps a
     * generic message from dragging unrelated document text into every prompt.
     */
    suspend fun buildContext(
        query: String,
        documentIds: Set<String>,
        maxChunks: Int = MAX_CONTEXT_CHUNKS,
        maxChars: Int = MAX_CONTEXT_CHARS
    ): String? = withContext(Dispatchers.IO) {
        // A new conversation has no selection: indexed files are reused only when the
        // user adds them from the library, exactly like a fresh attachment.
        if (query.isBlank() || documentIds.isEmpty()) return@withContext null
        val stored = store.readForSearch().filter { it.docId in documentIds }
        if (stored.isEmpty()) return@withContext null

        val queryVector = embedder.embed(query)
        if (queryVector.all { it == 0f }) return@withContext null

        val ranked = stored
            .map { chunk -> chunk to embedder.similarity(queryVector, chunk.embedding) }
            .filter { (_, score) -> score >= MIN_SIMILARITY }
            .sortedByDescending { (_, score) -> score }

        if (ranked.isEmpty()) return@withContext null

        val builder = StringBuilder()
        var used = 0
        var included = 0
        for ((chunk, _) in ranked) {
            if (included >= maxChunks) break
            val text = store.decryptText(chunk) ?: continue
            if (included > 0 && used + text.length > maxChars) break
            builder.append('[').append(included + 1).append("] ")
                .append(store.decryptName(chunk))
                .append(" (").append(chunk.ordinal + 1).append('/').append(chunk.total).append(")\n")
                .append(text.trim()).append("\n\n")
            used += text.length
            included++
        }
        if (builder.isEmpty()) null else builder.toString().trim()
    }

    suspend fun documents(): List<DocumentSummary> = withContext(Dispatchers.IO) {
        store.listDocuments()
    }

    suspend fun stats(): DocumentStats = withContext(Dispatchers.IO) { store.stats() }

    suspend fun deleteDocument(documentId: String) = withContext(Dispatchers.IO) {
        store.deleteDocument(documentId)
    }

    suspend fun clear() = withContext(Dispatchers.IO) { store.clear() }

    private fun documentId(uri: Uri, displayName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((uri.toString() + "|" + displayName).toByteArray())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val MAX_CONTEXT_CHUNKS = 4
        private const val MAX_CONTEXT_CHARS = 4000
        private const val MIN_SIMILARITY = 0.08f
    }
}
