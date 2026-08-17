package com.appathy.shinrigame

import java.util.Random

/** 宣告と本番の照らし合わせ */
object Labels {
    const val NONE = 0
    const val HONEST = 1
    const val LIAR = 2
    const val TIMID = 3

    fun name(v: Int): String {
        return when (v) {
            HONEST -> "正直者"
            LIAR -> "嘘つき"
            TIMID -> "小心者"
            else -> "－"
        }
    }
}

/**
 * ゲームごとの差分。
 *
 * 流れ（予告 → 宣告 → 本番）と、勝ち方（Rules）と、
 * 正直者・嘘つき・小心者の集計は共通側にある。
 */
interface GameDef {

    val id: String
    val displayName: String

    /** 予告・宣告・本番で選べるもの */
    val claims: Array<String>

    /** 宣告しないことを認めるか */
    val allowSilence: Boolean
        get() = true

    fun supports(rule: Int): Boolean

    /** AI がこの場面で選ぶもの。stage は "yokoku" / "sengoku" / "act"。 */
    fun aiPick(self: Actor, others: List<Actor>, stage: String, rnd: Random): Int

    /** 1ラウンドの判定 */
    fun resolve(participants: List<Actor>): RoundResult

    /** 宣告と本番から人柄を判定する */
    fun label(a: Actor, participants: List<Actor>): Int {
        if (a.sengoku < 0) return Labels.TIMID
        if (a.sengoku == a.actual) return Labels.HONEST
        return Labels.LIAR
    }

    fun cardAsset(claim: Int): String? = null

    fun prompt(stage: String): String
}

// ------------------------------------------------------------------ じゃんけん

class JankenGame : GameDef {

    override val id = "janken"
    override val displayName = "予告じゃんけん"
    override val claims = arrayOf("グー", "チョキ", "パー")

    override fun supports(rule: Int) = true

    override fun cardAsset(claim: Int): String? {
        return when (claim) {
            0 -> "card_rock"
            1 -> "card_scissors"
            2 -> "card_paper"
            else -> null
        }
    }

    override fun prompt(stage: String): String {
        return when (stage) {
            "yokoku" -> "何を出すつもりか予告してください。あとで変えてかまいません。"
            "sengoku" -> "本番で出すものを宣告してください。黙っていることもできます。"
            else -> "本番。実際に出すものを選んでください。"
        }
    }

    /**
     * 相手の宣告を見て決める。
     * 性格によって、素直に受け取るか、裏を読むかが変わる。
     */
    override fun aiPick(self: Actor, others: List<Actor>, stage: String, rnd: Random): Int {
        val c = self.character
        if (stage == "yokoku") return rnd.nextInt(3)

        if (stage == "sengoku") {
            if (c != null && rnd.nextDouble() < silenceRate(c)) return -1
            return rnd.nextInt(3)
        }

        // 本番。宣告どおりに出すかどうかは、その性格の嘘つきやすさで決まる。
        val lieRate = if (c == null) 0.3 else c.baseLieRate
        val keep = self.sengoku >= 0 && rnd.nextDouble() > lieRate
        if (keep) return self.sengoku

        // 相手の宣告に勝てるものを選ぶ
        val score = DoubleArray(3)
        var known = 0
        for (o in others) {
            if (o.sengoku < 0) continue
            known++
            for (h in 0 until 3) score[h] += Engine.beats(h, o.sengoku).toDouble()
        }
        if (known == 0) return rnd.nextInt(3)
        if (rnd.nextDouble() < 0.2) return rnd.nextInt(3)

        var best = 0
        for (h in 1 until 3) if (score[h] > score[best]) best = h
        return best
    }

    /** 弱く言いがちな性格ほど黙りやすい */
    private fun silenceRate(c: Character): Double {
        return Engine.clamp(0.08 + c.honest.low * 0.30, 0.05, 0.40)
    }

