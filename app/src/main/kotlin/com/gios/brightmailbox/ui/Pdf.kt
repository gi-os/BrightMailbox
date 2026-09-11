package com.gios.brightmailbox.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.gios.brightmailbox.ui.theme.Background
import com.gios.brightmailbox.ui.theme.LocalGrid
import com.gios.brightmailbox.ui.theme.LocalType
import com.gios.brightmailbox.ui.theme.Screen as Frame
import com.gios.brightmailbox.ui.theme.Secondary
import com.gios.brightmailbox.ui.theme.T
import java.io.File

/**
 * A PDF, read in the app.
 *
 * `PdfRenderer` has been in Android since API 21 and needs no dependency, no Play
 * Services and no network — which is the only reason a PDF reader belongs in a mail
 * client at all. The alternative was what v2.8 shipped: hand the file to
 * `ACTION_VIEW` and hope. On LightOS there is usually nothing installed that opens a
 * PDF, so the answer was "Nothing here opens pdf files" and a statement or a boarding
 * pass simply could not be read on the phone it was sent to.
 *
 * Pages are rendered one at a time as they scroll into view rather than up front: a
 * forty-page bank statement rendered eagerly at screen width is upwards of a hundred
 * megabytes of bitmap, which is an out-of-memory crash on this device, not a slow screen.
 *
 * White pages on the app's black ground, because a PDF *is* a sheet of paper and
 * inverting it would make every scanned document unreadable.
 */
@Composable
fun PdfScreen(
    file: File,
    name: String,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    val g = LocalGrid.current
    val t = LocalType.current

    /*
     * The renderer is opened once and closed when the screen goes.
     *
     * Both it and the descriptor are closeable and both leak a file handle if they are
     * not — and a leaked handle on a file in cacheDir keeps the storage the cache is
     * meant to be able to reclaim.
     */
    var renderer by remember(file) { mutableStateOf<PdfRenderer?>(null) }
    var descriptor by remember(file) { mutableStateOf<ParcelFileDescriptor?>(null) }
    var failed by remember(file) { mutableStateOf(false) }

    DisposableEffect(file) {
        runCatching {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            descriptor = fd
            renderer = PdfRenderer(fd)
        }.onFailure { failed = true }
        onDispose {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            renderer = null
            descriptor = null
        }
    }

    Frame {
        TopBar(name.take(28).uppercase())

        val r = renderer
        when {
            failed -> {
                Spacer(Modifier.height(g * 3f))
                T("This file could not be opened.", t.copy)
                Spacer(Modifier.height(g * 0.6f))
                T(
                    "It may be encrypted, or damaged in transit. Saving it to the phone " +
                        "and opening it elsewhere is the next thing to try.",
                    t.detail,
                    Secondary,
                )
                Spacer(Modifier.weight(1f))
            }
            r == null -> {
                Spacer(Modifier.height(g * 3f))
                T("Opening…", t.detail, Secondary)
                Spacer(Modifier.weight(1f))
            }
            else -> {
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(g * 0.6f),
                ) {
                    items(r.pageCount) { index -> PdfPage(r, index) }
                    item { Spacer(Modifier.height(g * 0.5f)) }
                }
            }
        }

        ActionBar(
            left = "BACK" to onClose,
            right = "SAVE" to onSave,
        )
    }
}

/**
 * One page.
 *
 * Rendered at the view's own pixel width, not the PDF's — a page is described in points
 * and rendering it at its native size would be both blurry and enormous. The aspect ratio
 * comes from the page so the box is the right shape before the bitmap exists, which stops
 * the list jumping as pages arrive.
 */
@Composable
private fun PdfPage(renderer: PdfRenderer, index: Int) {
    var bitmap by remember(renderer, index) { mutableStateOf<Bitmap?>(null) }
    var ratio by remember(renderer, index) { mutableStateOf(1f / 1.414f) }

    /*
     * PdfRenderer allows exactly ONE page open at a time across the whole renderer, and
     * opening a second while one is open throws. The whole open-render-close cycle is
     * therefore done synchronously inside this effect, and the bitmap — which is ours
     * once it is drawn into — is what survives.
     */
    DisposableEffect(renderer, index) {
        runCatching {
            synchronized(renderer) {
                val page = renderer.openPage(index)
                try {
                    ratio = page.width.toFloat() / page.height.toFloat()
                    val width = 1000
                    val height = (width / ratio).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // Paper is white. The renderer draws onto whatever is already there,
                    // so an unfilled bitmap leaves transparent gaps wherever the page is
                    // blank — which on a black ground is a black page with text on it.
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap = bmp
                } finally {
                    page.close()
                }
            }
        }
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }

    val bmp = bitmap
    if (bmp == null) {
        Spacer(
            Modifier.fillMaxWidth().aspectRatio(ratio).background(androidx.compose.ui.graphics.Color.DarkGray),
        )
    } else {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
