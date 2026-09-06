package app.grapheneos.info.ui.releases

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable

@OptIn(SavedStateHandleSaveableApi::class)
class ReleasesUiState(savedStateHandle: SavedStateHandle) {
    var didInitialScroll: Boolean by savedStateHandle.saveable {
        mutableStateOf(false)
    }
    /** Unsorted release notes, use .toSortedMap().toList().asReversed() to get them in the proper order. */
    val entries: MutableMap<String, String> = mutableStateMapOf()

    /** Map of entry key to release channel: "stable", "beta", "alpha" */
    val channelMap: MutableMap<String, String> = mutableStateMapOf()

    /** Loading state indicator */
    val isLoading = mutableStateOf(false)
}