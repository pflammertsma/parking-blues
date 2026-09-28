package com.parkingblues.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors backend/models.py ZoneType -- the wire value is what the API expects/returns. */
@Serializable
enum class ZoneType {
    @SerialName("blue") BLUE,
    @SerialName("white") WHITE,
}

/** Matches the `zone` query param accepted by POST /api/session (see backend/app.py). */
enum class ZoneFilter(val wireValue: String) {
    BLUE("blue"),
    WHITE("white"),
    BOTH("both"),
}
