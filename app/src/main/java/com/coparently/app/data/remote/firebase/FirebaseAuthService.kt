package com.coparently.app.data.remote.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for Firebase Authentication.
 * Handles user authentication using email/password and provides Firebase UID.
 *
 * @property firebaseAuth Firebase Authentication instance
 */
@Singleton
class FirebaseAuthService @Inject constructor(
    private val firebaseAuth: FirebaseAuth
) {
    /**
     * Gets the current Firebase user.
     */
    fun getCurrentUser(): FirebaseUser? {
        return firebaseAuth.currentUser
    }

    /**
     * Gets the current user's Firebase UID.
     */
    fun getCurrentUid(): String? {
        return firebaseAuth.currentUser?.uid
    }

    /**
     * Checks if user is authenticated.
     */
    fun isAuthenticated(): Boolean {
        return firebaseAuth.currentUser != null
    }

    /**
     * Signs in with email and password.
     *
     * @param email User's email address
     * @param password User's password
     * @return Result containing FirebaseUser on success or error message on failure
     */
    suspend fun signInWithEmail(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = firebaseAuth.signInWithEmailAndPassword(email, password).await()
            Result.success(result.user!!)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Creates a new account with email and password.
     *
     * @param email User's email address
     * @param password User's password
     * @return Result containing FirebaseUser on success or error message on failure
     */
    suspend fun createAccountWithEmail(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = firebaseAuth.createUserWithEmailAndPassword(email, password).await()
            Result.success(result.user!!)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sends a password reset email.
     *
     * @param email User's email address
     * @return Result indicating success or failure
     */
    suspend fun sendPasswordResetEmail(email: String): Result<Unit> {
        return try {
            firebaseAuth.sendPasswordResetEmail(email).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Signs out the current user.
     */
    fun signOut() {
        firebaseAuth.signOut()
    }

    /**
     * Deletes the current user account.
     *
     * @return Result indicating success or failure
     */
    suspend fun deleteCurrentUser(): Result<Unit> {
        return try {
            firebaseAuth.currentUser?.delete()?.await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

