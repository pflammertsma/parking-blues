package dev.lammertsma.parkingblues

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/** The user closed the Google account picker; not an error worth showing. */
class SignInCancelled : Exception("Sign-in cancelled")

/**
 * Shows Google's account picker and returns a Google ID token for the backend
 * to verify. [webClientId] is the OAuth *web* client id: it becomes the
 * token's audience, which the backend checks. [context] must be an Activity.
 */
suspend fun requestGoogleIdToken(context: Context, webClientId: String): Result<String> {
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
        .build()
    return try {
        val credential = CredentialManager.create(context).getCredential(context, request).credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            Result.success(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } else {
            Result.failure(IllegalStateException("Unexpected credential type"))
        }
    } catch (e: GetCredentialCancellationException) {
        Result.failure(SignInCancelled())
    } catch (e: GetCredentialException) {
        Result.failure(e)
    } catch (e: GoogleIdTokenParsingException) {
        Result.failure(e)
    }
}
