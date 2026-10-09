package com.localassistant.app.ui.common

import android.content.Context
import java.io.File

/**
 * On-device wake-word detector: mel-frequency cepstral features plus dynamic
 * time warping against user-recorded references, the approach popularised by
 * https://github.com/GiviMAD/rustpotter (References mode - no training step).
 *
 * The detector runs entirely on the device over the recorded microphone clip, so
 * deciding "was the wake phrase spoken here?" never touches the STT endpoint.
 */
private const val MFCC_FRAME = 640
private const val MFCC_SHIFT = 160
private const val MFCC_CEPSTRAL = 4
private const val MFCC_FEATURES = MFCC_CEPSTRAL * 2
private const val FFT_SIZE = 1024
private const val MIN_FRAMES = 5
private const val MAX_REFERENCE_FRAMES = 500
private const val MAX_REFERENCES = 8
// Floor so identical enrollments (spread 0) still leave a little tolerance.
private const val MIN_MATCH_THRESHOLD = 0.5f
// Fallback spread when fewer than two references are enrolled, in normalised units.
private const val SINGLE_REFERENCE_SPREAD = 1.5f
private const val REFERENCE_FILE = "wake_references.txt"

/**
 * Extracts the MFCC feature vectors of a WAV clip, returning an empty array when
 * the clip is too short to describe. The layout is row-major:
 * [cepstral1..4, delta1..4] per 10 ms frame.
 */
fun extractWakeFeatures(wavBytes: ByteArray): FloatArray {
    val parsed = parseWavBytes(wavBytes) ?: return FloatArray(0)
    val samples = pcmToFloats(parsed.second, parsed.first)
    return mfcc(samples)
}

/**
 * Converts 16-bit little-endian PCM bytes to floats in [-1, 1], resampling to
 * 16 kHz when the WAV header advertises another rate (the energy-saving mode
 * drops the microphone to 8 kHz).
 */
private fun pcmToFloats(pcmBytes: ByteArray, sampleRate: Int): FloatArray {
    val count = pcmBytes.size / 2
    val out = FloatArray(count)
    for (i in 0..<count) {
        val lo = pcmBytes[i * 2].toInt() and 0xFF
        val hi = pcmBytes[i * 2 + 1].toInt()
        out[i] = ((hi shl 8) or lo).toFloat() / 32768.0f
    }
    if (sampleRate == 16000 || sampleRate <= 0) return out

    val target = out.size * 16000 / sampleRate
    val resampled = FloatArray(target)
    for (i in 0..<target) {
        resampled[i] = out[i * sampleRate / 16000]
    }
    return resampled
}

/**
 * Extracts MFCC features from a normalised signal: 4 cepstral coefficients and
 * their deltas per frame, both Gaussian-smoothed over a 3-frame window.
 */
fun mfcc(samples: FloatArray): FloatArray {
    val frameCount = (samples.size - MFCC_FRAME) / MFCC_SHIFT + 1
    if (frameCount < MIN_FRAMES) return FloatArray(0)

    val half = MFCC_FRAME / 2
    // cos(pi * k * (j - 0.5) / N) is frame-independent, so it is built once.
    val cosTable = FloatArray((MFCC_CEPSTRAL + 1) * (half + 1))
    for (k in 1..MFCC_CEPSTRAL) {
        for (j in 1..half) {
            cosTable[k * (half + 1) + j] =
                kotlin.math.cos(kotlin.math.PI * k * (j - 0.5) / MFCC_FRAME).toFloat()
        }
    }

    val cepstral = FloatArray(frameCount * MFCC_CEPSTRAL)
    val re = FloatArray(FFT_SIZE)
    val im = FloatArray(FFT_SIZE)
    val magnitude = FloatArray(half + 1)

    for (t in 0..<frameCount) {
        val offset = t * MFCC_SHIFT
        for (i in 0..<FFT_SIZE) {
            re[i] = if (i < MFCC_FRAME) samples[offset + i] else 0.0f
            im[i] = 0.0f
        }
        fft(re, im)

        for (j in 1..half) {
            val reJ = re[j]
            val imJ = im[j]
            magnitude[j] = kotlin.math.sqrt(reJ * reJ + imJ * imJ).toFloat()
        }

        for (k in 1..MFCC_CEPSTRAL) {
            var sum = 0.0
            for (j in 1..half) {
                val weight = if (j < half) 1.0 else 0.5
                val mag = kotlin.math.max(magnitude[j].toDouble(), 1.0e-7)
                sum += weight * kotlin.math.log(mag, kotlin.math.E) * cosTable[k * (half + 1) + j]
            }
            cepstral[t * MFCC_CEPSTRAL + (k - 1)] = (sum / MFCC_CEPSTRAL).toFloat()
        }
    }

    val delta = FloatArray(frameCount * MFCC_CEPSTRAL)
    for (t in 0..<frameCount) {
        val prev = if (t == 0) frameCount - 1 else t - 1
        for (k in 0..<MFCC_CEPSTRAL) {
            delta[t * MFCC_CEPSTRAL + k] =
                cepstral[t * MFCC_CEPSTRAL + k] - cepstral[prev * MFCC_CEPSTRAL + k]
        }
    }

    val smoothCep = gaussianSmooth(cepstral, frameCount, MFCC_CEPSTRAL)
    val smoothDelta = gaussianSmooth(delta, frameCount, MFCC_CEPSTRAL)

    val features = FloatArray(frameCount * MFCC_FEATURES)
    for (t in 0..<frameCount) {
        for (k in 0..<MFCC_CEPSTRAL) {
            features[t * MFCC_FEATURES + k] = smoothCep[t * MFCC_CEPSTRAL + k]
            features[t * MFCC_FEATURES + MFCC_CEPSTRAL + k] = smoothDelta[t * MFCC_CEPSTRAL + k]
        }
    }
    normalizePerFeature(features)
    return features
}

