package io.github.petebutty.viewarr.ui.setup

import androidx.compose.foundation.focusGroup

import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
        // The hub places focus itself (on the service you just set up).
        if (state.step != SetupStep.Choose) runCatching { firstFocus.requestFocus() }
    }
    val atRoot = state.step == SetupStep.Choose
    BackHandler(enabled = !atRoot) { vm.back() }
    BackHandler(enabled = mode == SetupMode.Manage && state.step == SetupStep.Choose) { vm.finish(onClose) }

    Row(
        modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(horizontal = 64.dp, vertical = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(0.36f).padding(end = 40.dp)) {
            Text("Viewarr", style = MaterialTheme.typography.titleLarge, color = AppColors.Accent)
            Spacer(Modifier.height(24.dp))
            Text(if (state.step == SetupStep.SignIn && state.xc) "Sign in to your IPTV provider" else titleFor(state.step, mode), style = MaterialTheme.typography.displaySmall, color = AppColors.TextPrimary)
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
                        if (!state.xc && state.useKey) {
                            WizardField(state.fields[SetupViewModel.API_KEY].orEmpty(), { vm.onField(SetupViewModel.API_KEY, it) }, "API key", firstFocus, KeyboardType.Text, ImeAction.Done, vm::submit)
                        } else {
                            WizardField(state.username, vm::onUsername, "Username", firstFocus, KeyboardType.Text, ImeAction.Next) {}
                            WizardField(state.password, vm::onPassword, "Password", null, KeyboardType.Password, ImeAction.Done, vm::submit, password = true)
                        }
                    },
                    actions = {
                        Button(onClick = vm::submit, enabled = !state.busy) { Text(if (state.busy) "Signing in…" else "Sign in") }
                        if (!state.xc) OutlinedButton(onClick = vm::toggleKey, enabled = !state.busy) {
                            Text(if (state.useKey) "Use username & password instead" else "Use API key instead")
                        }
                    },
                )
                SetupStep.Tmdb -> TextStep(
                    qrUrl = "https://www.themoviedb.org/settings/api",
                    qrCaption = "Scan to open TMDB on your phone: sign in, copy your API key, then type it here.",
                    fields = {
                        WizardField(state.fields[TMDB_KEY].orEmpty(), { vm.onField(TMDB_KEY, it) }, "TMDB API key", firstFocus, KeyboardType.Text, ImeAction.Done, vm::submit)
                    },
                    actions = {
                        Button(onClick = vm::submit, enabled = !state.busy) { Text(if (state.busy) "Checking…" else "Save") }
                        OutlinedButton(onClick = vm::advance) { Text("Skip") }
                    },
                )
                SetupStep.Mdblist -> TextStep(
                    qrUrl = state.qrUrl?.let { u -> u.substringBefore('#') + "?go=mdblist#" + u.substringAfter('#') },
                    qrCaption = "Scan to paste your MDBList key from your phone (easier than typing).",
                    fields = {
                        WizardField(state.fields[SetupViewModel.MDBLIST_KEY].orEmpty(), { vm.onField(SetupViewModel.MDBLIST_KEY, it) }, "MDBList API key", firstFocus, KeyboardType.Password, ImeAction.Done, vm::submit)
                    },
                    actions = {
                        Button(onClick = vm::submit, enabled = !state.busy) { Text(if (state.busy) "Checking…" else "Save") }
                        OutlinedButton(onClick = vm::advance) { Text("Skip") }
                    },
                )
                SetupStep.Trakt, SetupStep.Plex -> CodeStep(state, firstFocus, vm)
                SetupStep.Seerr -> TextStep(
                    qrUrl = state.qrUrl?.let { u -> u.substringBefore('#') + "?go=seerr#" + u.substringAfter('#') },
                    qrCaption = "Scan to paste the address and API key from your phone (easier than typing).",
                    fields = {
                        WizardField(state.fields[SetupViewModel.SERVER].orEmpty(), { vm.onField(SetupViewModel.SERVER, it) }, "Seerr address", firstFocus, KeyboardType.Uri, ImeAction.Next, {})
                        WizardField(state.fields[SetupViewModel.API_KEY].orEmpty(), { vm.onField(SetupViewModel.API_KEY, it) }, "Seerr API key", null, KeyboardType.Password, ImeAction.Done, vm::submit)
                    },
                    actions = {
                        Button(onClick = vm::submit, enabled = !state.busy) { Text(if (state.busy) "Checking…" else "Save") }
                        OutlinedButton(onClick = vm::advance) { Text("Skip") }
                    },
                )
                SetupStep.Stremio -> TextStep(
                    qrUrl = state.qrUrl?.let { u -> u.substringBefore('#') + "?go=stremio#" + u.substringAfter('#') },
                    qrCaption = "Scan to paste addon links from your phone (easier than typing).",
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
    val items = SetupItem.entries.filter { it.available && (it != SetupItem.Vod || session != null) }
    val anything = summary(accounts, session).isNotEmpty()
    // The hub: one row per service. OK runs that service's steps and comes back here. Buttons stay on top.
    val itemFocus = remember { HashMap<SetupItem, FocusRequester>() }
    val doneFocus = remember { FocusRequester() }
    // OK on a connected service: change it, or disconnect it (asks twice).
    var options by remember { mutableStateOf<SetupItem?>(null) }
    LaunchedEffect(state.lastItem) {
        // Back from a service: focus returns to it. First visit: Live TV (first run) or the phone button.
        kotlinx.coroutines.android.awaitFrame()
        runCatching { (state.lastItem?.let { itemFocus[it] } ?: if (manage) firstFocus else itemFocus[items.first()])?.requestFocus() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = vm::openPhone, modifier = Modifier.focusRequester(firstFocus)) { Text("Set up on your phone") }
        if (manage) OutlinedButton(onClick = { vm.finish(onClose) }, modifier = Modifier.focusRequester(doneFocus)) { Text("Done") }
        else OutlinedButton(onClick = { vm.finish(onClose) }, enabled = anything, modifier = Modifier.focusRequester(doneFocus)) {
            Text(if (anything) "Start watching" else "Connect something first")
        }
    }
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, item ->
            val status = statusFor(item, accounts, session)
            MenuItem(
                text = item.label,
                selected = false,
                trailing = status ?: "Set up",
                onClick = { if (status != null) options = item else vm.open(item) },
                modifier = Modifier.focusRequester(itemFocus.getOrPut(item) { FocusRequester() })
                    .then(if (i == 0) Modifier.focusProperties { up = firstFocus } else Modifier),
            )
            Text(item.description, fontSize = 12.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(start = 12.dp, bottom = 6.dp))
        }
    }
    options?.let { item ->
        ServiceOptions(
            item = item,
            status = statusFor(item, accounts, session).orEmpty(),
            onChange = { options = null; vm.open(item) },
            onDisconnect = { options = null; vm.disconnect(item) },
            onClose = { options = null; runCatching { itemFocus[item]?.requestFocus() } },
        )
    }
}

