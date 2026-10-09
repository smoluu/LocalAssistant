package com.localassistant.app.test

import com.localassistant.app.ui.common.bestMatchCost
import com.localassistant.app.ui.common.convertPcmToWav
import com.localassistant.app.ui.common.extractWakeFeatures
import com.localassistant.app.ui.common.mfcc
import com.localassistant.app.ui.common.wakeCostThreshold
import org.junit.Test

/**
 * Unit tests for the on-device wake-word detector.
 *
 * The feature-extraction cases matter because a wrong frame/shift/FFT size
 * silently produces features that no reference can ever match, and the
 * threshold case decides how sensitive the wake trigger is.
 */
class WakeWordDetectorTest {

    private fun sinePcm(samples: Int, amplitude: Int, period: Int): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0..<samples) {
            val phase = kotlin.math.sin(2.0 * kotlin.math.PI * (i % period) / period)
            val value = (amplitude * phase).toInt()
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun pcmToFloats(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        for (i in 0..<out.size) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toFloat() / 32768.0f
        }
        return out
    }

    private fun framesOf(features: FloatArray): Int = features.size / 8

    @Test
    fun mfcc_emitsOneFeatureVectorPerTenMsFrame() {
        val features = mfcc(pcmToFloats(sinePcm(16000, 8000, 320)))
        // (16000 samples - 640 frame) / 160 shift + 1 = 97 frames, 8 floats each.
        assert(framesOf(features) == 97) { "expected one frame per 10 ms, got ${framesOf(features)}" }
        assert(features.all { it.isFinite() }) { "a feature vector held NaN or infinity" }
    }

    @Test
    fun mfcc_rejectsClipsShorterThanOneFrame() {
        assert(mfcc(pcmToFloats(sinePcm(639, 8000, 320))).isEmpty()) {
            "a clip shorter than the MFCC frame produced features"
        }
    }

    @Test
    fun extractWakeFeatures_matchesMfccOfTheSameSignal() {
        val pcm = sinePcm(4000, 6000, 200)
        val fromWav = extractWakeFeatures(convertPcmToWav(pcm, 16000))
        val direct = mfcc(pcmToFloats(pcm))
        assert(fromWav.size == direct.size) { "the WAV path and the direct path disagree on length" }
        assert(
            (0..<direct.size).all { kotlin.math.abs(fromWav[it] - direct[it]) < 1.0e-3 }
        ) { "the WAV path decoded the clip differently from the raw PCM path" }
    }

    @Test
    fun extractWakeFeatures_resamplesLowerSampleRates() {
        // The energy-saving mode records at 8 kHz; the detector works at 16 kHz,
        // so the half-rate clip must still yield the same frame count as the
        // 16 kHz recording of the same duration.
        val atEightK = extractWakeFeatures(convertPcmToWav(sinePcm(8000, 8000, 160), 8000))
        val atSixteenK = extractWakeFeatures(convertPcmToWav(sinePcm(16000, 8000, 320), 16000))
        assert(framesOf(atEightK) == framesOf(atSixteenK)) {
            "8 kHz audio was not resampled to the detector's frame grid"
        }
    }

    @Test
    fun bestMatchCost_matchesAReferenceToItself() {
        val features = mfcc(pcmToFloats(sinePcm(4000, 7000, 250)))
        assert(bestMatchCost(features, listOf(features)) == 0.0f) {
            "a reference did not match itself"
        }
    }

    @Test
    fun bestMatchCost_separatesDifferentSignals() {
        val spoken = mfcc(pcmToFloats(sinePcm(4000, 7000, 250)))
        val other = mfcc(pcmToFloats(sinePcm(4000, 3000, 97)))
        val cost = bestMatchCost(spoken, listOf(other))
        assert(cost > 0.0f && cost.isFinite()) { "unrelated audio matched at zero cost" }
    }

    @Test
    fun bestMatchCost_returnsMaxForMissingInput() {
        val features = mfcc(pcmToFloats(sinePcm(4000, 7000, 250)))
        assert(bestMatchCost(features, emptyList()) == Float.MAX_VALUE) {
            "no references reported a matchable cost"
        }
        assert(bestMatchCost(FloatArray(0), listOf(features)) == Float.MAX_VALUE) {
            "an empty clip reported a matchable cost"
        }
    }

    @Test
    fun wakeCostThreshold_clampsSensitivity() {
        assert(wakeCostThreshold(0.0f) == 10.0f) { "the loosest setting must accept the widest cost" }
        assert(wakeCostThreshold(1.0f) == 1.0f) { "the strictest setting must accept only near matches" }
        assert(wakeCostThreshold(-1.0f) == 10.0f) { "sensitivity below zero was not clamped" }
        assert(wakeCostThreshold(2.0f) == 1.0f) { "sensitivity above one was not clamped" }
    }
}
