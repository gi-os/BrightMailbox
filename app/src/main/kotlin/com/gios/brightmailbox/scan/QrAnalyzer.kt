package com.gios.brightmailbox.scan

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * A QR code off the live stream, decoded on the camera's own thread.
 *
 * **ZXing rather than ML Kit, and that is not a preference.** ML Kit's barcode scanner is the
 * obvious choice on any other Android phone and it is unavailable here: the unbundled model is
 * downloaded through Play Services and LightOS ships without GMS, so it would install, bind, and
 * never return a result. ZXing is pure Java, ships inside the APK, and needs nothing from the
 * platform.
 *
 * Ported from `gi-os/Roll` (which absorbed `gi-os/LightQR`), unchanged apart from the package.
 * Keep it that way: if the decoder needs a fix, fix it in Roll and copy it back, so the two do not
 * drift into two different scanners with one bug each.
 *
 * This replaced `com.journeyapps:zxing-android-embedded`, which worked but brought its own
 * activity, its own layout and its own idea of what a scanner looks like — none of it in the Light
 * design language, and a 3 MB dependency to be told so.
 */
class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {

    /**
     * QR only, and `TRY_HARDER`.
     *
     * Restricting the format list is most of the speed: [MultiFormatReader] with no hint runs
     * every one-dimensional reader over every row before it gets to the 2-D ones, which on a
     * 1280×720 frame is the difference between decoding at frame rate and decoding at three a
     * second. `TRY_HARDER` then buys back the distance — a code across a room is a small target,
     * and the extra passes it enables are what find it.
     */
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    /** Counts failures so a broken stream logs three lines rather than thirty a second. */
    private var complaints = 0

    override fun analyze(image: ImageProxy) {
        try {
            val source = luminance(image) ?: return
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            result?.text?.takeIf { it.isNotEmpty() }?.let(onResult)
        } catch (_: com.google.zxing.NotFoundException) {
            // No code in this frame. The overwhelmingly common case, and not a problem — ZXing
            // reports "nothing here" by throwing, so this catch is the normal path and must stay
            // silent or it would log twenty times a second.
        } catch (t: Throwable) {
            if (complaints++ < 3) Log.e(TAG, "decode failed", t)
        } finally {
            reader.reset()
            image.close()
        }
    }

    /**
     * The Y plane as a luminance source, honouring the row stride.
     *
     * **This is the bug LightQR shipped with.** The obvious version copies `planes[0].buffer` whole
     * and hands ZXing `width * height` bytes — but a camera plane is padded to a hardware-friendly
     * row length, so `rowStride` is routinely larger than `width` and the buffer is bigger than the
     * picture. Feeding that straight in either throws `IllegalArgumentException` on the length
     * check or, worse, decodes a sheared image where every row is offset a little further than the
     * one above it, which reads as "the scanner just doesn't work at some resolutions". So each row
     * is copied out at its own offset.
     *
     * Rotation is not handled and does not need to be: a QR code is found by its three finder
     * squares, which ZXing locates in two dimensions, so it decodes upside down and sideways alike.
     */
    private fun luminance(image: ImageProxy): PlanarYUVLuminanceSource? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val buffer = plane.buffer.also { it.rewind() }
        val stride = plane.rowStride
        val pixels = ByteArray(width * height)
        if (stride == width) {
            buffer.get(pixels, 0, minOf(pixels.size, buffer.remaining()))
        } else {
            val row = ByteArray(stride)
            for (y in 0 until height) {
                if (buffer.remaining() < stride) break
                buffer.get(row, 0, stride)
                System.arraycopy(row, 0, pixels, y * width, width)
            }
        }

        return PlanarYUVLuminanceSource(
            pixels,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
    }

    private companion object {
        const val TAG = "QrAnalyzer"
    }
}
