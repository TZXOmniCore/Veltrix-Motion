package com.veltrixmotion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min

class GyroDriveAccessibilityService : AccessibilityService() {

    private val scope      = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<String, Job>()

    @Volatile var isOverlayActive: Boolean = false

    // Direção (giroscópio ou analógico esquerdo do HUD)
    @Volatile private var steerValue: Float = 0f
    @Volatile var useHudLeftStick:    Boolean = false  // true = usa analógico do HUD
    @Volatile private var hudStickX:  Float = 0f
    @Volatile private var hudStickY:  Float = 0f

    // RT progressivo
    @Volatile var rtHolding:  Boolean = false
    @Volatile var rtPressure: Float   = 0f
    var onRtPressureChange: ((Float) -> Unit)? = null

    // LT progressivo
    @Volatile var ltHolding:  Boolean = false

    // Câmera
    @Volatile var cameraLocked: Boolean = false
    @Volatile var cameraLockDx: Float   = 0f
    @Volatile var cameraLockDy: Float   = 0f

    var touchMap: XCloudTouchMap = XCloudTouchMap.default()

    companion object {
        @Volatile var instance: GyroDriveAccessibilityService? = null
            private set
        fun isActive() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ScreenMetrics.init(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() = hardStop()
    override fun onDestroy() { hardStop(); instance = null; scope.cancel(); super.onDestroy() }

    // ── Ativação ──────────────────────────────────────────────────────────
    fun activateController() {
        isOverlayActive = true
        startSteeringLoop()
        startCameraLoop()
    }

    fun deactivateController() {
        isOverlayActive = false
        hardStop()
    }

    // ── Direção ───────────────────────────────────────────────────────────
    fun updateSteer(value: Float) {
        if (!useHudLeftStick) steerValue = value.coerceIn(-1f, 1f)
    }

    fun updateHudStick(dx: Float, dy: Float) {
        hudStickX  = dx; hudStickY = dy
        if (useHudLeftStick) steerValue = dx
    }

    fun releaseHudStick() {
        hudStickX = 0f; hudStickY = 0f
        if (useHudLeftStick) steerValue = 0f
    }

    private fun startSteeringLoop() {
        activeJobs["STEER"]?.cancel()
        activeJobs["STEER"] = scope.launch {
            while (isActive && isOverlayActive) {
                val sv = steerValue
                if (abs(sv) > 0.02f) injectAnalogSteer(sv)
                delay(16L)
            }
        }
    }

    private fun injectAnalogSteer(steer: Float) {
        if (!isOverlayActive) return
        val cx = ScreenMetrics.rx(touchMap.leftStickCX)
        val cy = ScreenMetrics.ry(touchMap.leftStickCY)
        val r  = ScreenMetrics.rr(touchMap.leftStickRadius)
        dispatch(Path().apply { moveTo(cx, cy); lineTo(cx + steer * r, cy) }, 32L)
    }

    // ── RT progressivo ────────────────────────────────────────────────────
    fun startRT() {
        if (!isOverlayActive) return
        rtHolding = true; rtPressure = 0f
        activeJobs["RT"]?.cancel()
        activeJobs["RT"] = scope.launch {
            var step = 0L
            val totalSteps = 1500L / 32L
            while (isActive && isOverlayActive && rtHolding) {
                rtPressure = min(1f, step.toFloat() / totalSteps)
                onRtPressureChange?.invoke(rtPressure)
                injectRTPressure(rtPressure)
                step++; delay(32L)
            }
        }
    }

    fun stopRT() {
        rtHolding = false; rtPressure = 0f
        onRtPressureChange?.invoke(0f)
        activeJobs["RT"]?.cancel(); activeJobs.remove("RT")
    }

    private fun injectRTPressure(pressure: Float) {
        val coord = touchMap.coordFor(XCloudButton.RT) ?: return
        val x  = ScreenMetrics.rx(coord.rx)
        val y  = ScreenMetrics.ry(coord.ry) - pressure * ScreenMetrics.rr(0.03f)
        val ms = (32L + (pressure * 80L).toLong())
        dispatch(Path().apply { moveTo(x, y) }, ms)
    }

    // ── LT progressivo ────────────────────────────────────────────────────
    fun startLT() {
        if (!isOverlayActive) return
        ltHolding = true
        activeJobs["LT"]?.cancel()
        activeJobs["LT"] = scope.launch {
            var step = 0L
            val totalSteps = 1000L / 32L  // freio chega a 100% mais rápido
            while (isActive && isOverlayActive && ltHolding) {
                val pressure = min(1f, step.toFloat() / totalSteps)
                val coord = touchMap.coordFor(XCloudButton.LT) ?: break
                val x  = ScreenMetrics.rx(coord.rx)
                val y  = ScreenMetrics.ry(coord.ry) - pressure * ScreenMetrics.rr(0.03f)
                val ms = (32L + (pressure * 60L).toLong())
                dispatch(Path().apply { moveTo(x, y) }, ms)
                step++; delay(32L)
            }
        }
    }

    fun stopLT() {
        ltHolding = false
        activeJobs["LT"]?.cancel(); activeJobs.remove("LT")
    }

    // ── Freio de mão (toque rápido em A) ─────────────────────────────────
    fun triggerHandbrake() {
        if (!isOverlayActive) return
        val coord = touchMap.coordFor(XCloudButton.A) ?: return
        // Toque rápido curto = freio de mão (não segurado = drift)
        injectTap(ScreenMetrics.rx(coord.rx), ScreenMetrics.ry(coord.ry), 60L)
    }

    fun holdHandbrake() {
        if (!isOverlayActive) return
        pressButton(XCloudButton.A)
    }

    fun releaseHandbrake() = releaseButton(XCloudButton.A)

    // ── Câmera ────────────────────────────────────────────────────────────
    fun lockCamera(dx: Float, dy: Float) {
        cameraLocked = true; cameraLockDx = dx; cameraLockDy = dy
    }

    fun unlockCamera() {
        cameraLocked = false; cameraLockDx = 0f; cameraLockDy = 0f
    }

    fun moveCameraStick(dx: Float, dy: Float) {
        if (cameraLocked) return
        cameraLockDx = dx; cameraLockDy = dy
    }

    fun releaseCameraStick() {
        if (cameraLocked) return
        cameraLockDx = 0f; cameraLockDy = 0f
    }

    private fun startCameraLoop() {
        activeJobs["CAM"]?.cancel()
        activeJobs["CAM"] = scope.launch {
            while (isActive && isOverlayActive) {
                val dx = cameraLockDx; val dy = cameraLockDy
                if (abs(dx) > 0.05f || abs(dy) > 0.05f) {
                    val cx = ScreenMetrics.rx(touchMap.rightStickCX)
                    val cy = ScreenMetrics.ry(touchMap.rightStickCY)
                    val r  = ScreenMetrics.rr(touchMap.rightStickRadius)
                    dispatch(Path().apply {
                        moveTo(cx, cy); lineTo(cx + dx * r, cy + dy * r)
                    }, 32L)
                }
                delay(32L)
            }
        }
    }

    // ── Botões genéricos ──────────────────────────────────────────────────
    fun pressButton(button: XCloudButton) {
        if (!isOverlayActive) return
        val coord = touchMap.coordFor(button) ?: return
        activeJobs[button.name]?.cancel()
        activeJobs[button.name] = scope.launch {
            while (isActive && isOverlayActive) {
                injectTap(ScreenMetrics.rx(coord.rx), ScreenMetrics.ry(coord.ry), 80L)
                delay(80L)
            }
        }
    }

    fun releaseButton(button: XCloudButton) {
        activeJobs[button.name]?.cancel(); activeJobs.remove(button.name)
    }

    fun tapButton(button: XCloudButton) {
        if (!isOverlayActive) return
        val coord = touchMap.coordFor(button) ?: return
        injectTap(ScreenMetrics.rx(coord.rx), ScreenMetrics.ry(coord.ry), 120L)
    }

    // ── Para tudo ─────────────────────────────────────────────────────────
    fun hardStop() {
        isOverlayActive = false
        rtHolding = false; rtPressure = 0f
        ltHolding = false
        cameraLocked = false; cameraLockDx = 0f; cameraLockDy = 0f
        steerValue = 0f; hudStickX = 0f; hudStickY = 0f
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    // ── Primitivos ────────────────────────────────────────────────────────
    private fun injectTap(x: Float, y: Float, ms: Long) =
        dispatch(Path().apply { moveTo(x, y) }, ms)

    private fun dispatch(path: Path, ms: Long) {
        if (!isOverlayActive) return
        runCatching {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, ms))
                    .build(), null, null
            )
        }
    }
}

