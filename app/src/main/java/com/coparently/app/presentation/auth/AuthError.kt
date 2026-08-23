package com.coparently.app.presentation.auth

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException

/**
 * Why an authentication attempt did not succeed, as a type rather than a sentence.
 *
 * The screen used to keep an English `String` in its UI state, assembled inside the ViewModel.
 * That is the one shape CLAUDE.md rules out for localizable text, and it forbids the obvious
 * shortcut too: a ViewModel must not be handed a `Context` to solve it. Carrying the *reason*
 * instead lets `AuthError.messageRes()` name a string resource the composable resolves, where
 * `stringResource` is legal.
 */
sealed interface AuthError {

    /** Email or password left blank. Caught before any network call. */
    data object EmptyFields : AuthError

    /** Wrong password, unknown account, or a credential Firebase would not accept. */
    data object InvalidCredentials : AuthError

    /** Sign-up against an email that already has an account. */
    data object EmailAlreadyInUse : AuthError

    /** Firebase rejected the password as too weak. */
    data object WeakPassword : AuthError

    /** The email is not a well-formed address. */
    data object InvalidEmail : AuthError

    /** The account exists but has been disabled in the Firebase console. */
    data object UserDisabled : AuthError

    /** The device could not reach Firebase. Retrying later is the fix. */
    data object Network : AuthError

    /** Firebase is rate-limiting this account or device. */
    data object TooManyRequests : AuthError

    /** The credential sheet was torn down by something other than the user. Retrying works. */
    data object Interrupted : AuthError

    /** Google's flow produced no account. Distinct from [NoSavedPassword]. */
    data object NoGoogleAccount : AuthError

    /** No password has been saved for this app yet. Distinct from [NoGoogleAccount]. */
    data object NoSavedPassword : AuthError

    /** Anything the mapping does not recognise. */
    data object Unknown : AuthError

    companion object {

        /**
         * Maps a failure of the email/password path.
         *
         * Never null: typing an email and a password has no "user changed their mind" outcome,
         * so every failure here is worth reporting.
         */
        fun fromEmailPassword(cause: Throwable): AuthError = firebaseError(cause) ?: Unknown

        /**
         * Maps a failure of the Google path.
         *
         * @return null when the user dismissed Google's sheet. A deliberate cancel is not an
         *   error, and callers must clear the displayed error rather than substitute [Unknown].
         */
        fun fromGoogle(cause: Throwable): AuthError? = when (cause) {
            is GetCredentialCancellationException -> null
            is GetCredentialInterruptedException -> Interrupted
            is NoCredentialException -> NoGoogleAccount
            else -> firebaseError(cause) ?: Unknown
        }

        /**
         * Maps a failure of the saved-password path.
         *
         * `NoCredentialException` reaches this function and [fromGoogle] alike and means
         * something different in each — "nothing saved yet" here, "no Google account" there.
         * That is why the two are separate entry points rather than one shared mapping.
         *
         * @return null when the user dismissed the sheet, on the same contract as [fromGoogle].
         */
        fun fromSavedPassword(cause: Throwable): AuthError? = when (cause) {
            is GetCredentialCancellationException -> null
            is GetCredentialInterruptedException -> Interrupted
            is NoCredentialException -> NoSavedPassword
            else -> firebaseError(cause) ?: Unknown
        }

        /** @return the Firebase-shaped reason, or null when [cause] is not from Firebase. */
        private fun firebaseError(cause: Throwable): AuthError? = when {
            cause is FirebaseNetworkException -> Network
            cause is FirebaseAuthException -> when (cause.errorCode) {
                "ERROR_WRONG_PASSWORD",
                "ERROR_USER_NOT_FOUND",
                "ERROR_INVALID_CREDENTIAL" -> InvalidCredentials
                "ERROR_EMAIL_ALREADY_IN_USE",
                "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> EmailAlreadyInUse
                "ERROR_WEAK_PASSWORD" -> WeakPassword
                "ERROR_INVALID_EMAIL" -> InvalidEmail
                "ERROR_USER_DISABLED" -> UserDisabled
                "ERROR_TOO_MANY_REQUESTS" -> TooManyRequests
                else -> Unknown
            }
            else -> null
        }
    }
}
