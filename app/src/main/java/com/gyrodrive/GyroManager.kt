package com.gyrodrive

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Gerencia o giroscópio / sensor de orientação do Android.
 *
 * Usa TYPE_GAME_ROTATION_VECTOR (mais preciso, sem deriva magnética)
 * com fallback para TYPE_ROTATION_VECTOR e TYPE_GYROSCOPE.
 *
 * Pipeline de processamento:
 *   Raw sensor  →  calibração  →  deadzone  →  suavização  →  output
 */
class GyroManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Preferência de sensor: game rotation vector é o mais estável
    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // ── Configuração ─────────────────────────────────────────────────────
    var sensitivity:  Float = 0.8f   // multiplicador após deadzone
    var deadzone:     Float = 0.08f  // zona morta (0..0.3)
    var smoothing:    Float = 0.25f  // alpha do filtro exponencial (0..0.95)
    var maxAngleDeg:  Float = 26.25f // graus de inclinação = 100% de output

    // ── Estado interno ────────────────────────────────────────────────────
    private var calibrationOffset: Float = 0f   // offset de calibração em graus
    private var smoothedValue:     Float = 0f   // valor após filtro
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var lastRawGamma: Float = 0f

    // ── Callback para o OverlayService ────────────────────────────────────
    var onSteerChange: ((steer: Float, rawDeg: Float) -> Unit)? = null

    // ── Controle ──────────────────────────────────────────────────────────
    fun start() {
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Chama com o celular na posição neutra para zerar o eixo. */
    fun calibrate() {
        calibrationOffset = lastRawGamma
    }

    fun resetCalibration() {
        calibrationOffset = 0f
        smoothedValue = 0f
    }

    val isAvailable: Boolean get() = sensor != null

    // ── SensorEventListener ───────────────────────────────────────────────
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> processRotationVector(event.values)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* ignorado */ }

    // ── Pipeline de processamento ─────────────────────────────────────────
    private fun processRotationVector(values: FloatArray) {
        // Converte quaternion → matriz → ângulos de Euler
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        /*
         * orientationAngles[2] = roll (rotação no eixo Z no modo landscape).
         * Em landscape: segurar o celular como volante → roll é o ângulo de direção.
         * Convertemos de radianos para graus.
         */
        val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()
        lastRawGamma = rollDeg

        // 1. Calibração
        val calibrated = rollDeg - calibrationOffset

        // 2. Normalização (-1 a +1)
        var norm = (calibrated / maxAngleDeg).coerceIn(-1f, 1f)

        // 3. Deadzone com rescale suave
        val dz = deadzone
        norm = if (abs(norm) < dz) {
            0f
        } else {
            val sign = if (norm > 0) 1f else -1f
            sign * ((abs(norm) - dz) / (1f - dz))
        }

        // 4. Sensibilidade
        norm = (norm * sensitivity).coerceIn(-1f, 1f)

        // 5. Filtro de suavização exponencial (anti-tremor)
        val alpha = 1f - smoothing  // quanto maior smoothing, mais lento
        smoothedValue = smoothedValue + alpha * (norm - smoothedValue)

        // 6. Emite callback
        onSteerChange?.invoke(smoothedValue, calibrated)
    }
}
