package com.wireguard.android.util

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

/** Local, QR-only decoder. Instances are confined to the single analysis worker. */
internal class QrFrameDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun decode(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): String? {
        val bytes = copyLuminance(buffer, width, height, rowStride, pixelStride)
        val source = PlanarYUVLuminanceSource(bytes, width, height, 0, 0, width, height, false)
        for (image in listOf(source, source.invert())) {
            try { return reader.decode(BinaryBitmap(HybridBinarizer(image)), hints).text }
            catch (_: ReaderException) { /* No complete QR in this frame; continue scanning. */ }
            finally { reader.reset() }
        }
        return null
    }

    companion object {
        /** Camera2 planes can contain row padding; never interpret it as image pixels. */
        fun copyLuminance(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
            require(width > 0 && height > 0 && pixelStride > 0 && rowStride > 0)
            val pixels = width.toLong() * height
            require(pixels <= 12_000_000L) { "Camera frame exceeds the analysis bound" }
            val rowBytes = (width - 1L) * pixelStride + 1
            require(rowStride >= rowBytes) { "Overlapping camera rows" }
            val source = buffer.duplicate()
            val base = source.position()
            val end = base + (height - 1L) * rowStride + rowBytes
            require(end <= source.limit()) { "Truncated camera plane" }
            val bytes = ByteArray(pixels.toInt())
            for (row in 0 until height) {
                if (pixelStride == 1) {
                    source.position(base + row * rowStride)
                    source.get(bytes, row * width, width)
                } else {
                    for (column in 0 until width)
                        bytes[row * width + column] = source.get(base + row * rowStride + column * pixelStride)
                }
            }
            return bytes
        }
    }
}
