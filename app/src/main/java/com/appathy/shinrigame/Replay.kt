package com.appathy.shinrigame

/** 1人分の1ラウンドの記録 */
class ActRecord(
    val id: String,
    val name: String,
    val isPlayer: Boolean,
    val declared: Int,
    val actual: Int,
    val intensity: String,
    val line: String
) {
    val kept: Boolean
        get() = declared == actual
}

/**
 * 1ラウンドの記録。
 *
 * 保存するのは観測された事実と、確定した行動だけ。
 * ここから得点も嘘の有無も再計算できるようにしておくことで、
 * リプレイと IF シミュレーションが追加実装なしで成立する。
 */
class RoundRecord(
    val index: Int,
    val stakes: Int,
    val acts: List<ActRecord>,
    val accusedName: String?,
    val accuseHit: Boolean,
    val persuadedName: String?,
    val persuadedClaim: Int,
    val persuadeHeard: Boolean,
    val scoreBefore: Map<String, Int>,
    val scoreAfter: Map<String, Int>
) {
    fun playerAct(): ActRecord? {
        for (a in acts) if (a.isPlayer) return a
        return null
    }
}

object Replay {

    /**
     * そのラウンドだけ、プレイヤーが別の行動を取っていた場合の得点を出す。
     * 他の参加者の行動は当時のまま固定する。
     */
    fun simulate(def: GameDef, rec: RoundRecord, playerActual: Int): Map<String, Int> {
        val temp = ArrayList<Actor>()
        for (r in rec.acts) {
            val a = Actor(null, r.id, r.name, r.isPlayer, "")
            a.declared = r.declared
            a.actual = if (r.isPlayer) playerActual else r.actual
            a.score = 0
            temp.add(a)
        }

        def.resolve(temp, rec.stakes) { }

        // 追及の結果は相手の行動だけで決まるので、プレイヤーの手を変えても動かない
        val out = HashMap<String, Int>()
        for (a in temp) {
            var g = a.score
            if (a.isPlayer && rec.accusedName != null) {
                g += if (rec.accuseHit) rec.stakes else -rec.stakes
            }
            out[a.id] = g
        }
        return out
    }

    /** 実際に起きたラウンドの増減 */
    fun actual(rec: RoundRecord): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (r in rec.acts) {
            val before = rec.scoreBefore[r.id] ?: 0
            val after = rec.scoreAfter[r.id] ?: 0
            out[r.id] = after - before
        }
        return out
    }
}
