package com.shazan.airbridge.input

import android.content.SharedPreferences

/**
 * Learns how hard this person normally touches, so a clearly harder press can act as a force click.
 *
 * Phones report touch "pressure" mostly from the contact area: pressing harder flattens the
 * fingertip, so the value rises. Absolute values differ wildly between phones (some always report
 * 1.0), so nothing is hard-coded: the calibrator keeps the peak pressure of recent ordinary
 * one-finger touches and sets the firm-press threshold at [factor] × their median.
 *
 * - Off until [MIN_SAMPLES] ordinary touches were seen.
 * - Off for good on phones whose pressure does not vary (all samples within [FLAT_RANGE] of each
 *   other): pressing harder could never be told apart there.
 * - Firm presses themselves are never learnt from (they would drag the baseline up).
 */
class FirmPressCalibrator(private val factor: Float = DEFAULT_FACTOR) {
    private val samples = ArrayDeque<Float>()

    val sampleCount: Int get() = samples.size

    fun learn(peak: Float) {
        if (peak.isNaN() || peak <= 0f) return
        samples.addLast(peak)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    /** False when this phone reports the same pressure for every touch. */
    val pressureVaries: Boolean
        get() {
            if (samples.size < MIN_SAMPLES) return true // unknown yet
            val lo = samples.min(); val hi = samples.max()
            return hi - lo > FLAT_RANGE * maxOf(hi, 1e-3f)
        }

    /** Pressure at which a still finger counts as a firm press; infinite while unknown or unsupported. */
    fun threshold(): Float {
        if (samples.size < MIN_SAMPLES || !pressureVaries) return Float.POSITIVE_INFINITY
        val sorted = samples.sorted()
        val median = sorted[sorted.size / 2]
        // Upper quartile of ordinary touches + a margin, so firm pressing is never needed by accident.
        val q3 = sorted[(sorted.size * 3) / 4]
        return maxOf(median * factor, q3 * 1.15f)
    }

    fun save(prefs: SharedPreferences) {
        prefs.edit().putString(PREF_SAMPLES, samples.joinToString(",")).apply()
    }

    fun load(prefs: SharedPreferences) {
        samples.clear()
        prefs.getString(PREF_SAMPLES, null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.forEach(::learn)
    }

    companion object {
        const val MIN_SAMPLES = 8
        const val MAX_SAMPLES = 40
        const val FLAT_RANGE = 0.03f
        const val DEFAULT_FACTOR = 1.45f
        private const val PREF_SAMPLES = "TP_FIRM_SAMPLES"
    }
}

/** Shared by the trackpad and the screen mode for the life of the process. */
object FirmPress {
    val calibrator = FirmPressCalibrator()
    /** User setting; when false no touch is ever a firm press. */
    @Volatile var enabled: Boolean = true

    fun threshold(): Float = if (enabled) calibrator.threshold() else Float.POSITIVE_INFINITY

    /** Called when a one-finger touch ends. */
    fun touchEnded(fingers: Int, peak: Float, wasFirm: Boolean, into: FirmPressCalibrator = calibrator) {
        if (fingers == 1 && !wasFirm) into.learn(peak)
    }
}

/**
 * Raw touch pressure, straight from the [android.view.MotionEvent]s (fed by `Modifier.motionEventSpy`).
 * Compose drops move events in which no finger moved, and a firm press is exactly that: same
 * place, rising pressure. Only one-finger events are passed on (that is all a firm press uses).
 */
class PressureFeed {
    var listener: ((pressure: Float) -> Unit)? = null

    fun onMotionEvent(e: android.view.MotionEvent) {
        val l = listener ?: return
        if (e.pointerCount != 1) return
        val a = e.actionMasked
        if (a != android.view.MotionEvent.ACTION_MOVE && a != android.view.MotionEvent.ACTION_DOWN) return
        var p = e.getPressure(0)
        for (h in 0 until e.historySize) p = maxOf(p, e.getHistoricalPressure(0, h))
        l(p)
    }
}
