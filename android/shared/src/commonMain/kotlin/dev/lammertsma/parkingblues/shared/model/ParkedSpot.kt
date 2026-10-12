package dev.lammertsma.parkingblues.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the driver confirmed as "I parked here" -- persisted on-device (see
 * ParkedSpotStore on Android) so the phone UI can show it later, after
 * Android Auto has disconnected and the backend session may no longer be
 * actively polled. Deliberately a plain snapshot, not a live reference to
 * a ParkingSegment: the segment it came from can be rejected/retargeted/
 * expired out of the session shortly after, but the driver's actual parked
 * location doesn't change with it.
 *
 * expiresAtEpochMillis comes directly from the segment's own
 * legal_until (blue zone only) at confirm time -- not recomputed
 * client-side, per the "backend is the single source of truth for the
 * algorithm" rule (see AGENTS.md); white-zone spots have no hard legal
 * expiry in this data, so this stays null for them (estimatedFeeChfPerHour
 * is kept instead, as a payment reminder rather than a deadline).
 *
 * Also the response shape of GET/PUT /api/me/parked (ParkingApiClient),
 * which is why the @SerialName annotations are here even though the local
 * on-device JSON round-trip (ParkedSpotStore) doesn't itself care about key
 * names -- one model instead of two for the same data.
 */
@Serializable
data class ParkedSpot(
    val lat: Double,
    val lon: Double,
    @SerialName("zone_type") val zoneType: ZoneType?,
    @SerialName("address_label") val addressLabel: String?,
    @SerialName("confirmed_at_epoch_millis") val confirmedAtEpochMillis: Long,
    @SerialName("expires_at_epoch_millis") val expiresAtEpochMillis: Long?,
    @SerialName("estimated_fee_chf_per_hour") val estimatedFeeChfPerHour: Double? = null,
)
