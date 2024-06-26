package eu.darken.sdmse.setup

import eu.darken.sdmse.common.coroutine.AppScope
import eu.darken.sdmse.common.datastore.valueBlocking
import eu.darken.sdmse.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.sdmse.common.debug.logging.log
import eu.darken.sdmse.common.debug.logging.logTag
import eu.darken.sdmse.common.flow.replayingShare
import eu.darken.sdmse.main.core.GeneralSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetupManager @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val setupModules: Set<@JvmSuppressWildcards SetupModule>,
    private val generalSettings: GeneralSettings,
    setupHealer: SetupHealer,
) {

    val state: Flow<State> = combine(
        combine(
            setupModules.map { module -> module.state.onEach { log(TAG, VERBOSE) { "Module $module -> $it" } } }
        ) { it.toList() },
        generalSettings.isSetupDismissed.flow,
        setupHealer.state,
    ) { moduleStates, isDismissed, healerState ->
        State(
            moduleStates = moduleStates,
            isDismissed = isDismissed,
            isHealerWorking = healerState.isWorking,
        )
    }
        .onEach { log(TAG) { "Setup state: $it" } }
        .replayingShare(appScope)

    suspend fun refresh() {
        log(TAG) { "refresh()" }
        setupModules.forEach { it.refresh() }
    }

    fun setDismissed(dismissed: Boolean) {
        log(TAG) { "dismissSetup()" }
        generalSettings.isSetupDismissed.valueBlocking = dismissed
    }

    data class State(
        val moduleStates: List<SetupModule.State>,
        val isDismissed: Boolean,
        val isHealerWorking: Boolean,
    ) {

        val startedLoadingAt: Instant?
            get() = moduleStates.filterIsInstance<SetupModule.State.Loading>().minOfOrNull { it.startAt }

        val isDone: Boolean = !isHealerWorking && moduleStates.all { it is SetupModule.State.Current && it.isComplete }
        val isIncomplete: Boolean = moduleStates.filterIsInstance<SetupModule.State.Current>().any { !it.isComplete }
        val isLoading: Boolean = moduleStates.any { it is SetupModule.State.Loading }
        val isWorking: Boolean = isHealerWorking || isLoading
    }

    companion object {
        private val TAG = logTag("Setup", "Manager")
    }
}