package com.gios.brightmailbox.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    data object FirstSync : Screen
    data object Home : Screen
    data class Read(val key: String) : Screen
    data object Notices : Screen
    data class Write(val replyTo: Msg? = null) : Screen
    data object Settings : Screen
    data object Rules : Screen
}

data class SyncProgress(val done: Int, val total: Int, val letters: Int, val notices: Int)

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
    fun visibleLetters(all: List<Msg>): List<Msg> =
        if (repo.ration == Ration.UNLIMITED) all else all.take(_allowed.value)

    val dayDone: Boolean
        get() = repo.ration == Ration.FIVE && _allowed.value == 0

    /** The one override: hold the wheel to unlock a sixth. */
    fun unlockOneMore() {
        repo.extraToday = repo.extraToday + 1
        refreshRation()
    }

    fun open(msg: Msg) = viewModelScope.launch {
        _body.value = null
        go(Screen.Read(msg.key))
        _body.value = repo.body(msg)
        repo.open(msg)
        refreshRation()
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
    fun completeSignIn(uri: android.net.Uri) = viewModelScope.launch {
        val account = runCatching { repo.auth.onRedirect(uri) }.getOrNull()
        if (account == null) {
            said("Sign-in didn't complete.")
            return@launch
        }
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
    }

    fun syncNow() = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        runCatching { repo.sync(limit = 30) }
            .onFailure { said("Couldn't reach the server.") }
        _busy.value = false
        refreshRation()
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
}
