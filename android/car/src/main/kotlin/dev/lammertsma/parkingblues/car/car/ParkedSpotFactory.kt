package dev.lammertsma.parkingblues.car.car

import dev.lammertsma.parkingblues.car.location.calculateDistanceMeters
import dev.lammertsma.parkingblues.shared.model.LatLon
import dev.lammertsma.parkingblues.shared.model.ParkedSpot
import dev.lammertsma.parkingblues.shared.model.SessionSnapshot

/**
 * Builds a [ParkedSpot] for a place the driver did not confirm through the car's "Parked here"
 * button: the spot where Android Auto disconnected (assumed), or one set on the phone map.
 *
 * What the spot is (blue or white zone, its address, when the limit ends, the fee) is read from
 * the nearest parking spot the session knows within [MATCH_RADIUS_M], the same facts "Parked here"
 * takes from the server's current target. Nothing is recomputed on the client: the times are the
 * server's own. With no known spot that close, the record is just a place.
 */
object ParkedSpotFactory {
    const val MATCH_RADIUS_M = 25.0

    fun at(
        snapshot: SessionSnapshot?,
        lat: Double,
        lon: Double,
        nowMillis: Long,
        assumed: Boolean,
        destination: LatLon? = null,
    ): ParkedSpot {
        val segment = snapshot?.let { MapClustering.activeCandidates(it) }.orEmpty()
            .map { it to calculateDistanceMeters(lat, lon, it.lat, it.lon) }
            .filter { (_, distance) -> distance <= MATCH_RADIUS_M }
            .minByOrNull { (_, distance) -> distance }
            ?.first
        return ParkedSpot(
            lat = lat,
            lon = lon,
            zoneType = segment?.zoneType,
            addressLabel = segment?.addressLabel,
            confirmedAtEpochMillis = nowMillis,
            expiresAtEpochMillis = segment?.legalUntil?.let(::parseIsoToEpochMillis),
            estimatedFeeChfPerHour = segment?.estimatedFeeChfPerHour,
            assumed = assumed,
            destination = destination,
        )
    }
}

/** ParkingSegment.legalUntil is an ISO-8601 offset datetime string, exactly as the backend emits it. */
internal fun parseIsoToEpochMillis(iso: String): Long? =
    runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
