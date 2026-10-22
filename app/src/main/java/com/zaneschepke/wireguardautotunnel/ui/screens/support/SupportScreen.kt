package com.zaneschepke.wireguardautotunnel.ui.screens.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Reviews
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import com.zaneschepke.wireguardautotunnel.BuildConfig
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.domain.model.UpdateDownloadState
import com.zaneschepke.wireguardautotunnel.domain.sideeffect.GlobalSideEffect
import com.zaneschepke.wireguardautotunnel.ui.LocalIsAndroidTV
import com.zaneschepke.wireguardautotunnel.ui.LocalNavController
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.functions.rememberClipboardHelper
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.navigation.Route
import com.zaneschepke.wireguardautotunnel.ui.screens.support.components.PermissionDialog
import com.zaneschepke.wireguardautotunnel.ui.screens.support.components.UpdateDialog
import com.zaneschepke.wireguardautotunnel.ui.screens.support.components.UpdateDownloadProgress
import com.zaneschepke.wireguardautotunnel.ui.screens.support.components.rememberUpdateDownloadPresentation
import com.zaneschepke.wireguardautotunnel.util.Constants
import com.zaneschepke.wireguardautotunnel.util.StringValue
import com.zaneschepke.wireguardautotunnel.util.extensions.canInstallPackages
import com.zaneschepke.wireguardautotunnel.util.extensions.launchPlayStoreListing
import com.zaneschepke.wireguardautotunnel.util.extensions.launchPlayStoreReview
import com.zaneschepke.wireguardautotunnel.util.extensions.openWebUrl
import com.zaneschepke.wireguardautotunnel.viewmodel.SharedAppViewModel
import com.zaneschepke.wireguardautotunnel.viewmodel.SupportViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.viewmodel.koinActivityViewModel
import org.orbitmvi.orbit.compose.collectAsState