/**
 * Scales every feature dimension to zero mean and unit variance, in place.
 *
 * Raw cepstral coefficients are sums of log magnitudes, so their absolute size
 * follows the recording's volume and length; comparing them across recordings
 * without this step puts the DTW cost on a scale that no fixed threshold can
 * sit on. After normalising, a cost of 1 means "one standard deviation apart".
 */
private fun normalizePerFeature(features: FloatArray) {
    val frameCount = features.size / MFCC_FEATURES
    if (frameCount < 2) return
    for (k in 0..<MFCC_FEATURES) {
        var sum = 0.0
        for (t in 0..<frameCount) sum += features[t * MFCC_FEATURES + k]
        val mean = sum / frameCount
        var variance = 0.0
        for (t in 0..<frameCount) {
            val d = features[t * MFCC_FEATURES + k] - mean
            variance += d * d
        }
        // A near-constant dimension (silence) has no scale to divide by, so leave it at the mean.
        val std = kotlin.math.sqrt(variance / frameCount)
        if (std < 1.0e-4f) continue
        for (t in 0..<frameCount) {
            features[t * MFCC_FEATURES + k] = ((features[t * MFCC_FEATURES + k] - mean) / std).toFloat()
        }
    }
}

/**
 * In-place iterative radix-2 Cooley-Tukey FFT; results are interleaved in
 * [re]/[im] and only the first half of the spectrum is ever read back.
 */
private fun fft(re: FloatArray, im: FloatArray) {
    val n = re.size
    var j = 0
    for (i in 1..<n) {
        var bit = n shr 1
        while (j and bit != 0) {
            j = j xor bit
            bit = bit shr 1
        }
        j = j or bit
        if (i < j) {
            val tr = re[i]
            re[i] = re[j]
            re[j] = tr
            val ti = im[i]
            im[i] = im[j]
            im[j] = ti
        }
    }

    var len = 2
    while (len <= n) {
        val angle = -2.0 * kotlin.math.PI / len
        val wlenRe = kotlin.math.cos(angle).toFloat()
        val wlenIm = kotlin.math.sin(angle).toFloat()
        val half = len shr 1
        var i = 0
        while (i < n) {
            var wRe = 1.0f
            var wIm = 0.0f
            for (k in 0..<half) {
                val ur = re[i + k]
                val ui = im[i + k]
                val vr = re[i + k + half] * wRe - im[i + k + half] * wIm
                val vi = re[i + k + half] * wIm + im[i + k + half] * wRe
                re[i + k] = ur + vr
                im[i + k] = ui + vi
                re[i + k + half] = ur - vr
                im[i + k + half] = ui - vi
                val nextRe = wRe * wlenRe - wIm * wlenIm
                wIm = wRe * wlenIm + wIm * wlenRe
                wRe = nextRe
            }
            i += len
        }
        len = len shl 1
    }
}

/**
 * Applies a 3-tap Gaussian filter along the time axis of a feature sequence.
 */
private fun gaussianSmooth(source: FloatArray, frameCount: Int, stride: Int): FloatArray {
    val out = FloatArray(source.size)
    for (t in 0..<frameCount) {
        val prev = if (t == 0) 0 else t - 1
        val next = if (t == frameCount - 1) t else t + 1
        for (k in 0..<stride) {
            out[t * stride + k] =
                0.25f * source[prev * stride + k] +
                0.5f * source[t * stride + k] +
                0.25f * source[next * stride + k]
        }
    }
    return out
}

/**
 * Dynamic time warping cost between two feature sequences, normalised by the
 * length of the best path. Lower means more similar; an empty sequence on
 * either side yields [Float.MAX_VALUE] so it can never match.
 */
fun bestMatchCost(features: FloatArray, references: List<FloatArray>): Float {
    if (features.isEmpty() || references.isEmpty()) return Float.MAX_VALUE

    var best = Float.MAX_VALUE
    for (reference in references) {
        if (reference.isEmpty()) continue
        val cost = dtwCost(features, reference)
        if (cost < best) best = cost
    }
    return best
}

