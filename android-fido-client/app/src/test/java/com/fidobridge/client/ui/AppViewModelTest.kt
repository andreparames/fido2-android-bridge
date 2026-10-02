package com.fidobridge.client.ui

import com.fidobridge.client.bridge.BridgePipeline
import com.fidobridge.client.bridge.BridgeState
import com.fidobridge.client.harness.FakeCredentialStore
import com.fidobridge.client.pairing.AppResetManager
import com.fidobridge.client.pairing.FakeIdentityStore
import com.fidobridge.client.pairing.PairingRepository
import com.fidobridge.client.security.KeystoreManager
import com.fidobridge.client.ui.model.InMemoryRequestLog
import com.fidobridge.client.ui.model.RequestType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private fun pipeline(state: BridgeState = BridgeState.Disconnected) =
        mockk<BridgePipeline>(relaxed = true).apply {
            every { this@apply.state } returns MutableStateFlow(state)
        }

    private fun viewModel(
        requestLog: InMemoryRequestLog = InMemoryRequestLog(),
        pipeline: BridgePipeline = pipeline(),
        appResetManager: AppResetManager = mockk(relaxed = true),
        userMessageBus: UserMessageBus = UserMessageBus()
    ): AppViewModel = AppViewModel(
        PairingRepository(FakeIdentityStore()),
        requestLog,
        pipeline,
        appResetManager,
        userMessageBus
    )

    @Test
    fun `requests expose the request log`() {
        val log = InMemoryRequestLog()
        log.record("id-1", RequestType.SIGN_IN, "example.com")

        val vm = viewModel(requestLog = log)

        assertEquals(listOf("id-1"), vm.requests.value.map { it.id })
    }

    @Test
    fun `bridge state is exposed`() {
        val vm = viewModel(pipeline = pipeline(BridgeState.Connected))

        assertEquals(BridgeState.Connected, vm.bridgeState.value)
    }

    @Test
    fun `clearLog clears the request log`() {
        val log = InMemoryRequestLog()
        log.record("id-1", RequestType.SIGN_IN, "example.com")

        val vm = viewModel(requestLog = log)
        vm.clearLog()

        assertTrue(vm.requests.value.isEmpty())
    }

    @Test
    fun `restoreRequests replaces the request log`() {
        val log = InMemoryRequestLog()
        log.record("id-1", RequestType.SIGN_IN, "example.com")
        val snapshot = log.records.value
        log.clear()

        val vm = viewModel(requestLog = log)
        vm.restoreRequests(snapshot)

        assertEquals(listOf("id-1"), vm.requests.value.map { it.id })
    }

    @Test
    fun `reconnect delegates to the pipeline`() {
        val pipeline = pipeline()
        val vm = viewModel(pipeline = pipeline)

        vm.reconnect()

        verify { pipeline.reconnect() }
    }

    @Test
    fun `acknowledge delegates to the pipeline`() {
        val pipeline = pipeline()
        val vm = viewModel(pipeline = pipeline)

        vm.acknowledgeSecurityAlert()

        verify { pipeline.acknowledgeSecurityAlert() }
    }

    @Test
    fun `reset stops the pipeline and clears all state`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val log = InMemoryRequestLog()
            log.record("id-1", RequestType.SIGN_IN, "example.com")
            val pipeline = pipeline()
            val appResetManager = AppResetManager(
                FakeIdentityStore(),
                FakeCredentialStore(),
                mockk<KeystoreManager>(relaxed = true),
                log
            )
            val vm = AppViewModel(PairingRepository(FakeIdentityStore()), log, pipeline, appResetManager, UserMessageBus())
            val done = CountDownLatch(1)
            var result: Boolean? = null

            vm.reset { success ->
                result = success
                done.countDown()
            }

            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(result == true)
            verify { pipeline.stop() }
            assertTrue(vm.requests.value.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `user message is exposed and dismissible`() {
        val bus = UserMessageBus()
        val vm = viewModel(userMessageBus = bus)
        bus.post("boom")

        assertEquals("boom", vm.userMessage.value)
        vm.dismissUserMessage()
        assertNull(vm.userMessage.value)
    }
}