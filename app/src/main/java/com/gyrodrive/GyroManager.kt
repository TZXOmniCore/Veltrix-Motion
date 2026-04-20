package com.veltrixmotion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.math.atan2

/**
 * Lê o sensor de movimento do dispositivo.
 *
 * Hierarquia de sensores (do melhor pro pior):
 *   1. TYPE_GAME_ROTATION_VECTOR  — giroscópio + acelerômetro (mais estável)
 *   2. TYPE_ROTATION_VECTOR       — giroscópio + magnetômetro
 *   3. TYPE_ACCELEROMETER         — só acelerômetro (Samsung A05s e similares)
 *
 * No modo acelerômetro:
 *   - Segura o celular na horizontal (paisagem)
 *   - Inclinar para esquerda/direita = direção
 *   - Usa o eixo Y do acelerômetro normalizado pela gravidade
 */
class GyroManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Tenta o melhor sensor disponível
    private val rotationSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val accelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Qual sensor está sendo usado
    private val useAccelOnly = rotationSensor == null

    // Configurações (atualizadas pelo perfil ativo)
    var sensitivity:  Float = 0.85f
    var deadzone:     Float = 0.07f
    var smoothing:    Float = 0.30f
    var maxAngleDeg:  Float = 28f
    var curveCenter:  Float = 0.40f
    var curveEdge:    Float = 1.20f

    private var calibOffset: Float = 0f
    private var smoothed:    Float = 0f
    private val rotMat = FloatArray(9)
    private val orient = FloatArray(3)
    private var lastRaw: Float = 0f

    // Para acelerômetro: valores brutos
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f

    var onSteerChange: ((steer: Float, rawDeg: Float) -> Unit)? = null

    // True se qualquer sensor de movimento estiver disponível
    val isAvailable: Boolean get() = rotationSensor != null || accelSensor != null

    fun start() {
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        } else if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        smoothed = 0f
    }

    fun calibrate()        { calibOffset = lastRaw }
    fun resetCalibration() { calibOffset = 0f; smoothed = 0f }

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
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> processRotationVector(event.values)

            Sensor.TYPE_ACCELEROMETER   -> processAccelerometer(event.values)
        }
    }

    // ── Rotation vector (giroscópio) ──────────────────────────────────────
    private fun processRotationVector(values: FloatArray) {
        SensorManager.getRotationMatrixFromVector(rotMat, values)
        SensorManager.getOrientation(rotMat, orient)
        // orient[2] = roll em landscape
        val roll = Math.toDegrees(orient[2].toDouble()).toFloat()
        lastRaw  = roll
        process(roll - calibOffset)
    }

    // ── Acelerômetro puro (Samsung A05s e similares sem giroscópio) ────────
    /**
     * Em landscape, o eixo X do acelerômetro representa a inclinação lateral.
     * Gravidade terrestre = ~9.8 m/s²
     *
     * Celular plano na horizontal: accelX ≈ 0
     * Celular inclinado esquerda:  accelX < 0 (até -9.8)
     * Celular inclinado direita:   accelX > 0 (até +9.8)
     *
     * Convertemos para graus: angle = atan2(X, Z) em graus
     */
    private fun processAccelerometer(values: FloatArray) {
        // Filtro passa-baixa para suavizar ruído do acelerômetro
        val alpha = 0.8f
        accelX = alpha * accelX + (1 - alpha) * values[0]
        accelY = alpha * accelY + (1 - alpha) * values[1]
        accelZ = alpha * accelZ + (1 - alpha) * values[2]

        // Calcula ângulo de inclinação lateral em graus
        val rollRad = atan2(accelX.toDouble(), accelZ.toDouble())
        val rollDeg = Math.toDegrees(rollRad).toFloat()
        lastRaw     = rollDeg

        process(rollDeg - calibOffset)
    }

    // ── Pipeline comum ────────────────────────────────────────────────────
    private fun process(calibrated: Float) {
        var norm = (calibrated / maxAngleDeg).coerceIn(-1f, 1f)

        // Deadzone
        val dz = deadzone
        norm = if (abs(norm) < dz) 0f
               else sign(norm) * ((abs(norm) - dz) / (1f - dz))

        // Curva por zona
        norm = applyCurve(norm)

        // Sensibilidade
        norm = (norm * sensitivity).coerceIn(-1f, 1f)

        // Suavização exponencial
        val alpha = 1f - smoothing
        smoothed += alpha * (norm - smoothed)

        onSteerChange?.invoke(smoothed, calibrated)
    }

    private fun applyCurve(input: Float): Float {
        val s   = sign(input)
        val abs = abs(input)
        val cc  = curveCenter.coerceIn(0.1f, 0.7f)
        val ce  = curveEdge.coerceIn(0.8f, 2.0f)
        val edgeStart = 1f - cc

        return s * when {
            abs < cc        -> abs * 0.5f
            abs > edgeStart -> {
                val t = (abs - edgeStart) / cc
                edgeStart * 0.5f + t * cc * ce
            }
            else -> abs * 0.5f + (abs - cc) * 0.5f
        }.coerceIn(0f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Retorna qual sensor está sendo usado (para mostrar na UI) */
    fun getSensorName(): String = when {
        rotationSensor != null -> "Giroscópio"
        accelSensor    != null -> "Acelerômetro"
        else                   -> "Nenhum"
    }
}
