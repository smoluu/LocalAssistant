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
const val MAX_REFERENCE_FRAMES = 500
// The same limit in seconds, so enrollment can ask the user to keep the phrase
// short instead of quoting a frame count.
const val MAX_REFERENCE_SECONDS = MAX_REFERENCE_FRAMES * MFCC_SHIFT / 16000
private const val MAX_REFERENCES = 8
// Floor so identical enrollments (spread 0) still leave a little tolerance.
private const val MIN_MATCH_THRESHOLD = 0.5f
// Fallback spread when fewer than two references are enrolled, in normalised units.
private const val SINGLE_REFERENCE_SPREAD = 1.5f
private const val REFERENCE_FILE = "wake_references.txt"
// Samples are rescaled to this peak so enrollment and detection see the same
// loudness whatever the microphone gain, the distance, or the volume was.
private const val TARGET_PEAK = 0.9f
// Moving-average window: a low-pass that keeps roughly everything below
// half the sample rate divided by the window (~2.7 kHz at 16 kHz), where the
// phrase's formants live and room noise does not.
private const val LOWPASS_WINDOW = 3
// Peak frame energy under which a clip is silence outright.
private const val SILENCE_PEAK = 0.01f
// A frame counts as voiced at this fraction of the loudest frame...
private const val VOICED_FRACTION = 0.35f
// ...and as silence below this one. Speech has both; noise is uniform.
private const val QUIET_FRACTION = 0.12f
private const val MIN_SPEECH_FRAMES = 3
private const val MIN_QUIET_FRAMES = 2

/**
 * Extracts the MFCC feature vectors of a WAV clip, returning an empty array when
 * the clip holds no speech. The layout is row-major:
 * [cepstral1..4, delta1..4] per 10 ms frame.
 */
fun extractWakeFeatures(wavBytes: ByteArray): FloatArray {
    val parsed = parseWavBytes(wavBytes) ?: return FloatArray(0)
    val samples = pcmToFloats(parsed.second, parsed.first)
    // Room noise must never reach the matcher: the features are normalised per
    // dimension, so a clip of noise is scaled up to look exactly as "loud" as
    // speech. Requiring voiced frames next to quiet ones is the one test that
    // survives that normalisation, and it is gain-invariant.
    if (!containsSpeech(samples)) return FloatArray(0)
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
 * Low-passes the clip and then scales its gain to [TARGET_PEAK].
 *
 * Both steps exist so that a reference recorded in one place matches a clip
 * recorded in another: the moving average drops the broadband part of room
 * noise, and the gain scaling removes the microphone gain, the distance to the
 * mouth and the speaking volume from the comparison. Nothing here is
 * per-clip normalised later, so the two paths cannot drift apart.
 */
private fun prepareSignal(samples: FloatArray): FloatArray {
    val out = FloatArray(samples.size)
    for (i in samples.indices) {
        var sum = 0.0f
        var taps = 0
        for (k in 0..<LOWPASS_WINDOW) {
            val j = i + k - LOWPASS_WINDOW / 2
            if (j >= 0 && j < samples.size) {
                sum += samples[j]
                taps++
            }
        }
        out[i] = sum / taps
    }
    normalizeGain(out)
    return out
}

/**
 * Scales the whole clip so its loudest sample sits at [TARGET_PEAK].
 *
 * A clip that is already silent stays silent: scaling it up would turn room
 * noise into loud noise, which the feature normalisation would then make
 * indistinguishable from speech.
 */
private fun normalizeGain(samples: FloatArray) {
    var peak = 0.0f
    for (value in samples) {
        val abs = if (value < 0.0f) -value else value
        if (abs > peak) peak = abs
    }
    if (peak < 1.0e-5f) return

    val scale = TARGET_PEAK / peak
    for (i in samples.indices) {
        samples[i] = samples[i] * scale
    }
}

/**
 * Decides whether a clip holds speech at all, before any feature is built.
 *
 * The test is relative to the clip's own loudest frame, so it survives gain
 * normalisation: speech is loud frames with quiet ones around it, while room
 * noise is one uniform level and silence has no loud frame. Either missing case
 * returns false, which is what keeps a quiet room from firing the wake word.
 */
private fun containsSpeech(samples: FloatArray): Boolean {
    val frameCount = (samples.size - MFCC_FRAME) / MFCC_SHIFT + 1
    if (frameCount < MIN_FRAMES) return false

    val energies = FloatArray(frameCount)
    var peak = 0.0f
    for (t in 0..<frameCount) {
        val offset = t * MFCC_SHIFT
        var sum = 0.0
        for (i in 0..<MFCC_FRAME) {
            val s = samples[offset + i].toDouble()
            sum += s * s
        }
        val rms = kotlin.math.sqrt(sum / MFCC_FRAME).toFloat()
        energies[t] = rms
        if (rms > peak) peak = rms
    }
    if (peak < SILENCE_PEAK) return false

    var voiced = 0
    var quiet = 0
    for (energy in energies) {
        if (energy >= peak * VOICED_FRACTION) voiced++
        if (energy <= peak * QUIET_FRACTION) quiet++
    }
    return voiced >= MIN_SPEECH_FRAMES && quiet >= MIN_QUIET_FRAMES
}

/**
 * Applies the same signal preparation to every clip - the enrolled references
 * and the live detection both come through here, so a reference is always
 * comparable to the clip that is being matched against it.
 */
fun mfcc(samples: FloatArray): FloatArray {
    val signal = prepareSignal(samples)
    val frameCount = (signal.size - MFCC_FRAME) / MFCC_SHIFT + 1
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
            re[i] = if (i < MFCC_FRAME) signal[offset + i] else 0.0f
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
 * Sensitivity scales that multiple across the slider: the strict end asks for
 * better than the spread the user's own takes already show, the lenient end
 * accepts several times it.
 */
fun wakeCostThreshold(sensitivity: Float, references: List<FloatArray>): Float {
    val clamped = kotlin.math.max(0.0f, kotlin.math.min(1.0f, sensitivity))
    val factor = 0.6f + 2.4f * (1.0f - clamped)
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
 * Number of 10 ms frames a feature sequence holds. Enrollment reports a rejected
 * clip in these units so the user knows how much to trim, not in raw floats.
 */
fun referenceFrameCount(features: FloatArray): Int = features.size / MFCC_FEATURES

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
