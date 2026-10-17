package com.wireguard.android.util

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class QrFrameDecoderTest {
    private val size = 384
    private val payload = "virtuvpn://enroll?test=local-only&label=Žiadne%20uloženie"
    private fun qr(text: String = payload): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        return ByteArray(size * size) { if (matrix[it % size, it / size]) 0 else 255.toByte() }
    }

    @Test fun completePayloadIsPreserved() {
        assertEquals(payload, QrFrameDecoder().decode(ByteBuffer.wrap(qr()), size, size, size, 1))
    }

    @Test fun invertedCodeIsRecognizedLocally() {
        val bytes = qr().map { (255 - (it.toInt() and 255)).toByte() }.toByteArray()
        assertEquals(payload, QrFrameDecoder().decode(ByteBuffer.wrap(bytes), size, size, size, 1))
    }

    @Test fun rotatedCodeIsRecognized() {
        val original = qr()
        val rotated = ByteArray(original.size)
        for (y in 0 until size) for (x in 0 until size) rotated[x * size + size - 1 - y] = original[y * size + x]
        assertEquals(payload, QrFrameDecoder().decode(ByteBuffer.wrap(rotated), size, size, size, 1))
    }

    @Test fun cameraPaddingAndPixelStrideDoNotBecomeImagePixels() {
        val pixels = qr()
        for (pixelStride in listOf(1, 2)) {
            val stride = size * pixelStride + 32
            val prefix = 13
            // Real Camera2 planes need not expose padding after the final row.
            val length = prefix + (size - 1) * stride + (size - 1) * pixelStride + 1
            val bytes = ByteArray(length) { 0x77.toByte() }
            for (y in 0 until size) for (x in 0 until size) bytes[prefix + y * stride + x * pixelStride] = pixels[y * size + x]
            val unchanged = bytes.clone()
            val buffer = ByteBuffer.wrap(bytes).apply { position(prefix) }.asReadOnlyBuffer()
            assertArrayEquals(pixels, QrFrameDecoder.copyLuminance(buffer, size, size, stride, pixelStride))
            assertEquals(payload, QrFrameDecoder().decode(buffer, size, size, stride, pixelStride))
            assertEquals(prefix, buffer.position())
            assertEquals(length, buffer.limit())
            assertArrayEquals(unchanged, bytes)
        }
    }

    @Test fun malformedCameraPlanesFailBeforeReadingOrAllocating() {
        val cases = listOf(intArrayOf(0, 2, 2, 1), intArrayOf(2, 0, 2, 1), intArrayOf(2, 2, 1, 1),
            intArrayOf(2, 2, 2, 0), intArrayOf(2, 2, 2, 2), intArrayOf(5000, 5000, 5000, 1),
            intArrayOf(2, 3, 4, 1), intArrayOf(Int.MAX_VALUE, 2, Int.MAX_VALUE, 1))
        for ((w, h, row, pixel) in cases) {
            assertThrows(IllegalArgumentException::class.java) { QrFrameDecoder.copyLuminance(ByteBuffer.allocate(4), w, h, row, pixel) }
        }
    }

    @Test fun unrelatedFramesNeverBecomeSuccessfulResults() {
        for (shade in listOf(0.toByte(), 255.toByte()))
            assertNull(QrFrameDecoder().decode(ByteBuffer.wrap(ByteArray(size * size) { shade }), size, size, size, 1))
    }

    @Test fun denseConfigurationKeepsEveryCharacter() {
        val text = "[Interface]\nPrivateKey = " + "A".repeat(43) + "=\nAddress = 192.0.2.2/32\n" +
            (1..5).joinToString("") { "[Peer]\nPublicKey = ${"B".repeat(43)}=\nAllowedIPs = 198.51.100.$it/32\nEndpoint = 203.0.113.$it:51820\n" }
        assertEquals(text, QrFrameDecoder().decode(ByteBuffer.wrap(qr(text)), size, size, size, 1))
    }
}
