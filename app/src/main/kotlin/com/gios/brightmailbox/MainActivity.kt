package com.gios.brightmailbox

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gios.brightmailbox.sync.SyncWorker
import com.gios.brightmailbox.ui.FirstSyncScreen
import com.gios.brightmailbox.ui.HomeScreen
import com.gios.brightmailbox.ui.MailboxViewModel
import com.gios.brightmailbox.ui.NoticesScreen
import com.gios.brightmailbox.ui.OriginalScreen
import com.gios.brightmailbox.ui.PasswordScreen
import com.gios.brightmailbox.ui.ReaderScreen
import com.gios.brightmailbox.ui.RulesScreen
import com.gios.brightmailbox.ui.Screen
import com.gios.brightmailbox.ui.SettingsScreen
import com.gios.brightmailbox.ui.SetupScreen
import com.gios.brightmailbox.ui.WriteScreen
import com.gios.brightmailbox.ui.theme.LightTheme
import com.gios.light.common.report.ReportContext
import com.gios.light.common.report.ReportOverlay
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * singleTask, so the OAuth redirect comes back into the running activity through
 * onNewIntent rather than stacking a second copy on top of the reader.
 *
 * The redirect is handled in the ViewModel, not here: it needs a coroutine scope that
 * survives a configuration change, and the sync it kicks off outlives this activity.
 */
class MainActivity : ComponentActivity() {

    /** Set by onCreate/onNewIntent, consumed once by the composition. */
    private var redirect by mutableStateOf<Uri?>(null)

    /** Text off the sign-in QR, consumed once by the composition. */
    private var scanned by mutableStateOf<String?>(null)

    /*
     * Registered here rather than in Compose because registerForActivityResult has to
     * run before the activity is STARTED. The result travels back through the same
     * one-shot state field the OAuth redirect uses, so both land in the ViewModel's
     * scope rather than the composition's.
     */
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        scanned = result.contents   // null when the user backed out; ignored below
    }

    private fun scanSignIn() = scanner.launch(
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setBeepEnabled(false)
            .setOrientationLocked(true)
            .setPrompt("Point at the code"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        redirect = oauthUri(intent)

        setContent {
            LightTheme {
                val vm: MailboxViewModel = viewModel()
                val screen by vm.screen.collectAsStateWithLifecycle()
                val letters by vm.letters.collectAsStateWithLifecycle()
                val notices by vm.notices.collectAsStateWithLifecycle()

                LaunchedEffect(redirect) {
                    redirect?.let {
                        redirect = null
                        vm.completeSignIn(it)
                    }
                }

                LaunchedEffect(scanned) {
                    scanned?.let {
                        scanned = null
                        vm.signInFromQr(it)
                    }
                }

                LaunchedEffect(Unit) {
                    if (vm.repo.auth.isSignedIn) {
                        SyncWorker.schedule(this@MainActivity)
                        vm.syncNow()
                    }
                }

                /*
                 * Which screen a report came from. Named, never described: "Read" not
                 * the subject of what is being read — see MailboxApp for why a report
                 * from this app says less than the others.
                 */
                LaunchedEffect(screen) {
                    ReportContext.screen = when (screen) {
                        Screen.Setup -> "Setup"
                        is Screen.Password -> "Password"
                        Screen.FirstSync -> "FirstSync"
                        Screen.Home -> "Home"
                        is Screen.Read -> "Read"
                        is Screen.Original -> "Original"
                        Screen.Notices -> "Notices"
                        is Screen.Write -> "Write"
                        Screen.Settings -> "Settings"
                        Screen.Rules -> "Rules"
                    }
                }

                when (val s = screen) {
                    Screen.Setup -> SetupScreen(vm)
                    is Screen.Password -> PasswordScreen(vm, s.service, onScan = ::scanSignIn)
                    Screen.FirstSync -> FirstSyncScreen(vm)
                    Screen.Home -> HomeScreen(vm)
                    Screen.Notices -> NoticesScreen(vm)
                    Screen.Settings -> SettingsScreen(vm)
                    Screen.Rules -> RulesScreen(vm)
                    is Screen.Write -> WriteScreen(vm, s.replyTo)
                    is Screen.Read -> {
                        val msg = (letters + notices).firstOrNull { it.key == s.key }
                        if (msg == null) HomeScreen(vm) else ReaderScreen(vm, msg)
                    }
                    is Screen.Original -> {
                        val msg = (letters + notices).firstOrNull { it.key == s.key }
                        if (msg == null) HomeScreen(vm) else OriginalScreen(vm, msg)
                    }
                }

                /*
                 * BrightControl draws banners; this is the report chip, clear of the
                 * action bar every screen puts on the fold.
                 *
                 * Its own Box rather than relying on LightTheme's: that one passes a
                 * plain `@Composable () -> Unit`, so there is no BoxScope in here and an
                 * overlay that wants to align itself would not compile.
                 */
                Box(Modifier.fillMaxSize()) {
                    ReportOverlay(corner = Alignment.BottomEnd, bottomInset = 64.dp)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        redirect = oauthUri(intent)
    }

    /** Our own custom scheme only; anything else is not ours to act on. */
    private fun oauthUri(intent: Intent?): Uri? {
        val uri = intent?.data ?: return null
        val scheme = BuildConfig.OAUTH_REDIRECT.substringBefore(':')
        return if (uri.scheme == scheme) uri else null
    }
}
