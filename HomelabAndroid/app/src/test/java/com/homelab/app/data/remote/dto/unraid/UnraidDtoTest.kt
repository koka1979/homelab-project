package com.homelab.app.data.remote.dto.unraid

import com.homelab.app.data.repository.UnraidAction
import com.homelab.app.domain.action.ActionRisk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test

class UnraidDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `array payload decodes capacity, disks and health`() {
        val payload = """
            {
              "array": {
                "state": "STARTED",
                "capacity": { "kilobytes": { "free": "1000", "used": "3000", "total": "4000" } },
                "parities": [ { "id": "p1", "name": "parity", "status": "DISK_OK", "temp": 34 } ],
                "disks": [
                  {
                    "id": "d1", "name": "disk1", "device": "sdb", "status": "DISK_OK",
                    "temp": 38, "numErrors": 0, "fsSize": 2000, "fsFree": 500, "fsUsed": 1500
                  },
                  { "id": "d2", "name": "disk2", "status": "DISK_DSBL", "numErrors": 12 }
                ]
              }
            }
        """.trimIndent()

        val array = json.decodeFromString(UnraidArrayData.serializer(), payload).array!!

        assertTrue(array.isStarted)
        assertEquals(0.75f, array.capacity?.kilobytes?.usedPercent)
        assertEquals(1, array.parities.size)
        assertEquals("disk1", array.disks.first().displayName)
        assertEquals(0.75f, array.disks.first().usedPercent)
        assertTrue(array.disks.first().isHealthy)
        assertFalse(array.disks[1].isHealthy)
    }

    @Test
    fun `container and vm payloads expose display names and running state`() {
        val docker = json.decodeFromString(
            UnraidDockerData.serializer(),
            """{"docker":{"containers":[{"id":"abc","names":["/plex"],"state":"RUNNING"}]}}"""
        )
        val vms = json.decodeFromString(
            UnraidVmsData.serializer(),
            """{"vms":{"domain":[{"uuid":"u-1","name":"Windows","state":"PAUSED"}]}}"""
        )

        val container = docker.docker!!.containers.single()
        assertEquals("plex", container.displayName)
        assertTrue(container.isRunning)

        val vm = vms.vms!!.domain.single()
        assertEquals("Windows", vm.displayName)
        assertTrue(vm.isPaused)
        assertFalse(vm.isRunning)
    }

    @Test
    fun `unknown fields in a newer schema do not break decoding`() {
        val info = json.decodeFromString(
            UnraidInfoData.serializer(),
            """{"info":{"os":{"distro":"Unraid","hostname":"tower","futureField":1},
                "memory":{"total":100,"used":40},"versions":{"unraid":"7.0.0"}}}"""
        )

        assertEquals("tower", info.info?.os?.hostname)
        assertEquals(0.4f, info.info?.memory?.usedPercent)
        assertEquals("7.0.0", info.info?.versions?.unraid)
    }

    @Test
    fun `schema mismatch detection separates unknown fields from real failures`() {
        assertTrue(UnraidGraphQl.isSchemaMismatch("""Cannot query field "restart" on type "DockerMutations"."""))
        assertTrue(UnraidGraphQl.isSchemaMismatch("Unknown argument \"correct\" on field \"start\"."))
        assertFalse(UnraidGraphQl.isSchemaMismatch("Unauthorized: API key lacks DOCKER permission"))
        assertFalse(UnraidGraphQl.isSchemaMismatch("Array is already started"))
    }

    @Test
    fun `mutation documents inline escaped identifiers`() {
        val documents = UnraidGraphQl.startContainer("""weird"name""")

        assertTrue(documents.first().contains("""start(id: "weird\"name")"""))
        // A namespaced candidate is always followed by the legacy flat form.
        assertEquals(2, documents.size)
        assertTrue(documents[1].startsWith("mutation { startContainer"))
    }

    @Test
    fun `controlled requests carry the risk class and a typed target`() {
        val request = UnraidAction.ARRAY_STOP.controlledRequest(
            instanceId = "Instance-1",
            targetRef = "array",
            confirmed = false
        )

        assertEquals("unraid:instance-1", request.providerRef)
        assertEquals("array.stop", request.action)
        assertEquals(ActionRisk.CRITICAL, request.risk)
        assertTrue(UnraidAction.ARRAY_STOP.requiresConfirmation)
        assertFalse(UnraidAction.CONTAINER_START.requiresConfirmation)
    }

    @Test
    fun `every action id matches the controlled action policy pattern`() {
        val pattern = Regex("^[a-z][a-z0-9.-]{1,127}$")
        UnraidAction.entries.forEach { action ->
            assertTrue(action.actionId, pattern.matches(action.actionId))
        }
    }
}
