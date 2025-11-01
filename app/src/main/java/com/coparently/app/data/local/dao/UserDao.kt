package com.coparently.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.coparently.app.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for UserEntity.
 * Provides methods to access user data from the Room database.
 */
@Dao
interface UserDao {
    /**
     * Gets all users as a Flow.
     */
    @Query("SELECT * FROM users")
    fun getAllUsers(): Flow<List<UserEntity>>

    /**
     * Gets a user by ID.
     */
    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getUserById(id: String): UserEntity?

    /**
     * Gets a user by email.
     */
    @Query("SELECT * FROM users WHERE email = :email")
    suspend fun getUserByEmail(email: String): UserEntity?

    /**
     * Inserts a new user.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    /**
     * Updates an existing user.
     */
    @Update
    suspend fun updateUser(user: UserEntity)

    /**
     * Deletes a user by ID.
     */
    @Query("DELETE FROM users WHERE id = :id")
    suspend fun deleteUserById(id: String)
}

