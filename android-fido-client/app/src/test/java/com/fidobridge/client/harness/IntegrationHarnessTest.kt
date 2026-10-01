package com.fidobridge.client.harness

import com.fidobridge.client.ctap.Ctap2Processor
import com.fidobridge.client.networking.CentrifugoTransport
import com.fidobridge.client.networking.RelayClient
import com.fidobridge.client.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArraySet

class IntegrationHarnessTest {

    @Test
    fun `relay harness get-assertion and make-credential`() = runBlocking {
        assumeTrue(
            "harness disabled: missing /tmp/fido2_harness.properties (or FIDO2_HARNESS=1)",
            HarnessConfig.isEnabled()
        )

        val config = HarnessConfig.fromEnv()
        val processor = Ctap2Processor(FakeCredentialStore(), FakeKeyGenerator(), FakeSigner())
        val transport = CentrifugoTransport(config.relayUrl, Protocol.relayChannel(config.channelId))
        val client = RelayClient(
            transport,
            config.channelId,
            config.phoneStaticPrivate,
            config.daemonStaticPublic
        )

        val sentTypes = CopyOnWriteArraySet<String>()
        val sentErrors = CopyOnWriteArraySet<String>()

        client.connect()
        val connected = withTimeoutOrNull(15_000) {
            while (client.state.value != RelayClient.ConnectionState.CONNECTED) delay(100)
        }
        assertTrue("relay did not connect within 15s", connected != null)

        val collector = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            client.inbound.collect { plaintext ->
                processor.process(plaintext) { result ->
                    result.onSuccess { response ->
                        if (client.send(response)) {
                            val type = Json.parseToJsonElement(response.decodeToString())
                                .jsonObject["type"]?.jsonPrimitive?.content.orEmpty()
                            if (type == "error") sentErrors.add(type) else sentTypes.add(type)
                        }
                    }
                }
            }
        }

        try {
            val expected = setOf("assertionResult", "makeCredentialResult")
            val deadline = System.currentTimeMillis() + config.timeoutSeconds * 1000
            while (!sentTypes.containsAll(expected)) {
                if (System.currentTimeMillis() > deadline) {
                    org.junit.Assert.fail(
                        "harness timed out; sentTypes=$sentTypes sentErrors=$sentErrors"
                    )
                }
                delay(500)
            }

            assertTrue("processor sent error responses: $sentErrors", sentErrors.isEmpty())
            assertEquals(expected, sentTypes.filter { expected.contains(it) }.toSet())
        } finally {
            client.close()
            collector.cancel()
        }
    }
}