    override fun resolve(participants: List<Actor>): RoundResult {
        val st = HashMap<String, Int>()
        val notes = ArrayList<String>()
        val present = HashSet<Int>()
        for (a in participants) present.add(a.actual)

        if (present.size != 2) {
            for (a in participants) st[a.id] = 0
            notes.add("あいこ")
            return RoundResult(st, notes)
        }

        val two = present.toList()
        val winner = if (Engine.beats(two[0], two[1]) == 1) two[0] else two[1]
        for (a in participants) {
            st[a.id] = if (a.actual == winner) 1 else -1
        }
        notes.add(claims[winner] + " の勝ち")
        return RoundResult(st, notes)
    }
}

// ------------------------------------------------------------------ だるまさんがころんだ

/**
 * 鬼がひとり出て「何カウントで振り向くか」を宣告する。
 * 進む側は歩数を宣告して動く。振り向いたカウントを超えて動いた者は捕まる。
 *
 * 鬼が宣告より早く振り向いた回は、少なく動いた者を小心者に数えない。
 */
class DarumaGame : GameDef {

    override val id = "daruma"
    override val displayName = "予告だるまさんがころんだ"
    override val claims = arrayOf("0歩", "1歩", "2歩", "3歩")

    override fun supports(rule: Int) = rule != Rules.TOURNAMENT

    override fun prompt(stage: String): String {
        return when (stage) {
            "yokoku" -> "何歩進むつもりか予告してください。鬼なら何カウントで振り向くかです。"
            "sengoku" -> "本番の歩数を宣告してください。黙っていることもできます。"
            else -> "本番。実際に進む歩数を選んでください。"
        }
    }

    override fun aiPick(self: Actor, others: List<Actor>, stage: String, rnd: Random): Int {
        val c = self.character
        val n = claims.size
        if (stage == "yokoku") return rnd.nextInt(n)
        if (stage == "sengoku") {
            if (c != null && rnd.nextDouble() < 0.18) return -1
            return rnd.nextInt(n)
        }

        val lieRate = if (c == null) 0.3 else c.baseLieRate
        if (self.sengoku >= 0 && rnd.nextDouble() > lieRate) return self.sengoku

        // 鬼の宣告を信じて、その手前まで動く
        var oniSengoku = -1
        for (o in others) if (o.isOni) oniSengoku = o.sengoku
        if (self.isOni) return rnd.nextInt(n)
        if (oniSengoku >= 1) return rnd.nextInt(oniSengoku + 1)
        return rnd.nextInt(n)
    }

    override fun resolve(participants: List<Actor>): RoundResult {
        val st = HashMap<String, Int>()
        val notes = ArrayList<String>()

        var oni: Actor? = null
        for (a in participants) if (a.isOni) oni = a
        if (oni == null) {
            for (a in participants) st[a.id] = 0
            return RoundResult(st, notes)
        }

        val turnAt = oni.actual
        notes.add(oni.name + "（鬼）は " + turnAt + " で振り向いた")

        var maxStep = -1
        for (a in participants) {
            if (a.isOni) continue
            if (a.actual <= turnAt && a.actual > maxStep) maxStep = a.actual
        }

        for (a in participants) {
            if (a.isOni) {
                st[a.id] = 0
                continue
            }
            if (a.actual > turnAt) {
                st[a.id] = -1
                notes.add(a.name + " は動きすぎて捕まった")
            } else if (a.actual == maxStep && maxStep > 0) {
                st[a.id] = 1
            } else {
                st[a.id] = 0
            }
        }
        return RoundResult(st, notes)
    }

    /**
     * 宣告より多く動けば嘘つき、少なければ小心者。
     * ただし鬼が宣告より早く振り向いた回は、少なく動いても数えない。
     */
    override fun label(a: Actor, participants: List<Actor>): Int {
        if (a.sengoku < 0) return Labels.TIMID
        if (a.sengoku == a.actual) return Labels.HONEST
        if (a.actual > a.sengoku) return Labels.LIAR

        if (!a.isOni) {
            var oni: Actor? = null
            for (o in participants) if (o.isOni) oni = o
            if (oni != null && oni.sengoku >= 0 && oni.actual < oni.sengoku) {
                return Labels.NONE
            }
        }
        return Labels.TIMID
    }
}

object Games {
    fun all(): List<GameDef> = listOf(JankenGame(), DarumaGame())
}
