package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.location.LocationDemandCoordinator
import ru.sodovaya.volty.domain.location.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryLocationCaptureTest {
    @Test fun no_provider_positive_before_actual_foreground_ack() = runTest {
        fixture {
            val start = capture.start()
            runCurrent()
            assertEquals(1, launches, "Native foreground launch must be issued asynchronously")
            assertTrue(repository.issued.isEmpty())
            assertFalse(start.isCompleted)
            assertFalse(foreground.canCapture(recording))
            assertTrue(foreground.acknowledge(capture.ticket, service))
            assertTrue(start.await())
            assertEquals(listOf(true), repository.issued)
            assertTrue(foreground.canCapture(recording))
            assertTrue(capture.state.value.capturing)
        }
    }

    @Test fun foreground_alone_cannot_admit_capture_before_provider_ack() = runTest {
        fixture {
            acknowledgeOnLaunch = true
            repository.startGate = CompletableDeferred()
            val start = capture.start()
            runCurrent()
            assertTrue(foreground.allows(recording))
            assertEquals(listOf(true), repository.issued)
            assertFalse(start.isCompleted)
            assertTrue(demand.nativeDemandHeld.value)
            assertFalse(foreground.canCapture(recording))
            assertFalse(capture.state.value.capturing)
            repository.startGate!!.complete(Unit)
            assertTrue(start.await())
            assertTrue(foreground.canCapture(recording))
        }
    }

    @Test fun rejected_launch_retires_original_ticket_without_starting_providers() = runTest {
        fixture {
            launchAccepted = false
            assertFalse(capture.start().await())
            assertEquals(1, launches)
            assertTrue(repository.issued.isEmpty())
            assertEquals(null, foreground.find(capture.ticket.id))
            assertFalse(capture.state.value.wanted)
            assertEquals(HistoryLocationCaptureFailure.LAUNCH, capture.state.value.failure)
            assertFalse(demand.nativeDemandHeld.value)
        }
    }

    @Test fun actual_service_loss_fences_capture_and_releases_original_providers() = runTest {
        fixture {
            acknowledgeOnLaunch = true
            assertTrue(capture.start().await())
            assertEquals(listOf(true), repository.issued)
            assertTrue(foreground.destroyed(service))
            assertFalse(foreground.canCapture(recording))
            runCurrent()
            assertEquals(listOf(true, false), repository.issued, "Service loss must trigger original native cleanup")
            assertFalse(capture.state.value.wanted)
            assertFalse(capture.state.value.capturing)
            assertFalse(demand.nativeDemandHeld.value)
        }
    }

    @Test fun replacing_recording_releases_old_capture_and_old_duplicate_cannot_stop_new_one() = runTest {
        fixture {
            acknowledgeOnLaunch = true
            assertTrue(capture.start().await())
            val replacementRecording = CapturedHistoryGpsRecording("next", 2L, Any())
            val replacement = newCapture(replacementRecording).also { extras += it }
            runCurrent()
            assertFalse(capture.state.value.wanted, "Foreground replacement retires the original capture owner")
            assertEquals(listOf(true, false), repository.issued)
            assertTrue(replacement.start().await())
            assertTrue(foreground.canCapture(replacementRecording))
            assertTrue(capture.close())
            assertTrue(foreground.canCapture(replacementRecording))
            assertTrue(demand.nativeDemandHeld.value)
            assertTrue(replacement.close())
            assertFalse(demand.nativeDemandHeld.value)
        }
    }

    @Test fun parent_scope_cancellation_retires_even_an_already_active_capture() = runTest {
        fixture {
            acknowledgeOnLaunch = true
            assertTrue(capture.start().await())
            assertEquals(listOf(true), repository.issued)
            scope.cancel()
            runCurrent()
            assertFalse(foreground.canCapture(recording), "Completed startup must not escape parent lifetime")
            assertEquals(null, foreground.find(capture.ticket.id))
            assertEquals(listOf(true, false), repository.issued)
            assertFalse(demand.nativeDemandHeld.value)
        }
    }

    @Test fun cancelled_partial_acquisition_retains_native_hold_until_original_stop_ack() = runTest {
        fixture {
            acknowledgeOnLaunch = true
            repository.startGate = CompletableDeferred()
            repository.stopGate = CompletableDeferred()
            val start = capture.start()
            runCurrent()
            assertEquals(listOf(true), repository.issued)
            assertTrue(demand.nativeDemandHeld.value)
            assertFalse(foreground.canCapture(recording))
            start.cancel()
            repository.startGate!!.complete(Unit)
            runCurrent()
            assertEquals(listOf(true, false), repository.issued)
            assertTrue(demand.nativeDemandHeld.value)
            assertFalse(capture.state.value.wanted)
            repository.stopGate!!.complete(Unit)
            runCurrent()
            assertTrue(start.isCancelled)
            assertFalse(demand.nativeDemandHeld.value)
        }
    }

    @Test fun closed_before_start_never_issues_foreground_launch() = runTest {
        fixture {
            assertTrue(capture.close())
            assertFalse(capture.start().await())
            assertEquals(0, launches, "A retired owner cannot launch a new native service")
            assertTrue(repository.issued.isEmpty())
        }
    }

    @Test fun failed_stop_stays_owned_and_successful_retry_clears_cleanup_failure() = runTest {
        fixture {
            acknowledgeOnLaunch = true
            assertTrue(capture.start().await())
            repository.failStop = true
            assertFalse(capture.close())
            assertFalse(foreground.canCapture(recording))
            assertFalse(capture.state.value.wanted)
            assertTrue(demand.nativeDemandHeld.value)
            assertEquals(HistoryLocationCaptureFailure.CLEANUP, capture.state.value.failure)
            repository.failStop = false
            assertTrue(capture.close())
            assertFalse(demand.nativeDemandHeld.value)
            assertEquals(null, capture.state.value.failure)
        }
    }

    @Test fun foreground_startup_has_finite_deadline_and_no_provider_positive() = runTest {
        fixture {
            val start = capture.start()
            runCurrent()
            assertEquals(1, launches)
            assertFalse(start.isCompleted)
            advanceTimeBy(10_000L)
            runCurrent()
            assertFalse(start.await())
            assertTrue(repository.issued.isEmpty())
            assertFalse(capture.state.value.wanted)
            assertEquals(HistoryLocationCaptureFailure.FOREGROUND, capture.state.value.failure)
            assertEquals(null, foreground.find(capture.ticket.id))
        }
    }

    @Test fun wrong_consumer_rejected_before_revoking_current_foreground_recording() = runTest {
        fixture {
            val wrong = LocationDemandCoordinator(repository, LocationConsumer.NEARBY_CHANNEL_SHARING)
            assertFailsWith<IllegalArgumentException> {
                HistoryLocationCapture(scope, dispatcher, CapturedHistoryGpsRecording("foreign", 2L, Any()), foreground,
                    wrong, launch = { error("Wrong consumer must not launch") })
            }
            assertSame(capture.ticket, foreground.find(capture.ticket.id))
            assertEquals(0, launches)
            assertTrue(repository.issued.isEmpty())
        }
    }

    @Test fun completed_start_cannot_be_replayed_after_capture_closed() = runTest {
        fixture {
            acknowledgeOnLaunch = true
            assertTrue(capture.start().await())
            assertTrue(capture.close())
            assertFalse(capture.start().await(), "A new start invocation cannot borrow the old successful receipt after retirement")
            assertEquals(1, launches)
            assertEquals(listOf(true, false), repository.issued)
        }
    }

    @Test fun synchronous_foreground_replacement_during_construction_cannot_touch_successor() = runTest {
        fixture {
            val third = CapturedHistoryGpsRecording("third", 3L, Any())
            var replaced = false
            val observer = scope.launch(UnconfinedTestDispatcher(testScheduler)) {
                foreground.state.collect { snapshot ->
                    if (!replaced && snapshot.ticket?.recording?.rideId == "reentrant") {
                        replaced = true
                        foreground.request(third) {}
                    }
                }
            }
            try {
                val retired = newCapture(CapturedHistoryGpsRecording("reentrant", 2L, Any())).also { extras += it }
                assertTrue(replaced, "Driver must retire request before constructor assignment completes")
                assertFalse(retired.state.value.wanted)
                assertSame(third, foreground.state.value.ticket?.recording)
                assertFalse(retired.start().await())
                runCurrent()
                assertSame(third, foreground.state.value.ticket?.recording)
                assertEquals(0, launches)
                assertTrue(repository.issued.isEmpty())
            } finally { observer.cancel() }
        }
    }

    private suspend fun TestScope.fixture(block: suspend Fixture.() -> Unit) {
        val f = Fixture(this)
        try { f.block() }
        finally {
            f.repository.startGate?.complete(Unit)
            f.repository.stopGate?.complete(Unit)
            f.repository.failStop = false
            f.extras.forEach { it.close() }
            f.capture.close()
            f.foreground.state.value.ticket?.let(f.foreground::stop)
            f.scope.cancel()
            runCurrent()
        }
    }

    private class Fixture(test: TestScope) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val recording = CapturedHistoryGpsRecording("ride", 1L, Any())
        val service = Any()
        val foreground = HistoryLocationForegroundAuthority({ true }, { "00000000-0000-0000-0000-000000000001" })
        val repository = Repository()
        val demand = LocationDemandCoordinator(repository, LocationConsumer.HISTORY_RECORDING)
        var launches = 0
        var launchAccepted = true
        var acknowledgeOnLaunch = false
        val extras = mutableListOf<HistoryLocationCapture>()
        val capture = newCapture(recording)
        fun newCapture(recording: CapturedHistoryGpsRecording) = HistoryLocationCapture(scope, dispatcher, recording, foreground, demand, launch = { ticket ->
            launches++
            if (acknowledgeOnLaunch) assertTrue(foreground.acknowledge(ticket, service))
            launchAccepted
        })
    }

    private class Repository : RideLocationRepository {
        override val requiredPermissions = emptyList<String>()
        override val state = MutableStateFlow(RideLocationState())
        val issued = mutableListOf<Boolean>()
        var startGate: CompletableDeferred<Unit>? = null
        var stopGate: CompletableDeferred<Unit>? = null
        var failStop = false
        override suspend fun refreshPermissionAndProviders() = Unit
        override suspend fun setDemand(consumer: LocationConsumer, enabled: Boolean) {
            assertEquals(LocationConsumer.HISTORY_RECORDING, consumer)
            issued += enabled
            if (enabled) withContext(NonCancellable) { startGate?.await() } else stopGate?.await()
            state.value = state.value.copy(demands = if (enabled) setOf(consumer) else emptySet())
            if (!enabled && failStop) error("Native stop failed after desired removal")
        }
    }
}
