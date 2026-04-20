package com.veltrixmotion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sign

/**
 * Lê o giroscópio e aplica pipeline completo:
 *   Raw → Calibração → Deadzone → Curva por zona → Suavização → Output
 *
 * Curva por zona (igual volante físico profissional):
 *   Zona central (0–curveCenter): movimentos suaves e precisos
 *   Zona de transição: linear
 *   Zona extrema (acima): resposta agressiva com multiplicador curveEdge
 */
class GyroManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // Configurações (atualizadas pelo perfil ativo)
    var sensitivity:  Float = 0.85f
    var deadzone:     Float = 0.07f
    var smoothing:    Float = 0.30f
    var maxAngleDeg:  Float = 28f
    var curveCenter:  Float = 0.40f   // até 40% do range = zona suave
    var curveEdge:    Float = 1.20f   // fator multiplicador na zona extrema

    private var calibOffset: Float = 0f
    private var smoothed:    Float = 0f
    private val rotMat = FloatArray(9)
    private val orient = FloatArray(3)
    private var lastRaw: Float = 0f

    var onSteerChange: ((steer: Float, rawDeg: Float) -> Unit)? = null

    val isAvailable: Boolean get() = sensor != null

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        smoothed = 0f
    }

    fun calibrate()      { calibOffset = lastRaw }
    fun resetCalibration() { calibOffset = 0f; smoothed = 0f }

    /** Aplica configurações de um perfil de controle */
    fun applyProfile(profile: ControlProfile) {
        sensitivity = profile.sensitivity
        deadzone    = profile.deadzone
        smoothing   = profile.smoothing
        maxAngleDeg = profile.maxAngleDeg
        curveCenter = profile.curveCenter
        curveEdge   = profile.curveEdge
        smoothed    = 0f
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotMat, event.values)
        SensorManager.getOrientation(rotMat, orient)

        val roll = Math.toDegrees(orient[2].toDouble()).toFloat()
        lastRaw  = roll

        val calibrated = roll - calibOffset
        var norm = (calibrated / maxAngleDeg).coerceIn(-1f, 1f)

        // 1. Deadzone com rescale
        val dz = deadzone
        norm = if (abs(norm) < dz) 0f
               else sign(norm) * ((abs(norm) - dz) / (1f - dz))

        // 2. Curva por zona
        norm = applyCurve(norm)

        // 3. Sensibilidade
        norm = (norm * sensitivity).coerceIn(-1f, 1f)

        // 4. Suavização exponencial
        val alpha = 1f - smoothing
        smoothed += alpha * (norm - smoothed)

        onSteerChange?.invoke(smoothed, calibrated)
    }

    /**
     * Curva de sensibilidade por zona:
     *
     *   Zona CENTRAL (|input| < curveCenter):
     *     Comprime a saída pela metade → movimentos pequenos = controle fino
     *
     *   Zona EXTREMA (|input| > (1 - curveCenter)):
     *     Expande com curveEdge → inclinações grandes = reação rápida
     *
     *   Zona de TRANSIÇÃO: linear entre as duas zonas
     *
     * Exemplo com curveCenter=0.4, curveEdge=1.2:
     *   Input 20% → Output ~10% (suave)
     *   Input 50% → Output 50% (linear)
     *   Input 90% → Output ~95% (agressivo)
     */
    private fun applyCurve(input: Float): Float {
        val s   = sign(input)
        val abs = abs(input)
        val cc  = curveCenter.coerceIn(0.1f, 0.7f)
        val ce  = curveEdge.coerceIn(0.8f, 2.0f)
        val edgeStart = 1f - cc

        return s * when {
            abs < cc         -> abs * (cc * 0.5f / cc)           // zona central — metade da velocidade
            abs > edgeStart  -> {                                  // zona extrema — agressiva
                val t = (abs - edgeStart) / cc
                edgeStart * 0.5f + t * cc * ce
            }
            else             -> abs * 0.5f + (abs - cc) * 0.5f   // transição linear
        }.coerceIn(0f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
