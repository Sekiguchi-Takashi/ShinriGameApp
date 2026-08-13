package com.appathy.shinrigame

import android.content.Context
import org.json.JSONObject
import java.util.Random

class Option(val label: String, val action: () -> Unit)

/** emphasis は「疑わしさ（断言強度 high）」のみで決まる。真偽は絶対に渡さない。 */
class LogLine(val text: String, val emphasis: Boolean)

/** テーブル上の1席分 */
class Seat(
    val name: String,
    val portrait: String,
    val cardHand: Int,
    val cardAsset: String?,
    val cardLabel: String,
    val note: String,
    val trust: Double,
    val isPlayer: Boolean,
    val memoRate: Double,
    val memoCount: Int
)

class Actor(
    val character: Character?,
    val id: String,
    val name: String,
    val isPlayer: Boolean,
    val portrait: String
) {
    var score = 0
    var declared = -1
    var actual = -1
    var lying = false
    var intensity = "mid"

    var observedCount = 0
    var observedMatch = 0
    var highCount = 0
    var highMatch = 0

    var trustInPlayer = 0.5

    fun matchRate(): Double {
        if (observedCount == 0) return 0.5
        return observedMatch.toDouble() / observedCount.toDouble()
    }

    fun highMatchRate(): Double {
        if (highCount == 0) return 0.5
        return highMatch.toDouble() / highCount.toDouble()
    }
}

class Game(ctx: Context) {

    companion object {
        const val P_SELECT = -2
        const val P_ROSTER = -1
        const val P_DECLARE = 0
        const val P_REVEAL = 1
        const val P_TALK = 2
        const val P_TALK_HAND = 3
        const val P_FINAL = 4
        const val P_ACT = 5
        const val P_RESULT = 6
        const val P_END = 7
        const val P_REPLAY = 8
        const val P_IF = 9
    }

    val memory = Memory(ctx)
    private val table = CharacterTable(Assets.read(ctx, "characters.json"))
    private val dialogue = Dialogue(JSONObject(Assets.read(ctx, "dialogue_common.json")))
    private val rnd = Random()

    var def: GameDef = Games.all()[0]
        private set

    val actors = ArrayList<Actor>()
    lateinit var player: Actor

    var round = 0
    var phase = P_SELECT
    val log = ArrayList<LogLine>()

    private var pendingTarget: Actor? = null
    private var persuadeTarget: Actor? = null
    private var persuadeHand = -1
    private var accuseTarget: Actor? = null
    private var persuadeWorked = false

    private var aiCount = 3

    /** セッション中の全ラウンド。リプレイと IF シミュレーションの元になる。 */
    val records = ArrayList<RoundRecord>()
    private var replayIndex = 0
    private var sessionCounted = false

    /** 画面側が拾って対戦画面へ移るための合図 */
    var wantVersus = false
    private val lastLine = HashMap<String, String>()

    init {
        toSelect()
    }

    /** 参加者を組み直す。基本3人は必ず残り、増員分はミオ→モブの順で埋まる。 */
    private fun buildRoster() {
        actors.clear()
        player = Actor(null, "player", "あなた", true, "char_player")
        actors.add(player)
        for (id in table.roster(aiCount, memory.sessions())) {
            val c = table.characters[id]
            if (c != null) actors.add(Actor(c, c.id, c.name, false, c.portrait))
        }
    }

    val totalRounds: Int
        get() = def.stakes.size

    fun stakes(): Int = def.stakes[round]

    /** 予告を確定させるフェーズ。画面全体を赤枠で囲って区別する。 */
    fun isDeclarePhase(): Boolean {
        return phase == P_DECLARE || phase == P_FINAL
    }

    fun phaseLabel(): String {
        return when (phase) {
            P_SELECT -> "ゲーム選択"
            P_ROSTER -> "参加者"
            P_DECLARE -> "予告"
            P_REVEAL -> "予告公開"
            P_TALK -> "会話"
            P_TALK_HAND -> "会話"
            P_FINAL -> "最終予告"
            P_ACT -> "実行"
            P_RESULT -> "結果"
            P_REPLAY -> "リプレイ"
            P_IF -> "もしも"
            P_END -> "終了"
            else -> "終了"
        }
    }

