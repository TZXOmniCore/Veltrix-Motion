package com.gyrodrive

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * ★ CORAÇÃO DO GYRODRIVE ★
 *
 * Injeta toques reais via dispatchGesture().
 * Coordenadas são RATIOS (0.0–1.0) convertidos para pixels reais
 * via ScreenMetrics no momento da injeção.
 * → Funciona em qualquer resolução automaticamente.
 */
class GyroDriveAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<String, Job>()

    @Volatile private var steerValue: Float = 0f

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
        startSteeringLoop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() = stopAllGestures()
    override fun onDestroy() { instance = null; scope.cancel(); super.onDestroy() }

    // ── Loop de direção a 60 Hz ──────────────────────────────────────────
    private fun startSteeringLoop() {
        activeJobs["STEER"]?.cancel()
        activeJobs["STEER"] = scope.launch {
            while (isActive) {
                if (abs(steerValue) > 0.02f) injectAnalogSteer(steerValue)
                else                          injectAnalogCenter()
                delay(16L)
            }
        }
    }

    fun updateSteer(value: Float) { steerValue = value.coerceIn(-1f, 1f) }

    // ── Analógico esquerdo ───────────────────────────────────────────────
    private fun injectAnalogSteer(steer: Float) {
        val m  = touchMap
        val cx = ScreenMetrics.rx(m.leftStickCX)
        val cy = ScreenMetrics.ry(m.leftStickCY)
        val r  = ScreenMetrics.rr(m.leftStickRadius)
        val path = Path().apply { moveTo(cx, cy); lineTo(cx + steer * r, cy) }
        dispatch(path, 32L)
    }

    private fun injectAnalogCenter() {
        val path = Path().apply {
            moveTo(ScreenMetrics.rx(touchMap.leftStickCX),
                   ScreenMetrics.ry(touchMap.leftStickCY))
        }
        dispatch(path, 16L)
    }

    // ── Botões ───────────────────────────────────────────────────────────
    fun pressButton(button: XCloudButton) {
        val coord = touchMap.coordFor(button) ?: return
        activeJobs[button.name]?.cancel()
        activeJobs[button.name] = scope.launch {
            while (isActive) {
                injectTap(ScreenMetrics.rx(coord.rx), ScreenMetrics.ry(coord.ry), 80L)
                delay(80L)
            }
        }
    }

    fun releaseButton(button: XCloudButton) {
        activeJobs[button.name]?.cancel()
        activeJobs.remove(button.name)
    }

    fun tapButton(button: XCloudButton) {
        val coord = touchMap.coordFor(button) ?: return
        injectTap(ScreenMetrics.rx(coord.rx), ScreenMetrics.ry(coord.ry), 120L)
    }

    // ── Analógico direito (câmera) ───────────────────────────────────────
    fun moveCameraStick(dx: Float, dy: Float) {
        activeJobs["CAM"]?.cancel()
        if (abs(dx) < 0.05f && abs(dy) < 0.05f) return
        activeJobs["CAM"] = scope.launch {
            while (isActive) {
                val cx = ScreenMetrics.rx(touchMap.rightStickCX)
                val cy = ScreenMetrics.ry(touchMap.rightStickCY)
                val r  = ScreenMetrics.rr(touchMap.rightStickRadius)
                val path = Path().apply {
                    moveTo(cx, cy)
                    lineTo(cx + dx * r, cy + dy * r)
                }
                dispatch(path, 32L)
                delay(32L)
            }
        }
    }

    fun releaseCameraStick() {
        activeJobs["CAM"]?.cancel()
        activeJobs.remove("CAM")
    }

    // ── Primitivos ───────────────────────────────────────────────────────
    private fun injectTap(x: Float, y: Float, ms: Long) =
        dispatch(Path().apply { moveTo(x, y) }, ms)

    private fun dispatch(path: Path, ms: Long) =
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, ms))
                .build(),
            null, null
        )

    fun stopAllGestures() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        scope.coroutineContext.cancelChildren()
        startSteeringLoop()
    }
}

// ── Enum de botões ───────────────────────────────────────────────────────
enum class XCloudButton {
    RT, LT, RB, LB,
    A, B, X, Y,
    MENU, VIEW,
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT,
    REWIND
}

// ── Coordenada relativa (ratio) ──────────────────────────────────────────
/**
 * Posição de um botão como fração da tela.
 *
 *   rx = posição X / largura_total_tela
 *   ry = posição Y / altura_total_tela
 *
 * Exemplos do mesmo ratio em telas diferentes:
 *   rx=0.95  →  2400px tela: 2280px  |  1600px tela: 1520px  |  720px tela: 684px
 *
 * Para calibrar um botão:
 *   Ative "Localização do ponteiro" (Dev Options), toque o botão no xCloud,
 *   anote X e Y, então:  rx = X / largura_tela,  ry = Y / altura_tela
 */
data class TouchRatio(val rx: Float, val ry: Float)

// ── Mapa do gamepad virtual do xCloud ────────────────────────────────────
/**
 * O xCloud posiciona seu overlay de controle de forma PROPORCIONAL
 * ao tamanho do viewport — por isso ratios são a representação correta.
 *
 * Ratios abaixo medidos analisando o DOM do xCloud em landscape.
 * São válidos para qualquer tela pois o xCloud escala o layout igual.
 */
data class XCloudTouchMap(
    val leftStickCX:      Float,
    val leftStickCY:      Float,
    val leftStickRadius:  Float,   // relativo ao menor lado da tela

    val rightStickCX:     Float,
    val rightStickCY:     Float,
    val rightStickRadius: Float,

    private val buttons: Map<XCloudButton, TouchRatio>
) {
    fun coordFor(b: XCloudButton): TouchRatio? = buttons[b]

    companion object {

        /**
         * Ratios do layout padrão do xCloud em landscape.
         *
         * Visualização proporcional (qualquer tela):
         *
         *   ←─────────────────── 100% largura ────────────────────→
         *   LT 5%                                             RT 95%   ↑ 6%
         *   LB 9%                                             RB 91%   ↑ 11%
         *
         *   LeftStick 9%,76%    VIEW 46%  MENU 54%    RightStick 63%,76%
         *
         *   DPad 18%,62%                           Y 90%,43%
         *                                       X 86%,54%  B 94%,54%
         *                                          A 90%,65%
         */
        fun default() = XCloudTouchMap(
            leftStickCX      = 0.092f,
            leftStickCY      = 0.760f,
            leftStickRadius  = 0.110f,

            rightStickCX     = 0.625f,
            rightStickCY     = 0.760f,
            rightStickRadius = 0.090f,

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