@Composable
fun SupportScreen(
    viewModel: SupportViewModel = koinViewModel(),
    sharedViewModel: SharedAppViewModel = koinActivityViewModel(),
) {
    val context = LocalContext.current
    val navController = LocalNavController.current
    val isTv = LocalIsAndroidTV.current
    val scope = rememberCoroutineScope()

    val supportState by viewModel.collectAsState()

    val clipboardManager = rememberClipboardHelper()

    var showPermissionDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        sharedViewModel.supportAutoUpdateRequests.collect { startDownload ->
            if (BuildConfig.DEBUG) return@collect
            if (BuildConfig.FLAVOR != Constants.STANDALONE_FLAVOR) return@collect
            if (startDownload && !context.canInstallPackages()) {
                viewModel.checkForStandaloneUpdate(startDownloadIfAvailable = false)
                showPermissionDialog = true
            } else {
                viewModel.checkForStandaloneUpdate(startDownloadIfAvailable = startDownload)
            }
        }
    }

    val sourceUrl = stringResource(R.string.github_url)
    val privacyPolicyUrl = stringResource(R.string.privacy_policy_url)
    val playStoreUrl = "https://play.google.com/store/apps/details?id=${context.packageName}"
    val playReviewsUrl =
        "https://play.google.com/store/apps/details?id=${context.packageName}&showAllReviews=true"

    val version = remember {
        "v${BuildConfig.VERSION_NAME +
                if(BuildConfig.DEBUG) "-debug" else "" }"
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (isTv) {
            focusRequester.requestFocus()
        }
    }

    if (supportState.appUpdate != null) {
        UpdateDialog(
            viewModel = viewModel,
            context = context,
            onPermissionNeeded = { showPermissionDialog = true },
        )
    }

    if (showPermissionDialog) {
        PermissionDialog(context = context, onDismiss = { showPermissionDialog = false })
    }

    fun openWebUrl(url: String) {
        context.openWebUrl(url).onFailure {
            scope.launch {
                viewModel.postSideEffect(
                    GlobalSideEffect.Snackbar(
                        StringValue.StringResource(R.string.no_browser_detected),
                        ToastType.Error,
                    )
                )
            }
        }
    }

    val scrollState = rememberScrollState()

    // The update row is the last one, so this brings it into view
    LaunchedEffect(Unit) {
        sharedViewModel.showUpdateStatusRequested.collect { requested ->
            if (!requested) return@collect
            val bottom = snapshotFlow { scrollState.maxValue }.first { it != Int.MAX_VALUE }
            scrollState.animateScrollTo(bottom)
            sharedViewModel.consumeShowUpdateStatus()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
    ) {
        GroupLabel(
            stringResource(R.string.thank_you),
            modifier = Modifier.padding(horizontal = 16.dp),
            MaterialTheme.colorScheme.onSurface,
        )
        Column {
            GroupLabel(stringResource(R.string.resources), Modifier.padding(horizontal = 16.dp))
            SurfaceRow(
                leading = {
                    Icon(
                        ImageVector.vectorResource(R.drawable.github),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
                title = stringResource(R.string.source_code),
                trailing = { Icon(Icons.AutoMirrored.Outlined.Launch, null) },
                onClick = { openWebUrl(sourceUrl) },
                modifier = Modifier.focusRequester(focusRequester),
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Balance, contentDescription = null) },
                title = stringResource(R.string.licenses),
                onClick = { navController.push(Route.License) },
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Policy, contentDescription = null) },
                title = stringResource(R.string.privacy_policy),
                trailing = { Icon(Icons.AutoMirrored.Outlined.Launch, null) },
                onClick = { openWebUrl(privacyPolicyUrl) },
            )
            if (BuildConfig.FLAVOR == Constants.GOOGLE_PLAY_FLAVOR) {
                SurfaceRow(
                    leading = { Icon(Icons.Outlined.Reviews, contentDescription = null) },
                    title = stringResource(R.string.review),
                    trailing = { Icon(Icons.AutoMirrored.Outlined.Launch, null) },
                    onClick = {
                        context.launchPlayStoreReview().onFailure { openWebUrl(playReviewsUrl) }
                    },
                )
            }
        }
        Column {
            GroupLabel(
                stringResource(R.string.other),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Memory, contentDescription = null) },
                title = stringResource(R.string.about),
                description = {
                    Column {
                        DescriptionText(stringResource(R.string.version_template, version))
                        DescriptionText(
                            stringResource(R.string.flavor_template, BuildConfig.FLAVOR)
                        )
                    }
                },
                onClick = { clipboardManager.copy(version) },
            )
            val download = supportState.download
            val presentation = rememberUpdateDownloadPresentation(download)
            val downloadReady = download is UpdateDownloadState.Completed
            val downloadFailed = download is UpdateDownloadState.Failed
            SurfaceRow(
                leading = { Icon(Icons.Outlined.InstallMobile, contentDescription = null) },
                title =
                    stringResource(
                        when {
                            presentation.showProgress -> R.string.downloading_update
                            downloadReady -> R.string.update_ready_to_install
                            downloadFailed -> R.string.update_download_failed_title
                            else -> R.string.check_for_update
                        }
                    ),
                description =
                    when {
                        presentation.showProgress -> {
                            { UpdateDownloadProgress(presentation) }
                        }
                        downloadReady -> {
                            { DescriptionText(stringResource(R.string.update_tap_to_install)) }
                        }
                        downloadFailed -> {
                            { DescriptionText(stringResource(R.string.update_tap_to_retry)) }
                        }
                        else -> null
                    },
                onClick = {
                    if (BuildConfig.DEBUG) {
                        scope.launch {
                            viewModel.postSideEffect(
                                GlobalSideEffect.Snackbar(
                                    StringValue.StringResource(R.string.update_check_unsupported),
                                    ToastType.Warning,
                                )
                            )
                        }
                        return@SurfaceRow
                    }
                    when (BuildConfig.FLAVOR) {
                        Constants.GOOGLE_PLAY_FLAVOR ->
                            context.launchPlayStoreListing().onFailure { openWebUrl(playStoreUrl) }
                        Constants.FDROID_FLAVOR -> openWebUrl("$sourceUrl/releases")
                        // A running or finished download reopens the dialog, no new check
                        else ->
                            if (download is UpdateDownloadState.Downloading || downloadReady) {
                                viewModel.showActiveDownload()
                            } else {
                                viewModel.checkForStandaloneUpdate()
                            }
                    }
                },
                trailing =
                    if (BuildConfig.FLAVOR == Constants.STANDALONE_FLAVOR) null
                    else {
                        { Icon(Icons.AutoMirrored.Outlined.Launch, null) }
                    },
            )
        }
    }
}
