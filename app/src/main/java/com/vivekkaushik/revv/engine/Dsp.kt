package com.vivekkaushik.revv.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A fast xorshift generator: audio needs plenty of random numbers and no thread safety. */
internal class Noise(seed: Int) {
    private var state = if (seed == 0) 0x2545F491 else seed

    /** Uniform in [0, 1). */
    fun next(): Double {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x
        return (x ushr 8) / 16_777_216.0
    }

    /** Roughly normal, mean 0 and spread 1: the sum of three uniforms. */
    fun gaussian(): Double = (next() + next() + next() - 1.5) * 2.0
}

/** engine-sim's fourth-order Butterworth low-pass, made by the bilinear transform. */
internal class ButterworthLowPass(cutoffHz: Double, sampleRate: Double) {
    private val gain: Double
    private val a1: Double
    private val a2: Double
    private val a3: Double
    private val a4: Double
    private var x1 = 0.0
    private var x2 = 0.0
    private var x3 = 0.0
    private var x4 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0
    private var y3 = 0.0
    private var y4 = 0.0

    init {
        val f = tan(PI * cutoffHz / sampleRate)
        val f2 = f * f
        val f3 = f2 * f
        val f4 = f2 * f2
        val m = -2.0 * cos(5.0 * PI / 8.0)
        val n = -2.0 * cos(7.0 * PI / 8.0)
        val a0 = 1.0 + (m + n) * f + (2.0 + n * m) * f2 + (m + n) * f3 + f4
        a1 = (-4.0 - 2.0 * (n + m) * f + 2.0 * (m + n) * f3 + 4.0 * f4) / a0
        a2 = (6.0 - 2.0 * (2.0 + m * n) * f2 + 6.0 * f4) / a0
        a3 = (-4.0 + 2.0 * (m + n) * f - 2.0 * (m + n) * f3 + 4.0 * f4) / a0
        a4 = (1.0 - (n + m) * f + (2.0 + m * n) * f2 - (m + n) * f3 + f4) / a0
        gain = f4 / a0
    }

    fun process(x: Double): Double {
        val y = gain * (x + 4 * x1 + 6 * x2 + 4 * x3 + x4) - a1 * y1 - a2 * y2 - a3 * y3 - a4 * y4
        x4 = x3
        x3 = x2
        x2 = x1
        x1 = x
        y4 = y3
        y3 = y2
        y2 = y1
        // Denormals slow some ARM cores to a crawl once the engine stops.
        y1 = if (abs(y) < 1e-30) 0.0 else y
        return y1
    }
}

/** A first-order low-pass, which engine-sim uses to find the signal's DC offset. */
internal class OnePoleLowPass(cutoffHz: Double, sampleRate: Double) {
    private val alpha: Double
    private var y = 0.0

    init {
        val dt = 1.0 / sampleRate
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        alpha = dt / (rc + dt)
    }

    fun process(x: Double): Double {
        y += alpha * (x - y)
        if (abs(y) < 1e-30) y = 0.0
        return y
    }
}

/**
 * engine-sim's jitter filter: reads the signal back from a slightly random point in its recent
 * past, the randomness itself smoothed, so pulses wander by a fraction of a millisecond and the
 * sound turns rough instead of machine-perfect.
 */
internal class JitterFilter(private val maxJitter: Int, noiseCutoffHz: Double, sampleRate: Double, private val noise: Noise) {
    private val history = DoubleArray(maxJitter)
    private var offset = 0
    private val smoothing = ButterworthLowPass(noiseCutoffHz, sampleRate)

    fun process(x: Double, scale: Double): Double {
        history[offset] = x
        offset++
        if (offset >= maxJitter) offset = 0
        val last = maxJitter - 1
        val s = smoothing.process(noise.next() * last * scale).coerceIn(0.0, last.toDouble())
        // Truncating a non-negative number is floor(), without the native call.
        val i0 = s.toInt()
        val i1 = min(i0 + 1, last)
        val fraction = s - i0
        val v0 = history[(i0 + offset) % maxJitter]
        val v1 = history[(i1 + offset) % maxJitter]
        return v1 * fraction + v0 * (1 - fraction)
    }
}

/**
 * engine-sim's leveling filter, used here only to hold peaks under [target]: it follows the
 * signal's peak and turns the gain down at once when the peak rises above it, then eases back
 * up, never past 1.
 */
internal class Limiter(private val target: Double) {
    private var peak = 0.0
    private var gain = 1.0

    fun process(x: Double): Double {
        peak = max(abs(x), peak * PEAK_DECAY)
        val wanted = if (peak > target) target / peak else 1.0
        gain = if (wanted < gain) wanted else gain + (wanted - gain) * RELEASE
        return (x * gain).coerceIn(-1.0, 1.0)
    }

    private companion object {
        const val PEAK_DECAY = 0.9995
        const val RELEASE = 0.001
    }
}

