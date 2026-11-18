package com.zaneschepke.wireguardautotunnel.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.zaneschepke.wireguardautotunnel.data.entity.TravelSession
import kotlinx.coroutines.flow.Flow

@Dao
interface TravelSessionDao {
    @Insert suspend fun insert(s: TravelSession): Long

    @Update suspend fun update(s: TravelSession)

    @Query("SELECT * FROM travel_session ORDER BY start_ms DESC") fun all(): Flow<List<TravelSession>>

    @Query("DELETE FROM travel_session") suspend fun clear()
}