    // ---------------------------------------------------------------- 状況

    private fun situationFor(a: Actor): Situation {
        var top = -999
        for (o in actors) if (o.score > top) top = o.score

        var rank = 0
        for (o in actors) if (o.score > a.score) rank++

        val endgame = round.toDouble() / (totalRounds - 1).toDouble()
        val gap = Engine.clamp((top - a.score).toDouble() / def.scoreScale, 0.0, 1.0)
        val risk = Engine.clamp(
            (rank.toDouble() / (actors.size - 1).toDouble()) * endgame, 0.0, 1.0
        )
        val st = stakes().toDouble() / 3.0

        return Situation(st, risk, gap, endgame)
    }

    /**
     * その相手が予告を守る確率の見立て。
     *
     * 今セッションの観測に、過去の記録を上限つきで混ぜる。
     * プレイヤーに対しても同じ計算を使うので、遊び込むほど AI 側もこちらを読んでくる。
     */
    private fun beliefHonesty(a: Actor): Double {
        val pn = memory.priorCount(a.id)
        val pm = memory.priorMatched(a.id)
        val n = a.observedCount + pn
        if (n == 0) return 0.5
        val m = a.observedMatch + pm
        return Engine.clamp(m.toDouble() / n.toDouble(), 0.15, 0.85)
    }

    // ---------------------------------------------------------------- AI

    /** 期待値が最も高い選択肢を選ぶ（予告する候補） */
    private fun aiChooseClaim(self: Actor): Int {
        val n = def.claims.size
        var known = 0
        for (o in actors) if (o !== self && o.declared >= 0) known++
        if (known == 0) return rnd.nextInt(n)

        val score = def.evaluate(actors, self, stakes()) { beliefHonesty(it) }

        var best = 0
        for (h in 1 until n) if (score[h] > score[best]) best = h

        // 完全最適化は避ける
        if (rnd.nextDouble() < 0.15) return rnd.nextInt(n)
        return best
    }

    /**
     * 予告する。解決順序: 真偽 → claim → 強度 → 台詞（台詞に真偽は渡さない）。
     * 実際の行動はここでは決めない。最終予告が出そろってから commit で決める。
     */
    private fun aiDeclare(a: Actor, hand: Int) {
        val c = a.character ?: return
        val rate = Engine.lieRate(c, situationFor(a))
        a.lying = rnd.nextDouble() < rate
        a.declared = hand
        a.actual = -1
        a.intensity = Engine.sampleIntensity(c, a.lying, rnd)
    }

    /**
     * 実行。最終予告が出そろってから実際の行動を決める。
     *
     * 正直なら予告通りにする（読まれる代償を負う）。
     * 嘘なら予告以外から、場の最終予告に対して最も強い選択肢を選ぶ。
     * この順序でないと予告が誰にも作用せず、嘘が損なだけの選択肢になる。
     */
    private fun commit(a: Actor) {
        if (!a.lying) {
            a.actual = a.declared
            return
        }

        val n = def.claims.size
        val score = def.evaluate(actors, a, stakes()) { beliefHonesty(it) }

        var best = -1
        for (h in 0 until n) {
            if (h == a.declared) continue
            if (best < 0 || score[h] > score[best]) best = h
        }
        if (rnd.nextDouble() < 0.15) best = Engine.other(a.declared, n, rnd)
        a.actual = best
    }

    private fun aiLine(a: Actor, intent: String, claim: String, target: String): String {
        val c = a.character ?: return ""
        return dialogue.pick(c.voiceId, intent, a.intensity, claim, target, rnd)
    }

    // ---------------------------------------------------------------- 進行

    private fun toSelect() {
        phase = P_SELECT
        log.clear()
        line("遊ぶゲームを選んでください。")
        line("")
        line("どちらも流れは同じです。予告し、会話し、最終予告を出し、")
        line("そのあとで実際の行動を決めます。予告と違えてもかまいません。")
    }

