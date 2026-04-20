package com.veltrixmotion

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Gerencia perfis de controle salvos.
 * Cada perfil contém:
 *  - Nome
 *  - Configurações de giroscópio (sensibilidade, deadzone, suavização, curva)
 *  - Mapa de coordenadas do xCloud (calibrado pelo usuário)
 */
class ProfileManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("veltrix_profiles", Context.MODE_PRIVATE)

    // Perfis padrão de fábrica
    companion object {
        const val PROFILE_FORZA    = "forza"
        const val PROFILE_GENERIC  = "generic"
        const val PROFILE_DRIFT    = "drift"
        const val KEY_ACTIVE       = "active_profile"

        val DEFAULT_PROFILES = listOf(
            ControlProfile(
                id          = PROFILE_FORZA,
                name        = "Forza Horizon",
                emoji       = "🏎️",
                sensitivity  = 0.85f,
                deadzone     = 0.07f,
                smoothing    = 0.30f,
                maxAngleDeg  = 28f,
                curveCenter  = 0.40f,   // zona central suave (40% do range)
                curveEdge    = 1.20f,   // zona extrema agressiva
                touchMap     = null     // usa default do xCloud
            ),
            ControlProfile(
                id          = PROFILE_DRIFT,
                name        = "Drift / Forza",
                emoji       = "💨",
                sensitivity  = 1.10f,
                deadzone     = 0.05f,
                smoothing    = 0.15f,   // menos suavização = resposta imediata
                maxAngleDeg  = 22f,     // ângulo menor = mais sensível
                curveCenter  = 0.25f,
                curveEdge    = 1.40f,
                touchMap     = null
            ),
            ControlProfile(
                id          = PROFILE_GENERIC,
                name        = "Genérico",
                emoji       = "🎮",
                sensitivity  = 0.80f,
                deadzone     = 0.08f,
                smoothing    = 0.25f,
                maxAngleDeg  = 30f,
                curveCenter  = 0.50f,
                curveEdge    = 1.00f,
                touchMap     = null
            )
        )
    }

    fun getActiveProfileId(): String =
        prefs.getString(KEY_ACTIVE, PROFILE_FORZA) ?: PROFILE_FORZA

    fun setActiveProfile(id: String) =
        prefs.edit().putString(KEY_ACTIVE, id).apply()

    fun getActiveProfile(): ControlProfile {
        val id = getActiveProfileId()
        return loadCustomProfile(id) ?: DEFAULT_PROFILES.find { it.id == id }
            ?: DEFAULT_PROFILES.first()
    }

    fun getAllProfiles(): List<ControlProfile> {
        val customs = listOf("custom_1", "custom_2").mapNotNull { loadCustomProfile(it) }
        return DEFAULT_PROFILES + customs
    }

    /** Salva um perfil customizado (coordenadas calibradas) */
    fun saveProfile(profile: ControlProfile) {
        val json = JSONObject().apply {
            put("id",          profile.id)
            put("name",        profile.name)
            put("emoji",       profile.emoji)
            put("sensitivity", profile.sensitivity)
            put("deadzone",    profile.deadzone)
            put("smoothing",   profile.smoothing)
            put("maxAngleDeg", profile.maxAngleDeg)
            put("curveCenter", profile.curveCenter)
            put("curveEdge",   profile.curveEdge)
            profile.touchMap?.let { map ->
                put("touchMap", JSONObject().apply {
                    put("lsCX", map.leftStickCX);  put("lsCY", map.leftStickCY)
                    put("lsR",  map.leftStickRadius)
                    put("rsCX", map.rightStickCX); put("rsCY", map.rightStickCY)
                    put("rsR",  map.rightStickRadius)
                    val btns = JSONObject()
                    XCloudButton.values().forEach { btn ->
                        map.coordFor(btn)?.let { c ->
                            btns.put(btn.name, JSONObject().apply {
                                put("rx", c.rx); put("ry", c.ry)
                            })
                        }
                    }
                    put("buttons", btns)
                })
            }
        }
        prefs.edit().putString("profile_${profile.id}", json.toString()).apply()
    }

    private fun loadCustomProfile(id: String): ControlProfile? {
        val json = prefs.getString("profile_$id", null) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            ControlProfile(
                id          = obj.getString("id"),
                name        = obj.getString("name"),
                emoji       = obj.optString("emoji", "🎮"),
                sensitivity  = obj.getDouble("sensitivity").toFloat(),
                deadzone     = obj.getDouble("deadzone").toFloat(),
                smoothing    = obj.getDouble("smoothing").toFloat(),
                maxAngleDeg  = obj.getDouble("maxAngleDeg").toFloat(),
                curveCenter  = obj.getDouble("curveCenter").toFloat(),
                curveEdge    = obj.getDouble("curveEdge").toFloat(),
                touchMap     = if (obj.has("touchMap")) parseTouchMap(obj.getJSONObject("touchMap")) else null
            )
        }.getOrNull()
    }

    private fun parseTouchMap(obj: JSONObject): XCloudTouchMap {
        val btns = obj.getJSONObject("buttons")
        val map  = mutableMapOf<XCloudButton, TouchRatio>()
        XCloudButton.values().forEach { btn ->
            if (btns.has(btn.name)) {
                val c = btns.getJSONObject(btn.name)
                map[btn] = TouchRatio(c.getDouble("rx").toFloat(), c.getDouble("ry").toFloat())
            }
        }
        val default = XCloudTouchMap.default()
        return XCloudTouchMap(
            leftStickCX      = obj.getDouble("lsCX").toFloat(),
            leftStickCY      = obj.getDouble("lsCY").toFloat(),
            leftStickRadius  = obj.getDouble("lsR").toFloat(),
            rightStickCX     = obj.getDouble("rsCX").toFloat(),
            rightStickCY     = obj.getDouble("rsCY").toFloat(),
            rightStickRadius = obj.getDouble("rsR").toFloat(),
            buttons          = map.ifEmpty { default.allButtons() }
        )
    }
}

data class ControlProfile(
    val id:          String,
    val name:        String,
    val emoji:       String,
    val sensitivity: Float,
    val deadzone:    Float,
    val smoothing:   Float,
    val maxAngleDeg: Float,
    val curveCenter: Float,  // 0.0-1.0: quanto do range central é suave
    val curveEdge:   Float,  // multiplicador na zona extrema (>1 = mais agressivo)
    val touchMap:    XCloudTouchMap?
)
