package com.gios.brightmailbox

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gios.brightmailbox.hw.LightKey
import com.gios.brightmailbox.hw.LightKeys
import com.gios.brightmailbox.hw.LocalWheelBus
import com.gios.brightmailbox.hw.WheelBus
import com.gios.brightmailbox.sync.SyncWorker
import com.gios.brightmailbox.ui.AccountDetailScreen
import com.gios.brightmailbox.ui.ArchiveScreen
import com.gios.brightmailbox.ui.DownloadsScreen
import com.gios.brightmailbox.ui.DraftsScreen
import com.gios.brightmailbox.ui.FlaggedScreen
import com.gios.brightmailbox.ui.MenuScreen
import com.gios.brightmailbox.ui.SearchScreen
import com.gios.brightmailbox.ui.SentScreen
import com.gios.brightmailbox.ui.ClientIdScreen
import com.gios.brightmailbox.ui.FirstSyncScreen
import com.gios.brightmailbox.ui.HomeScreen
import com.gios.brightmailbox.ui.LocalAccountWords
import com.gios.brightmailbox.ui.LocalPreviews
import com.gios.brightmailbox.ui.MailboxViewModel
import com.gios.brightmailbox.ui.NoticesScreen
import com.gios.brightmailbox.ui.PasswordScreen
import com.gios.brightmailbox.ui.ReaderScreen
import com.gios.brightmailbox.ui.RulesScreen
import com.gios.brightmailbox.ui.Said
import com.gios.brightmailbox.ui.ScanScreen
import com.gios.brightmailbox.ui.Screen
import com.gios.brightmailbox.ui.SettingsScreen
import com.gios.brightmailbox.ui.SetupScreen
import com.gios.brightmailbox.ui.WriteScreen
import com.gios.brightmailbox.ui.theme.LightTheme
import com.gios.light.common.report.ReportContext
import com.gios.light.common.report.ReportOverlay

/*
 * The slide-up sheet.
 *
 * A letter arrives the way a sheet arrives on a phone: white, from the bottom edge,
 * decelerating into place — never at a constant speed, which is what a plain tween gives
 * and what made the first version feel mechanical. The curve is the one iOS uses for a
 * presented sheet: fast at the start, long slow settle, no overshoot.
 *
 * Out is quicker than in, which is the usual asymmetry — arriving is an event worth
 * watching, leaving is not.
 */
