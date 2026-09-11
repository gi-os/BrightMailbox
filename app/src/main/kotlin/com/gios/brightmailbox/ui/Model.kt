package com.gios.brightmailbox.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.brightmailbox.data.Depth
import com.gios.brightmailbox.data.Msg
import com.gios.brightmailbox.data.Ration
import com.gios.brightmailbox.data.Repo
import com.gios.brightmailbox.data.SenderRule
import com.gios.brightmailbox.mail.Outgoing
import com.gios.brightmailbox.sort.Pile
import com.gios.brightmailbox.text.Clean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the app is. Flat on purpose — LightOS supplies the back button. */
sealed interface Screen {
    data object Setup : Screen
    /** Typing an app password for a service that uses one. */
    data class Password(val service: com.gios.brightmailbox.auth.Service) : Screen
    /** The viewfinder, reading a sign-in code off the companion page. */
    data class Scan(val service: com.gios.brightmailbox.auth.Service) : Screen
    data object FirstSync : Screen
    data object Home : Screen
    data class Read(val key: String) : Screen
    data object Notices : Screen
    data class Write(val replyTo: Msg? = null) : Screen
    data object Settings : Screen
    data object Rules : Screen
    /** One mailbox: rename it, or remove it. */
    data class AccountScreen(val id: String) : Screen
    /** Typing in the OAuth client id for a build that shipped without one. */
    data class ClientId(val service: com.gios.brightmailbox.auth.Service) : Screen
}

data class SyncProgress(val done: Int, val total: Int, val letters: Int, val notices: Int)

private const val PREFETCH = 8

class MailboxViewModel(app: Application) : AndroidViewModel(app) {

    val repo = Repo.get(app)

    private val _screen = MutableStateFlow<Screen>(
        if (repo.auth.isSignedIn) Screen.Home else Screen.Setup,
    )
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val letters = repo.letters()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val notices = repo.notices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val noticeCount = repo.unreadNotices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val waiting = repo.waitingLetters()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val rules: StateFlow<List<SenderRule>> = repo.rules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _allowed = MutableStateFlow(5)
    val allowed: StateFlow<Int> = _allowed.asStateFlow()

    private val _readToday = MutableStateFlow(0)
    val readToday: StateFlow<Int> = _readToday.asStateFlow()

    private val _body = MutableStateFlow<Clean.Body?>(null)
    val body: StateFlow<Clean.Body?> = _body.asStateFlow()

    /**
     * The sender's HTML for the open message, or null once we know there is none.
     *
     * Two flows rather than one because they arrive at different times and the reader
     * wants whichever is ready: the flattened text is on disk from the prefetch and draws
     * immediately, the HTML is read straight after. Without that split, a formatted
     * message would show nothing at all until both were in hand.
     */
    private val _html = MutableStateFlow<String?>(null)
    val html: StateFlow<String?> = _html.asStateFlow()

    /** What is attached to the open message. Names and sizes only; no bytes. */
    private val _attachments =
        MutableStateFlow<List<com.gios.brightmailbox.mail.Attachment>>(emptyList())
    val attachments: StateFlow<List<com.gios.brightmailbox.mail.Attachment>> =
        _attachments.asStateFlow()

    /** Set from the reader's ··· sheet. Survives leaving and re-entering a message. */
    private val _plainText = MutableStateFlow(false)
    val plainText: StateFlow<Boolean> = _plainText.asStateFlow()

    fun togglePlainText() { _plainText.value = !_plainText.value }

    /**
     * The message being read, held here rather than looked up in the lists.
     *
     * The reader used to find its message by key in `letters + notices`, and opening a
     * Letter sets `readHere`, which is a column the Letters query filters on. So the row
     * left the list a second after it was opened, the lookup returned null, and the reader
     * was replaced by the home screen mid-read — "opening an email immediately exits the
     * email". The screen being read must not depend on a query whose whole job is to stop
     * listing what has been read.
     */
    private val _opened = MutableStateFlow<Msg?>(null)
    val opened: StateFlow<Msg?> = _opened.asStateFlow()

    /**
     * The signed-in mailboxes, as state rather than as a call.
     *
     * `auth.accounts()` reads SharedPreferences and returns a fresh list, so a screen that
     * called it directly never noticed a rename — it had no reason to recompose. Every
     * screen that names an account reads this instead, and anything that changes an
     * account calls [refreshAccounts].
     */
    private val _accounts = MutableStateFlow(repo.auth.accounts())
    val accounts: StateFlow<List<com.gios.brightmailbox.auth.Account>> = _accounts.asStateFlow()

    fun refreshAccounts() { _accounts.value = repo.auth.accounts() }

    init { refreshRation() }

    fun go(s: Screen) {
        _screen.value = s
        if (s is Screen.Home) refreshRation()
    }

    fun said(message: String?) { _toast.value = message }