    private fun toRoster(g: GameDef) {
        def = g
        phase = P_ROSTER
        log.clear()
        line(def.displayName)
        line("")
        line("参加人数を選んでください。")
        line("")
        val sessions = memory.sessions()
        if (sessions < table.unlockAfterSessions) {
            val left = table.unlockAfterSessions - sessions
            line("あと " + left + " セッションで新しい相手が加わります。")
        } else {
            line("ミオが参加できるようになっています。")
        }
        line("このゲームは AI " + def.minAi + "〜" + def.maxAi + "人で成立します。")
        line("解放済みの相手で足りない分は、名前だけの参加者で埋まります。")

        val pn = memory.observed("player")
        if (pn >= 3) {
            val pr = Math.round(memory.matchRate("player") * 100).toInt()
            line("")
            line("【相手から見たあなた】")
            line("　予告一致率 " + pr + "%（観測 " + pn + "回）")
            line(readOfPlayer(pr))
        }
        line("観測を積み上げる相手は " + table.unlockedCount(sessions) + " 人までです。")
    }

    private fun startGame(n: Int) {
        aiCount = n
        buildRoster()
        records.clear()
        sessionCounted = false
        round = 0
        for (a in actors) {
            a.score = 0
            a.observedCount = 0
            a.observedMatch = 0
            a.highCount = 0
            a.highMatch = 0
            a.trustInPlayer = memory.initialTrust()
        }
        beginRound()
    }

    private fun beginRound() {
        for (a in actors) {
            a.declared = -1
            a.actual = -1
            a.lying = false
        }
        persuadeTarget = null
        persuadeHand = -1
        accuseTarget = null
        pendingTarget = null
        persuadeWorked = false
        lastLine.clear()
        phase = P_DECLARE
        log.clear()
        line("── 第" + (round + 1) + "ラウンド（配点 " + stakes() + "）")
        line(def.roundPrompt())
    }

    private fun line(s: String, emphasis: Boolean = false) {
        log.add(LogLine(s, emphasis))
    }

    private fun emph(a: Actor): Boolean {
        return a.intensity == "high"
    }

    /** 場に出ているカード。結果フェーズでは実際の行動を表にする。 */
    fun table(): List<Seat> {
        if (phase == P_REPLAY || phase == P_IF) return replayTable()
        val out = ArrayList<Seat>()
        val reveal = (phase == P_RESULT)
        for (a in actors) {
            val hand = if (reveal) a.actual else a.declared
            var note = ""
            if (reveal && a.declared >= 0) {
                note = if (a.declared == a.actual) "予告通り" else "予告と違う"
            }
            out.add(
                Seat(
                    a.name,
                    a.portrait,
                    hand,
                    if (hand >= 0) def.cardAsset(hand) else null,
                    if (hand >= 0) def.claims[hand] else "",
                    note,
                    a.trustInPlayer,
                    a.isPlayer,
                    if (a.isPlayer) -1.0 else memory.matchRate(a.id),
                    if (a.isPlayer) 0 else memory.observed(a.id)
                )
            )
        }
        return out
    }

    /** リプレイ中は、その回に実際に出たものを並べる */
    private fun replayTable(): List<Seat> {
        val out = ArrayList<Seat>()
        if (records.isEmpty()) return out
        val rec = records[replayIndex]
        for (r in rec.acts) {
            out.add(
                Seat(
                    r.name,
                    portraitOf(r.id),
                    r.actual,
                    def.cardAsset(r.actual),
                    def.claims[r.actual],
                    if (r.kept) "予告通り" else "予告と違う",
                    0.0,
                    true,
                    -1.0,
                    0
                )
            )
        }
        return out
    }

    private fun portraitOf(id: String): String {
        for (a in actors) if (a.id == id) return a.portrait
        return "char_player"
    }

    fun header(): String {
        if (phase == P_SELECT || phase == P_ROSTER) return "心理戦ゲーム"
        if (phase == P_REPLAY || phase == P_IF) {
            val rec = if (records.isEmpty()) null else records[replayIndex]
            val n = (rec?.index ?: 0) + 1
            return def.displayName + "　リプレイ " + n + " / " + records.size
        }
        val sb = StringBuilder()
        sb.append(def.displayName).append("　")
        sb.append(round + 1).append(" / ").append(totalRounds)
        sb.append("　配点 ").append(stakes()).append("\n")
        for (a in actors) {
            sb.append(a.name).append(" ").append(a.score).append("　")
        }
        return sb.toString()
    }

