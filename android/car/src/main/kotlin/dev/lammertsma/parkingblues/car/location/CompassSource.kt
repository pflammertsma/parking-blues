package dev.lammertsma.parkingblues.car.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Where the phone is pointing, in degrees clockwise from **true** north.
 *
 * Uses the fused rotation-vector sensor and corrects magnetic north to true
 * north with the local declination. "Pointing" depends on how the phone is
 * held: lying flat, it is the top edge; held upright to look at the screen,
 * it is the direction the back camera faces, which is the way the person
 * is looking. Emits nothing if the device has no such sensor, and skips
 * readings the sensor itself flags as unreliable.
 *
 * Collect only while the UI is visible: the sensor is registered for as long
 * as the flow is collected.
 */
fun compassHeadings(context: Context, currentPosition: () -> Pair<Double, Double>?): Flow<Float> = callbackFlow {
    val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    val rotationVector = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    if (sensors == null || rotationVector == null) {
        close()
        awaitClose { }
        return@callbackFlow
    }

    val matrix = FloatArray(9)
    var reliable = true
    var declination = 0f
    var declinationAt = 0L

    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (!reliable) return
            SensorManager.getRotationMatrixFromVector(matrix, event.values)

            // matrix maps device axes to world axes (x = east, y = north, z = up).
            // Device Z in world-up terms near +/-1 means the phone lies flat.
            val flat = abs(matrix[8]) > FLAT_THRESHOLD
            val east: Float
            val north: Float
            if (flat) {
                east = matrix[1]   // device +Y (top edge)
                north = matrix[4]
            } else {
                east = -matrix[2]  // device -Z (back camera direction)
                north = -matrix[5]
            }
            val magnetic = Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat()

            val now = System.currentTimeMillis()
            if (now - declinationAt > DECLINATION_REFRESH_MS) {
                currentPosition()?.let { (lat, lon) ->
                    declination = GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, now).declination
                    declinationAt = now
                }
            }
            trySend(HeadingTracker.normalize(magnetic + declination))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            reliable = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
                accuracy != SensorManager.SENSOR_STATUS_NO_CONTACT
        }
    }

    sensors.registerListener(listener, rotationVector, SensorManager.SENSOR_DELAY_UI)
    awaitClose { sensors.unregisterListener(listener) }
}.conflate()

private const val FLAT_THRESHOLD = 0.7f
private const val DECLINATION_REFRESH_MS = 60_000L
