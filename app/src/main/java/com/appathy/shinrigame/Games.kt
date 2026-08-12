package com.appathy.shinrigame

/**
 * ゲームごとの差分を閉じ込める。
 *
 * ライフサイクル（予告 → 会話 → 最終予告 → 実行 → 結果）と
 * 心理まわり（嘘率・断言強度・台詞・信頼・観測記録）は全ゲーム共通で、
 * ここには一切書かない。ゲームを追加するときに書くのはこのファイルだけ。
 */
interface GameDef {

    val id: String

    val displayName: String

    /** 予告できる選択肢のラベル */
    val claims: Array<String>

    /** ラウンドごとの配点。要素数がラウンド数になる。 */
    val stakes: IntArray

    /** score_gap を正規化するための想定満点 */
    val scoreScale: Double

    /** 予告どおりに実行したときの加点 */
    fun matchBonus(stakes: Int): Int

    /**
     * 各選択肢の期待値。AI が予告する手を選ぶのに使う。
     * 相手の予告と、その相手の予告一致率（信念）から算出する。
     */
    fun evaluate(actors: List<Actor>, self: Actor, stakes: Int, belief: (Actor) -> Double): DoubleArray

    /** 得点計算。log に説明を追記する。 */
    fun resolve(actors: List<Actor>, stakes: Int, log: (String) -> Unit)

    /** 実行フェーズでプレイヤーに見せる説明 */
    fun actPrompt(): String

    /** ラウンド開始時の説明 */
    fun roundPrompt(): String

    /**
     * その選択肢に対応するカード画像名。専用の絵がなければ null を返す。
     * null のときは UI が claims のラベルで文字カードを描く。
     */
    fun cardAsset(claim: Int): String? = null
}

// ------------------------------------------------------------------ 共通

/** 相手の予告と一致率から、その相手が実際に出す選択肢の確率分布を作る */
fun claimBelief(o: Actor, size: Int, honesty: Double): DoubleArray {
    val p = DoubleArray(size)
    if (o.declared < 0) {
        for (i in 0 until size) p[i] = 1.0 / size
        return p
    }
    val rest = (1.0 - honesty) / (size - 1).toDouble()
    for (i in 0 until size) p[i] = rest
    p[o.declared] = honesty
    return p
}

// ------------------------------------------------------------------ 予告じゃんけん

class JankenGame : GameDef {

    override val id = "yokoku_janken"
    override val displayName = "予告じゃんけん"
    override val claims = arrayOf("グー", "チョキ", "パー")
    override val stakes = intArrayOf(1, 1, 2, 1, 3)
    override val scoreScale = 12.0

    override fun matchBonus(stakes: Int): Int = stakes

    override fun evaluate(
        actors: List<Actor>,
        self: Actor,
        stakes: Int,
        belief: (Actor) -> Double
    ): DoubleArray {
        val score = DoubleArray(3)
        for (o in actors) {
            if (o === self) continue
            if (o.declared < 0) continue
            val p = claimBelief(o, 3, belief(o))
            for (h in 0 until 3) {
                for (i in 0 until 3) score[h] += p[i] * Engine.beats(h, i)
            }
        }
        return score
    }

    override fun resolve(actors: List<Actor>, stakes: Int, log: (String) -> Unit) {
        for (a in actors) {
            var gained = 0
            for (o in actors) {
                if (o === a) continue
                gained += Engine.beats(a.actual, o.actual) * stakes
            }
            if (a.declared == a.actual) gained += matchBonus(stakes)
            a.score += gained
        }
    }

    override fun cardAsset(claim: Int): String? {
        return when (claim) {
            0 -> "card_rock"
            1 -> "card_scissors"
            2 -> "card_paper"
            else -> null
        }
    }

    override fun actPrompt(): String =
        "実際に出す手を選んでください。予告と違う手を出すこともできます。"

    override fun roundPrompt(): String =
        "予告を選んでください。実際に出す手は後で決められます。"
}

// ------------------------------------------------------------------ 予告だるまさんがころんだ

/**
 * 全員が同時に 0〜3 歩進む。
 * その回に最も多く進んだ者がただ1人なら「動いた」とみなされ、捕まって1歩下がる。
 * 同数で並べば全員セーフ。
 *
 * 大きく進みたいが、突出すると捕まる。予告は他人を抑え込むために使える。
 */
class DarumaGame : GameDef {

    override val id = "yokoku_daruma"
    override val displayName = "予告だるまさんがころんだ"
    override val claims = arrayOf("0歩", "1歩", "2歩", "3歩")
    override val stakes = intArrayOf(1, 1, 2, 1, 3)
    override val scoreScale = 15.0

    override fun matchBonus(stakes: Int): Int = 1

    private fun payoff(mine: Int, others: IntArray): Int {
        var max = mine
        for (v in others) if (v > max) max = v
        var count = if (mine == max) 1 else 0
        for (v in others) if (v == max) count++
        if (mine == max && count == 1) return -1
        return mine
    }

    override fun evaluate(
        actors: List<Actor>,
        self: Actor,
        stakes: Int,
        belief: (Actor) -> Double
    ): DoubleArray {
        val others = ArrayList<Actor>()
        for (o in actors) {
            if (o !== self && o.declared >= 0) others.add(o)
        }
        val n = claims.size
        val score = DoubleArray(n)
        if (others.isEmpty()) return score

        val probs = ArrayList<DoubleArray>()
        for (o in others) probs.add(claimBelief(o, n, belief(o)))

        // 相手の組み合わせを全列挙する（人数が少ないので現実的）
        val combo = IntArray(others.size)
        var total = 1
        for (i in others.indices) total *= n

        for (k in 0 until total) {
            var x = k
            var w = 1.0
            for (i in others.indices) {
                combo[i] = x % n
                x /= n
                w *= probs[i][combo[i]]
            }
            if (w <= 0.0) continue
            for (h in 0 until n) score[h] += w * payoff(h, combo)
        }
        return score
    }

    override fun resolve(actors: List<Actor>, stakes: Int, log: (String) -> Unit) {
        var max = -1
        for (a in actors) if (a.actual > max) max = a.actual
        var count = 0
        for (a in actors) if (a.actual == max) count++

        for (a in actors) {
            var gained: Int
            if (a.actual == max && count == 1) {
                gained = -1
                log(a.name + " は動きすぎて捕まった（-1歩）")
            } else {
                gained = a.actual
            }
            if (a.declared == a.actual) gained += matchBonus(stakes)
            a.score += gained
        }

        if (count > 1) {
            log("最多が並んだので、誰も捕まらなかった")
        }
    }

    override fun actPrompt(): String =
        "実際に進む歩数を選んでください。予告と違ってもかまいません。"

    override fun roundPrompt(): String =
        "何歩進むか予告してください。単独で最多になると捕まります。"
}

object Games {
    fun all(): List<GameDef> = listOf(JankenGame(), DarumaGame())
}