    private fun refreshRation() = viewModelScope.launch {
        _allowed.value = repo.allowedToday()
        _readToday.value = repo.readToday()
    }

    /* -------------------------------------------------------------------- reading */

    /**
     * Today's visible Letters.
     *
     * Beyond the ration they are not deleted and not hidden in a folder — they are
     * tomorrow's, and the count of what waits is shown. The list is already ranked by
     * the DAO, so this only takes the top of it.
     */
    fun visibleLetters(all: List<Msg>): List<Msg> {
        if (repo.ration == Ration.UNLIMITED) return all
        /*
         * Score picks, time orders.
         *
         * [all] arrives newest first. The ration is meant to hand back the letters worth
         * reading rather than merely the most recent, so the model still chooses WHICH
         * ones — but the five it chooses are then put back in time order, because a list
         * of five sorted by an invisible number is a list that looks shuffled.
         *
         * A coarse bucket, not the raw double: scores drift by thousandths on every sync
         * and sorting on the exact value would reshuffle the day's five for no visible
         * reason.
         */
        return all.sortedWith(
            compareByDescending<Msg> { (it.score * 20).toInt() }
                .thenByDescending { it.receivedAt },
        )
            .take(_allowed.value)
            .sortedByDescending { it.receivedAt }
    }

    val dayDone: Boolean
        get() = repo.ration == Ration.FIVE && _allowed.value == 0

    /** The one override: hold the wheel to unlock a sixth. */
    fun unlockOneMore() {
        repo.extraToday = repo.extraToday + 1
        refreshRation()
    }

    /**
     * Open a Letter or a Notice.
     *
     * The body is usually on disk already — [Repo.prefetchBodies] fetches the ones on the
     * front screen as soon as a sync finishes — so this is normally instant. When it is
     * not, the screen is up first and the text arrives into it, rather than the tap doing
     * nothing for two seconds.
     */
    fun open(msg: Msg) = viewModelScope.launch {
        _body.value = null
        _html.value = null
        _attachments.value = emptyList()
        _opened.value = msg
        go(Screen.Read(msg.key))
        // Text first: it is on disk from the prefetch, so the page is never blank while
        // the HTML is read out of the cache beside it.
        _body.value = repo.body(msg)
        _html.value = repo.original(msg)
        // Cheap: the list is cached beside the body and needs no extra round trip once
        // the body has been fetched once.
        _attachments.value = if (msg.hasAttachments) repo.attachments(msg) else emptyList()
        repo.open(msg)
        refreshRation()
    }

    /**
     * Fetch an attachment and hand the file back so the screen can open it.
     *
     * Says something first, because this is the one action in the app that can take
     * several seconds over IMAP with nothing on screen to show for it.
     */
    fun openAttachment(
        msg: Msg,
        att: com.gios.brightmailbox.mail.Attachment,
        onReady: (java.io.File) -> Unit,
    ) = viewModelScope.launch {
        said("Getting ${att.name}…")
        val f = repo.attachmentFile(msg, att)
        if (f == null) said("Could not fetch that file.") else { said(null); onReady(f) }
    }

    fun archive(msg: Msg) = viewModelScope.launch {
        repo.archive(msg)
        go(Screen.Home)
    }

    fun markAllNoticesRead() = viewModelScope.launch {
        repo.markAllNoticesRead()
        said("Marked read.")
    }

    fun move(msg: Msg, to: Pile) = viewModelScope.launch {
        said(repo.move(msg, to))
        go(Screen.Home)
    }

    fun dropRule(address: String) = viewModelScope.launch { repo.dropRule(address) }

    /* -------------------------------------------------------------------- sign-in */

    /**
     * Finish the OAuth round trip.
     *
     * Runs on the ViewModel's scope rather than the activity's, because the first sync it
     * triggers walks a whole mailbox and must not die if the activity is recreated when
     * the browser hands control back.
     */
    /**
     * Sign in with an app password.
     *
     * The credential is checked against the IMAP server before it is stored, so the
     * failure the user sees is "that is not an app password" at the moment they typed
     * it — not a sync that silently never runs. [onDone] carries the error sentence, or
     * null when the account was added and the first sync has started.
     */
    fun signInWithPassword(
        service: com.gios.brightmailbox.auth.Service,
        email: String,
        password: String,
        onDone: (String?) -> Unit,
    ) = viewModelScope.launch {
        _busy.value = true
        val result = repo.auth.signInWithPassword(service, email, password)
        _busy.value = false
        result.fold(
            onSuccess = { onDone(null); refreshAccounts(); firstSync() },
            onFailure = { onDone(it.message ?: "That did not work.") },
        )
    }