// ── Dados ─────────────────────────────────────────────────────────────────
enum class XCloudButton {
    RT, LT, RB, LB, A, B, X, Y,
    MENU, VIEW, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, REWIND
}

data class TouchRatio(val rx: Float, val ry: Float)

data class XCloudTouchMap(
    val leftStickCX: Float, val leftStickCY: Float, val leftStickRadius: Float,
    val rightStickCX: Float, val rightStickCY: Float, val rightStickRadius: Float,
    private val buttons: Map<XCloudButton, TouchRatio>
) {
    fun coordFor(b: XCloudButton): TouchRatio? = buttons[b]
    fun allButtons(): Map<XCloudButton, TouchRatio> = buttons

    companion object {
        fun default() = XCloudTouchMap(
            0.092f, 0.760f, 0.110f, 0.625f, 0.760f, 0.090f,
            buttons = mapOf(
                XCloudButton.LT          to TouchRatio(0.050f, 0.056f),
                XCloudButton.RT          to TouchRatio(0.950f, 0.056f),
                XCloudButton.LB          to TouchRatio(0.092f, 0.111f),
                XCloudButton.RB          to TouchRatio(0.908f, 0.111f),
                XCloudButton.Y           to TouchRatio(0.900f, 0.426f),
                XCloudButton.X           to TouchRatio(0.858f, 0.537f),
                XCloudButton.B           to TouchRatio(0.942f, 0.537f),
                XCloudButton.A           to TouchRatio(0.900f, 0.648f),
                XCloudButton.VIEW        to TouchRatio(0.458f, 0.093f),
                XCloudButton.MENU        to TouchRatio(0.542f, 0.093f),
                XCloudButton.REWIND      to TouchRatio(0.500f, 0.093f),
                XCloudButton.DPAD_UP     to TouchRatio(0.183f, 0.537f),
                XCloudButton.DPAD_DOWN   to TouchRatio(0.183f, 0.704f),
                XCloudButton.DPAD_LEFT   to TouchRatio(0.146f, 0.620f),
                XCloudButton.DPAD_RIGHT  to TouchRatio(0.221f, 0.620f),
            )
        )
    }
}
