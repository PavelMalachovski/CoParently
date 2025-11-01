package com.coparently.app.data.repository

import com.coparently.app.data.local.dao.UserDao
import com.coparently.app.data.local.entity.UserEntity
import com.coparently.app.data.remote.firebase.FcmService
import com.coparently.app.data.remote.firebase.FirebaseAuthService
import com.coparently.app.data.remote.firebase.FirestoreUserDataSource
import com.coparently.app.domain.model.User
import com.coparently.app.domain.repository.UserRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of UserRepository.
 * Maps between domain models (User) and data layer entities (UserEntity).
 * Integrates Firebase Authentication and Firestore for multi-user support.
 */
@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val firebaseAuthService: FirebaseAuthService,
    private val firestoreUserDataSource: FirestoreUserDataSource,
    private val fcmService: FcmService
) : UserRepository {

    override fun getAllUsers(): Flow<List<User>> {
        return userDao.getAllUsers().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getUserById(id: String): User? {
        return userDao.getUserById(id)?.toDomain()
    }

    override suspend fun getUserByEmail(email: String): User? {
        return userDao.getUserByEmail(email)?.toDomain()
    }

    override suspend fun getCurrentUser(): User? {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return null
        return getUserById(firebaseUser.uid)
    }

    override suspend fun upsertUser(user: User) {
        userDao.insertUser(user.toEntity())

        // Also sync to Firestore
        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            val userData = mapOf(
                "id" to user.id,
                "email" to user.email,
                "name" to user.name,
                "role" to user.role,
                "colorCode" to user.colorCode,
                "profilePhotoUrl" to (user.profilePhotoUrl ?: ""),
                "googleCalendarSyncEnabled" to user.googleCalendarSyncEnabled,
                "googleCalendarId" to (user.googleCalendarId ?: ""),
                "partnerId" to (user.partnerId ?: ""),
                "fcmToken" to (user.fcmToken ?: "")
            )
            firestoreUserDataSource.upsertUser(firebaseUser.uid, userData)
        }
    }

    override suspend fun updateUser(user: User) {
        userDao.updateUser(user.toEntity())

        // Also sync to Firestore
        val firebaseUser = firebaseAuthService.getCurrentUser()
        if (firebaseUser != null) {
            val userData = mapOf(
                "id" to user.id,
                "email" to user.email,
                "name" to user.name,
                "role" to user.role,
                "colorCode" to user.colorCode,
                "profilePhotoUrl" to (user.profilePhotoUrl ?: ""),
                "googleCalendarSyncEnabled" to user.googleCalendarSyncEnabled,
                "googleCalendarId" to (user.googleCalendarId ?: ""),
                "partnerId" to (user.partnerId ?: ""),
                "fcmToken" to (user.fcmToken ?: "")
            )
            firestoreUserDataSource.updateUser(firebaseUser.uid, userData)
        }
    }

    override suspend fun deleteUser(id: String) {
        userDao.deleteUserById(id)
    }

    override suspend fun syncWithFirestore() {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return

        // Fetch user data from Firestore
        val firestoreData = firestoreUserDataSource.getUserById(firebaseUser.uid) ?: return

        // Update local database
        val user = firestoreData.toUser()
        userDao.insertUser(user.toEntity())
    }

    override suspend fun updateFcmToken(token: String) {
        val firebaseUser = firebaseAuthService.getCurrentUser() ?: return
        val currentUser = getUserById(firebaseUser.uid) ?: return

        val updatedUser = currentUser.copy(fcmToken = token)
        updateUser(updatedUser)
    }

    /**
     * Maps UserEntity to User domain model.
     */
    private fun UserEntity.toDomain(): User {
        return User(
            id = id,
            email = email,
            name = name,
            role = role,
            colorCode = colorCode,
            profilePhotoUrl = profilePhotoUrl,
            googleCalendarSyncEnabled = googleCalendarSyncEnabled,
            googleCalendarId = googleCalendarId,
            partnerId = partnerId,
            fcmToken = fcmToken
        )
    }

    /**
     * Maps User domain model to UserEntity.
     */
    private fun User.toEntity(): UserEntity {
        return UserEntity(
            id = id,
            email = email,
            name = name,
            role = role,
            colorCode = colorCode,
            profilePhotoUrl = profilePhotoUrl,
            googleCalendarSyncEnabled = googleCalendarSyncEnabled,
            googleCalendarId = googleCalendarId,
            partnerId = partnerId,
            fcmToken = fcmToken
        )
    }

    /**
     * Maps Firestore user data to User domain model.
     */
    private fun Map<String, Any?>.toUser(): User {
        return User(
            id = this["id"] as? String ?: UUID.randomUUID().toString(),
            email = this["email"] as? String ?: "",
            name = this["name"] as? String ?: "",
            role = this["role"] as? String ?: "mom",
            colorCode = this["colorCode"] as? String ?: "#FF4081",
            profilePhotoUrl = this["profilePhotoUrl"] as? String,
            googleCalendarSyncEnabled = this["googleCalendarSyncEnabled"] as? Boolean ?: false,
            googleCalendarId = this["googleCalendarId"] as? String,
            partnerId = this["partnerId"] as? String,
            fcmToken = this["fcmToken"] as? String
        )
    }
}

