package app.anothermorsetrainer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anothermorsetrainer.morsekit.AccountProfile
import app.anothermorsetrainer.morsekit.SyncTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Settings › Account & Sync: the optional account (accounts Worker; the sync
 * engine is SyncEngine, driven by SyncCoordinator). Three sections, like the
 * Leaderboard & Buddy pair before it: Account (sign in, or the profile, Sync
 * now and Sign out), Devices and Delete account, the last two only while
 * signed in. Nothing that works without an account needs one.
 */

/** The destructive red the Reset row uses. */
private val Destructive = Color(0xFFF2788F)

/** "Last synced" / "last seen" words for [thenMs]. */
@Composable
private fun agoText(thenMs: Long): String {
    // Re-read every minute so "just now" ages while the screen is open.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(thenMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    val (span, n) = SyncTime.ago(now, thenMs)
    val count = n.toInt()
    return when (span) {
        SyncTime.Span.JUST_NOW -> stringResource(R.string.account_just_now)
        SyncTime.Span.MINUTES -> pluralStringResource(R.plurals.account_minutes_ago, count, count)
        SyncTime.Span.HOURS -> pluralStringResource(R.plurals.account_hours_ago, count, count)
        SyncTime.Span.DAYS -> pluralStringResource(R.plurals.account_days_ago, count, count)
    }
}

/** Settings › Account & Sync › Account. */
@Composable
internal fun AccountSection() {
    val account by SyncCoordinator.state.collectAsState()
    SectionHeader(stringResource(R.string.account_header))
    if (account.signedOutBanner && !account.isSignedIn) SignedOutBanner()
    if (account.isSignedIn) SignedInAccount() else SignInForm()
}

