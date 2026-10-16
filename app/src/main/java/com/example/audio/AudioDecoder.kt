package com.example.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TAG = "AudioDecoder"
const val TARGET_SAMPLE_RATE = 16000
private const val TIMEOUT_US = 5000L

/**
 * Android-native audio decoder and WAV converter.
 *
 * Whisper models require 16kHz 16-bit linear PCM mono WAV audio.
 * This class decodes saved .m4a (AAC in MP4 container) files using Android's
 * hardware MediaExtractor and MediaCodec APIs, properly detects dynamic format changes,
 * downmixes multi-channel PCM to mono, applies a band-limited anti-aliased sinc resampler,
 * streams the output directly to disk with minimal memory overhead, and writes standard RIFF WAV headers.
 */
object AudioDecoder {

    /**
     * Decodes an .m4a audio file into a 16kHz 16-bit mono WAV file.
     *
     * @param inputFile The source .m4a audio file.
     * @param outputFile The destination .wav file.
     * @return Result containing the generated WAV file, or an exception on failure.
     */
    fun decodeM4aTo16kWav(inputFile: File, outputFile: File): Result<File> {
        if (!inputFile.exists() || inputFile.length() == 0L) {
            return Result.failure(IllegalArgumentException("Input audio file does not exist or is empty"))
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        return try {
            extractor.setDataSource(inputFile.absolutePath)

            // Locate the audio track
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || inputFormat == null) {
                return Result.failure(IllegalStateException("No audio track found in file: ${inputFile.name}"))
            }

            extractor.selectTrack(audioTrackIndex)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return Result.failure(IllegalStateException("Audio MIME type missing in track"))

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var srcSampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100

            var srcChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            // Prepare temporary output stream with standard 44-byte WAV header placeholder
            val fos = FileOutputStream(outputFile)
            writeWavHeader(fos, TARGET_SAMPLE_RATE, 1, 0)

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            var totalPcmBytesWritten = 0

            // Initialize streaming band-limited resampler
            val resampler = StreamingResampler(srcSampleRate, TARGET_SAMPLE_RATE)

            // Small 4KB byte buffer for writing output PCM to disk efficiently without per-sample I/O
            val pcmOutBuffer = ByteArray(4096)
            var pcmOutOffset = 0

            val writeSample: (Short) -> Unit = { sample ->
                val s = sample.toInt()
                pcmOutBuffer[pcmOutOffset++] = (s and 0xFF).toByte()
                pcmOutBuffer[pcmOutOffset++] = ((s shr 8) and 0xFF).toByte()
                totalPcmBytesWritten += 2
                if (pcmOutOffset >= pcmOutBuffer.size) {
                    fos.write(pcmOutBuffer, 0, pcmOutOffset)
                    pcmOutOffset = 0
                }
            }

            while (!isOutputEOS) {
                if (!isInputEOS) {
                    val inputBufIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputBufIndex,
                                    0,
                                    0,
                                    0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isInputEOS = true
                            } else {
                                codec.queueInputBuffer(
                                    inputBufIndex,
                                    0,
                                    sampleSize,
                                    extractor.sampleTime,
                                    0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

                        val shortCount = bufferInfo.size / 2
                        val shortBuffer = outputBuffer.asShortBuffer()
                        val rawShorts = ShortArray(shortCount)
                        shortBuffer.get(rawShorts)

                        // Downmix to mono if multi-channel
                        val monoShorts = if (srcChannels > 1) {
                            val monoCount = shortCount / srcChannels
                            val mono = ShortArray(monoCount)
                            for (i in 0 until monoCount) {
                                var sum = 0
                                for (ch in 0 until srcChannels) {
                                    sum += rawShorts[i * srcChannels + ch].toInt()
                                }
                                mono[i] = (sum / srcChannels).coerceIn(-32768, 32767).toShort()
                            }
                            mono
                        } else {
                            rawShorts
                        }

                        // Stream mono PCM through the band-limited resampler
                        resampler.process(monoShorts, writeSample)
                    }

                    codec.releaseOutputBuffer(outputBufIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        val updatedRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (updatedRate > 0 && updatedRate != srcSampleRate) {
                            srcSampleRate = updatedRate
                            resampler.updateSourceRate(updatedRate)
                        }
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        val updatedChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (updatedChannels > 0) {
                            srcChannels = updatedChannels
                        }
                    }
                    Log.i(TAG, "MediaCodec confirmed output format: rate=$srcSampleRate, channels=$srcChannels")
                }
            }

            // Flush resampler tail
            resampler.finish(writeSample)

            // Flush remaining buffered PCM bytes to disk
            if (pcmOutOffset > 0) {
                fos.write(pcmOutBuffer, 0, pcmOutOffset)
                pcmOutOffset = 0
            }
            fos.flush()
            try {
                fos.close()
            } catch (_: Exception) {}

            // Update WAV header with exact data size
            updateWavHeader(outputFile, totalPcmBytesWritten, TARGET_SAMPLE_RATE, 1)

            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode m4a audio to wav", e)
            if (outputFile.exists()) {
                outputFile.delete()
            }
            Result.failure(e)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {}
            try {
                codec?.release()
            } catch (_: Exception) {}
            try {
                extractor.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * One-shot band-limited resampling utility for audio arrays.
     * Useful for in-memory processing and testing.
     */
    fun resampleBandLimited(input: ShortArray, srcRate: Int, dstRate: Int = TARGET_SAMPLE_RATE): ShortArray {
        if (input.isEmpty() || srcRate == dstRate) return input
        val resampler = StreamingResampler(srcRate, dstRate)
        val approxOutCount = (input.size.toDouble() * dstRate / srcRate).roundToInt() + 64
        val outList = ArrayList<Short>(approxOutCount)
        resampler.process(input) { outList.add(it) }
        resampler.finish { outList.add(it) }
        return outList.toShortArray()
    }

    /**
     * High-quality band-limited audio resampler using windowed-sinc interpolation with a Blackman window.
     * Prevents high-frequency aliasing folding into the speech band when downsampling to 16 kHz.
     */
    class StreamingResampler(
        private var srcRate: Int,
        private val dstRate: Int = TARGET_SAMPLE_RATE,
        private val filterHalfWidth: Int = 16
    ) {
        // Overlap buffer retaining incoming audio samples for the filter convolution
        private var buffer = ShortArray(0)
        private var inputPos = 0.0
        private var isFirstChunk = true

        fun updateSourceRate(newRate: Int) {
            if (newRate > 0 && newRate != srcRate) {
                srcRate = newRate
            }
        }

        fun process(inputSamples: ShortArray, output: (Short) -> Unit) {
            if (inputSamples.isEmpty()) return

            if (srcRate == dstRate) {
                for (s in inputSamples) {
                    output(s)
                }
                return
            }

            // Append inputSamples to buffer
            val initialPad = if (isFirstChunk) {
                isFirstChunk = false
                filterHalfWidth
            } else 0

            val oldSize = buffer.size
            val newTotal = oldSize + initialPad + inputSamples.size
            val newBuffer = ShortArray(newTotal)
            if (oldSize > 0) {
                System.arraycopy(buffer, 0, newBuffer, 0, oldSize)
            }
            // initialPad zeros are already 0
            System.arraycopy(inputSamples, 0, newBuffer, oldSize + initialPad, inputSamples.size)
            buffer = newBuffer

            if (initialPad > 0) {
                inputPos = filterHalfWidth.toDouble()
            }

            val ratio = srcRate.toDouble() / dstRate.toDouble()
            val cutoffScale = 0.9 * minOf(1.0, dstRate.toDouble() / srcRate.toDouble())

            while (inputPos + filterHalfWidth < buffer.size) {
                val center = inputPos.roundToInt()
                var sumVal = 0.0
                var sumWeights = 0.0

                val startK = maxOf(0, center - filterHalfWidth)
                val endK = minOf(buffer.size - 1, center + filterHalfWidth)

                for (k in startK..endK) {
                    val delta = inputPos - k
                    val absDelta = abs(delta)
                    if (absDelta <= filterHalfWidth) {
                        val sinc = if (absDelta < 1e-9) {
                            1.0
                        } else {
                            val x = Math.PI * cutoffScale * delta
                            sin(x) / x
                        }
                        // Blackman window
                        val w = 0.42 + 0.5 * cos(Math.PI * delta / filterHalfWidth) + 0.08 * cos(2.0 * Math.PI * delta / filterHalfWidth)
                        val weight = sinc * w
                        sumVal += buffer[k].toInt() * weight
                        sumWeights += weight
                    }
                }

                val outVal = if (sumWeights != 0.0) sumVal / sumWeights else 0.0
                output(outVal.roundToInt().coerceIn(-32768, 32767).toShort())

                inputPos += ratio
            }

            // Drop consumed samples, keep overlap for the next chunk
            val minNeeded = (inputPos.roundToInt() - filterHalfWidth).coerceAtLeast(0)
            if (minNeeded > 0) {
                val remaining = buffer.size - minNeeded
                val trimmed = ShortArray(remaining)
                System.arraycopy(buffer, minNeeded, trimmed, 0, remaining)
                buffer = trimmed
                inputPos -= minNeeded
            }
        }

        fun finish(output: (Short) -> Unit) {
            if (srcRate == dstRate || buffer.isEmpty()) {
                buffer = ShortArray(0)
                return
            }

            // Pad filterHalfWidth zeros at the end to flush remaining output samples
            val oldSize = buffer.size
            val padded = ShortArray(oldSize + filterHalfWidth)
            System.arraycopy(buffer, 0, padded, 0, oldSize)
            buffer = padded

            val ratio = srcRate.toDouble() / dstRate.toDouble()
            val cutoffScale = 0.9 * minOf(1.0, dstRate.toDouble() / srcRate.toDouble())

            while (inputPos + filterHalfWidth < buffer.size) {
                val center = inputPos.roundToInt()
                var sumVal = 0.0
                var sumWeights = 0.0

                val startK = maxOf(0, center - filterHalfWidth)
                val endK = minOf(buffer.size - 1, center + filterHalfWidth)

                for (k in startK..endK) {
                    val delta = inputPos - k
                    val absDelta = abs(delta)
                    if (absDelta <= filterHalfWidth) {
                        val sinc = if (absDelta < 1e-9) {
                            1.0
                        } else {
                            val x = Math.PI * cutoffScale * delta
                            sin(x) / x
                        }
                        val w = 0.42 + 0.5 * cos(Math.PI * delta / filterHalfWidth) + 0.08 * cos(2.0 * Math.PI * delta / filterHalfWidth)
                        val weight = sinc * w
                        sumVal += buffer[k].toInt() * weight
                        sumWeights += weight
                    }
                }

                val outVal = if (sumWeights != 0.0) sumVal / sumWeights else 0.0
                output(outVal.roundToInt().coerceIn(-32768, 32767).toShort())

                inputPos += ratio
            }

            buffer = ShortArray(0)
        }
    }

    /**
     * Writes standard 44-byte RIFF WAV header for 16-bit linear PCM audio.
     */
    internal fun writeWavHeader(
        out: FileOutputStream,
        sampleRate: Int,
        channels: Int,
        pcmDataLength: Int
    ) {
        val totalDataLen = pcmDataLength + 36
        val byteRate = sampleRate * channels * 2
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // Subchunk1Size (16 for PCM)
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // AudioFormat (1 for PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte() // BlockAlign
        header[33] = 0
        header[34] = 16 // BitsPerSample
        header[35] = 0
        header[36] = 'd'.code.toByte() // 'data' chunk
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (pcmDataLength and 0xff).toByte()
        header[41] = ((pcmDataLength shr 8) and 0xff).toByte()
        header[42] = ((pcmDataLength shr 16) and 0xff).toByte()
        header[43] = ((pcmDataLength shr 24) and 0xff).toByte()

        out.write(header, 0, 44)
    }

    /**
     * Updates the data size fields in the WAV header via RandomAccessFile.
     */
    internal fun updateWavHeader(
        file: File,
        pcmDataLength: Int,
        sampleRate: Int,
        channels: Int
    ) {
        val totalDataLen = pcmDataLength + 36
        val raf = RandomAccessFile(file, "rw")
        try {
            raf.seek(4)
            raf.write(
                byteArrayOf(
                    (totalDataLen and 0xff).toByte(),
                    ((totalDataLen shr 8) and 0xff).toByte(),
                    ((totalDataLen shr 16) and 0xff).toByte(),
                    ((totalDataLen shr 24) and 0xff).toByte()
                )
            )

            raf.seek(40)
            raf.write(
                byteArrayOf(
                    (pcmDataLength and 0xff).toByte(),
                    ((pcmDataLength shr 8) and 0xff).toByte(),
                    ((pcmDataLength shr 16) and 0xff).toByte(),
                    ((pcmDataLength shr 24) and 0xff).toByte()
                )
            )
        } finally {
            raf.close()
        }
    }
}
