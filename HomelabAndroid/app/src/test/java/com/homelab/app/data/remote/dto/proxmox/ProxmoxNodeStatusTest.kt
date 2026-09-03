package com.homelab.app.data.remote.dto.proxmox

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test

class ProxmoxNodeStatusTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /**
     * `/nodes/{node}/status` reports memory and swap as objects. Modelling them as scalars made
     * the node detail screen fail to decode with "Expected numeric literal at path: $.data.swap".
     */
    @Test
    fun `node status decodes the object shape proxmox actually returns`() {
        val payload = """
            {
              "data": {
                "uptime": 907323,
                "loadavg": ["0.14", "0.19", "0.20"],
                "cpu": 0.0172164119066774,
                "wait": 0.000371,
                "swap": { "total": 64424505344, "free": 64424505344, "used": 0 },
                "memory": { "total": 135029366784, "used": 33757341696, "free": 101272025088 },
                "rootfs": { "total": 100861726720, "used": 25215431680, "free": 70521167872, "avail": 70521167872 },
                "ksm": { "shared": 0 },
                "kversion": "Linux 6.8.12-4-pve",
                "pveversion": "pve-manager/8.2.4/faa83925c9641325",
                "cpuinfo": { "cpus": 16, "cores": 8, "sockets": 2, "model": "Intel(R) Xeon(R)" },
                "boot-info": { "mode": "efi", "secureboot": 0 }
              }
            }
        """.trimIndent()

        val status = json.decodeFromString(
            ProxmoxApiResponse.serializer(ProxmoxNodeStatus.serializer()),
            payload
        ).data

        assertEquals(64424505344L, status.swapTotal)
        assertEquals(0L, status.swapUsed)
        assertEquals(0.0, status.swapPercent)

        assertEquals(135029366784L, status.memTotal)
        assertEquals(33757341696L, status.memUsed)
        assertEquals(25.0, status.memPercent)

        assertEquals(16, status.cpus)
        assertEquals(25.0, status.rootfsPercent)
        assertEquals("Linux 6.8.12-4-pve", status.kversion)
        assertTrue(status.formattedUptime.endsWith("h"))
    }

    @Test
    fun `a node without swap configured reports zero instead of failing`() {
        val status = json.decodeFromString(
            ProxmoxNodeStatus.serializer(),
            """{"cpu":0.5,"memory":{"total":100,"used":40},"swap":{"total":0,"free":0,"used":0}}"""
        )

        assertEquals(0L, status.swapTotal)
        assertEquals(0.0, status.swapPercent)
        assertEquals(40.0, status.memPercent)
        assertEquals(50.0, status.cpuPercent)
        assertNull(status.cpus)
    }
}