    fun options(): List<Option> {
        val list = ArrayList<Option>()
        when (phase) {
            P_SELECT -> {
                for (g in Games.all()) {
                    list.add(Option(g.displayName) { toRoster(g) })
                }
                list.add(Option("2人対戦（同じ Wi-Fi）") { wantVersus = true })
            }
            P_ROSTER -> {
                for (n in def.minAi..def.maxAi) {
                    if (n < 3) continue
                    list.add(Option("あなた + AI " + n + "人") { startGame(n) })
                }
                list.add(Option("ゲームを選び直す") { toSelect() })
            }
            P_DECLARE -> {
                for (h in def.claims.indices) {
                    list.add(Option("予告：" + def.claims[h]) { playerDeclare(h) })
                }
            }
            P_REVEAL -> {
                list.add(Option("会話へ") { toTalk() })
            }
            P_TALK -> {
                for (a in actors) {
                    if (a.isPlayer) continue
                    list.add(
                        Option(a.name + "を追及する（当たり +" + stakes() + " / 外れ -" + stakes() + "）") {
                            doAccuse(a)
                        }
                    )
                }
                for (a in actors) {
                    if (a.isPlayer) continue
                    list.add(Option(a.name + "に勧める") { pendingTarget = a; phase = P_TALK_HAND })
                }
                list.add(Option("何も言わない") { doSilent() })
            }
            P_TALK_HAND -> {
                val t = pendingTarget
                if (t != null) {
                    for (h in def.claims.indices) {
                        list.add(Option(t.name + "に「" + def.claims[h] + "」を勧める") {
                            doPersuade(t, h)
                        })
                    }
                }
                list.add(Option("やめる") { phase = P_TALK })
            }
            P_FINAL -> {
                for (h in def.claims.indices) {
                    list.add(Option("最終予告：" + def.claims[h]) { playerFinal(h) })
                }
            }
            P_ACT -> {
                for (h in def.claims.indices) {
                    list.add(Option("実行：" + def.claims[h]) { playerAct(h) })
                }
            }
            P_RESULT -> {
                if (round + 1 < totalRounds) {
                    list.add(Option("次のラウンドへ") { round++; beginRound() })
                } else {
                    list.add(Option("セッション結果を見る") { finish() })
                }
            }
            P_REPLAY -> {
                if (replayIndex > 0) {
                    list.add(Option("前のラウンド") { toReplay(replayIndex - 1) })
                }
                if (replayIndex + 1 < records.size) {
                    list.add(Option("次のラウンド") { toReplay(replayIndex + 1) })
                }
                list.add(Option("別の手を選んでいたら") { toIf() })
                list.add(Option("リプレイを終える") { finish() })
            }
            P_IF -> {
                list.add(Option("ラウンドに戻る") { toReplay(replayIndex) })
                list.add(Option("リプレイを終える") { finish() })
            }
            P_END -> {
                if (records.isNotEmpty()) {
                    list.add(Option("リプレイを見る") { toReplay(0) })
                }
                list.add(Option("もう一度遊ぶ") { startGame(aiCount) })
                list.add(Option("ゲームを選び直す") { toSelect() })
                list.add(Option("累積の観測記録を消す") {
                    memory.clear()
                    toSelect()
                })
            }
        }
        return list
    }

    // ---------------------------------------------------------------- 操作

    private fun playerDeclare(h: Int) {
        // 初回予告は同時。先に全員分を決めてから確定させ、
        // 後から宣言するAIが先のAIの予告を見てしまうのを防ぐ。
        val first = HashMap<Actor, Int>()
        for (a in actors) {
            if (a.isPlayer) continue
            first[a] = aiChooseClaim(a)
        }
        for (a in actors) {
            if (a.isPlayer) continue
            aiDeclare(a, first[a] ?: rnd.nextInt(def.claims.size))
        }
        player.declared = h

        line("")
        line("【予告公開】")
        for (a in actors) {
            if (a.isPlayer) {
                line("あなた：" + def.claims[a.declared])
            } else {
                val text = aiLine(a, "DECLARE", def.claims[a.declared], "")
                lastLine[a.id] = text
                line(a.name + "：「" + text + "」", emph(a))
            }
        }
        phase = P_REVEAL
    }

