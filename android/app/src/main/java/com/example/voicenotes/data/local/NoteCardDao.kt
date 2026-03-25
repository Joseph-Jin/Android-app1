package com.example.voicenotes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteCardDao {
    @Query("SELECT * FROM note_cards ORDER BY createdAtEpochMillis DESC")
    fun observeCards(): Flow<List<NoteCardEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: NoteCardEntity)

    @Query("SELECT * FROM note_cards WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): NoteCardEntity?

    @Query(
        """
        SELECT * FROM note_cards
        WHERE status = :status
        ORDER BY createdAtEpochMillis ASC
        LIMIT 1
        """
    )
    suspend fun getNextProcessingCard(status: String): NoteCardEntity?

    @Query("DELETE FROM note_cards")
    suspend fun deleteAll()
}