private fun dtwCost(a: FloatArray, b: FloatArray): Float {
    val n = a.size / MFCC_FEATURES
    val m = b.size / MFCC_FEATURES
    if (n == 0 || m == 0) return Float.MAX_VALUE

    val inf = Float.MAX_VALUE / 4.0f
    var previous = FloatArray(m + 1)
    var current = FloatArray(m + 1)
    previous[0] = 0.0f
    for (col in 1..m) previous[col] = inf

    for (row in 1..n) {
        current[0] = inf
        for (col in 1..m) {
            val local = euclidean(a, (row - 1) * MFCC_FEATURES, b, (col - 1) * MFCC_FEATURES)
            val bestPrevious = kotlin.math.min(
                kotlin.math.min(previous[col], previous[col - 1]),
                current[col - 1]
            )
            current[col] = local + bestPrevious
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[m] / (n + m)
}

private fun euclidean(a: FloatArray, aOffset: Int, b: FloatArray, bOffset: Int): Float {
    var sum = 0.0f
    for (k in 0..<MFCC_FEATURES) {
        val d = a[aOffset + k] - b[bOffset + k]
        sum += d * d
    }
    return kotlin.math.sqrt(sum)
}

/**
 * The cost a clip may have against the enrolled references and still count as
 * the wake word.
 *
 * The threshold is derived from the references themselves: their median pairwise
 * cost is how much the user's own takes of the same phrase already differ from
 * each other, so accepting up to a multiple of that spread needs no absolute
 * constant that would depend on volume, phrase length or microphone.
 * Sensitivity scales that multiple: strict stays near the observed spread,
 * lenient accepts several times it.
 */
fun wakeCostThreshold(sensitivity: Float, references: List<FloatArray>): Float {
    val clamped = kotlin.math.max(0.0f, kotlin.math.min(1.0f, sensitivity))
    val factor = 1.2f + 2.6f * (1.0f - clamped)
    return (referenceSpread(references) * factor).coerceAtLeast(MIN_MATCH_THRESHOLD)
}

/**
 * Median pairwise DTW cost among the enrolled references - the spread the user's
 * own takes of the phrase already show. Below two references there is nothing to
 * measure, so a single reference falls back to a fixed cost in normalised units.
 */
private fun referenceSpread(references: List<FloatArray>): Float {
    val usable = references.filter { it.isNotEmpty() }
    if (usable.size < 2) return SINGLE_REFERENCE_SPREAD

    val costs = mutableListOf<Float>()
    for (i in usable.indices) {
        for (j in (i + 1)..<usable.size) {
            val cost = dtwCost(usable[i], usable[j])
            if (cost.isFinite()) costs.add(cost)
        }
    }
    if (costs.isEmpty()) return SINGLE_REFERENCE_SPREAD
    costs.sort()
    return costs[costs.size / 2]
}

/**
 * Loads the enrolled wake-word references from the app's private files directory.
 * Returns an empty list when nothing has been recorded yet.
 */
fun loadWakeReferences(context: Context): List<FloatArray> {
    val file = java.io.File(pathForReferences(context))
    if (!file.exists()) return emptyList()
    return try {
        val lines = file.readText().split('\n')
        val references = mutableListOf<FloatArray>()
        for (line in lines) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(' ')
            if (parts.size < 2) continue
            val values = parts.drop(1).mapNotNull { it.toFloatOrNull() }
            if (values.isNotEmpty()) {
                val reference = values.toFloatArray()
                // Enrollment files written before the features were normalised hold
                // raw log-magnitude coefficients. Normalising again is a no-op for
                // current entries, so old enrollments stay usable without re-recording.
                normalizePerFeature(reference)
                references += reference
            }
        }
        references
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * Appends one reference to the enrollment file, keeping the newest entries
 * bounded so the file cannot grow without limit.
 */
fun saveWakeReference(context: Context, features: FloatArray): Boolean {
    if (features.isEmpty() || features.size % MFCC_FEATURES != 0) return false
    if (features.size / MFCC_FEATURES > MAX_REFERENCE_FRAMES) return false

    val references = loadWakeReferences(context) + features
    val bounded = if (references.size <= MAX_REFERENCES) {
        references
    } else {
        references.drop(references.size - MAX_REFERENCES)
    }
    return writeReferences(context, bounded)
}

/**
 * Removes every enrolled wake-word reference.
 */
fun clearWakeReferences(context: Context): Boolean {
    return writeReferences(context, emptyList())
}

private fun writeReferences(context: Context, references: List<FloatArray>): Boolean {
    return try {
        val file = java.io.File(pathForReferences(context))
        val text = buildString {
            append("#localassistant-wake-refs v1\n")
            for (reference in references) {
                append("${reference.size / MFCC_FEATURES}")
                for (value in reference) {
                    append(' ')
                    append(value.toString())
                }
                append('\n')
            }
        }
        file.writeText(text)
        true
    } catch (_: Exception) {
        false
    }
}

private fun pathForReferences(context: Context): String {
    return "${context.filesDir}/$REFERENCE_FILE"
}
