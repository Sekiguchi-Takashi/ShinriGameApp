package com.appathy.shinrigame

/**
 * 勝ち方の決め方。得点を積むのではなく、勝ち残りで決める。
 *
 * SURVIVAL   負けると✕がつき、３つで脱落。最後の1人が勝ち。
 * TOURNAMENT 一対一の勝ち抜き。あいこは引き分けで、その場でやり直す。
 * OPEN       毎回の勝者を数える。勝ちは何人いてもよい。
 */
object Rules {

    const val SURVIVAL = 0
    const val TOURNAMENT = 1
    const val OPEN = 2

    const val STRIKES_OUT = 3
    const val OPEN_ROUNDS = 7

    fun name(rule: Int): String {
        return when (rule) {
            SURVIVAL -> "勝ち残り"
            TOURNAMENT -> "トーナメント"
            else -> "みんなで勝負"
        }
    }

    fun summary(rule: Int): String {
        return when (rule) {
            SURVIVAL -> "負けると✕がひとつ。３つで脱落。最後の1人が勝ちです。"
            TOURNAMENT -> "一対一の勝ち抜き。あいこはその場でやり直します。"
            else -> "毎回の勝者を数えます。勝ちは何人いてもかまいません。全" +
                OPEN_ROUNDS + "回戦。"
        }
    }

    /** そのルールで選べる参加人数（プレイヤーを含む） */
    fun playerCounts(rule: Int): IntArray {
        return when (rule) {
            SURVIVAL -> intArrayOf(2, 3, 4, 5, 6)
            TOURNAMENT -> intArrayOf(4, 6)
            else -> intArrayOf(2, 3, 4, 5, 6)
        }
    }
}

/** 1ラウンドの結果。勝ち = 1、負け = -1、それ以外 = 0。 */
class RoundResult(
    val status: Map<String, Int>,
    val notes: List<String>
)

/**
 * ルールごとの進行状況。
 *
 * どのルールでも「今回戦うのは誰か」を返し、結果を受け取って状態を進める。
 * ゲームの中身（じゃんけんか、だるまさんか）とは切り離してある。
 */
class Match(val rule: Int, val actors: List<Actor>) {

    val strikes = HashMap<String, Int>()
    val wins = HashMap<String, Int>()
    private val out = HashSet<String>()

    var round = 0
        private set

    /** トーナメントで今戦っている2人 */
    private var pair: List<Actor> = emptyList()
    private var bracket = ArrayList<Actor>()
    private var pairIndex = 0
    private var nextRoundUp = ArrayList<Actor>()

    val log = ArrayList<String>()

    init {
        for (a in actors) {
            strikes[a.id] = 0
            wins[a.id] = 0
        }
        if (rule == Rules.TOURNAMENT) {
            bracket = ArrayList(actors)
            nextRoundUp = ArrayList()
            pairIndex = 0
            advanceToNextPair()
        }
    }

    fun alive(a: Actor): Boolean = !out.contains(a.id)

    fun aliveActors(): List<Actor> {
        val l = ArrayList<Actor>()
        for (a in actors) if (alive(a)) l.add(a)
        return l
    }

    /** このラウンドで実際に手を出す顔ぶれ */
    fun participants(): List<Actor> {
        if (rule == Rules.TOURNAMENT) return pair
        return aliveActors()
    }

    fun finished(): Boolean {
        return when (rule) {
            Rules.SURVIVAL -> aliveActors().size <= 1
            Rules.TOURNAMENT -> bracket.size + nextRoundUp.size <= 1 && pair.size < 2
            else -> round >= Rules.OPEN_ROUNDS
        }
    }

    /** 勝者。OPEN では複数人になりうる。 */
    fun winners(): List<Actor> {
        if (rule == Rules.OPEN) {
            var best = -1
            for (a in actors) {
                val w = wins[a.id] ?: 0
                if (w > best) best = w
            }
            val l = ArrayList<Actor>()
            for (a in actors) if ((wins[a.id] ?: 0) == best) l.add(a)
            return l
        }
        return aliveActors()
    }

    fun statusLine(a: Actor): String {
        return when (rule) {
            Rules.SURVIVAL -> {
                if (!alive(a)) "脱落"
                else "✕".repeat(strikes[a.id] ?: 0)
            }
            Rules.TOURNAMENT -> if (!alive(a)) "敗退" else ""
            else -> (wins[a.id] ?: 0).toString() + "勝"
        }
    }

    /**
     * 1ラウンドの結果を反映する。
     * 戻り値が false なら、あいこなどでラウンドが成立しなかったのでやり直す。
     */
    fun apply(result: RoundResult): Boolean {
        for (n in result.notes) log.add(n)

        when (rule) {
            Rules.SURVIVAL -> {
                var moved = false
                for (a in participants()) {
                    if ((result.status[a.id] ?: 0) < 0) {
                        strikes[a.id] = (strikes[a.id] ?: 0) + 1
                        moved = true
                        log.add(a.name + " に ✕（" + strikes[a.id] + " / " + Rules.STRIKES_OUT + "）")
                        if ((strikes[a.id] ?: 0) >= Rules.STRIKES_OUT) {
                            out.add(a.id)
                            log.add(a.name + " は脱落しました")
                        }
                    }
                }
                if (!moved) log.add("誰も負けなかったので、そのまま次へ")
                round++
                return true
            }

            Rules.TOURNAMENT -> {
                var winner: Actor? = null
                var loser: Actor? = null
                for (a in pair) {
                    val s = result.status[a.id] ?: 0
                    if (s > 0) winner = a
                    if (s < 0) loser = a
                }
                if (winner == null || loser == null) {
                    log.add("あいこ。もう一度")
                    return false
                }
                out.add(loser.id)
                nextRoundUp.add(winner)
                wins[winner.id] = (wins[winner.id] ?: 0) + 1
                log.add(winner.name + " が勝ち上がり、" + loser.name + " が敗退")
                round++
                advanceToNextPair()
                return true
            }

            else -> {
                for (a in participants()) {
                    if ((result.status[a.id] ?: 0) > 0) {
                        wins[a.id] = (wins[a.id] ?: 0) + 1
                    }
                }
                round++
                return true
            }
        }
    }

    // ------------------------------------------------------------ トーナメント

    private fun advanceToNextPair() {
        while (true) {
            if (pairIndex + 1 < bracket.size) {
                pair = listOf(bracket[pairIndex], bracket[pairIndex + 1])
                pairIndex += 2
                return
            }
            // 端数は不戦勝
            if (pairIndex < bracket.size) {
                val bye = bracket[pairIndex]
                nextRoundUp.add(bye)
                log.add(bye.name + " は不戦勝")
                pairIndex++
            }
            if (nextRoundUp.size <= 1) {
                pair = emptyList()
                if (nextRoundUp.size == 1) {
                    bracket = ArrayList(nextRoundUp)
                    nextRoundUp = ArrayList()
                }
                return
            }
            bracket = ArrayList(nextRoundUp)
            nextRoundUp = ArrayList()
            pairIndex = 0
        }
    }

    fun bracketLine(): String {
        if (rule != Rules.TOURNAMENT) return ""
        if (pair.size < 2) return "決着"
        return pair[0].name + " 対 " + pair[1].name
    }
}
