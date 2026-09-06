package app.grapheneos.info

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.grapheneos.info.ui.releases.ReleasesScreen
import app.grapheneos.info.ui.releases.ReleasesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class InfoAppScreens(@StringRes val title: Int) {
    Releases(title = R.string.releases)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoApp() {
    val releasesViewModel: ReleasesViewModel = viewModel()
    val releasesUiState = releasesViewModel.uiState.collectAsState()

    val changelogLazyListState = rememberLazyListState()
    val changelogLazyListStateScope = rememberCoroutineScope()

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarCoroutine = rememberCoroutineScope()

    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val localUriHandler = LocalUriHandler.current
    val layoutDirection = LocalLayoutDirection.current

    val openUriIllegalArguementExceptionSnackbarError =
        stringResource(R.string.browser_link_illegal_argument_exception_snackbar_error)

    Scaffold(
        modifier = Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.lineageos_logo),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .height(22.dp)
                                .width(55.dp)
                        )
                        Text(
                            text = stringResource(id = R.string.app_name),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            try {
                                val device = android.os.Build.DEVICE?.lowercase() ?: "akita"
                                localUriHandler.openUri("https://github.com/rhythmcreative/lineageos-$device-ota/releases")
                            } catch (e: IllegalArgumentException) {
                                snackbarCoroutine.launch {
                                    snackbarHostState.showSnackbar(
                                        openUriIllegalArguementExceptionSnackbarError
                                    )
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.releases_top_bar_info_button_content_description)
                        )
                    }
                },
                scrollBehavior = topAppBarScrollBehavior
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState)
        },
    ) { innerPadding ->
        ReleasesScreen(
            modifier = Modifier
                .padding(top = innerPadding.calculateTopPadding())
                .consumeWindowInsets(innerPadding),
            entries = releasesUiState.value.entries.toSortedMap().toList().asReversed(),
            updateChangelog = { useCaches, onFinishedUpdating ->
                releasesViewModel.updateChangelog(
                    useCaches = useCaches,
                    showSnackbarError = {
                        snackbarHostState.showSnackbar(it)
                    },
                    scrollChangelogLazyListTo = {
                        changelogLazyListStateScope.launch {
                            withContext(Dispatchers.Main) {
                                changelogLazyListState.animateScrollToItem(it)
                            }
                        }
                    },
                    onFinishedUpdating = onFinishedUpdating,
                )
            },
            changelogLazyListState = changelogLazyListState,
            additionalContentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection),
                top = 0.dp,
                end = innerPadding.calculateEndPadding(layoutDirection),
                bottom = innerPadding.calculateBottomPadding()
            ),
            showSnackbarError = {
                snackbarCoroutine.launch {
                    snackbarHostState.showSnackbar(it)
                }
            }
        )
    }
}
