package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.GaugePeaks
import ru.sodovaya.volty.domain.repository.VehicleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * **The only way a test may own a [KableBmsRepository].** Use this instead of
 * `runTest { … }` wherever the repository is involved, and let the harness own
 * its lifetime.
 *
 * ### Why this exists rather than a convention
 *
 * [KableBmsRepository.startLinkReconnectLoop] is `while (isActive) { attempt;
 * delay(backoff) }` — an unbounded stream of *delayed* tasks on the injected
 * dispatcher. `runTest` advances virtual time until every coroutine on its
 * scheduler is idle, and a reconnect loop is never idle: a test that leaves one
 * running does not fail, it **wedges the build**, spinning a fresh connect
 * attempt per virtual iteration until the Gradle daemon dies of an OOM with no
 * test XML written at all. That happened once already, for over an hour, and
 * cost far more than the bug it was hiding.
 *
 * Two things that look like they would prevent it do not:
 *
 *  - **`@AfterTest { repo.close() }`** runs AFTER `runTest` returns — that is,
 *    after the wedge. Every repository test in this file's neighbourhood used
 *    to carry one, and none of them was protection.
 *  - **`repo.disconnect()` as the last line of the body** is skipped the moment
 *    an assertion above it fails — so a genuine regression turns into a hung
 *    build instead of a red test. `KableBmsRepositoryDisconnectLinkTest` was
 *    the sharpest case: the loop there is stopped *by the code under test*, so
 *    the exact regression the test exists to catch was also the one that would
 *    hide it.
 *
 * The cancellation therefore has to happen inside `runTest`, in a `finally`,
 * and the only way to make that impossible to forget is to make the repository
 * unobtainable without it. That is this function: the repository is created
 * here and handed to [body], and its scope is cancelled whatever [body] does.
 * A test that never starts a loop pays nothing for it.
 */
@OptIn(ExperimentalCoroutinesApi::class, DelicateBmsRepositoryTestApi::class)
internal fun bleRepositoryTest(
    vehicleRepository: VehicleRepository = EmptyVehicleRepository(),
    serviceStart: () -> Unit = {},
    serviceStop: () -> Unit = {},
    bleAdapterStateProvider: BleAdapterStateProvider = BleAdapterStateProvider { true },
    body: suspend TestScope.(KableBmsRepository) -> Unit
): TestResult = runTest {
    val repo = KableBmsRepository.forTesting(
        vehicleRepository = vehicleRepository,
        serviceStart = serviceStart,
        serviceStop = serviceStop,
        coroutineContext = StandardTestDispatcher(testScheduler),
        bleAdapterStateProvider = bleAdapterStateProvider,
    )
    try {
        body(repo)
    } finally {
        // Cancels the repository scope, and with it every reconnect loop,
        // session job and sample consumer it owns. Inside runTest, so the
        // scheduler is idle by the time runTest checks — and in a finally, so
        // a failing assertion reports itself instead of hanging.
        repo.close()
    }
}

/**
 * The default store for [bleRepositoryTest]: knows no vehicles and remembers
 * nothing. Tests that need to observe what the repository persisted pass their
 * own recording implementation instead.
 */
internal class EmptyVehicleRepository : VehicleRepository {
    override val vehicles: Flow<List<Vehicle>> = flowOf(emptyList())
    override suspend fun get(id: String): Vehicle? = null
    override suspend fun upsert(vehicle: Vehicle) {}
    override suspend fun delete(id: String) {}
    override suspend fun touch(id: String) {}
    // Explicit, because both of VehicleRepository's gauge-peak members are abstract:
    // no fake gets a silent default. Nothing in this file rides a learned dial range
    // (G §9.2), and an EMPTY map is the honest answer rather than a missing one --
    // absence in that map means "has learned nothing", which is exactly the case here.
    override val gaugePeaks: Flow<Map<String, GaugePeaks>> = flowOf(emptyMap())
    override suspend fun updateGaugePeaks(id: String, currentA: Float, powerW: Float) {}
}
