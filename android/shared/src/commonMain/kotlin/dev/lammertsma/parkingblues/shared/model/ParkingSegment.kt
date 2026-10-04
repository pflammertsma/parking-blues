package dev.lammertsma.parkingblues.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One candidate parking spot, exactly as returned by segment_json() in
 * backend/app.py. legalUntil is only ever present for BLUE segments;
 * estimatedFeeChfPerHour only for WHITE -- see that function for why
 * (blue-zone time rule vs. a single flat, explicitly-unverified fee
 * guess in backend/fee_estimate.py).
 */
@Serializable
data class ParkingSegment(
    val id: String,
    val lat: Double,
    val lon: Double,
    @SerialName("zone_type") val zoneType: ZoneType,
    @SerialName("address_label") val addressLabel: String,
    @SerialName("estimated_capacity") val estimatedCapacity: Int,
    @SerialName("max_duration_minutes") val maxDurationMinutes: Int? = null,
    @SerialName("distance_m") val distanceM: Double,
    @SerialName("distance_from_you_m") val distanceFromYouM: Double,
    @SerialName("legal_until") val legalUntil: String? = null,
    @SerialName("estimated_fee_chf_per_hour") val estimatedFeeChfPerHour: Double? = null,
)
