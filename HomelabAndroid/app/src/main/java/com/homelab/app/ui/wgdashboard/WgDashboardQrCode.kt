package com.homelab.app.ui.wgdashboard

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders a WireGuard client configuration as a QR code, which is how the WireGuard app on a
 * phone imports a tunnel.
 *
 * A configuration is a few hundred characters, so the lowest error-correction level keeps the
 * modules large enough to scan from a phone screen. Returns null when the content is too long
 * for a QR code or cannot be encoded; the caller then shows the plain text instead.
 */
internal fun wireGuardQrBitmap(content: String, sizePx: Int): Bitmap? {
    if (content.isBlank() || sizePx <= 0) return null
    return runCatching {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 1
            )
        )
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            val offset = y * matrix.width
            for (x in 0 until matrix.width) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }
    }.getOrNull()
}