private val SheetEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
private const val SHEET_IN = 380
private const val SHEET_OUT = 280

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

    /**
     * Wheel notches, on their way from the hardware to whatever is on screen.
     *
     * Held by the activity because [dispatchKeyEvent] is the only place that sees a key
     * before the view hierarchy does — `DecorView` offers it to the window callback before
     * `superDispatchKeyEvent` walks the views — and that is exactly what beats a **focused
     * WebView**, which is what a message is. A Compose key handler inside the reader would
     * never get the event.
     */
    private val wheel = WheelBus()

    /*
     * The scanner is a screen now, not an activity result.
     *
     * It used `com.journeyapps:zxing-android-embedded`, whose ScanContract launches its own
     * activity with its own layout — a viewfinder that looked like a different app, in the
     * middle of signing in to this one. `ui/Scan.kt` is ours and `scan/QrAnalyzer.kt` is
     * Roll's decoder, so there is no third-party UI and nothing to hand a result back from.
     */

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
                val opened by vm.opened.collectAsStateWithLifecycle()

                /*
                 * The message on screen, preferring the one the app was told to open.
                 *
                 * The lists are the fallback, for a screen restored after the process
                 * died. They cannot be the primary source: reading a Letter is exactly
                 * what takes it out of the Letters query, so a lookup there answers null
                 * a moment after the reader opens.
                 */
                fun message(key: String) = opened?.takeIf { it.key == key }
                    ?: (letters + notices).firstOrNull { it.key == key }

                LaunchedEffect(redirect) {
                    redirect?.let {
                        redirect = null
                        vm.completeSignIn(it)
                    }
                }

                LaunchedEffect(Unit) {
                    if (vm.repo.auth.isSignedIn) {
                        SyncWorker.schedule(this@MainActivity)
                        vm.syncOnOpen()
                    }
                }

                /*
                 * The IDLE watch lives exactly as long as the screen does.
                 *
                 * `repeatOnLifecycle(STARTED)` starts it when the activity becomes
                 * visible and **cancels it when the activity stops**, which is the whole
                 * design: an open connection held by a screen nobody is looking at is the
                 * version of this feature with a battery cost, and this is the version
                 * without one. A `LaunchedEffect(Unit)` would have been the former —
                 * it survives the activity going to the background.
                 */
                val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                LaunchedEffect(owner) {
                    owner.lifecycle.repeatOnLifecycle(
                        androidx.lifecycle.Lifecycle.State.STARTED,
                    ) { vm.watch() }
                }

                /*
                 * Ask for permission to notify.
                 *
                 * It was declared in the manifest and **never requested**, which since
                 * Android 13 means denied — so `nm.notify` posted nothing, and the
                 * `runCatching` around it swallowed the refusal without a word. No banner
                 * on BrightControl, no sound, no lock-face row, and nothing anywhere
                 * saying why. The one permission the app's whole premise rests on.
                 *
                 * Asked after sign-in rather than on first launch: a prompt before anyone
                 * has seen what the app does is a prompt people refuse, and this one
                 * cannot be asked twice.
                 */
                val notify = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                ) { }
                LaunchedEffect(screen) {
                    if (!vm.repo.auth.isSignedIn) return@LaunchedEffect
                    if (android.os.Build.VERSION.SDK_INT < 33) return@LaunchedEffect
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) notify.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }

                /*
                 * Start the WebView engine now, while nobody is waiting for it.
                 *
                 * The first `WebView(ctx)` in a process loads the whole rendering engine,
                 * which takes long enough that the first letter's slide-up was over before
                 * the page had painted anything — the view is held at zero opacity until
                 * `onPageCommitVisible`, so the first letter of a session appeared out of
                 * nowhere after the animation had finished. Every letter after it was
                 * fine, because the engine was warm by then. That is the whole reason this
                 * only ever happened once.
                 *
                 * Deliberately after the first frame: this blocks the main thread for a
                 * few hundred milliseconds and it should land on a drawn list rather than
                 * on the launch. The view is thrown away immediately — what survives is
                 * the process-wide engine, which is the expensive part.
                 */
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    runCatching {
                        android.webkit.WebView(this@MainActivity).apply {
                            loadDataWithBaseURL(null, "<html></html>", "text/html", "UTF-8", null)
                            destroy()
                        }
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
                        is Screen.Scan -> "Scan"
                        Screen.FirstSync -> "FirstSync"
                        Screen.Home -> "Home"
                        is Screen.Read -> "Read"
                        Screen.Notices -> "Notices"
                        Screen.Menu -> "Menu"
                        Screen.Archive -> "Archive"
                        Screen.Sent -> "Sent"
                        Screen.Flagged -> "Flagged"
                        Screen.Drafts -> "Drafts"
                        Screen.Downloads -> "Downloads"
                        Screen.Search -> "Search"
                        is Screen.Write -> "Write"
                        Screen.Settings -> "Settings"
                        Screen.Rules -> "Rules"
                        is Screen.AccountScreen -> "Account"
                        is Screen.ClientId -> "ClientId"
                    }
                }

                // Every screen that names a mailbox reads the names from here.
                val accounts by vm.accounts.collectAsStateWithLifecycle()
                val previews by vm.previews.collectAsStateWithLifecycle()

                /*
                 * The screen stays on while the app is doing something slow.
                 *
                 * Archiving four hundred messages is a couple of minutes of IMAP, and a
                 * send over a weak connection is not instant either. LightOS turns the
                 * panel off after thirty seconds — and on this phone that is not just the
                 * backlight: the process is a candidate for the doze rules the moment the
                 * screen goes, so what the person sees is a bar that stopped halfway and a
                 * job that did not finish.
                 *
                 * Tied to `work`, which is exactly the set of operations that report a
                 * progress bar, so the flag is held for as long as there is something to
                 * watch and not one second longer. Cleared in `onDispose`, which runs on
                 * every change of the condition as well as on teardown — a KEEP_SCREEN_ON
                 * left set is a flat battery by morning.
                 */
                val work by vm.work.collectAsStateWithLifecycle()
                androidx.compose.runtime.DisposableEffect(work != null) {
                    if (work != null) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                CompositionLocalProvider(
                    LocalAccountWords provides accounts.associate { it.id to it.word },
                    LocalPreviews provides previews,
                    LocalWheelBus provides wheel,
                ) {
                /*
                 * Opening a message: the list fades to black while the letter rides up
                 * past it.
                 *
                 * `togetherWith` is the point — both screens are composed and animating
                 * at the same time, so the outgoing list is still there to fade. An
                 * animation inside the reader could not do this: by the time the reader
                 * exists, the list is already gone and there is nothing left to fade.
                 *
                 * The letter does NOT fade. It slides at full opacity, because a slide
                 * that also changes opacity reads as two animations disagreeing. The
                 * fade is the list's alone, and it fades to nothing over the app's black
                 * ground, which is what "fades to black" means here.
                 *
                 * Everything else in the app still cuts. A transition is for the one
                 * move that changes what kind of thing you are looking at; putting one
                 * on every screen change would make the phone feel slow.
                 */
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        /*
                         * Any list to a message, and back to whichever list it was.
                         *
                         * This used to name Home on both sides, from when Home was the
                         * only way into a message. There are four now — the two piles, the
                         * archive and a search — and the sheet should arrive and leave the
                         * same way regardless of which one you were standing in.
                         */
                        val opening = targetState is Screen.Read && initialState !is Screen.Read
                        val closing = initialState is Screen.Read && targetState !is Screen.Read
                        when {
                            opening -> slideInVertically(
                                animationSpec = tween(SHEET_IN, easing = SheetEasing),
                                initialOffsetY = { it },
                            ) togetherWith fadeOut(animationSpec = tween(SHEET_IN))

                            // The same motion backwards, so putting a letter away is the
                            // gesture that opened it, undone — not a different animation
                            // that happens to end in the same place.
                            closing -> fadeIn(animationSpec = tween(SHEET_OUT)) togetherWith
                                slideOutVertically(
                                    animationSpec = tween(SHEET_OUT, easing = SheetEasing),
                                    targetOffsetY = { it },
                                )

                            else -> EnterTransition.None togetherWith ExitTransition.None
                        }
                    },
                    label = "screen",
                ) { current ->
                when (val s = current) {
                    Screen.Setup -> SetupScreen(vm)
                    is Screen.Password ->
                        PasswordScreen(
                            vm,
                            s.service,
                            s.preset,
                            onScan = { vm.go(Screen.Scan(s.service)) },
                        )
                    is Screen.Scan -> ScanScreen(vm, s.service)
                    Screen.FirstSync -> FirstSyncScreen(vm)
                    Screen.Home -> HomeScreen(vm)
                    Screen.Notices -> NoticesScreen(vm)
                    Screen.Menu -> MenuScreen(vm)
                    Screen.Archive -> ArchiveScreen(vm)
                    Screen.Sent -> SentScreen(vm)
                    Screen.Flagged -> FlaggedScreen(vm)
                    Screen.Drafts -> DraftsScreen(vm)
                    Screen.Downloads -> DownloadsScreen(vm)
                    Screen.Search -> SearchScreen(vm)
                    Screen.Settings -> SettingsScreen(vm)
                    Screen.Rules -> RulesScreen(vm)
                    is Screen.Write -> WriteScreen(vm, s.replyTo, s.draftId, s.mode, s.from)
                    is Screen.AccountScreen -> AccountDetailScreen(vm, s.id)
                    is Screen.ClientId -> ClientIdScreen(vm, s.service)
                    is Screen.Read -> {
                        val msg = message(s.key)
                        if (msg == null) HomeScreen(vm) else ReaderScreen(vm, msg)
                    }
                }
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
                    /*
                     * The progress line, hard against the top edge and above every screen.
                     *
                     * In the overlay rather than in a screen because the work outlives the
                     * screen that started it: a bulk archive keeps running while you walk
                     * back to the inbox, and a bar drawn inside Notices would disappear
                     * mid-way and read as having stopped.
                     */
                    com.gios.brightmailbox.ui.WorkBar(
                        vm,
                        Modifier.align(Alignment.TopCenter),
                    )
                    /*
                     * What the app just said, over whatever screen is up.
                     *
                     * Above the action bar rather than at the very bottom, because every
                     * screen in this app ends in a bar and a sentence printed over WRITE
                     * is a sentence that hides the control it is talking about.
                     */
                    Said(
                        vm,
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
                    )
                    ReportOverlay(corner = Alignment.BottomEnd, bottomInset = 64.dp)
                }
            }
        }
    }

    /**
     * Turns only.
     *
     * The wheel press, the camera button and brightness belong to LightControl, which owns
     * the buttons phone-wide and passes bare turns through to `com.gios.*`. An app that
     * claimed the click would fight it — and worse, claiming keys this app has no use for
     * would take away controls that currently work.
     *
     * Both halves of the notch are consumed. The sensor sends a DOWN/UP pair per notch and
     * letting the UP through would be a second event for the same physical movement.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
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
