package io.github.petebutty.viewarr.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import io.github.petebutty.viewarr.data.accounts.Accounts
import io.github.petebutty.viewarr.data.session.Session
import io.github.petebutty.viewarr.ui.common.MenuItem
import io.github.petebutty.viewarr.ui.common.QrImage
import io.github.petebutty.viewarr.ui.setup.SetupViewModel.Companion.ADDON_URLS
import io.github.petebutty.viewarr.ui.setup.SetupViewModel.Companion.PASSWORD
import io.github.petebutty.viewarr.ui.setup.SetupViewModel.Companion.SERVER
import io.github.petebutty.viewarr.ui.setup.SetupViewModel.Companion.TMDB_KEY
import io.github.petebutty.viewarr.ui.setup.SetupViewModel.Companion.USER
import io.github.petebutty.viewarr.ui.theme.AppColors
import kotlinx.coroutines.android.awaitFrame
import androidx.compose.material3.Text as M3Text

/** First-run setup, or (Manage) the Settings → Accounts & sources screen: same steps either way. */
@Composable
fun SetupWizard(
    mode: SetupMode = SetupMode.FirstRun,
    onClose: () -> Unit = {},
    vm: SetupViewModel = hiltViewModel(key = "setup-${mode.name}"),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(mode) { if (mode == SetupMode.Manage) vm.openManage() }
    LaunchedEffect(state.phoneFinished) { if (state.phoneFinished) vm.finish(onClose) }
    LaunchedEffect(state.step, state.plexChoices.size) {
        // Two frames: after a step change the new screen's buttons aren't attached on the first one.
        awaitFrame()
        awaitFrame()
        runCatching { firstFocus.requestFocus() }
    }
    val atRoot = (mode == SetupMode.FirstRun && state.step == SetupStep.Phone) || (mode == SetupMode.Manage && state.step == SetupStep.Choose)
    BackHandler(enabled = !atRoot) { vm.back() }
    BackHandler(enabled = mode == SetupMode.Manage && state.step == SetupStep.Choose) { vm.finish(onClose) }

    Row(
        modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(horizontal = 64.dp, vertical = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(0.36f).padding(end = 40.dp)) {
            Text("Viewarr", style = MaterialTheme.typography.titleLarge, color = AppColors.Accent)
            Spacer(Modifier.height(24.dp))
            Text(titleFor(state.step, mode), style = MaterialTheme.typography.displaySmall, color = AppColors.TextPrimary)
            Spacer(Modifier.height(14.dp))
            Text(bodyFor(state), style = MaterialTheme.typography.bodyLarge, color = AppColors.TextSecondary)
        }
        Column(
            Modifier
                .weight(0.64f)
                .background(AppColors.Panel, RoundedCornerShape(16.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (state.step) {
                SetupStep.Phone -> PhoneStep(state, accounts, session, firstFocus, vm, onClose)
                SetupStep.Welcome -> {
                    Text(
                        "You'll choose what to connect: live TV from Dispatcharr, and films and shows from Plex, Stremio addons, " +
                            "Jellyfin or Emby, with TMDB and Trakt for discovery. Anything you skip can be added later in Settings.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.TextPrimary,
                    )
                    Button(onClick = vm::start, modifier = Modifier.focusRequester(firstFocus)) { Text("Get started") }
                }
                SetupStep.Choose -> ChooseStep(state, accounts, session, firstFocus, vm, onClose)
                SetupStep.Server -> TextStep(
                    qrUrl = null,
                    fields = { WizardField(state.serverInput, vm::onServerInput, "Server address", firstFocus, KeyboardType.Uri, ImeAction.Done, vm::submit) },
                    actions = { Button(onClick = vm::submit, enabled = !state.busy) { Text(if (state.busy) "Checking…" else "Continue") } },
                )
                SetupStep.SignIn -> TextStep(
                    qrUrl = null,
                    fields = {
                        WizardField(state.username, vm::onUsername, "Username", firstFocus, KeyboardType.Text, ImeAction.Next) {}
                        WizardField(state.password, vm::onPassword, "Password", null, KeyboardType.Password, ImeAction.Done, vm::submit, password = true)
                    },
                    actions = { Button(onClick = vm::submit, enabled = !state.busy) { Text(if (state.busy) "Signing in…" else "Sign in") } },
                )
                SetupStep.Tmdb -> TextStep(
                    qrUrl = null,
                    fields = {
                        WizardField(state.fields[TMDB_KEY].orEmpty(), { vm.onField(TMDB_KEY, it) }, "TMDB API key", firstFocus, KeyboardType.Text, ImeAction.Done, vm::submit)
                    },
                    actions = {
                        Button(onClick = vm::submit, enabled = !state.busy) { Text(if (state.busy) "Checking…" else "Save") }
                        OutlinedButton(onClick = vm::advance) { Text("Skip") }
                    },
                )
                SetupStep.Trakt, SetupStep.Plex -> CodeStep(state, firstFocus, vm)
                SetupStep.Stremio -> TextStep(
                    qrUrl = null,
                    fields = {
                        accounts.addons.forEach { addon ->
                            MenuItem(text = addon.name, selected = false, trailing = "Remove", onClick = { vm.removeAddon(addon.baseUrl) })
                        }
                        WizardField(state.fields[ADDON_URLS].orEmpty(), { vm.onField(ADDON_URLS, it) }, "Addon URL (manifest.json)", firstFocus, KeyboardType.Uri, ImeAction.Done, vm::submit)
                    },
                    actions = {
                        Button(onClick = vm::submit, enabled = !state.busy) { Text(if (state.busy) "Checking…" else "Add") }
                        OutlinedButton(onClick = vm::advance) { Text(if (accounts.addons.isEmpty()) "Skip" else "Continue") }
                    },
                )
                SetupStep.Jellyfin, SetupStep.Emby -> TextStep(
                    qrUrl = null,
                    fields = {
                        WizardField(state.fields[SERVER].orEmpty(), { vm.onField(SERVER, it) }, "Server address", firstFocus, KeyboardType.Uri, ImeAction.Next) {}
                        WizardField(state.fields[USER].orEmpty(), { vm.onField(USER, it) }, "Username", null, KeyboardType.Text, ImeAction.Next) {}
                        WizardField(state.fields[PASSWORD].orEmpty(), { vm.onField(PASSWORD, it) }, "Password", null, KeyboardType.Password, ImeAction.Done, vm::submit, password = true)
                        state.code?.let { BigCode(it) }
                    },
                    actions = {
                        Button(onClick = vm::submit, enabled = !state.busy) { Text(if (state.busy) "Connecting…" else "Sign in") }
                        if (state.step == SetupStep.Jellyfin) OutlinedButton(onClick = vm::startQuickConnect) { Text("Quick Connect") }
                        OutlinedButton(onClick = vm::advance) { Text("Skip") }
                    },
                )
                SetupStep.Vod -> {
                    val count = state.vodCount
                    when {
                        count == null && state.error == null -> Text("Checking your Dispatcharr server…", color = AppColors.TextSecondary)
                        count != null && count > 0 -> {
                            Text("Your server has $count films and series from your provider.", color = AppColors.TextPrimary)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { vm.setVod(true) }, modifier = Modifier.focusRequester(firstFocus)) { Text("Turn on") }
                                OutlinedButton(onClick = { vm.setVod(false) }) { Text("Leave off") }
                            }
                        }
                        else -> {
                            Text(
                                "Your Dispatcharr server has no films or series from your provider, so there's nothing to add. " +
                                    "If your provider adds VOD later, turn it on here.",
                                color = AppColors.TextPrimary,
                            )
                            Button(onClick = { vm.setVod(false) }, modifier = Modifier.focusRequester(firstFocus)) { Text("Continue") }
                        }
                    }
                }
                SetupStep.Done -> {
                    Text(summary(accounts, session).ifEmpty { "Nothing connected yet. You can add sources any time in Settings." }, color = AppColors.TextPrimary)
                    Button(onClick = { vm.finish(onClose) }, modifier = Modifier.focusRequester(firstFocus)) { Text("Start watching") }
                }
            }
            state.info?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.Accent) }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.Error) }
        }
    }
}

