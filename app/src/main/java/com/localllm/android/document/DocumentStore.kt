package com.localllm.android.document

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.localllm.android.data.crypto.ChatCrypto
import java.nio.ByteBuffer

/**
 * Storage for the document index, kept in its own database file.
 *
 * It is separate from the Room chat database on purpose: adding a table there would
 * bump its schema version, and the app's destructive-migration fallback would wipe
 * the user's conversations. Chunk text is encrypted with the same Keystore key as
 * chat rows, with the chunk identity as AAD so a ciphertext moved to another row
 * fails authentication instead of decrypting as if it belonged there.
 */
class DocumentStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    data class ChunkInput(val text: String, val embedding: FloatArray)

    /**
     * A row as read for ranking: text and name stay encrypted until a chunk is
     * actually selected, so a linear scan does not pay one Keystore round trip per
     * chunk on every message.
     */
    data class StoredChunk(
        val docId: String,
        val docNameCipher: String,
        val ordinal: Int,
        val total: Int,
        val textCipher: String,
        val embedding: FloatArray
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                doc_id TEXT NOT NULL,
                doc_name TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                total INTEGER NOT NULL,
                text TEXT NOT NULL,
                embedding BLOB NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_doc_chunks_doc ON $TABLE(doc_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema; nothing to migrate yet.
    }

    /** Replaces every chunk of [docId] atomically, so a re-import never duplicates rows. */
    fun replaceDocument(docId: String, docName: String, chunks: List<ChunkInput>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete(TABLE, "doc_id = ?", arrayOf(docId))
            chunks.forEachIndexed { ordinal, chunk ->
                val values = ContentValues().apply {
                    put("doc_id", docId)
                    put("doc_name", ChatCrypto.encrypt(docName, nameAad(docId)))
                    put("ordinal", ordinal)
                    put("total", chunks.size)
                    put("text", ChatCrypto.encrypt(chunk.text, aad(docId, ordinal)))
                    put("embedding", encode(chunk.embedding))
                }
                db.insertOrThrow(TABLE, null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun readForSearch(): List<StoredChunk> {
        val result = mutableListOf<StoredChunk>()
        readableDatabase.query(
            TABLE,
            arrayOf("doc_id", "doc_name", "ordinal", "total", "text", "embedding"),
            null, null, null, null, "id ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val embedding = decode(cursor.getBlob(5)) ?: continue
                result += StoredChunk(
                    docId = cursor.getString(0),
                    docNameCipher = cursor.getString(1),
                    ordinal = cursor.getInt(2),
                    total = cursor.getInt(3),
                    textCipher = cursor.getString(4),
                    embedding = embedding
                )
            }
        }
        return result
    }

    /**
     * Decrypts a selected chunk, or returns null when this install can no longer
     * read it (backup restore, cleared Keystore). One unreadable row must not break
     * search for the rest.
     */
    fun decryptText(chunk: StoredChunk): String? = try {
        ChatCrypto.decrypt(chunk.textCipher, aad(chunk.docId, chunk.ordinal))
    } catch (t: Throwable) {
        Log.w(TAG, "Skipping undecryptable document chunk", t)
        null
    }

    fun decryptName(chunk: StoredChunk): String = try {
        ChatCrypto.decrypt(chunk.docNameCipher, nameAad(chunk.docId))
    } catch (t: Throwable) {
        Log.w(TAG, "Unreadable document name", t)
        "document"
    }

    /** One row per indexed document, newest first, for the attachment picker. */
    fun listDocuments(): List<DocumentSummary> {
        val result = mutableListOf<DocumentSummary>()
        readableDatabase.rawQuery(
            "SELECT doc_id, doc_name, COUNT(*) FROM $TABLE GROUP BY doc_id, doc_name ORDER BY MAX(id) DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0)
                val name = try {
                    ChatCrypto.decrypt(cursor.getString(1), nameAad(docId))
                } catch (t: Throwable) {
                    Log.w(TAG, "Unreadable document name", t)
                    "document"
                }
                result += DocumentSummary(docId, name, cursor.getInt(2))
            }
        }
        return result
    }

    fun stats(): DocumentStats {
        readableDatabase.rawQuery(
            "SELECT COUNT(*), COUNT(DISTINCT doc_id) FROM $TABLE",
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return DocumentStats.EMPTY
            return DocumentStats(documents = cursor.getInt(1), chunks = cursor.getInt(0))
        }
    }

    fun deleteDocument(docId: String) {
        writableDatabase.delete(TABLE, "doc_id = ?", arrayOf(docId))
    }

    fun clear() {
        writableDatabase.delete(TABLE, null, null)
    }

    private fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    private fun decode(bytes: ByteArray): FloatArray? {
        if (bytes.isEmpty() || bytes.size % Float.SIZE_BYTES != 0) return null
        val vector = FloatArray(bytes.size / Float.SIZE_BYTES)
        ByteBuffer.wrap(bytes).asFloatBuffer().get(vector)
        return vector
    }

    private fun aad(docId: String, ordinal: Int): String = "doc:$docId:$ordinal"

    private fun nameAad(docId: String): String = "docname:$docId"

    companion object {
        private const val TAG = "DocumentStore"
        private const val DATABASE_NAME = "local_llm_documents.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE = "doc_chunks"
    }
}

/** A saved file in the library, as shown in the attachment picker. */
data class DocumentSummary(
    val id: String,
    val name: String,
    val chunks: Int
)

/** Index statistics shown in Settings. */
data class DocumentStats(
    val documents: Int,
    val chunks: Int
) {
    companion object {
        val EMPTY = DocumentStats(documents = 0, chunks = 0)
    }
}
