package com.homelab.app.data.repository

import com.homelab.app.data.remote.TlsClientSelector
import com.homelab.app.data.remote.api.UnraidApi
import com.homelab.app.data.remote.dto.unraid.UnraidGraphQlRequest
import com.homelab.app.data.remote.dto.unraid.UnraidGraphQlResponse
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import junit.framework.TestCase.fail
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class UnraidRepositoryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /** Records the documents it is asked for and replays a scripted answer per call. */
    private class FakeUnraidApi(
        private val answers: List<() -> UnraidGraphQlResponse>
    ) : UnraidApi {
        val documents = mutableListOf<String>()
        private var index = 0

        override suspend fun graphql(
            instanceId: String,
            body: UnraidGraphQlRequest
        ): UnraidGraphQlResponse {
            documents += body.query
            val answer = answers.getOrElse(index) { answers.last() }
            index++
            return answer()
        }
    }

    private fun httpError(code: Int, body: String): Nothing =
        throw HttpException(Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())))

    private fun repository(api: UnraidApi) =
        UnraidRepository(api, json, mockk<TlsClientSelector>(relaxed = true))

    @Test
    fun `a validation error returned as HTTP 400 falls through to the next candidate`() = runTest {
        // Unraid answers a document it cannot validate with 400 instead of a 200 carrying errors.
        val api = FakeUnraidApi(
            listOf(
                { httpError(400, """{"errors":[{"message":"Cannot query field \"autoStart\" on type \"Container\"."}]}""") },
                { json.decodeFromString(UnraidGraphQlResponse.serializer(), """{"data":{"docker":{"containers":[{"id":"a","names":["/plex"],"state":"RUNNING"}]}}}""") }
            )
        )

        val containers = repository(api).getContainers("instance-1")

        assertEquals(1, containers.size)
        assertEquals("plex", containers.single().displayName)
        assertEquals(2, api.documents.size)
    }

    @Test
    fun `an unsupported section is reported as unavailable instead of failing the dashboard`() = runTest {
        val api = FakeUnraidApi(
            listOf({ httpError(400, """{"errors":[{"message":"Cannot query field \"vms\" on type \"Query\"."}]}""") })
        )

        assertTrue(repository(api).getVms("instance-1").isEmpty())
    }

    @Test
    fun `a rejected api key stays an authentication error rather than a schema fallback`() = runTest {
        val api = FakeUnraidApi(listOf({ httpError(403, """{"errors":[{"message":"Forbidden resource"}]}""") }))

        try {
            repository(api).getContainers("instance-1")
            fail("expected an authentication failure")
        } catch (error: UnraidApiException) {
            assertEquals(UnraidApiException.Kind.INVALID_CREDENTIALS, error.kind)
            assertEquals("Forbidden resource", error.detail)
        }
        assertEquals(1, api.documents.size)
    }

    @Test
    fun `a genuine server failure keeps its status and message for the user`() = runTest {
        val api = FakeUnraidApi(listOf({ httpError(500, "upstream timeout") }))

        try {
            repository(api).startArray("instance-1")
            fail("expected a server error")
        } catch (error: UnraidApiException) {
            assertEquals(UnraidApiException.Kind.SERVER_ERROR, error.kind)
            assertEquals("HTTP 500: upstream timeout", error.detail)
        }
    }
}
