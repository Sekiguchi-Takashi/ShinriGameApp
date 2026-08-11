package com.appathy.shinrigame

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private var game: Game? = null
    private var header: TextView? = null
    private var logView: TextView? = null
    private var scroll: ScrollView? = null
    private var buttons: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 32, 32, 32)
        root.setBackgroundColor(Color.WHITE)

        val h = TextView(this)
        h.textSize = 15f
        h.setTextColor(Color.parseColor("#B0338C"))
        h.setPadding(0, 0, 0, 16)
        root.addView(h)
        header = h

        val sv = ScrollView(this)
        val lv = TextView(this)
        lv.textSize = 15f
        lv.setTextColor(Color.parseColor("#222222"))
        lv.setLineSpacing(6f, 1f)
        sv.addView(lv)
        root.addView(
            sv,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        scroll = sv
        logView = lv

        val bl = LinearLayout(this)
        bl.orientation = LinearLayout.VERTICAL
        bl.setPadding(0, 16, 0, 0)
        root.addView(bl)
        buttons = bl

        setContentView(root)

        game = Game(this)
        render()
    }

    private fun render() {
        val g = game ?: return
        header?.text = g.header()
        logView?.text = g.log.toString()

        val bl = buttons ?: return
        bl.removeAllViews()
        for (opt in g.options()) {
            val b = Button(this)
            b.text = opt.label
            b.setAllCaps(false)
            b.textSize = 15f
            b.setOnClickListener {
                opt.action()
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