    private fun toTalk() {
        line("")
        line("【会話】")
        for (a in actors) {
            if (a.isPlayer) continue
            val target = randomOther(a)
            val intent = pickIntent()
            val claim = if (intent == "PERSUADE") {
                def.claims[suggestClaim(a)]
            } else {
                def.claims[a.declared]
            }
            val text = aiLine(a, intent, claim, target.name)
            line(a.name + "：「" + text + "」", emph(a))
        }
        line("")
        line("あなたの行動を選んでください。")
        phase = P_TALK
    }

    private fun pickIntent(): String {
        val x = rnd.nextDouble()
        if (x < 0.45) return "PERSUADE"
        if (x < 0.80) return "ACCUSE"
        return "DEFEND"
    }

    /**
     * 相手には自分の予告と別の選択肢を勧める（半分は無作為にして撹乱する）。
     * この時点で実際の行動は未確定なので、自分の予告を基準にする。
     */
    private fun suggestClaim(a: Actor): Int {
        val n = def.claims.size
        if (rnd.nextDouble() < 0.5) return rnd.nextInt(n)
        if (a.declared < 0) return rnd.nextInt(n)
        return Engine.other(a.declared, n, rnd)
    }

    private fun randomOther(a: Actor): Actor {
        val pool = ArrayList<Actor>()
        for (o in actors) if (o !== a) pool.add(o)
        return pool[rnd.nextInt(pool.size)]
    }

    private fun doAccuse(a: Actor) {
        accuseTarget = a
        a.trustInPlayer = Engine.clamp(a.trustInPlayer - 0.1, 0.0, 1.0)
        line("あなた：「" + a.name + "、その予告は嘘だ」")
        toFinal()
    }

    private fun doPersuade(a: Actor, h: Int) {
        persuadeTarget = a
        persuadeHand = h
        line("あなた：「" + a.name + "、" + def.claims[h] + "にしたほうがいい」")
        toFinal()
    }

    private fun doSilent() {
        line("あなた：（何も言わない）")
        toFinal()
    }

    private fun toFinal() {
        line("")
        line("【最終予告】")
        val chosen = HashMap<Actor, Int>()
        for (a in actors) {
            if (a.isPlayer) continue
            var hand = aiChooseClaim(a)
            val pt = persuadeTarget
            if (pt === a && persuadeHand >= 0) {
                if (rnd.nextDouble() < a.trustInPlayer * 0.5) {
                    hand = persuadeHand
                    persuadeWorked = true
                }
            }
            chosen[a] = hand
        }
        for (a in actors) {
            if (a.isPlayer) continue
            aiDeclare(a, chosen[a] ?: rnd.nextInt(def.claims.size))
            val text = aiLine(a, "DECLARE", def.claims[a.declared], "")
            lastLine[a.id] = text
            line(a.name + "：「" + text + "」", emph(a))
        }
        line("")
        line("あなたの最終予告を選んでください。")
        phase = P_FINAL
    }

    private fun playerFinal(h: Int) {
        player.declared = h
        // 全員の最終予告が出そろった。ここで AI の実際の行動が決まる。
        for (a in actors) {
            if (a.isPlayer) continue
            commit(a)
        }
        line("")
        line("あなたの最終予告：" + def.claims[h])
        line(def.actPrompt())
        phase = P_ACT
    }

    private fun playerAct(h: Int) {
        player.actual = h
        player.lying = player.declared != h
        resolve()
    }

    // ---------------------------------------------------------------- 判定