/** A connected service's choices. Disconnect needs a second press so it can't happen by accident. */
@Composable
private fun ServiceOptions(item: SetupItem, status: String, onChange: () -> Unit, onDisconnect: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    var confirm by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); runCatching { first.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.width(460.dp).background(AppColors.Panel, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(item.label, fontSize = 20.sp, color = AppColors.TextPrimary)
            Text(status, fontSize = 13.sp, color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 10.dp))
            MenuItem(text = if (item == SetupItem.Stremio) "Add or remove addons" else "Change", selected = false, onClick = onChange, modifier = Modifier.focusRequester(first))
            MenuItem(
                text = if (confirm) "Press OK again to disconnect" else "Disconnect",
                selected = false,
                onClick = { if (confirm) onDisconnect() else confirm = true },
                modifier = Modifier.onFocusChanged { if (!it.isFocused) confirm = false },
            )
            MenuItem(text = "Back", selected = false, onClick = onClose)
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
            OutlinedButton(onClick = vm::back, modifier = Modifier.focusRequester(firstFocus)) { Text("Back: set up with the remote") }
            if (summary(accounts, session).isNotEmpty()) Button(onClick = { vm.finish(onClose) }) { Text("Start watching") }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TextStep(qrUrl: String?, qrCaption: String = "Or scan with your phone to type it there", fields: @Composable () -> Unit, actions: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fields()
            // Moving down onto the buttons always lands on the first (main) one, not whichever is nearest.
            val primary = remember { FocusRequester() }
            Row(Modifier.focusProperties { enter = { primary } }.focusGroup()) {
                Row(Modifier.focusRequester(primary), horizontalArrangement = Arrangement.spacedBy(12.dp)) { actions() }
            }
        }
        if (qrUrl != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(190.dp)) {
                QrImage(qrUrl, Modifier.size(170.dp))
                Spacer(Modifier.height(8.dp))
                Text(qrCaption, fontSize = 12.sp, color = AppColors.TextSecondary)
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
            } else if (state.codeUrl != null) {
                Text("Scan the code with your phone and sign in to Plex.", fontSize = 18.sp, color = AppColors.TextPrimary)
                Text("This TV links itself as soon as you've signed in.", color = AppColors.TextSecondary)
                Text("Waiting for you to sign in…", fontSize = 13.sp, color = AppColors.TextSecondary)
            } else if (state.busy) {
                Text("Getting a code…", color = AppColors.TextSecondary)
            }
            OutlinedButton(onClick = vm::advance, modifier = Modifier.focusRequester(firstFocus)) { Text("Skip") }
        }
        state.codeUrl?.let { QrImage(it, Modifier.size(200.dp)) }
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
    // TV text box rules: the keyboard opens only when you press OK on the box (not just by landing on it), and
    // Up / Down always leave the box, so the buttons below can be reached with the remote.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    val kbScope = androidx.compose.runtime.rememberCoroutineScope()
    // Keys, codes and addresses: a "password"-type keyboard has no suggestions or autocorrect (the TV keyboard
    // ignores the autocorrect flag on ordinary text). Shown as plain text unless it really is a password.
    val noSuggestions = keyboardType == KeyboardType.Text || keyboardType == KeyboardType.Uri
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { M3Text(label) },
        singleLine = true,
        // Read-only until OK is pressed on it: landing on a box (e.g. moving Down from the one above) must not
        // bring the keyboard up, or the next OK types a letter instead of opening it.
        readOnly = !editing,
        modifier = (if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .fillMaxWidth()
            .onFocusChanged { f ->
                if (f.isFocused && !editing) keyboard?.hide()
                if (!f.isFocused) editing = false
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> { keyboard?.hide(); editing = false; focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down); true }
                    Key.DirectionUp -> { keyboard?.hide(); editing = false; focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (!editing) {
                        if (e.type == KeyEventType.KeyDown) { editing = true; kbScope.launch { kotlinx.coroutines.android.awaitFrame(); keyboard?.show() } }
                        true
                    } else false
                    else -> false
                }
            },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (noSuggestions && label.contains("key", ignoreCase = true)) KeyboardType.Password else keyboardType,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onAny = { editing = false; keyboard?.hide(); onIme() }),
    )
    // Landing on the box (e.g. when a step opens) must not pop the keyboard up over the screen.
    LaunchedEffect(Unit) { kotlinx.coroutines.android.awaitFrame(); if (!editing) keyboard?.hide() }
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
    SetupStep.Choose -> if (mode == SetupMode.Manage) "Accounts & sources" else "Set up Viewarr"
    SetupStep.Server -> "Find your live TV server"
    SetupStep.SignIn -> "Sign in to Dispatcharr"
    SetupStep.Tmdb -> "Connect TMDB"
    SetupStep.Trakt -> "Sign in to Trakt"
    SetupStep.Plex -> "Link Plex"
    SetupStep.Stremio -> "Add Stremio addons"
    SetupStep.Jellyfin -> "Connect Jellyfin"
    SetupStep.Emby -> "Connect Emby"
    SetupStep.Vod -> "Provider VOD"
    SetupStep.Seerr -> "Connect Seerr"
    SetupStep.Mdblist -> "MDBList ratings"
    SetupStep.Done -> "You're all set"
}