/** "You were signed out." until the user signs in again or dismisses it. */
@Composable
private fun SignedOutBanner() {
    val dismissLabel = stringResource(R.string.account_dismiss_banner)
    SettingsGroup {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Brand.warning, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.account_signed_out_banner),
                color = Brand.textPrimary,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { SyncCoordinator.dismissBanner() }) {
                Icon(Icons.Filled.Close, contentDescription = dismissLabel, tint = Brand.textSecondary)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** Signed out: what an account is for, the e-mail field and Send link; then the wait for the link. */
@Composable
private fun SignInForm() {
    val phase by SyncCoordinator.signIn.collectAsState()
    var email by remember { mutableStateOf("") }
    SettingsGroup {
        Text(
            stringResource(R.string.account_intro),
            color = Brand.textPrimary,
            fontSize = 13.sp,
            modifier = Modifier.padding(16.dp)
        )
        GroupDivider()
        when (val p = phase) {
            is SyncCoordinator.SignInPhase.Waiting -> {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = Brand.teal, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.account_check_email), color = Brand.textPrimary, fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.account_check_email_to, p.email), color = Brand.textSecondary, fontSize = 12.sp)
                    }
                }
                GroupDivider()
                LinkRow(stringResource(R.string.common_cancel)) { SyncCoordinator.cancelSignIn() }
            }
            else -> {
                val sending = p == SyncCoordinator.SignInPhase.Sending
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    singleLine = true,
                    enabled = !sending,
                    label = { Text(stringResource(R.string.account_email)) },
                    placeholder = { Text(stringResource(R.string.account_email_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (!sending) SyncCoordinator.startSignIn(email) }),
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                )
                val line = when (p) {
                    SyncCoordinator.SignInPhase.Expired -> stringResource(R.string.account_link_expired)
                    is SyncCoordinator.SignInPhase.Error -> p.message
                    else -> null
                }
                if (line != null) {
                    Text(line, color = Destructive, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                }
                GroupDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !sending && email.isNotBlank()) { SyncCoordinator.startSignIn(email) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(if (sending) R.string.account_sending else R.string.account_send_link),
                        color = if (email.isNotBlank() && !sending) Brand.teal else Brand.textSecondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
    SectionFooter(stringResource(R.string.account_footer))
}

/** Signed in: the e-mail, the editable profile, Last synced, Sync now and Sign out. */
@Composable
private fun SignedInAccount() {
    val account by SyncCoordinator.state.collectAsState()
    val syncing by SyncCoordinator.syncing.collectAsState()
    val scope = rememberCoroutineScope()
    var callsign by remember(account.accountId) { mutableStateOf(account.callsign.orEmpty()) }
    var name by remember(account.accountId) { mutableStateOf(account.displayName.orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    var profileLine by remember { mutableStateOf<String?>(null) }
    var signingOut by remember { mutableStateOf(false) }
    val callsignOk = AccountProfile.isValidCallsign(callsign)
    val nameOk = AccountProfile.isValidDisplayName(name)
    val changed = AccountProfile.normalizeCallsign(callsign) != account.callsign.orEmpty() ||
        AccountProfile.normalizeDisplayName(name) != account.displayName.orEmpty()
    val savedText = stringResource(R.string.account_saved)

    SettingsGroup {
        Text(
            account.email?.let { stringResource(R.string.account_signed_in_as, it) } ?: stringResource(R.string.account_signed_in),
            color = Brand.textPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(16.dp)
        )
        GroupDivider()
        OutlinedTextField(
            value = callsign,
            onValueChange = { callsign = it.uppercase(); profileLine = null },
            singleLine = true,
            label = { Text(stringResource(R.string.account_callsign)) },
            placeholder = { Text(stringResource(R.string.account_callsign_hint)) },
            isError = !callsignOk,
            supportingText = if (callsignOk) null else { { Text(stringResource(R.string.account_callsign_invalid)) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp)
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; profileLine = null },
            singleLine = true,
            label = { Text(stringResource(R.string.account_display_name)) },
            placeholder = { Text(stringResource(R.string.account_display_name_hint)) },
            isError = !nameOk,
            supportingText = if (nameOk) null else { { Text(stringResource(R.string.account_display_name_invalid)) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        )
        profileLine?.let {
            Text(it, color = Brand.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
        val canSave = changed && callsignOk && nameOk && !saving
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = canSave) {
                    saving = true
                    scope.launch {
                        val c = AccountProfile.normalizeCallsign(callsign).ifEmpty { null }
                        val n = AccountProfile.normalizeDisplayName(name).ifEmpty { null }
                        profileLine = when (val r = SyncCoordinator.updateProfile(c, n)) {
                            is AccountResult.Ok -> {
                                callsign = r.value.callsign.orEmpty()
                                name = r.value.displayName.orEmpty()
                                savedText
                            }
                            is AccountResult.Failed ->
                                AppStrings.get(R.string.account_save_failed, SyncCoordinator.failureLine(r.status, r.message))
                            AccountResult.SignedOut -> null
                        }
                        saving = false
                    }
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(if (saving) R.string.account_saving else R.string.account_save_profile),
                color = if (canSave) Brand.teal else Brand.textSecondary,
                fontWeight = FontWeight.Medium
            )
        }
        GroupDivider()
        Text(
            stringResource(
                R.string.account_last_synced,
                account.lastSyncedAt?.let { agoText(it) } ?: stringResource(R.string.account_not_yet)
            ),
            color = Brand.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !syncing) { SyncCoordinator.onForeground() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (syncing) CircularProgressIndicator(color = Brand.teal, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Text(
                stringResource(if (syncing) R.string.account_syncing else R.string.account_sync_now),
                color = if (syncing) Brand.textSecondary else Brand.teal,
                fontWeight = FontWeight.Medium
            )
        }
        GroupDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !signingOut) {
                    signingOut = true
                    scope.launch {
                        SyncCoordinator.signOut()
                        signingOut = false
                    }
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(if (signingOut) R.string.account_signing_out else R.string.account_sign_out),
                color = Destructive,
                fontWeight = FontWeight.Medium
            )
        }
    }
    SectionFooter(stringResource(R.string.account_footer))
}

/** Settings › Account & Sync › Devices: every signed-in device, each but this one with its own Sign out. */
@Composable
internal fun AccountDevicesSection() {
    val account by SyncCoordinator.state.collectAsState()
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<AccountDevice>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        when (val r = SyncCoordinator.devices()) {
            is AccountResult.Ok -> { devices = r.value; error = null }
            is AccountResult.Failed ->
                error = AppStrings.get(R.string.account_devices_failed, SyncCoordinator.failureLine(r.status, r.message))
            AccountResult.SignedOut -> devices = emptyList()
        }
    }
    LaunchedEffect(account.accountId) { if (account.isSignedIn) reload() }

    SectionHeader(stringResource(R.string.account_devices))
    SettingsGroup {
        val list = devices
        when {
            list == null && error == null -> Text(
                stringResource(R.string.account_devices_loading),
                color = Brand.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp)
            )
            else -> list.orEmpty().forEachIndexed { i, d ->
                if (i > 0) GroupDivider()
                DeviceRow(d, busy = busyId == d.id) {
                    busyId = d.id
                    scope.launch {
                        when (val r = SyncCoordinator.revokeDevice(d.id)) {
                            is AccountResult.Ok -> reload()
                            is AccountResult.Failed -> error = AppStrings.get(
                                R.string.account_device_sign_out_failed, SyncCoordinator.failureLine(r.status, r.message)
                            )
                            AccountResult.SignedOut -> {}
                        }
                        busyId = null
                    }
                }
            }
        }
        error?.let {
            if (!devices.isNullOrEmpty()) GroupDivider()
            Text(it, color = Destructive, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
    }
    SectionFooter(stringResource(R.string.account_devices_footer))
}

@Composable
private fun DeviceRow(device: AccountDevice, busy: Boolean, onSignOut: () -> Unit) {
    val title = device.deviceName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.account_device_unnamed)
    val detail = buildList {
        device.platform?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (device.client.isNotBlank() && !device.client.startsWith("amt-")) add(device.client)
        if (device.current) add(stringResource(R.string.account_this_device))
        else if (device.lastSeenAt > 0) add(stringResource(R.string.account_last_seen, agoText(device.lastSeenAt)))
    }.joinToString(" · ")
    val signOutLabel = stringResource(R.string.account_device_sign_out_named, title)
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Brand.textPrimary, fontWeight = FontWeight.Medium)
            if (detail.isNotEmpty()) Text(detail, color = Brand.textSecondary, fontSize = 12.sp)
        }
        if (!device.current) {
            TextButton(
                onClick = onSignOut,
                enabled = !busy,
                modifier = Modifier.semantics { contentDescription = signOutLabel }
            ) {
                Text(stringResource(R.string.account_device_sign_out), color = if (busy) Brand.textSecondary else Destructive)
            }
        }
    }
}