/** An in-place radix-2 complex FFT of a fixed power-of-two [size]. */
internal class Fft(private val size: Int) {
    private val levels = Integer.numberOfTrailingZeros(size)
    private val cosines = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sines = DoubleArray(size / 2) { sin(2 * PI * it / size) }
    private val reversed = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
    }

    /** The forward transform, or the inverse one without its 1/size scaling. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                val r = re[i]
                re[i] = re[j]
                re[j] = r
                val m = im[i]
                im[i] = im[j]
                im[j] = m
            }
        }
        var half = 1
        while (half < size) {
            val stride = size / (half * 2)
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val c = cosines[k]
                    val s = if (inverse) sines[k] else -sines[k]
                    val l = j + half
                    val tr = re[l] * c - im[l] * s
                    val ti = re[l] * s + im[l] * c
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += stride
                }
                start += half * 2
            }
            half *= 2
        }
    }
}

/**
 * Convolution with a long impulse response, a [block] at a time: the response is cut into
 * block-long parts, each multiplied with the matching earlier input in the frequency domain
 * (uniformly partitioned overlap-save). engine-sim convolves sample by sample, which is far too
 * slow here: 10,000 taps would be 441 million multiplications a second.
 */
internal class Convolver(impulse: FloatArray, private val block: Int) {
    private val size = block * 2
    private val bins = block + 1
    private val fft = Fft(size)
    private val parts = max(1, (impulse.size + block - 1) / block)
    private val filterRe = Array(parts) { DoubleArray(bins) }
    private val filterIm = Array(parts) { DoubleArray(bins) }
    private val inputRe = Array(parts) { DoubleArray(bins) }
    private val inputIm = Array(parts) { DoubleArray(bins) }
    private var newest = 0
    private val window = DoubleArray(size)
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)

    init {
        for (part in 0 until parts) {
            re.fill(0.0)
            im.fill(0.0)
            val from = part * block
            for (i in from until min(from + block, impulse.size)) re[i - from] = impulse[i].toDouble()
            fft.transform(re, im, inverse = false)
            re.copyInto(filterRe[part], endIndex = bins)
            im.copyInto(filterIm[part], endIndex = bins)
        }
    }

    /** Convolves the next [block] samples of [input] into [output]. */
    fun process(input: DoubleArray, output: DoubleArray) {
        window.copyInto(window, destinationOffset = 0, startIndex = block, endIndex = size)
        input.copyInto(window, destinationOffset = block, startIndex = 0, endIndex = block)
        window.copyInto(re)
        im.fill(0.0)
        fft.transform(re, im, inverse = false)
        newest = (newest + 1) % parts
        re.copyInto(inputRe[newest], endIndex = bins)
        im.copyInto(inputIm[newest], endIndex = bins)

        re.fill(0.0)
        im.fill(0.0)
        for (part in 0 until parts) {
            val slot = (newest - part + parts) % parts
            val xr = inputRe[slot]
            val xi = inputIm[slot]
            val hr = filterRe[part]
            val hi = filterIm[part]
            for (k in 0 until bins) {
                re[k] += xr[k] * hr[k] - xi[k] * hi[k]
                im[k] += xr[k] * hi[k] + xi[k] * hr[k]
            }
        }
        // Real signals have mirrored spectra: fill in the upper half from the lower.
        for (k in 1 until block) {
            re[size - k] = re[k]
            im[size - k] = -im[k]
        }
        fft.transform(re, im, inverse = true)
        for (i in 0 until block) output[i] = re[block + i] / size
    }
}

/** Reading engine-sim's impulse responses: 16-bit PCM WAV files. */
internal object ImpulseResponse {
    /** engine-sim's limit: anything later than about 0.23 s is room reverb rather than exhaust. */
    const val MAX_SAMPLES = 10_000

    /** Below this (of 32,768) the tail is noise. */
    private const val SILENT = 100

    /**
     * The response in [wav] as engine-sim uses it: the quiet tail trimmed off, at most
     * [MAX_SAMPLES] long, and scaled to unit energy so every recording plays about as loud.
     */
    fun read(wav: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(wav.size >= 12 && String(wav, 0, 4, Charsets.US_ASCII) == "RIFF") { "Not a WAV file" }
        var channels = 1
        var bits = 16
        var position = 12
        while (position + 8 <= wav.size) {
            val id = String(wav, position, 4, Charsets.US_ASCII)
            val length = buffer.getInt(position + 4)
            val body = position + 8
            when (id) {
                "fmt " -> {
                    channels = buffer.getShort(body + 2).toInt()
                    bits = buffer.getShort(body + 14).toInt()
                }
                "data" -> {
                    require(bits == 16) { "Only 16-bit WAV files are supported" }
                    val frames = min(length, wav.size - body) / (2 * channels)
                    val samples = ShortArray(frames) { buffer.getShort(body + it * 2 * channels) }
                    return trimmed(samples)
                }
            }
            position = body + length + (length and 1)
        }
        throw IllegalArgumentException("No audio in the WAV file")
    }

    private fun trimmed(samples: ShortArray): FloatArray {
        val end = (samples.indexOfLast { abs(it.toInt()) > SILENT } + 1).coerceAtMost(MAX_SAMPLES)
        val response = FloatArray(max(end, 1)) { if (it < end) samples[it] / 32_768f else 0f }
        val energy = sqrt(response.sumOf { it.toDouble() * it })
        if (energy > 0) for (i in response.indices) response[i] = (response[i] / energy).toFloat()
        return response
    }
}
