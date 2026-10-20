package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CapturedSongDao {

    @Query("SELECT * FROM captured_songs ORDER BY capturedAtEpochMillis DESC, id DESC")
    fun observeAllSongs(): Flow<List<CapturedSongEntity>>

    @Query(
        """
        SELECT * FROM captured_songs
        WHERE fullRawTitle LIKE '%' || :query || '%'
           OR songTitle LIKE '%' || :query || '%'
           OR artistName LIKE '%' || :query || '%'
           OR COALESCE(sourceAppPackage, '') LIKE '%' || :query || '%'
        ORDER BY capturedAtEpochMillis DESC, id DESC
        """
    )
    fun observeFilteredSongs(query: String): Flow<List<CapturedSongEntity>>

    @Query("SELECT * FROM captured_songs ORDER BY capturedAtEpochMillis DESC, id DESC")
    suspend fun getAllSongsSnapshot(): List<CapturedSongEntity>

    @Query(
        """
        SELECT * FROM captured_songs
        WHERE LOWER(TRIM(songTitle)) = LOWER(TRIM(:songTitle))
          AND LOWER(TRIM(artistName)) = LOWER(TRIM(:artistName))
        ORDER BY capturedAtEpochMillis DESC
        LIMIT 1
        """
    )
    suspend fun findLatestByTitleAndArtist(songTitle: String, artistName: String): CapturedSongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: CapturedSongEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<CapturedSongEntity>): List<Long>

    @Update
    suspend fun updateSong(song: CapturedSongEntity)

    @Delete
    suspend fun deleteSong(song: CapturedSongEntity)

    @Query("DELETE FROM captured_songs WHERE id = :id")
    suspend fun deleteSongById(id: Long)

    @Query("DELETE FROM captured_songs")
    suspend fun deleteAllSongs()

    @Query("SELECT COUNT(*) FROM captured_songs")
    fun observeTotalCount(): Flow<Int>
}