@Composable
private fun ColumnScope.ChooseStep(
    state: SetupUiState,
    accounts: Accounts,
    session: Session?,
    firstFocus: FocusRequester,
    vm: SetupViewModel,
    onClose: () -> Unit,
) {
    val manage = state.mode == SetupMode.Manage
    val items = SetupItem.entries.filter { it.available && (it != SetupItem.Vod || session != null || SetupItem.LiveTv in state.selected) }
    // The buttons go first so they're always on screen: a long list used to push Continue out of the panel.
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (manage) {
            Button(onClick = vm::openPhone, modifier = Modifier.focusRequester(firstFocus)) { Text("Set up on your phone") }
            OutlinedButton(onClick = { vm.finish(onClose) }) { Text("Done") }
        } else {
            Button(onClick = vm::beginChosen, enabled = state.selected.isNotEmpty()) {
                Text(if (state.selected.isEmpty()) "Tick something first" else "Continue with ${state.selected.size} selected")
            }
            OutlinedButton(onClick = vm::openPhone) { Text("Use my phone instead") }
        }
    }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, item ->
            val status = statusFor(item, accounts, session)
            MenuItem(
                text = item.label,
                selected = !manage && item in state.selected,
                trailing = when {
                    manage -> status ?: "Set up"
                    item in state.selected -> "✓"
                    else -> status
                },
                onClick = { if (manage) vm.open(item) else vm.toggle(item) },
                modifier = if (i == 0 && !manage) Modifier.focusRequester(firstFocus) else Modifier,
            )
            Text(item.description, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        }
    }
}

