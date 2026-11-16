package com.wordlookup.screen.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary ORDER BY (CASE WHEN mastery = 'forgot' THEN 100 ELSE 0 END) + lookupCount DESC LIMIT 3")
    suspend fun getTopBlindspots(): List<VocabularyEntity>

    @Query("SELECT word FROM vocabulary")
    suspend fun getAllSavedWords(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM vocabulary WHERE word = :word COLLATE NOCASE)")
    suspend fun exists(word: String): Boolean

    @Query("SELECT * FROM vocabulary WHERE word = :word COLLATE NOCASE LIMIT 1")
    suspend fun getByWord(word: String): VocabularyEntity?

    @Query("UPDATE vocabulary SET lookupCount = lookupCount + 1 WHERE word = :word COLLATE NOCASE")
    suspend fun incrementLookupCount(word: String)

    @Query("UPDATE vocabulary SET mastery = :mastery WHERE word = :word COLLATE NOCASE")
    suspend fun updateMastery(word: String, mastery: String)

    @Query("UPDATE vocabulary SET note = :note WHERE word = :word COLLATE NOCASE")
    suspend fun updateNote(word: String, note: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: VocabularyEntity)

    @Query("DELETE FROM vocabulary WHERE word = :word COLLATE NOCASE")
    suspend fun deleteByWord(word: String)

    @Delete
    suspend fun delete(item: VocabularyEntity)
}
r