    /**
     * Sign in from the companion page's QR.
     *
     * Errors go to [said] rather than the screen's inline error, because a scan can be
     * started from anywhere and the toast is the one surface that is always visible.
     */
    fun signInFromQr(text: String) = viewModelScope.launch {
        when (val p = com.gios.brightmailbox.auth.QrSignIn.parse(text)) {
            is com.gios.brightmailbox.auth.QrSignIn.Result.Bad -> said(p.why)
            is com.gios.brightmailbox.auth.QrSignIn.Result.Ok -> {
                _busy.value = true
                val r = repo.auth.signInWithPassword(p.service, p.email, p.password)
                _busy.value = false
                r.fold(
                    onSuccess = { refreshAccounts(); firstSync() },
                    onFailure = { said(it.message ?: "That code did not work.") },
                )
            }
        }
    }

    fun completeSignIn(uri: android.net.Uri) = viewModelScope.launch {
        val account = runCatching { repo.auth.onRedirect(uri) }.getOrNull()
        if (account == null) {
            said("Sign-in didn't complete.")
            return@launch
        }
        refreshAccounts()
        firstSync().join()
    }

    /* -------------------------------------------------------------------- syncing */

    fun firstSync() = viewModelScope.launch {
        go(Screen.FirstSync)
        _progress.value = SyncProgress(0, 0, 0, 0)
        runCatching {
            repo.firstSync { done, total, letters, notices ->
                _progress.value = SyncProgress(done, total, letters, notices)
            }
        }.onFailure { said("Sync failed. It will keep trying in the background.") }
        refreshRation()
        prefetch()
    }

    /**
     * Check now, and say what happened.
     *
     * Both outcomes are reported. A refresh that finds nothing says so, because the
     * complaint that prompted this was "it isn't populating even after refreshing" — and
     * a button that looks identical whether it worked, found nothing, or failed to
     * connect is a button you press again rather than a fact you can act on.
     */
    fun syncNow() = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        runCatching { repo.sync(limit = 30) }
            .onSuccess { r ->
                when {
                    r.failures.isNotEmpty() -> said(r.failures.first())
                    r.fetched > 0 -> said("${r.fetched} new.")
                    else -> said("Nothing new.")
                }
            }
            .onFailure { said("Couldn't reach the server.") }
        _busy.value = false
        refreshRation()
        prefetch()
    }

    /**
     * Fetch the text of the Letters on the front screen, quietly, after a sync.
     *
     * IMAP carries no snippet, so an unfetched message has nothing to show while its body
     * is on its way — the reader opens on an empty page for as long as the round trip
     * takes. The ration means the number of Letters that can be opened today is small and
     * known, so fetching exactly those costs one short burst on a connection that is
     * already open and makes every open instant.
     */
    private fun prefetch() = viewModelScope.launch {
        runCatching { repo.prefetchBodies(letters.value.take(PREFETCH), notices.value.take(2)) }
    }

    /* -------------------------------------------------------------------- writing */

    fun send(accountId: String, msg: Outgoing) = viewModelScope.launch {
        _busy.value = true
        runCatching { repo.send(accountId, msg) }
            .onSuccess { said("Sent."); go(Screen.Home) }
            .onFailure { said("Not sent. Your draft is still here.") }
        _busy.value = false
    }

    /* ------------------------------------------------------------------- settings */

    fun setRation(r: Ration) {
        repo.ration = r
        refreshRation()
    }

    /**
     * Change how much history a first sync reads.
     *
     * Choosing a bigger number is a request for the mail that number describes, so it runs
     * the deep sync again rather than waiting for the next fresh install. Choosing a
     * smaller one deletes nothing: what has been fetched stays.
     */
    fun setDepth(d: Depth) {
        val deeper = d.perAccount > repo.depth.perAccount
        repo.depth = d
        if (deeper && repo.auth.isSignedIn) firstSync()
    }

    /**
     * Give a service its OAuth client id from the phone.
     *
     * A build with no id in it has a dead ADD button, and the id is not a secret — it is
     * the public half of a registration anyone can make in three minutes. Typing it here
     * is the difference between "rebuild the app" and "sign in", which matters because the
     * person holding the phone is rarely the person holding the build.
     */
    fun setClientId(service: com.gios.brightmailbox.auth.Service, raw: String) =
        viewModelScope.launch {
            if (repo.auth.setClientId(service, raw)) {
                refreshAccounts()
                said("Saved. Now sign in.")
                go(Screen.Setup)
            } else {
                said("That is not a client id. It looks like 8-4-4-4-12 characters.")
            }
        }

    fun renameAccount(id: String, name: String) {
        repo.auth.setName(id, name)
        refreshAccounts()
        go(Screen.Settings)
    }

    /** Remove a mailbox. Its mail goes with it — the rows are keyed by account. */
    fun forgetAccount(id: String) = viewModelScope.launch {
        repo.auth.forget(id)
        runCatching { repo.dao.deleteAccount(id) }
        refreshAccounts()
        if (repo.auth.isSignedIn) go(Screen.Settings) else go(Screen.Setup)
    }
}