@Composable
private fun PhoneStep(
    state: SetupUiState,
    accounts: Accounts,
    session: Session?,
    firstFocus: FocusRequester,
    vm: SetupViewModel,
    onClose: () -> Unit,
) {
    val manage = state.mode == SetupMode.Manage
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Top) {
        val url = state.qrUrl
        if (url != null) QrImage(url, Modifier.size(250.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "1. Open the camera on your phone",
                "2. Point it at this code and tap the link",
                "3. Follow the steps on your phone",
            ).forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary) }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    state.phoneConnected -> "📱 Phone connected" + (state.phoneActivity?.let { " · $it" } ?: "")
                    else -> "Waiting for your phone… It needs to be on the same Wi-Fi as this TV."
                },
                fontSize = 14.sp,
                color = if (state.phoneConnected) AppColors.Accent else AppColors.TextSecondary,
            )
            val connected = summary(accounts, session)
            if (connected.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(connected.lines().joinToString("\n") { "✓ $it" }, fontSize = 13.sp, color = AppColors.TextSecondary)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (manage) {
            OutlinedButton(onClick = vm::back, modifier = Modifier.focusRequester(firstFocus)) { Text("Back") }
        } else {
            OutlinedButton(onClick = vm::start, modifier = Modifier.focusRequester(firstFocus)) { Text("Use the remote instead") }
            if (summary(accounts, session).isNotEmpty()) Button(onClick = { vm.finish(onClose) }) { Text("Finish") }
        }
    }
}

@Composable
private fun TextStep(qrUrl: String?, fields: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fields()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
        }
        if (qrUrl != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(190.dp)) {
                QrImage(qrUrl, Modifier.size(170.dp))
                Spacer(Modifier.height(8.dp))
                Text("Or scan with your phone to type it there", fontSize = 12.sp, color = AppColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun CodeStep(state: SetupUiState, firstFocus: FocusRequester, vm: SetupViewModel) {
    if (state.plexChoices.isNotEmpty()) {
        state.plexChoices.forEachIndexed { i, choice ->
            MenuItem(
                text = choice.name,
                selected = false,
                trailing = choice.url.substringAfter("://").substringBefore('/'),
                onClick = { vm.pickPlex(choice) },
                modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val url = state.codeUrl?.removePrefix("https://")
            if (state.code != null && url != null) {
                Text("On your phone or computer, go to", color = AppColors.TextSecondary)
                Text(url, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                Text("and enter", color = AppColors.TextSecondary)
                BigCode(state.code)
                Text("Waiting for you to approve…", fontSize = 13.sp, color = AppColors.TextSecondary)
            } else if (state.busy) {
                Text("Getting a code…", color = AppColors.TextSecondary)
            }
            OutlinedButton(onClick = vm::advance, modifier = Modifier.focusRequester(firstFocus)) { Text("Skip") }
        }
        state.codeUrl?.let { if (state.code != null) QrImage(it, Modifier.size(170.dp)) }
    }
}

@Composable
private fun BigCode(code: String) {
    Text(
        code,
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        color = AppColors.Accent,
        modifier = Modifier.background(AppColors.Background, RoundedCornerShape(8.dp)).padding(horizontal = 18.dp, vertical = 6.dp),
    )
}

@Composable
private fun WizardField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    focus: FocusRequester?,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    password: Boolean = false,
    onIme: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { M3Text(label) },
        singleLine = true,
        modifier = (if (focus != null) Modifier.focusRequester(focus) else Modifier).fillMaxWidth(),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onAny = { onIme() }),
    )
}

@Composable
private fun WizardField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    focus: FocusRequester?,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    onIme: () -> Unit,
    password: Boolean = false,
) = WizardField(value, onValueChange, label, focus, keyboardType, imeAction, password, onIme)

