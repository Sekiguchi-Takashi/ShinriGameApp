package com.appathy.shinrigame

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private var game: Game? = null
    private var frame: LinearLayout? = null
    private var header: TextView? = null
    private var banner: TextView? = null
    private var tableRow: LinearLayout? = null
    private var logView: TextView? = null
    private var scroll: ScrollView? = null
    private var buttons: LinearLayout? = null

    private val bgWood = Color.parseColor("#C2AA8E")
    private val inkMain = Color.parseColor("#2C3E58")
    private val inkSub = Color.parseColor("#5B6472")
    private val declareRed = Color.parseColor("#C8324B")
    private val paper = Color.parseColor("#FBF8F3")

    /** 予告は薄いオレンジ、実行は薄い緑。何を決める場面かを色で分ける。 */
    private val btnDeclare = Color.parseColor("#FBDFC0")
    private val btnAct = Color.parseColor("#CFE8D0")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.setBackgroundColor(paper)
        outer.setPadding(10, 10, 10, 10)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(22, 20, 22, 20)
        outer.addView(
            root,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        frame = root

        val bn = TextView(this)
        bn.textSize = 13f
        bn.gravity = Gravity.CENTER
        bn.setPadding(0, 6, 0, 10)
        bn.setTypeface(null, Typeface.BOLD)
        root.addView(bn)
        banner = bn

        val h = TextView(this)
        h.textSize = 15f
        h.setTextColor(inkMain)
        h.setTypeface(null, Typeface.BOLD)
        h.setPadding(0, 0, 0, 12)
        root.addView(h)
        header = h

        val tr = LinearLayout(this)
        tr.orientation = LinearLayout.HORIZONTAL
        tr.setBackgroundColor(bgWood)
        tr.setPadding(8, 12, 8, 12)
        root.addView(
            tr,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        tableRow = tr

        val sv = ScrollView(this)
        val lv = TextView(this)
        lv.textSize = 15f
        lv.setTextColor(inkSub)
        lv.setLineSpacing(8f, 1f)
        lv.setPadding(0, 16, 0, 0)
        sv.addView(lv)
        root.addView(
            sv,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        scroll = sv
        logView = lv

        val bl = LinearLayout(this)
        bl.orientation = LinearLayout.VERTICAL
        bl.setPadding(0, 12, 0, 0)
        root.addView(bl)
        buttons = bl

        setContentView(outer)

        game = Game(this)
        render()
    }

    private fun cardRes(asset: String?): Int {
        return when (asset) {
            "card_rock" -> R.drawable.card_rock
            "card_scissors" -> R.drawable.card_scissors
            "card_paper" -> R.drawable.card_paper
            else -> R.drawable.card_back
        }
    }

    /** 専用の絵がないゲーム用の文字カード */
    private fun textCard(label: String): TextView {
        val tv = TextView(this)
        tv.text = label
        tv.textSize = 18f
        tv.gravity = Gravity.CENTER
        tv.setTextColor(inkMain)
        tv.setTypeface(null, Typeface.BOLD)
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F3EFE7"))
        bg.setStroke(3, inkMain)
        bg.cornerRadius = 10f
        tv.background = bg
        tv.setPadding(0, 22, 0, 22)
        return tv
    }

    /** 表情差分が増えても書き換えずに済むよう、名前で引く */
    private fun portraitRes(name: String): Int {
        val id = resources.getIdentifier(name, "drawable", packageName)
        if (id != 0) return id
        return R.drawable.char_player
    }

    /** そのキャラがプレイヤーをどれだけ信じているか */
    private fun trustBar(trust: Double): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        val filled = Math.round(trust * 10).toInt()
        for (i in 0 until 10) {
            val cell = TextView(this)
            cell.setBackgroundColor(
                if (i < filled) Color.parseColor("#7ED2A0") else Color.parseColor("#8C7B63")
            )
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.rightMargin = 1
            row.addView(cell, lp)
        }
        return row
    }

    /** 予告フェーズは画面全体を細い赤枠で囲う */
    private fun applyFrame(declaring: Boolean) {
        val f = frame ?: return
        val bg = GradientDrawable()
        bg.setColor(paper)
        if (declaring) {
            bg.setStroke(3, declareRed)
        } else {
            bg.setStroke(3, Color.parseColor("#E4DDD0"))
        }
        f.background = bg
    }

    private fun renderBanner(g: Game) {
        val b = banner ?: return
        if (g.isDeclarePhase()) {
            b.text = "◆ " + g.phaseLabel() + "フェーズ　この宣言は嘘でもかまいません ◆"
            b.setTextColor(declareRed)
        } else {
            b.text = g.phaseLabel() + "フェーズ"
            b.setTextColor(inkSub)
        }
    }

    private fun renderTable(g: Game) {
        val tr = tableRow ?: return
        tr.removeAllViews()
        if (g.phase == Game.P_SELECT || g.phase == Game.P_ROSTER || g.phase == Game.P_END) {
            tr.visibility = android.view.View.GONE
            return
        }
        tr.visibility = android.view.View.VISIBLE

        // 席数が増えるほど1席あたりを詰める
        val seatList = g.table()
        val seats = seatList.size
        val faceSize = when {
            seats <= 4 -> 110
            seats == 5 -> 88
            else -> 72
        }
        val nameSize = if (seats <= 4) 12f else 10f
        for (seat in seatList) {
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.gravity = Gravity.CENTER_HORIZONTAL
            col.setPadding(if (seats <= 4) 5 else 2, 0, if (seats <= 4) 5 else 2, 0)

            val face = ImageView(this)
            face.setImageResource(portraitRes(seat.portrait))
            face.scaleType = ImageView.ScaleType.FIT_CENTER
            val fp = LinearLayout.LayoutParams(faceSize, faceSize)
            fp.bottomMargin = 4
            col.addView(face, fp)

            val name = TextView(this)
            name.text = seat.name
            name.textSize = nameSize
            name.gravity = Gravity.CENTER
            name.setTextColor(Color.WHITE)
            name.setTypeface(null, Typeface.BOLD)
            col.addView(
                name,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            if (seat.cardHand >= 0 && seat.cardAsset == null) {
                col.addView(
                    textCard(seat.cardLabel),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            } else {
                val iv = ImageView(this)
                iv.setImageResource(cardRes(seat.cardAsset))
                iv.adjustViewBounds = true
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                col.addView(
                    iv,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            if (!seat.isPlayer) {
                col.addView(trustBar(seat.trust), LinearLayout.LayoutParams(faceSize - 20, 8))

                val memo = TextView(this)
                if (seat.memoCount > 0) {
                    val r = Math.round(seat.memoRate * 100).toInt()
                    memo.text = "一致 " + r + "%\n" + seat.memoCount + "回"
                } else {
                    memo.text = "未観測"
                }
                memo.textSize = if (seats <= 4) 10f else 9f
                memo.gravity = Gravity.CENTER
                memo.setTextColor(Color.parseColor("#FFF3DE"))
                memo.setPadding(0, 3, 0, 0)
                col.addView(
                    memo,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            val note = TextView(this)
            note.text = seat.note
            note.textSize = if (seats <= 4) 11f else 9f
            note.gravity = Gravity.CENTER
            note.setTextColor(Color.parseColor("#FFF3DE"))
            col.addView(
                note,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            tr.addView(
                col,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
    }

    private fun renderLog(g: Game) {
        val sb = SpannableStringBuilder()
        for (l in g.log) {
            val start = sb.length
            sb.append(l.text).append("\n")
            if (l.emphasis) {
                val end = sb.length
                sb.setSpan(RelativeSizeSpan(1.18f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(
                    StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        logView?.setText(sb)
    }

    private fun render() {
        val g = game ?: return
        applyFrame(g.isDeclarePhase())
        renderBanner(g)
        header?.text = g.header()
        renderTable(g)
        renderLog(g)

        val bl = buttons ?: return
        bl.removeAllViews()
        val tint = when (g.phase) {
            Game.P_DECLARE, Game.P_FINAL -> btnDeclare
            Game.P_ACT -> btnAct
            else -> 0
        }
        for (opt in g.options()) {
            val b = Button(this)
            b.text = opt.label
            b.setAllCaps(false)
            b.textSize = 15f
            if (tint != 0) {
                val bg = GradientDrawable()
                bg.setColor(tint)
                bg.cornerRadius = 12f
                bg.setStroke(2, Color.parseColor("#00000022"))
                b.background = bg
                b.setTextColor(inkMain)
            }
            b.setOnClickListener {
                opt.action()
                if (g.wantVersus) {
                    g.wantVersus = false
                    startActivity(android.content.Intent(this, VersusActivity::class.java))
                }
                render()
            }
            bl.addView(
                b,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        scroll?.post {
            scroll?.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
