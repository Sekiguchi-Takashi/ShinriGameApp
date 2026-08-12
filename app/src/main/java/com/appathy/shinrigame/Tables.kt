package com.appathy.shinrigame

import android.content.Context
import org.json.JSONObject
import java.util.Random

data class Presentation(val low: Double, val mid: Double, val high: Double)

data class Character(
    val id: String,
    val name: String,
    val archetype: String,
    val portrait: String,
    val voiceId: String,
    val baseLieRate: Double,
    val weights: Map<String, Double>,
    val honest: Presentation,
    val lie: Presentation
)

object Assets {
    fun read(ctx: Context, name: String): String {
        return ctx.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

class Dialogue(private val root: JSONObject) {

    fun pick(
        voice: String,
        intent: String,
        intensity: String,
        claim: String,
        target: String,
        rnd: Random
    ): String {
        val voices = root.getJSONObject("voices")
        val vo = if (voices.has(voice)) voices.getJSONObject(voice) else voices.getJSONObject("cool")
        val io = if (vo.has(intent)) vo.getJSONObject(intent) else vo.getJSONObject("DECLARE")
        val arr = if (io.has(intensity)) io.getJSONArray(intensity) else io.getJSONArray("mid")
        if (arr.length() == 0) return ""
        val raw = arr.getString(rnd.nextInt(arr.length()))
        return raw.replace("{claim}", claim).replace("{target}", target)
    }
}

class CharacterTable(json: String) {

    val characters = LinkedHashMap<String, Character>()
    val starter = ArrayList<String>()

    init {
        val root = JSONObject(json)

        val arr = root.getJSONArray("characters")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            characters[o.getString("id")] = parse(o)
        }

        val mobs = root.optJSONArray("mobs")
        if (mobs != null) {
            for (i in 0 until mobs.length()) {
                val o = mobs.getJSONObject(i)
                val base = characters[o.getString("base")]
                if (base != null) {
                    characters[o.getString("id")] = scale(
                        base,
                        o.getString("id"),
                        o.getString("name"),
                        o.optString("voice_id", base.voiceId),
                        o.getDouble("intensity_scale")
                    )
                }
            }
        }

        val st = root.getJSONObject("roster").getJSONArray("starter")
        for (i in 0 until st.length()) starter.add(st.getString(i))
    }

    private fun parse(o: JSONObject): Character {
        val weights = HashMap<String, Double>()
        val wo = o.getJSONObject("situation_weights")
        val keys = wo.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            weights[k] = wo.getDouble(k)
        }
        val p = o.getJSONObject("presentation")
        return Character(
            o.getString("id"),
            o.getString("name"),
            o.getString("archetype"),
            o.optString("portrait", "char_player").removeSuffix(".png"),
            o.getString("voice_id"),
            o.getDouble("base_lie_rate"),
            weights,
            pres(p.getJSONObject("honest")),
            pres(p.getJSONObject("lie"))
        )
    }

    private fun pres(o: JSONObject): Presentation {
        return Presentation(o.getDouble("low"), o.getDouble("mid"), o.getDouble("high"))
    }

    // モブは基本タイプへの参照 + intensity_scale のみで導出する。
    // 嘘率は下げず、コントラストだけを一様分布へ寄せる。
    private fun scale(
        base: Character,
        id: String,
        name: String,
        voice: String,
        s: Double
    ): Character {
        val weights = HashMap<String, Double>()
        for (e in base.weights.entries) weights[e.key] = e.value * s
        return Character(
            id,
            name,
            base.archetype + "_MOB",
            base.portrait,
            voice,
            base.baseLieRate,
            weights,
            blend(base.honest, s),
            blend(base.lie, s)
        )
    }

    private fun blend(p: Presentation, s: Double): Presentation {
        val u = (1.0 - s) / 3.0
        return Presentation(u + s * p.low, u + s * p.mid, u + s * p.high)
    }
}