private fun statusFor(item: SetupItem, a: Accounts, session: Session?) = statusText(item, a, session)

private fun summary(a: Accounts, session: Session?): String = SetupItem.entries
    .mapNotNull { item -> statusFor(item, a, session)?.let { "${item.label}: $it" } }
    .joinToString("\n")

private fun titleFor(step: SetupStep, mode: SetupMode) = when (step) {
    SetupStep.Phone -> if (mode == SetupMode.Manage) "Set up on your phone" else "Welcome to Viewarr"
    SetupStep.Welcome -> "Welcome"
    SetupStep.Choose -> if (mode == SetupMode.Manage) "Accounts & sources" else "What do you want to set up?"
    SetupStep.Server -> "Find your Dispatcharr server"
    SetupStep.SignIn -> "Sign in to Dispatcharr"
    SetupStep.Tmdb -> "Connect TMDB"
    SetupStep.Trakt -> "Sign in to Trakt"
    SetupStep.Plex -> "Link Plex"
    SetupStep.Stremio -> "Add Stremio addons"
    SetupStep.Jellyfin -> "Connect Jellyfin"
    SetupStep.Emby -> "Connect Emby"
    SetupStep.Vod -> "Provider VOD"
    SetupStep.Done -> "You're all set"
}

private fun bodyFor(state: SetupUiState) = when (state.step) {
    SetupStep.Phone -> "Scan the code to set everything up on your phone: live TV, films and shows, and the accounts that go with them. " +
        "Typing passwords and links there is much easier than with the remote."
    SetupStep.Welcome -> "Let's connect this TV to your services. It only takes a few minutes."
    SetupStep.Choose -> if (state.mode == SetupMode.Manage) "Choose a service to connect, change or check."
    else "Tick everything you use. Movies & Shows need TMDB, so it's added automatically when you pick a film or TV source."
    SetupStep.Server -> "Enter the address you use to open Dispatcharr in a browser, for example 192.168.1.50:9191. " +
        "If you leave out the port, 9191 is tried too."
    SetupStep.SignIn -> "Connected to Dispatcharr ${state.serverVersion} at ${state.serverUrl}. Sign in with your Dispatcharr web login, " +
        "not the XC / Xtream password your IPTV apps use. Easier and recommended for recordings (DVR): set up from your phone " +
        "and paste your API key (Dispatcharr → Users → your user → API Key). Your password isn't stored on this TV."
    SetupStep.Tmdb -> "Your free TMDB API key is at themoviedb.org → Settings → API. Long keys are easier to type on your phone."
    SetupStep.Trakt -> "Trakt keeps your watchlist and history in sync with your other apps."
    SetupStep.Plex -> "Link this TV to your Plex account. Viewarr then plays straight from your Plex server."
    SetupStep.Stremio -> "Paste the manifest link from your addon's configure page (for AIOStreams, the 'Copy manifest URL' button)."
    SetupStep.Jellyfin -> "Sign in with your Jellyfin account, or use Quick Connect from the Jellyfin app."
    SetupStep.Emby -> "Sign in with your Emby account."
    SetupStep.Vod -> "Films and series from your IPTV provider, served through Dispatcharr."
    SetupStep.Done -> "Everything you connected is ready. You can change any of it in Settings → Accounts & sources."
}
