package com.reelcounter

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.time.LocalDate

/**
 * Watches Instagram and YouTube through the accessibility API.
 *
 * - "Reel page open" = one of the known full-screen pager views is visible on screen.
 * - "One reel scrolled" = the full-screen pager settles on a different item
 *   (or, when the app doesn't report item indices, one distinct swipe gesture).
 */
class ReelCounterService : AccessibilityService() {

    private lateinit var store: CountStore
    private var overlay: TextView? = null

    private var activeSource: Source? = null
    private var sessionCount = 0
    private var lastPageCheckAt = 0L
    private var lastIndex = -1
    private var lastScrollAt = 0L
    private var lastPage: AccessibilityNodeInfo? = null
    private val handler = Handler(Looper.getMainLooper())
    private val settleCheck = Runnable { activeSource?.let { if (it in ITEM_IDS) checkCurrentPage(it) } }

    override fun onServiceConnected() {
        super.onServiceConnected()
        store = CountStore.get(this)
        running = true
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(settleCheck)
        hideOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        val source = SOURCES[pkg]

        if (source == null) {
            // Another app came to the foreground: the user left Reels/Shorts.
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                pkg != packageName && pkg !in TRANSIENT_PACKAGES
            ) {
                leaveReels()
            }
            return
        }

        val now = SystemClock.uptimeMillis()
        val isScroll = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        // Content-changed events arrive constantly during playback, so throttle tree lookups.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            (isScroll && activeSource != source) ||
            now - lastPageCheckAt > PAGE_CHECK_INTERVAL_MS
        ) {
            lastPageCheckAt = now
            if (isReelPageVisible(source)) enterReels(source) else leaveReels()
            if (activeSource == source && source in ITEM_IDS) checkCurrentPage(source)
        }
        if (activeSource == source && source in ITEM_IDS) {
            // The app can go quiet once a swipe settles, so look again after events stop.
            handler.removeCallbacks(settleCheck)
            handler.postDelayed(settleCheck, SETTLE_CHECK_DELAY_MS)
        }

        if (isScroll && activeSource == source && source !in ITEM_IDS) handleScroll(event, source, now)
    }

    /**
     * For apps that don't report scroll events for every swipe (YouTube Shorts): count each time
     * a different page view fills the screen. Mid-swipe the old and new reel are both on screen,
     * so they are always different views; a swipe that springs back leaves the same view in place.
     */
    private fun checkCurrentPage(source: Source) {
        val page = settledPage(source) ?: return
        if (page == lastPage) return
        if (lastPage != null) countOne(source)
        lastPage = page
    }

    private fun settledPage(source: Source): AccessibilityNodeInfo? {
        val id = ITEM_IDS[source] ?: return null
        val minHeight = resources.displayMetrics.heightPixels * 0.75
        val bounds = Rect()
        for (root in appRoots(source)) {
            for (page in root.findAccessibilityNodeInfosByViewId(id)) {
                if (!page.isVisibleToUser) continue
                page.getBoundsInScreen(bounds)
                if (bounds.height() >= minHeight) return page
            }
        }
        return null // mid-swipe: no page fills the screen yet
    }

    private fun handleScroll(event: AccessibilityEvent, source: Source, now: Long) {
        val node = event.source
        if (node != null && !isReelPager(node, source)) return // e.g. comments sheet, captions

        val from = event.fromIndex
        val to = event.toIndex
        if (from >= 0 && to >= 0 && event.itemCount > 0) {
            if (lastIndex == -1) {
                // First scroll since entering: we don't know the starting item yet.
                // Mid-swipe events show from = the item we started on.
                lastIndex = from
                if (from != to) return
            }
            // A full-screen pager shows exactly one item once the swipe settles.
            if (from == to && from != lastIndex) {
                lastIndex = from
                countOne(source)
            }
        } else {
            // No index info: treat a burst of scroll events as one swipe.
            if (now - lastScrollAt > GESTURE_GAP_MS) countOne(source)
            lastScrollAt = now
        }
    }

    private fun isReelPager(node: AccessibilityNodeInfo, source: Source): Boolean {
        val id = node.viewIdResourceName
        if (id != null && id in PAGER_IDS.getValue(source)) return true
        // Fallback in case the app renamed its views: a scrolling container that
        // fills most of the screen is the reel pager, not a bottom sheet.
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val screenHeight = resources.displayMetrics.heightPixels
        return bounds.height() >= screenHeight * 0.6
    }

    private fun appRoots(source: Source): List<AccessibilityNodeInfo> {
        val pkg = PACKAGE_OF.getValue(source)
        return buildList {
            runCatching { windows }.getOrNull()?.forEach { w -> w.root?.let(::add) }
            rootInActiveWindow?.let(::add)
        }.filter { it.packageName?.toString() == pkg }
    }

    private fun isReelPageVisible(source: Source): Boolean {
        val ids = PAGE_IDS.getValue(source)
        for (root in appRoots(source)) {
            for (id in ids) {
                val found = root.findAccessibilityNodeInfosByViewId(id)
                if (found.any { it.isVisibleToUser }) return true
            }
        }
        return false
    }

    private fun enterReels(source: Source) {
        if (activeSource == source) return
        activeSource = source
        sessionCount = 0
        lastIndex = -1
        lastScrollAt = 0L
        lastPage = null
        showOverlay()
        updateOverlay()
    }

    private fun leaveReels() {
        if (activeSource == null) return
        activeSource = null
        lastIndex = -1
        lastPage = null
        hideOverlay()
    }

    private fun countOne(source: Source) {
        store.increment(source)
        sessionCount++
        updateOverlay()
    }

    // ---- Overlay -------------------------------------------------------------------------

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundResource(R.drawable.overlay_bg)
            val pad = dp(10)
            setPadding(pad, dp(6), pad, dp(6))
            gravity = Gravity.CENTER
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(96)
        }
        runCatching { wm.addView(view, params) }.onSuccess { overlay = view }
    }

    private fun hideOverlay() {
        val view = overlay ?: return
        overlay = null
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view) }
    }

    private fun updateOverlay() {
        val view = overlay ?: return
        val source = activeSource ?: return
        val today = LocalDate.now()
        val appToday = store.totalFor(today, source)
        val allToday = store.totalFor(today)
        val name = if (source == Source.INSTAGRAM) "Reels" else "Shorts"
        view.text = "This session: $sessionCount\n$name today: $appToday · All: $allToday"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        @Volatile var running = false
            private set

        private const val PAGE_CHECK_INTERVAL_MS = 500L
        private const val GESTURE_GAP_MS = 700L
        private const val SETTLE_CHECK_DELAY_MS = 400L

        private const val IG = "com.instagram.android"
        private const val YT = "com.google.android.youtube"

        private val SOURCES = mapOf(IG to Source.INSTAGRAM, YT to Source.YOUTUBE)
        private val PACKAGE_OF = mapOf(Source.INSTAGRAM to IG, Source.YOUTUBE to YT)

        /** Views that only exist on the full-screen Reels / Shorts player. */
        private val PAGE_IDS = mapOf(
            Source.INSTAGRAM to listOf(
                "$IG:id/clips_viewer_view_pager",
                "$IG:id/clips_viewer_pager",
                "$IG:id/clips_video_container",
            ),
            Source.YOUTUBE to listOf(
                "$YT:id/reel_recycler",
                "$YT:id/reel_player_page_container",
                "$YT:id/reel_watch_player",
            ),
        )

        /** The vertical pager whose scroll events mean "next reel". */
        private val PAGER_IDS = mapOf(
            Source.INSTAGRAM to setOf(
                "$IG:id/clips_viewer_view_pager",
                "$IG:id/clips_viewer_pager",
            ),
            Source.YOUTUBE to setOf("$YT:id/reel_recycler"),
        )

        /** One full-screen page per reel, for apps whose pager doesn't report every swipe. */
        private val ITEM_IDS = mapOf(
            Source.YOUTUBE to "$YT:id/reel_player_page_container",
        )

        /** Windows that can pop over Reels without the user actually leaving. */
        private val TRANSIENT_PACKAGES = setOf(
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
        )
    }
}
