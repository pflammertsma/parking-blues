package dev.lammertsma.parkingblues.shared.model

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
 */
@Serializable
data class ParkedSpot(
    val lat: Double,
    val lon: Double,
    val zoneType: ZoneType?,
    val addressLabel: String?,
    val confirmedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val estimatedFeeChfPerHour: Double? = null,
)
