package org.siros.wwwallet.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ShakeDetector monitors the device accelerometer to detect shaking motions.
 *
 * @param context The application context used to access the SensorManager.
 * @param onShake Callback invoked when a shake is detected.
 * @param sensitivity Threshold for acceleration change. Higher = harder to trigger.
 *                   Default 12f is usually good for "rage-shaking".
 * @param cooldownMs Minimum time between detection events to avoid duplicate triggers.
 */
class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit,
    private val sensitivity: Float = 12f,
    private val cooldownMs: Long = 1000L
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastShakeTime: Long = 0

    /**
     * Starts listening for shake events.
     */
    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    /**
     * Stops listening for shake events to save battery.
     */
    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Calculate the magnitude of acceleration: sqrt(x^2 + y^2 + z^2)
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        // Subtract gravity (~9.81 m/s^2) to get only the movement force
        val gForce = abs(magnitude - SensorManager.GRAVITY_EARTH)

        if (gForce > sensitivity) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastShakeTime > cooldownMs) {
                lastShakeTime = currentTime
                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for shake detection
    }
}