    private fun resolve() {
        val st = stakes()
        val before = HashMap<String, Int>()
        for (a in actors) before[a.id] = a.score

        line("")
        line("【結果】")
        for (a in actors) {
            line(a.name + "　予告 " + def.claims[a.declared] + " → 実際 " + def.claims[a.actual])
        }
        line("")

        def.resolve(actors, st) { line(it) }

        for (a in actors) {
            if (a.isPlayer) continue
            a.observedCount++
            if (a.declared == a.actual) a.observedMatch++
            if (a.intensity == "high") {
                a.highCount++
                if (a.declared == a.actual) a.highMatch++
            }
            memory.record(a.id, a.declared == a.actual, a.intensity == "high")
            if (a.declared == a.actual) {
                a.trustInPlayer = Engine.clamp(a.trustInPlayer + 0.05, 0.0, 1.0)
            }
        }

        player.observedCount++
        if (player.declared == player.actual) player.observedMatch++
        // AI 側もプレイヤーを観測する。読み合いを片側だけにしない。
        memory.record("player", player.declared == player.actual, false)

        val pt2 = persuadeTarget
        if (pt2 != null) {
            if (persuadeWorked && pt2.actual == persuadeHand) {
                line("説得成立：" + pt2.name + "は勧めたとおりに動いた")
            } else if (persuadeWorked) {
                line("説得は届いたが裏切られた：" + pt2.name + "は勧めを予告して別のことをした")
            } else {
                line("説得不成立：" + pt2.name + "は勧めを聞き入れなかった")
            }
        }

        val at = accuseTarget
        if (at != null) {
            if (at.declared != at.actual) {
                player.score += st
                line("看破成功：" + at.name + "は予告を破っていた（+" + st + "）")
            } else {
                player.score -= st
                line("誤射：" + at.name + "は予告通りだった（-" + st + "）")
                at.trustInPlayer = Engine.clamp(at.trustInPlayer - 0.1, 0.0, 1.0)
            }
        }

        line("")
        for (a in actors) {
            line(a.name + "：" + a.score)
        }

        recordRound(st, before)
        phase = P_RESULT
    }

    private fun recordRound(st: Int, before: Map<String, Int>) {
        val acts = ArrayList<ActRecord>()
        for (a in actors) {
            acts.add(
                ActRecord(
                    a.id,
                    a.name,
                    a.isPlayer,
                    a.declared,
                    a.actual,
                    a.intensity,
                    lastLine[a.id] ?: ""
                )
            )
        }
        val after = HashMap<String, Int>()
        for (a in actors) after[a.id] = a.score

        val at = accuseTarget
        records.add(
            RoundRecord(
                round,
                st,
                acts,
                at?.name,
                at != null && at.declared != at.actual,
                persuadeTarget?.name,
                persuadeHand,
                persuadeWorked,
                before,
                after
            )
        )
    }

    private fun finish() {
        log.clear()
        line("── " + def.displayName + " 終了")
        line("")

        var best = actors[0]
        for (a in actors) if (a.score > best.score) best = a
        line("勝者：" + best.name + "（" + best.score + "）")
        line("")
        line("【観測された傾向】")
        line("断定ではなく、このセッションで観測された範囲の傾向です。")
        line("")

        for (a in actors) {
            if (a.isPlayer) continue
            val n = a.observedCount
            val rate = Math.round(a.matchRate() * 100).toInt()
            val hr = Math.round(a.highMatchRate() * 100).toInt()
            line(a.name)
            line("　予告一致率　" + rate + "%（観測 " + n + "回）")
            if (a.highCount > 0) {
                line("　強く断言した時の一致率　" + hr + "%（" + a.highCount + "回）")
            } else {
                line("　強く断言した場面はありませんでした")
            }
            line("　確信度　" + confidence(n))
            line("")
        }

        val pr = Math.round(player.matchRate() * 100).toInt()
        line("あなた")
        line("　予告一致率　" + pr + "%")
        line("")

        val pn = memory.observed("player")
        if (pn > 0) {
            val lr = Math.round(memory.matchRate("player") * 100).toInt()
            line("【相手から見たあなた】")
            line("　通算の予告一致率 " + lr + "%（観測 " + pn + "回）")
            line(readOfPlayer(lr))
            line("")
        }

        if (!sessionCounted) {
            memory.finishSession()
            sessionCounted = true
        }
        line("── これまでの累積観測（" + memory.sessions() + "セッション）")
        line("")
        for (a in actors) {
            if (a.isPlayer) continue
            val n = memory.observed(a.id)
            if (n == 0) continue
            val r = Math.round(memory.matchRate(a.id) * 100).toInt()
            line(a.name + "　一致率 " + r + "%　観測 " + n + "回　確信度 " + memory.confidence(a.id))
            val hn = memory.highObserved(a.id)
            if (hn > 0) {
                val hr = Math.round(memory.highMatchRate(a.id) * 100).toInt()
                line("　強く断言した時　一致率 " + hr + "%（" + hn + "回）")
            }
        }
        line("")
        phase = P_END
    }

