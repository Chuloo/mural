package chat.mural.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.mural.MuralViewModel
import chat.mural.R
import chat.mural.core.CloudAction
import chat.mural.AccountViewModel
import chat.mural.MinutePurchaseViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuralApp(
    vm: MuralViewModel,
    microphoneMessage: String?,
    onRequestMicrophone: () -> Unit,
    onOpenAppSettings: (() -> Unit)?,
    onExport: () -> Unit,
    onImport: () -> Unit,
    account: AccountViewModel? = null,
    onGoogleSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onDeleteAccount: () -> Unit = {},
    accountTransitionBusy: Boolean = false,
    purchases: MinutePurchaseViewModel? = null,
    onBuyMinutes: (String) -> Unit = {},
) {
    val reportState by vm.reportState.collectAsStateWithLifecycle()
    val prefs = vm.archive.preferences
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showConsent by rememberSaveable { mutableStateOf(false) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showMinutes by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = tab != 0 && !showSettings && !showAccount && !showMinutes) { tab = 0 }
    fun perform(action: CloudAction) {
        when (action) {
            CloudAction.StartVoice -> if (!vm.needsMinuteAccess()) onRequestMicrophone()
            is CloudAction.SendTyped -> vm.sendTyped(action.text)
            is CloudAction.Lookup -> vm.lookup(action.word, action.sentence)
            is CloudAction.CurrentTopic -> vm.currentTopic(action.query)
            CloudAction.Help -> vm.help()
        }
    }
    fun withConsent(action: CloudAction) {
        if (vm.archive.preferences.aiConsentVersion == AI_CONSENT_VERSION) perform(action)
        else {
            vm.pendingCloudAction = action
            showConsent = true
        }
    }

    MuralTheme {
        Surface(Modifier.fillMaxSize(), color = MuralColors.Cream) {
            if (vm.loadingHistory) {
                Box(Modifier.fillMaxSize().testTag("history-loading"), contentAlignment = Alignment.Center) {
                    SoftAnimatedBackground(Modifier.fillMaxSize())
                    MuralOrb(modifier = Modifier.size(150.dp))
                }
            } else if (!prefs.hasOnboarded) {
                OnboardingScreen(prefs.learningLanguageID, prefs.meaningLanguage) { language, meaning ->
                    vm.selectLanguage(language)
                    vm.updatePreferences(
                        vm.archive.preferences.copy(
                            learningLanguageID = language,
                            meaningLanguage = meaning,
                            meaningVisible = true,
                            hasOnboarded = true,
                            aiConsentVersion = null,
                        ),
                    )
                    vm.pendingCloudAction = null
                    showConsent = true
                }
            } else {
                Scaffold(
                    containerColor = MuralColors.Cream,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        Row(
                            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Brand()
                            Spacer(Modifier.weight(1f))
                            SoftRoundButton(MuralSymbol.Settings, stringResource(R.string.settings_tab_title),
                                onClick = { showSettings = true }, modifier = Modifier.testTag("tab-settings"), diameter = 44.dp)
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                    Column(Modifier.fillMaxSize().then(if (tab == 0) Modifier.padding(bottom =
                        90.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()) else Modifier)) {
                        when (tab) {
                            0 -> TalkScreen(
                                vm = vm,
                                microphoneMessage = microphoneMessage,
                                onMicrophone = { withConsent(CloudAction.StartVoice) },
                                onOpenAppSettings = onOpenAppSettings,
                                onSendTyped = { text -> withConsent(CloudAction.SendTyped(text)) },
                                onLookup = { word, sentence -> withConsent(CloudAction.Lookup(word, sentence)) },
                                onHelp = { withConsent(CloudAction.Help) },
                            )
                            1 -> TopicsScreen(
                                vm,
                                onChoose = { tab = 0 },
                                onCurrentTopic = { query -> withConsent(CloudAction.CurrentTopic(query)) },
                            )
                            2 -> WordsScreen(vm)
                        }
                    }
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(108.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, MuralColors.Cream.copy(alpha = .92f)))))
                    Box(Modifier.align(Alignment.BottomCenter)) { FloatingNavigation(tab) { tab = it } }
                    }
                }
            }

            if (showSettings) {
                ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = MuralColors.Cream,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    Column(Modifier.fillMaxHeight(.94f)) {
                        SettingsScreen(vm, onExport, onImport, onReviewConsent = {
                            vm.pendingCloudAction = null; showConsent = true
                        }, onAccount = if (account?.configuration != null) ({
                            account.refresh(); showAccount = true
                        }) else null, onDismiss = { showSettings = false })
                    }
                }
            }

            if (showConsent) AIConsentDialog(
                onAgree = {
                    vm.updatePreferences(vm.archive.preferences.copy(aiConsentVersion = AI_CONSENT_VERSION))
                    showConsent = false
                    val action = vm.pendingCloudAction
                    vm.pendingCloudAction = null
                    action?.let(::perform)
                },
                onDecline = {
                    showConsent = false
                    vm.pendingCloudAction = null
                },
            )

            if (vm.showMinuteAccess && !showAccount && !showSettings && !showMinutes) {
                val memberState = account?.state?.collectAsStateWithLifecycle()?.value
                GuestMinutesSheet(vm.guestState, memberState?.signedIn == true,
                    memberRemaining = memberState?.minutes?.availableMilliseconds,
                    busy = accountTransitionBusy || memberState?.busy == true || vm.hostedReadiness.checking,
                    ready = vm.hostedReadiness.ready, onContinue = {
                        vm.dismissMinuteAccess(); onRequestMicrophone()
                    }, onSignIn = if (account?.configuration != null) ({
                        vm.dismissMinuteAccess(); showAccount = true; onGoogleSignIn()
                    }) else null,
                    onBuy = if (purchases?.enabled == true) ({
                        vm.dismissMinuteAccess(); purchases.refresh(); showMinutes = true
                    }) else null,
                    onRetry = vm::refreshHostedReadiness,
                    onSettings = { vm.dismissMinuteAccess(); showSettings = true }, onDismiss = vm::dismissMinuteAccess)
            }

            if (showAccount && !showMinutes && account?.configuration != null) {
                val accountState by account.state.collectAsStateWithLifecycle()
                AccountSheet(accountState, onDismiss = { showAccount = false }, onSignIn = onGoogleSignIn,
                    onSignOut = onSignOut, onDelete = onDeleteAccount, onRefresh = account::refresh,
                    transitionBusy = accountTransitionBusy, provider = vm.conversationProvider,
                    hostedAvailable = vm.hostedReadiness.enabled, conversationRunning = vm.isRunning,
                    onSelectProvider = vm::selectConversationProvider,
                    onBuyMinutes = if (purchases?.enabled == true) ({ purchases.refresh(); showMinutes = true }) else null,
                    guestMinutes = vm.guestState.takeIf { it.status == chat.mural.core.GuestMinuteStatus.READY }?.remainingMilliseconds,
                    memberAlreadyClaimedTrial = vm.guestState.status == chat.mural.core.GuestMinuteStatus.MEMBER_TRIAL_USED)
            }
            if (showMinutes && purchases?.enabled == true && account != null) {
                val purchaseState by purchases.state.collectAsStateWithLifecycle()
                val accountState by account.state.collectAsStateWithLifecycle()
                MinutePurchaseSheet(purchaseState, accountState.signedIn, onBuyMinutes,
                    onSignIn = onGoogleSignIn, onRefresh = purchases::refresh,
                    onDismiss = { showMinutes = false; account.refresh() },
                    accountBusy = accountTransitionBusy || accountState.busy)
            }

            reportState.selection?.let { selected ->
                androidx.compose.runtime.key(reportState.generation) {
                    ReportConversationSheet(selected.excerpt, selected.languageID, reportState.available,
                        reportState.delivery, vm::submitReport, vm::dismissReport,
                        java.util.Locale.getDefault().language)
                }
            }

            vm.error?.let { message ->
                AlertDialog(
                    onDismissRequest = vm::dismissError,
                    title = { Text(stringResource(R.string.error_dialog_title)) },
                    text = { Text(message) },
                    confirmButton = { MuralTextButton(onClick = vm::dismissError) { Text(stringResource(R.string.common_ok)) } },
                    dismissButton = if (vm.errorNeedsKeySetup) ({
                        MuralTextButton(onClick = { vm.dismissError(); showSettings = true }) { Text(stringResource(R.string.error_go_to_settings)) }
                    }) else if (vm.errorNeedsAccountSignIn && account?.configuration != null) ({
                        MuralTextButton(onClick = { vm.dismissError(); account.refresh(); showAccount = true }) {
                            Text(stringResource(R.string.account_title))
                        }
                    }) else null,
                )
            }
        }
    }
}