private fun bodyFor(state: SetupUiState) = when (state.step) {
    SetupStep.Phone -> "Scan the code to set everything up on your phone: live TV, films and shows, and the accounts that go with them. " +
        "Typing passwords and links there is much easier than with the remote."
    SetupStep.Welcome -> "Let's connect this TV to your services. It only takes a few minutes."
    SetupStep.Choose -> if (state.mode == SetupMode.Manage) "Choose a service to connect, change or check."
    else if (true) "Pick each service you use and connect it, one at a time. Live TV finds your Dispatcharr server by itself. " +
        "Or set everything up from your phone. When you're done, choose Start watching."
    else "Tick everything you use. Movies & Shows need TMDB, so it's added automatically when you pick a film or TV source."
    SetupStep.Server -> "Dispatcharr: the address you open it at in a browser, for example 192.168.1.50:9191 (9191 is tried if you leave out the port). " +
        "IPTV provider: the server address from your provider's welcome email (Xtream Codes / XC login)."
    SetupStep.SignIn -> if (state.xc) "That isn't a Dispatcharr server, so Viewarr will sign in to it as an IPTV provider (Xtream Codes). " +
        "Use the username and password from your provider. You'll get channels, the guide, catch-up and the provider's films and series; " +
        "recordings need Dispatcharr." else if (state.useKey) "Found Dispatcharr ${state.serverVersion} at ${state.serverUrl}. Enter your API key: in Dispatcharr, " +
        "open Users, edit your user and copy the API Key (don't press Regenerate). Long keys are easier to paste from the phone setup."
        else "Found Dispatcharr ${state.serverVersion} at ${state.serverUrl}. Use your Dispatcharr website login, not the XC password your IPTV apps use. " +
        "The password isn't stored on this TV."
    SetupStep.Tmdb -> "Your free TMDB API key is at themoviedb.org → Settings → API. Long keys are easier to type on your phone."
    SetupStep.Trakt -> "Trakt keeps your watchlist and history in sync with your other apps."
    SetupStep.Plex -> "Link this TV to your Plex account. Viewarr then plays straight from your Plex server."
    SetupStep.Stremio -> "Paste the manifest link from your addon's configure page (for AIOStreams, the 'Copy manifest URL' button)."
    SetupStep.Jellyfin -> "Sign in with your Jellyfin account, or use Quick Connect from the Jellyfin app."
    SetupStep.Emby -> "Sign in with your Emby account."
    SetupStep.Vod -> "Films and series from your IPTV provider, served through Dispatcharr."
    SetupStep.Mdblist -> "Optional: a free MDBList key adds IMDb, Rotten Tomatoes, Metacritic and Letterboxd ratings to every film and show. " +
        "Sign in at mdblist.com, open Preferences → API access, and copy your API key."
    SetupStep.Seerr -> "Your Seerr address (for example 192.168.1.50:5055) and its API key from Seerr → Settings → General. The key is long: the phone setup is easier."
    SetupStep.Done -> "Everything you connected is ready. You can change any of it in Settings → Accounts & sources."
}
