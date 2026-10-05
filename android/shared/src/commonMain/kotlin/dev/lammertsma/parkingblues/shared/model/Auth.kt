package dev.lammertsma.parkingblues.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The signed-in Google account as the backend knows it. */
@Serializable
data class AccountInfo(
    val sub: String,
    val email: String = "",
    val name: String = "",
    val role: String = "user",
)

/** Response of the backend's sign-in and refresh endpoints. */
@Serializable
data class AuthSession(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("refresh_token") val refreshToken: String,
    val account: AccountInfo,
)
