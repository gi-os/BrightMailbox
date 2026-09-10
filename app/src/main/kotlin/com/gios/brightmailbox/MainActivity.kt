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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gios.brightmailbox.sync.SyncWorker
import com.gios.brightmailbox.ui.FirstSyncScreen
import com.gios.brightmailbox.ui.HomeScreen
import com.gios.brightmailbox.ui.MailboxViewModel
import com.gios.brightmailbox.ui.NoticesScreen
import com.gios.brightmailbox.ui.ReaderScreen
import com.gios.brightmailbox.ui.RulesScreen
import com.gios.brightmailbox.ui.Screen
import com.gios.brightmailbox.ui.SettingsScreen
import com.gios.brightmailbox.ui.SetupScreen
import com.gios.brightmailbox.ui.WriteScreen
import com.gios.brightmailbox.ui.theme.LightTheme

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

                LaunchedEffect(Unit) {
                    if (vm.repo.auth.isSignedIn) {
                        SyncWorker.schedule(this@MainActivity)
                        vm.syncNow()
                    }
                }

                when (val s = screen) {
                    Screen.Setup -> SetupScreen(vm)
                    is Screen.Password -> PasswordScreen(vm, s.service)
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
