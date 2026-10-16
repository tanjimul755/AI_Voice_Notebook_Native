package com.example.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AudioDecoder"
private const val TARGET_SAMPLE_RATE = 16000
private const val TIMEOUT_US = 5000L

/**
 * Android-native audio decoder and WAV converter.
 *
 * Whisper models require 16kHz 16-bit linear PCM mono WAV audio.
 * This class decodes saved .m4a (AAC in MP4 container) files using Android's
 * hardware/platform MediaExtractor and MediaCodec APIs, resamples to 16kHz mono,
 * and produces a temporary RIFF WAVE file ready for offline Whisper transcription.
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

            val srcSampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100

            val srcChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            // Prepare temporary output stream with placeholder WAV header
            val fos = FileOutputStream(outputFile)
            writeWavHeader(fos, TARGET_SAMPLE_RATE, 1, 0)

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            var totalPcmBytesWritten = 0

            // Accumulator for downmixing and resampling
            val pcmShortAccumulator = ArrayList<Short>(4096)

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
                        if (srcChannels > 1) {
                            val monoCount = shortCount / srcChannels
                            for (i in 0 until monoCount) {
                                var sum = 0
                                for (ch in 0 until srcChannels) {
                                    sum += rawShorts[i * srcChannels + ch]
                                }
                                pcmShortAccumulator.add((sum / srcChannels).toShort())
                            }
                        } else {
                            for (s in rawShorts) {
                                pcmShortAccumulator.add(s)
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outputBufIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true
                    }
                }
            }

            // Resample accumulated mono samples to 16kHz
            val resampledShorts = resampleLinear(
                pcmShortAccumulator.toShortArray(),
                srcSampleRate,
                TARGET_SAMPLE_RATE
            )

            // Write 16-bit little-endian samples to WAV
            val byteBuffer = ByteBuffer.allocate(resampledShorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in resampledShorts) {
                byteBuffer.putShort(sample)
            }
            val resampledBytes = byteBuffer.array()
            fos.write(resampledBytes)
            totalPcmBytesWritten += resampledBytes.size

            fos.flush()
            try {
                fos.close()
            } catch (_: Exception) {}

            // Update WAV header with actual data size
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
     * Resamples a 16-bit mono PCM array using linear interpolation.
     */
    private fun resampleLinear(
        input: ShortArray,
        srcRate: Int,
        dstRate: Int
    ): ShortArray {
        if (input.isEmpty() || srcRate == dstRate) {
            return input
        }

        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLength = (input.size / ratio).toInt().coerceAtLeast(1)
        val output = ShortArray(outLength)

        for (i in 0 until outLength) {
            val srcIndexDouble = i * ratio
            val srcIndex = srcIndexDouble.toInt()
            val fraction = srcIndexDouble - srcIndex

            if (srcIndex + 1 < input.size) {
                val s0 = input[srcIndex].toInt()
                val s1 = input[srcIndex + 1].toInt()
                val interpolated = s0 + (fraction * (s1 - s0)).toInt()
                output[i] = interpolated.coerceIn(-32768, 32767).toShort()
            } else if (srcIndex < input.size) {
                output[i] = input[srcIndex]
            } else {
                output[i] = 0
            }
        }
        return output
    }

    /**
     * Writes standard 44-byte RIFF WAV header.
     */
    private fun writeWavHeader(
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
    private fun updateWavHeader(
        file: File,
        pcmDataLength: Int,
        sampleRate: Int,
        channels: Int
    ) {
        val totalDataLen = pcmDataLength + 36
        val byteRate = sampleRate * channels * 2
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
