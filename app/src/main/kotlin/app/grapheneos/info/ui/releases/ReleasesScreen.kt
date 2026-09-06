package app.grapheneos.info.ui.releases

import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.grapheneos.info.R
import app.grapheneos.info.ui.reusablecomposables.ScreenLazyColumn
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleasesScreen(
    modifier: Modifier = Modifier,
    showSnackbarError: (String) -> Unit,
    entries: List<Pair<String, String>>,
    channelMap: Map<String, String> = emptyMap(),
    deviceChannel: String = "alpha",
    selectedChannel: String = "all",
    onChannelSelected: (String) -> Unit = {},
    updateChangelog: (useCaches: Boolean, finishedUpdating: () -> Unit) -> Unit,
    changelogLazyListState: LazyListState,
    additionalContentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val localUriHandler = LocalUriHandler.current
    val refreshCoroutineScope = rememberCoroutineScope()

    val openUriIllegalArguementExceptionSnackbarError =
        stringResource(R.string.browser_link_illegal_argument_exception_snackbar_error)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                refreshCoroutineScope.launch {
                    updateChangelog(true) {}
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var isRefreshing by rememberSaveable { mutableStateOf(false) }
    val state = rememberPullToRefreshState()

    val device = Build.DEVICE?.lowercase() ?: "akita"
    val model = if (!Build.MODEL.isNullOrBlank()) Build.MODEL else "Pixel 8a"

    val filteredEntries = remember(entries, selectedChannel, channelMap) {
        if (selectedChannel == "all") {
            entries
        } else {
            entries.filter { channelMap[it.first]?.lowercase() == selectedChannel.lowercase() }
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            updateChangelog(false) {
                isRefreshing = false
                refreshCoroutineScope.launch {
                    state.animateToHidden()
                }
            }
        },
        state = state,
        modifier = modifier.fillMaxSize()
    ) {
        ScreenLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = changelogLazyListState,
            additionalContentPadding = additionalContentPadding,
            verticalArrangement = Arrangement.Top
        ) {
            // Device Information & Channel Header Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Smartphone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(32.dp)
                                    .padding(end = 8.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "$model ($device)",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                val androidVersion = Build.VERSION.RELEASE ?: "17"
                                val lineageVersion = try {
                                    val sp = Class.forName("android.os.SystemProperties")
                                    val getM = sp.getMethod("get", String::class.java, String::class.java)
                                    getM.invoke(null, "ro.lineage.build.version", "24.0") as String
                                } catch (_: Exception) {
                                    "24.0"
                                }
                                Text(
                                    text = "LineageOS $lineageVersion • Android $androidVersion",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.device_channel),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            ChannelBadge(channel = deviceChannel)
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Channel Filter Chips
                        Text(
                            text = stringResource(R.string.filter_by_channel),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val filterOptions = listOf(
                                "all" to stringResource(R.string.channel_all),
                                "stable" to stringResource(R.string.channel_stable),
                                "beta" to stringResource(R.string.channel_beta),
                                "alpha" to stringResource(R.string.channel_alpha)
                            )

                            for ((channelKey, label) in filterOptions) {
                                val isSelected = selectedChannel == channelKey
                                val count = if (channelKey == "all") {
                                    entries.size
                                } else {
                                    entries.count { channelMap[it.first]?.lowercase() == channelKey }
                                }

                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onChannelSelected(channelKey) },
                                    label = {
                                        Text(text = if (count > 0) "$label ($count)" else label)
                                    },
                                    leadingIcon = if (isSelected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                                            )
                                        }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }

            if (entries.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (filteredEntries.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.no_releases_for_channel),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(onClick = { onChannelSelected("all") }) {
                                Text(text = stringResource(R.string.channel_all))
                            }
                        }
                    }
                }
            } else {
                items(
                    items = filteredEntries,
                    key = { it.first }
                ) {
                    Changelog(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        entry = it.second
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Button(onClick = {
                        try {
                            localUriHandler.openUri("https://github.com/rhythmcreative/lineageos-$device-ota/releases")
                        } catch (_: IllegalArgumentException) {
                            showSnackbarError(openUriIllegalArguementExceptionSnackbarError)
                        }
                    }) {
                        Text(text = stringResource(R.string.releases_see_all_button))
                    }
                }
            }
        }
    }
}
