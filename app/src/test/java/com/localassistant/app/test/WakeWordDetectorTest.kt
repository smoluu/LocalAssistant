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

    // A clip shaped like speech: loud bursts separated by real gaps. The
    // detector rejects anything without quiet frames next to voiced ones, so
    // fixtures that stand in for an utterance have to be built this way.
    private fun speechPcm(samples: Int, amplitude: Int, period: Int): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0..<samples) {
            if ((i / period) % 4 >= 2) continue
            val phase = kotlin.math.sin(2.0 * kotlin.math.PI * (i % period) / period)
            val value = (amplitude * phase).toInt()
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    // Adds a sine whose period is three samples (~5.3 kHz at 16 kHz), which the
    // detector's 3-tap low-pass averages out exactly.
    private fun addNoise(pcm: ByteArray, amplitude: Int, period: Int): ByteArray {
        val out = ByteArray(pcm.size)
        val samples = pcm.size / 2
        for (i in 0..<samples) {
            val low = pcm[i * 2].toInt() and 0xFF
            val high = (pcm[i * 2 + 1].toInt() shl 8) or low
            val noise = (amplitude * kotlin.math.sin(2.0 * kotlin.math.PI * (i % period) / period)).toInt()
            val sum = (high + noise).coerceIn(-32767, 32767)
            out[i * 2] = (sum and 0xFF).toByte()
            out[i * 2 + 1] = ((sum shr 8) and 0xFF).toByte()
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
        val pcm = speechPcm(8000, 6000, 2000)
        val fromWav = extractWakeFeatures(convertPcmToWav(pcm, 16000))
        val direct = mfcc(pcmToFloats(pcm))
        assert(fromWav.size == direct.size) { "the WAV path and the direct path disagree on length" }
        assert(
            (0..<direct.size).all { kotlin.math.abs(fromWav[it] - direct[it]) < 1.0e-3 }
        ) { "the WAV path decoded the clip differently from the raw PCM path" }
    }

    @Test
    fun extractWakeFeatures_rejectsClipsWithoutSpeech() {
        // A uniform sine has no quiet frame next to a voiced one, so it is room
        // noise as far as the detector is concerned; an all-zero clip is silence.
        // Both must produce no features, which is what stops the wake chat from
        // firing on the silence between utterances.
        assert(framesOf(extractWakeFeatures(convertPcmToWav(sinePcm(8000, 6000, 200), 16000))) == 0) {
            "a clip with no speech-shaped gaps produced features"
        }
        assert(framesOf(extractWakeFeatures(convertPcmToWav(ByteArray(16000), 16000))) == 0) {
            "a silent clip produced features"
        }
    }

    @Test
    fun extractWakeFeatures_resamplesLowerSampleRates() {
        // The energy-saving mode records at 8 kHz; the detector works at 16 kHz,
        // so the half-rate clip must still yield the same frame count as the
        // 16 kHz recording of the same duration.
        val atEightK = extractWakeFeatures(convertPcmToWav(speechPcm(8000, 8000, 1000), 8000))
        val atSixteenK = extractWakeFeatures(convertPcmToWav(speechPcm(16000, 8000, 2000), 16000))
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
    fun bestMatchCost_isIndependentOfVolume() {
        // The same signal at three different volumes. Raw cepstral coefficients
        // are sums of log magnitudes, so they grow with volume; after per-feature
        // normalisation they must not, which is what lets one threshold serve
        // every microphone and every loudness.
        val loud = mfcc(pcmToFloats(sinePcm(4000, 8000, 250)))
        val middle = mfcc(pcmToFloats(sinePcm(4000, 4000, 250)))
        val quiet = mfcc(pcmToFloats(sinePcm(4000, 400, 250)))
        val againstLoud = bestMatchCost(quiet, listOf(loud))
        val againstMiddle = bestMatchCost(quiet, listOf(middle))
        assert(kotlin.math.abs(againstLoud - againstMiddle) < 0.05f) {
            "the same phrase at different volumes cost $againstLoud and $againstMiddle"
        }
    }

    @Test
    fun wakeCostThreshold_scalesWithSensitivity() {
        val references = listOf(
            mfcc(pcmToFloats(sinePcm(4000, 7000, 250))),
            mfcc(pcmToFloats(sinePcm(4000, 3000, 97)))
        )
        val lenient = wakeCostThreshold(0.0f, references)
        val strict = wakeCostThreshold(1.0f, references)
        assert(lenient > strict) { "the loosest setting must accept a wider cost than the strictest" }
        assert(strict >= 0.5f) { "the strictest setting fell below the minimum tolerance" }
        assert(wakeCostThreshold(-1.0f, references) == lenient) { "sensitivity below zero was not clamped" }
        assert(wakeCostThreshold(2.0f, references) == strict) { "sensitivity above one was not clamped" }
    }

    @Test
    fun wakeCostThreshold_fallsBackWhenSpreadIsUnmeasurable() {
        // With fewer than two references there is no spread to measure, so the
        // fixed fallback applies - and it must stay in normalised units, nowhere
        // near the tens-of-raw-magnitude scale the first implementation used.
        val single = listOf(mfcc(pcmToFloats(sinePcm(4000, 7000, 250))))
        val lenient = wakeCostThreshold(0.0f, single)
        assert(lenient > wakeCostThreshold(1.0f, single)) { "a single reference left no tolerance at all" }
        assert(lenient < 10.0f) { "the fallback spread was not in normalised units" }
        assert(wakeCostThreshold(1.0f, emptyList()) >= 0.5f) {
            "no references produced a threshold below the minimum tolerance"
        }
    }

    @Test
    fun mfcc_ignoresBroadbandNoiseAboveThePhrase() {
        // The low-pass is what lets a reference enrolled in one room match a
        // clip heard later. A sine whose period is three samples (~5.3 kHz at
        // 16 kHz) averages to exactly zero over the 3-tap window, so the noisy
        // clip must still match its clean version far better than an unrelated
        // phrase does.
        val clean = speechPcm(8000, 7000, 2000)
        val noisy = addNoise(clean, 3000, 3)
        val unrelated = speechPcm(8000, 7000, 131)
        val noiseCost = bestMatchCost(mfcc(pcmToFloats(noisy)), listOf(mfcc(pcmToFloats(clean))))
        val unrelatedCost = bestMatchCost(mfcc(pcmToFloats(clean)), listOf(mfcc(pcmToFloats(unrelated))))
        assert(noiseCost < unrelatedCost) {
            "broadband noise was not filtered out: noise cost $noiseCost, unrelated cost $unrelatedCost"
        }
    }
}
