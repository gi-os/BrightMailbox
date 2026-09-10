package com.gios.brightmailbox.ui

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.gios.brightmailbox.data.Msg
import com.gios.brightmailbox.ui.theme.LocalGrid
import com.gios.brightmailbox.ui.theme.LocalType
import com.gios.brightmailbox.ui.theme.Screen as Frame
import com.gios.brightmailbox.ui.theme.Secondary
import com.gios.brightmailbox.ui.theme.T

/**
 * The message as its sender built it.
 *
 * **Why this is not handed to WebTools.** The plan was an intent, following MAKE A
 * TICKET and SEND TO LIBRARY. WebTools only declares `http`, `https`, `webtools://` and
 * CAPTIVE_PORTAL, so a `content://` HTML file does not resolve there and the row would
 * have been a dead button. Teaching it a new filter was possible, but rendering here is
 * the better answer anyway, for a reason specific to mail:
 *
 * **A browser is the wrong thing to open an email in.** Marketing mail is full of
 * tracking pixels, and a browser fetches them — which tells the sender the moment the
 * message was read, from where, on what. This view runs with JavaScript off and remote
 * images blocked unless Settings says otherwise, so opening the original does not
 * report back. That is not a setting a general-purpose browser can have.
 *
 * Links still leave: a tap is an ACTION_VIEW, which on this phone is WebTools. Bodies
 * render here, the web happens there.
 */
@Composable
fun OriginalScreen(vm: MailboxViewModel, msg: Msg) {
    val g = LocalGrid.current
    val t = LocalType.current
    val context = LocalContext.current
    var html by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf(false) }

    LaunchedEffect(msg.key) {
        val h = vm.repo.original(msg)
        if (h.isNullOrBlank()) missing = true else html = h
    }

    Frame {
        TopBar("ORIGINAL")
        Spacer(Modifier.height(g * 0.6f))

        when {
            missing -> {
                Spacer(Modifier.height(g * 2f))
                T("This message was plain text. There is nothing more to show.", t.detail, Secondary)
                Spacer(Modifier.weight(1f))
            }

            html == null -> {
                Spacer(Modifier.height(g * 2f))
                T("Fetching…", t.detail, Secondary)
                Spacer(Modifier.weight(1f))
            }

            else -> AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        // The tracking-pixel switch. Off by default; Settings turns it on.
                        settings.blockNetworkImage = !vm.repo.showImages
                        settings.loadsImagesAutomatically = vm.repo.showImages
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                // Never navigate in here. A link is the web, and the web
                                // is the browser's job.
                                runCatching {
                                    ctx.startActivity(
                                        Intent(Intent.ACTION_VIEW, request.url)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }.onFailure { vm.said("No browser here to open that.") }
                                return true
                            }
                        }
                    }
                },
                update = { web ->
                    /*
                     * A null base URL, deliberately. Giving the email an origin would let
                     * a relative reference resolve against a real host, which is another
                     * way to leak that the message was opened.
                     */
                    web.loadDataWithBaseURL(null, html.orEmpty(), "text/html", "UTF-8", null)
                },
            )
        }

        ActionBar(
            left = "BACK" to { vm.go(Screen.Read(msg.key)) },
            right = (if (vm.repo.showImages) "HIDE IMAGES" else "SHOW IMAGES") to {
                vm.repo.showImages = !vm.repo.showImages
                // The setting is read when the view is built, so bounce the screen.
                vm.go(Screen.Read(msg.key))
                vm.go(Screen.Original(msg.key))
            },
        )
        Spacer(Modifier.height(g * 0.4f))
    }
}
