package com.coparently.app.presentation.auth

import androidx.annotation.StringRes
import com.coparently.app.R

/**
 * The string resource that explains an [AuthError] to the parent.
 *
 * This is the half of the split that knows about resources. Keeping it out of the ViewModel is
 * what lets that class stay free of a `Context` — the arrangement CLAUDE.md asks for and which
 * the login screen, alone among the redesigned screens, did not have.
 *
 * Deliberately **not** `@Composable`. Returning the resource id rather than the resolved string
 * lets the call site decide when to resolve it, which matters inside `AnimatedVisibility`: its
 * content lambda still runs while the card animates out, at which point the error is already
 * null and there is nothing to resolve.
 *
 * @return the id of the localized message for this error
 */
@StringRes
fun AuthError.messageRes(): Int = when (this) {
    AuthError.EmptyFields -> R.string.auth_error_empty_fields
    AuthError.InvalidCredentials -> R.string.auth_error_invalid_credentials
    AuthError.EmailAlreadyInUse -> R.string.auth_error_email_already_in_use
    AuthError.WeakPassword -> R.string.auth_error_weak_password
    AuthError.InvalidEmail -> R.string.auth_error_invalid_email
    AuthError.UserDisabled -> R.string.auth_error_user_disabled
    AuthError.Network -> R.string.auth_error_network
    AuthError.TooManyRequests -> R.string.auth_error_too_many_requests
    AuthError.Interrupted -> R.string.auth_error_interrupted
    AuthError.NoGoogleAccount -> R.string.auth_error_no_google_account
    AuthError.NoSavedPassword -> R.string.auth_error_no_saved_password
    AuthError.Unknown -> R.string.auth_error_unknown
}
