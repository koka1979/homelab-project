package com.homelab.app.data.repository

import com.homelab.app.data.remote.TlsClientSelector
import com.homelab.app.data.remote.api.WgDashboardApi
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardConfiguration
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardConfigurationDetail
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardPeer
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardPeersRequest
import com.homelab.app.data.remote.dto.wgdashboard.WgDashboardResponse
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import junit.framework.TestCase.fail
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class WgDashboardRepositoryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    private fun httpError(code: Int, body: String): Nothing =
        throw HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    /** Serves scripted answers and records what the repository asked for. */
    private class FakeWgDashboardApi(
        private val configurations: () -> WgDashboardResponse<List<WgDashboardConfiguration>> = {
            WgDashboardResponse(status = true, data = emptyList())
        },
        private val version: () -> WgDashboardResponse<String> = {
            WgDashboardResponse(status = true, data = "v4.2.3")
        },
        private val detail: () -> WgDashboardResponse<WgDashboardConfigurationDetail> = {
            WgDashboardResponse(status = true, data = WgDashboardConfigurationDetail())
        },
        private val toggle: () -> WgDashboardResponse<Boolean> = {
            WgDashboardResponse(status = true, data = true)
        },
        private val peerAction: () -> WgDashboardResponse<JsonElement> = {
            WgDashboardResponse(status = true)
        }
    ) : WgDashboardApi {
        val restricted = mutableListOf<Pair<String, List<String>>>()
        val allowed = mutableListOf<Pair<String, List<String>>>()
        var toggledConfiguration: String? = null

        override suspend fun getConfigurations(instanceId: String) = configurations()

        override suspend fun getConfigurationInfo(instanceId: String, configurationName: String) = detail()

        override suspend fun getVersion(instanceId: String) = version()

        override suspend fun toggleConfiguration(instanceId: String, configurationName: String): WgDashboardResponse<Boolean> {
            toggledConfiguration = configurationName
            return toggle()
        }

        override suspend fun restrictPeers(
            instanceId: String,
            configName: String,
            body: WgDashboardPeersRequest
        ): WgDashboardResponse<JsonElement> {
            restricted += configName to body.peers
            return peerAction()
        }

        override suspend fun allowAccessPeers(
            instanceId: String,
            configName: String,
            body: WgDashboardPeersRequest
        ): WgDashboardResponse<JsonElement> {
            allowed += configName to body.peers
            return peerAction()
        }
    }

    private fun repository(api: WgDashboardApi) =
        WgDashboardRepository(api, mockk<TlsClientSelector>(relaxed = true))

    @Test
    fun `the overview lists running tunnels first`() = runTest {
        val api = FakeWgDashboardApi(
            configurations = {
                WgDashboardResponse(
                    status = true,
                    data = listOf(
                        WgDashboardConfiguration(name = "wg1", status = false),
                        WgDashboardConfiguration(name = "wg0", status = true)
                    )
                )
            }
        )

        val overview = repository(api).getOverview("instance")

        assertEquals(listOf("wg0", "wg1"), overview.configurations.map { it.name })
        assertEquals("v4.2.3", overview.version)
    }

    @Test
    fun `a build without the version endpoint still shows its tunnels`() = runTest {
        val api = FakeWgDashboardApi(
            configurations = {
                WgDashboardResponse(status = true, data = listOf(WgDashboardConfiguration(name = "wg0")))
            },
            version = { httpError(404, "not found") }
        )

        val overview = repository(api).getOverview("instance")

        assertEquals(1, overview.configurations.size)
        assertNull(overview.version)
        assertTrue(overview.unavailableSections.isNotEmpty())
    }

    @Test
    fun `a refused request keeps the server's own message`() = runTest {
        // WGDashboard answers a refusal with HTTP 200 and status false, so the envelope has to
        // be inspected - otherwise the failure would look like an empty dashboard.
        val api = FakeWgDashboardApi(
            configurations = {
                WgDashboardResponse(status = false, message = "Please provide a valid configuration name")
            }
        )

        try {
            repository(api).getOverview("instance")
            fail("expected the refusal to surface")
        } catch (error: WgDashboardApiException) {
            assertEquals(WgDashboardApiException.Kind.SERVER_ERROR, error.kind)
            assertEquals("Please provide a valid configuration name", error.detail)
        }
    }

    @Test
    fun `a rejected api key is reported as invalid credentials`() = runTest {
        val api = FakeWgDashboardApi(
            configurations = { httpError(401, """{"status":false,"message":"API Key does not exist","data":null}""") }
        )

        try {
            repository(api).getOverview("instance")
            fail("expected the rejected key to surface")
        } catch (error: WgDashboardApiException) {
            assertEquals(WgDashboardApiException.Kind.INVALID_CREDENTIALS, error.kind)
            assertEquals("API Key does not exist", error.detail)
        }
    }

    @Test
    fun `toggling a tunnel returns the status the server reports`() = runTest {
        val api = FakeWgDashboardApi(toggle = { WgDashboardResponse(status = true, data = false) })

        val result = repository(api).toggleConfiguration("instance", "wg0")

        assertEquals("wg0", api.toggledConfiguration)
        assertEquals(false, result)
    }

    @Test
    fun `restricting and allowing a peer sends its public key`() = runTest {
        val api = FakeWgDashboardApi()
        val repository = repository(api)

        repository.restrictPeers("instance", "wg0", listOf("KEY1"))
        repository.allowAccessPeers("instance", "wg0", listOf("KEY1"))

        assertEquals(listOf("wg0" to listOf("KEY1")), api.restricted)
        assertEquals(listOf("wg0" to listOf("KEY1")), api.allowed)
    }

    @Test
    fun `a peer action without a peer is refused before it reaches the server`() = runTest {
        val api = FakeWgDashboardApi()

        try {
            repository(api).restrictPeers("instance", "wg0", emptyList())
            fail("expected an empty peer list to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(api.restricted.isEmpty())
        }
    }

    @Test
    fun `the peer list is returned for the selected tunnel`() = runTest {
        val api = FakeWgDashboardApi(
            detail = {
                WgDashboardResponse(
                    status = true,
                    data = WgDashboardConfigurationDetail(
                        configurationPeers = listOf(WgDashboardPeer(id = "KEY1", name = "phone")),
                        configurationRestrictedPeers = listOf(WgDashboardPeer(id = "KEY2"))
                    )
                )
            }
        )

        val detail = repository(api).getConfigurationDetail("instance", "wg0")

        assertEquals("phone", detail.configurationPeers.single().displayName)
        assertEquals(1, detail.configurationRestrictedPeers.size)
    }

    @Test
    fun `a wrong key and a disabled api are told apart`() {
        val rejectedKey = classifyWgDashboardLogin(
            code = 401,
            body = """{"status":false,"message":"API Key does not exist","data":null}""",
            json = json
        )
        val disabledApi = classifyWgDashboardLogin(
            code = 401,
            body = """{"status":false,"message":"Unauthorized access.","data":null}""",
            json = json
        )

        assertEquals(
            WgDashboardApiException.Kind.INVALID_CREDENTIALS,
            (rejectedKey as WgDashboardLoginOutcome.Rejected).kind
        )
        assertEquals(
            WgDashboardApiException.Kind.API_DISABLED,
            (disabledApi as WgDashboardLoginOutcome.Rejected).kind
        )
    }

    @Test
    fun `a page that is not the api is recognised`() {
        val outcome = classifyWgDashboardLogin(code = 200, body = "<!doctype html><html>", json = json)

        assertEquals(
            WgDashboardApiException.Kind.NOT_WGDASHBOARD,
            (outcome as WgDashboardLoginOutcome.Rejected).kind
        )
    }

    @Test
    fun `a valid key is accepted`() {
        val outcome = classifyWgDashboardLogin(
            code = 200,
            body = """{"status":true,"message":null,"data":[]}""",
            json = json
        )

        assertTrue(outcome is WgDashboardLoginOutcome.Accepted)
    }
}