/** Settings › Account & Sync › Delete account, behind a confirmation. */
@Composable
internal fun AccountDeleteSection() {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SectionHeader(stringResource(R.string.account_delete))
    SettingsGroup {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = !deleting) { confirm = true }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(if (deleting) R.string.account_deleting else R.string.account_delete),
                color = Destructive,
                fontWeight = FontWeight.Medium
            )
        }
        error?.let {
            GroupDivider()
            Text(it, color = Destructive, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
    }
    SectionFooter(stringResource(R.string.account_delete_footer))

    if (confirm) {
        // Desktop: Escape closes the dialog (Android's Back), not the screen.
        BackHandler { confirm = false }
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Brand.navyElevated,
            title = { Text(stringResource(R.string.account_delete_confirm_title), color = Brand.textPrimary) },
            text = { Text(stringResource(R.string.account_delete_confirm_body), color = Brand.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    deleting = true
                    error = null
                    scope.launch {
                        val r = SyncCoordinator.deleteAccount()
                        if (r is AccountResult.Failed) {
                            error = AppStrings.get(R.string.account_delete_failed, SyncCoordinator.failureLine(r.status, r.message))
                        }
                        deleting = false
                    }
                }) { Text(stringResource(R.string.account_delete), color = Destructive, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.common_cancel), color = Brand.teal) }
            }
        )
    }
}