    // ---------------------------------------------------------------- リプレイ

    private fun toReplay(index: Int) {
        replayIndex = Engine.clamp(index.toDouble(), 0.0, (records.size - 1).toDouble()).toInt()
        val rec = records[replayIndex]
        phase = P_REPLAY
        log.clear()

        line("── 第" + (rec.index + 1) + "ラウンド（配点 " + rec.stakes + "）")
        line("")
        line("終わったあとなので、誰が予告を守ったかが分かります。")
        line("")

        val diff = Replay.actual(rec)
        for (r in rec.acts) {
            val mark = if (r.kept) "守った" else "破った"
            line(r.name + "　予告 " + def.claims[r.declared] + " → 実際 " + def.claims[r.actual] + "　" + mark)
            if (r.line.isNotEmpty()) {
                val strength = when (r.intensity) {
                    "high" -> "強く"
                    "low" -> "弱く"
                    else -> ""
                }
                line("　" + strength + "「" + r.line + "」", r.intensity == "high")
            }
            line("　このラウンドの増減　" + signed(diff[r.id] ?: 0))
            line("")
        }

        if (rec.accusedName != null) {
            val res = if (rec.accuseHit) "看破成功" else "誤射"
            line("あなたは " + rec.accusedName + " を追及した → " + res)
        }
        if (rec.persuadedName != null) {
            val res = if (rec.persuadeHeard) "予告は動いた" else "聞き入れられなかった"
            line("あなたは " + rec.persuadedName + " に " + def.claims[rec.persuadedClaim] + " を勧めた → " + res)
        }
    }

    private fun toIf() {
        val rec = records[replayIndex]
        phase = P_IF
        log.clear()

        val me = rec.playerAct()
        line("── 第" + (rec.index + 1) + "ラウンドのもしも")
        line("")
        line("他の参加者の行動は当時のまま固定して、")
        line("あなたの行動だけを差し替えると、増減はこう変わります。")
        line("")

        val base = Replay.simulate(def, rec, me?.actual ?: 0)
        val basePlayer = base["player"] ?: 0

        for (h in def.claims.indices) {
            val sim = Replay.simulate(def, rec, h)
            val v = sim["player"] ?: 0
            val d = v - basePlayer
            val tag = if (me != null && h == me.actual) "　← 実際に選んだ手" else ""
            val delta = if (d == 0) "" else "（" + signed(d) + "）"
            line(def.claims[h] + "　" + signed(v) + " " + delta + tag)
        }

        line("")
        line("予告は " + def.claims[me?.declared ?: 0] + " でした。")
        line("予告どおりに動けば一致ボーナスが付き、外せば読み合いで有利になります。")
    }

    /** 相手側がプレイヤーをどう扱うかを、断定せずに伝える */
    private fun readOfPlayer(rate: Int): String {
        if (rate >= 70) {
            return "　予告を守る相手だと見られています。勧めは通りやすいぶん、行動も読まれます。"
        }
        if (rate <= 35) {
            return "　予告を破る相手だと見られています。行動は読まれにくいぶん、勧めは通りません。"
        }
        return "　まだ決めかねられています。"
    }

    private fun signed(v: Int): String {
        return if (v > 0) "+" + v else v.toString()
    }

    private fun confidence(n: Int): String {
        if (n <= 2) return "低（観測数が足りません）"
        if (n <= 4) return "中"
        return "高"
    }
}
