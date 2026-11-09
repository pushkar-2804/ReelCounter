package com.reelcounter

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Today's total, an hourly chart and list, and a day-by-day history. Built in code, no XML layouts. */
class MainActivity : Activity() {

    private lateinit var store: CountStore
    private var selectedDay: LocalDate = LocalDate.now()

    private lateinit var statusText: TextView
    private lateinit var enableButton: Button
    private lateinit var dayLabel: TextView
    private lateinit var nextDayButton: Button
    private lateinit var totalText: TextView
    private lateinit var splitText: TextView
    private lateinit var chart: HourlyChartView
    private lateinit var hourList: LinearLayout
    private lateinit var historyList: LinearLayout

    private val density by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CountStore.get(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = isServiceEnabled()
        statusText.text =
            if (enabled) "● Counting is ON" else "● Counting is OFF — enable the accessibility service"
        statusText.setTextColor(if (enabled) 0xFF2E7D32.toInt() else 0xFFC62828.toInt())
        enableButton.text = if (enabled) "Accessibility settings" else "Enable counting"

        val today = LocalDate.now()
        dayLabel.text = when (selectedDay) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> selectedDay.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))
        }
        nextDayButton.isEnabled = selectedDay < today

        val hourly = store.hourly(selectedDay)
        val ig = hourly.getValue(Source.INSTAGRAM)
        val yt = hourly.getValue(Source.YOUTUBE)
        totalText.text = (ig.sum() + yt.sum()).toString()
        splitText.text = "Instagram Reels: ${ig.sum()}   ·   YouTube Shorts: ${yt.sum()}"
        chart.setData(hourly)

        hourList.removeAllViews()
        val activeHours = (0 until 24).filter { ig[it] + yt[it] > 0 }
        if (activeHours.isEmpty()) {
            hourList.addView(text("Nothing scrolled on this day.", 14f, 0xFF888888.toInt()))
        }
        for (h in activeHours) {
            hourList.addView(
                row("%02d:00 – %02d:00".format(h, (h + 1) % 24), "IG ${ig[h]}  ·  YT ${yt[h]}  ·  ${ig[h] + yt[h]}")
            )
        }

        historyList.removeAllViews()
        val days = store.dailySummaries()
        if (days.isEmpty()) {
            historyList.addView(text("No history yet.", 14f, 0xFF888888.toInt()))
        }
        for (d in days) {
            val r = row(
                d.day.format(DateTimeFormatter.ofPattern("EEE, d MMM")),
                "IG ${d.instagram}  ·  YT ${d.youtube}  ·  ${d.total}"
            )
            if (d.day == selectedDay) r.setBackgroundColor(0x14000000)
            r.setOnClickListener {
                selectedDay = d.day
                refresh()
            }
            historyList.addView(r)
        }
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val me = "$packageName/${ReelCounterService::class.java.name}"
        val meShort = "$packageName/.${ReelCounterService::class.java.simpleName}"
        return enabled.split(':').any { it.equals(me, true) || it.equals(meShort, true) }
    }

    private fun exportCsv() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Reel Counter log")
            putExtra(Intent.EXTRA_TEXT, store.exportCsv())
        }
        startActivity(Intent.createChooser(send, "Export log (CSV)"))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear all data?")
            .setMessage("This permanently deletes every logged count.")
            .setPositiveButton("Clear") { _, _ ->
                store.clearAll()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- UI ------------------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        root.addView(text("Reel Counter", 26f, 0xFF111111.toInt(), bold = true))
        statusText = text("", 14f, 0xFF000000.toInt())
        root.addView(statusText, margins(top = 8))

        enableButton = Button(this).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(enableButton, margins(top = 4))

        // Day picker: ◀  Today  ▶
        val dayRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val prev = Button(this).apply {
            text = "◀"
            setOnClickListener {
                selectedDay = selectedDay.minusDays(1)
                refresh()
            }
        }
        nextDayButton = Button(this).apply {
            text = "▶"
            setOnClickListener {
                if (selectedDay < LocalDate.now()) selectedDay = selectedDay.plusDays(1)
                refresh()
            }
        }
        dayLabel = text("", 18f, 0xFF111111.toInt(), bold = true).apply { gravity = Gravity.CENTER }
        dayLabel.setOnClickListener {
            selectedDay = LocalDate.now()
            refresh()
        }
        dayRow.addView(prev, LinearLayout.LayoutParams(dp(56), WRAP_CONTENT))
        dayRow.addView(dayLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        dayRow.addView(nextDayButton, LinearLayout.LayoutParams(dp(56), WRAP_CONTENT))
        root.addView(dayRow, margins(top = 16))

        // Total for the selected day
        val card = card()
        totalText = text("0", 48f, 0xFF111111.toInt(), bold = true).apply { gravity = Gravity.CENTER }
        card.addView(totalText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        card.addView(
            text("reels & shorts scrolled", 13f, 0xFF666666.toInt()).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        )
        splitText = text("", 13f, 0xFF333333.toInt()).apply { gravity = Gravity.CENTER }
        card.addView(splitText, margins(top = 8).apply { width = MATCH_PARENT })
        root.addView(card, margins(top = 8))

        // By hour
        root.addView(section("By hour"))
        val chartCard = card()
        chart = HourlyChartView(this)
        chartCard.addView(chart, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        chartCard.addView(legend(), margins(top = 8))
        hourList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chartCard.addView(hourList, margins(top = 8).apply { width = MATCH_PARENT })
        root.addView(chartCard)

        // By day
        root.addView(section("By day"))
        val historyCard = card()
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyCard.addView(historyList, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(historyCard)

        val actions = LinearLayout(this)
        actions.addView(
            Button(this).apply {
                text = "Export CSV"
                setOnClickListener { exportCsv() }
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        actions.addView(
            Button(this).apply {
                text = "Clear data"
                setOnClickListener { confirmClear() }
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        )
        root.addView(actions, margins(top = 16))

        root.addView(
            text(
                "Tip: on Android 13+, if the toggle is greyed out, open App info → ⋮ → " +
                    "\"Allow restricted settings\" first.",
                12f, 0xFF888888.toInt()
            ),
            margins(top = 12)
        )

        return ScrollView(this).apply {
            setBackgroundColor(0xFFF4F4F6.toInt())
            addView(root)
        }
    }

    private fun legend(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        addView(swatch(HourlyChartView.COLOR_INSTAGRAM))
        addView(text(" Instagram    ", 12f, 0xFF444444.toInt()))
        addView(swatch(HourlyChartView.COLOR_YOUTUBE))
        addView(text(" YouTube", 12f, 0xFF444444.toInt()))
    }

    private fun swatch(color: Int): View = View(this).apply {
        setBackgroundColor(color)
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = 16 * density
        }
    }

    private fun section(title: String): TextView =
        text(title, 16f, 0xFF111111.toInt(), bold = true).apply { layoutParams = margins(20, 8) }

    private fun row(left: String, right: String): LinearLayout = LinearLayout(this).apply {
        setPadding(dp(4), dp(8), dp(4), dp(8))
        addView(text(left, 14f, 0xFF222222.toInt()), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(text(right, 14f, 0xFF555555.toInt()))
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun margins(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(v: Int) = (v * density).toInt()
}
