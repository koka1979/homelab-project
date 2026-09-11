package com.homelab.app.data.remote.dto.wgdashboard

import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Test

class WgDashboardDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    private fun configurations(payload: String): List<WgDashboardConfiguration> =
        json.decodeFromString(
            WgDashboardResponse.serializer(ListSerializer(WgDashboardConfiguration.serializer())),
            payload
        ).data.orEmpty()

    @Test
    fun `the envelope carries status message and data`() {
        val response = json.decodeFromString(
            WgDashboardResponse.serializer(ListSerializer(WgDashboardConfiguration.serializer())),
            """{"status":false,"message":"API Key does not exist","data":null}"""
        )

        assertFalse(response.status)
        assertEquals("API Key does not exist", response.message)
        assertNull(response.data)
    }

    @Test
    fun `a tunnel is decoded with traffic and peer counts`() {
        val parsed = configurations(
            """
            {"status":true,"message":null,"data":[
              {"Status":true,"Name":"wg0","PublicKey":"abc","Address":"10.0.0.1/24","ListenPort":"51820",
               "Protocol":"wg","ConnectedPeers":2,"TotalPeers":5,
               "DataUsage":{"Total":12.5,"Sent":7.5,"Receive":5.0}}
            ]}
            """.trimIndent()
        )

        val tunnel = parsed.single()
        assertEquals("wg0", tunnel.name)
        assertTrue(tunnel.status)
        assertEquals("51820", tunnel.listenPort)
        assertEquals(2, tunnel.connectedPeers)
        assertEquals(5, tunnel.totalPeers)
        assertEquals(12.5, tunnel.totalGb, 0.001)
        assertFalse(tunnel.isAmnezia)
    }

    @Test
    fun `a listen port sent as a number is accepted`() {
        // The dashboard reads the value with a raw config parser, so both shapes occur.
        val parsed = configurations(
            """{"status":true,"data":[{"Name":"wg0","ListenPort":51820}]}"""
        )

        assertEquals("51820", parsed.single().listenPort)
    }

    @Test
    fun `an AmneziaWG tunnel is recognised`() {
        val parsed = configurations(
            """{"status":true,"data":[{"Name":"awg0","Protocol":"awg"}]}"""
        )

        assertTrue(parsed.single().isAmnezia)
    }

    @Test
    fun `a missing data usage block leaves the tunnel usable`() {
        val parsed = configurations("""{"status":true,"data":[{"Name":"wg0","Status":false}]}""")

        assertNull(parsed.single().dataUsage)
        assertEquals(0.0, parsed.single().totalGb, 0.001)
    }

    @Test
    fun `peers keep their counters and ignore the nested configuration`() {
        val detail = json.decodeFromString(
            WgDashboardResponse.serializer(WgDashboardConfigurationDetail.serializer()),
            """
            {"status":true,"data":{
              "configurationInfo":{"Name":"wg0","Status":true},
              "configurationPeers":[
                {"id":"KEY1","name":"phone","status":"running","allowed_ip":"10.0.0.2/32",
                 "latest_handshake":"00:01:12","total_receive":0.5,"total_sent":0.25,
                 "cumu_receive":1.0,"cumu_sent":0.75,"configuration":{"Name":"wg0","Status":true}}
              ],
              "configurationRestrictedPeers":[{"id":"KEY2","name":"laptop","status":"stopped"}]
            }}
            """.trimIndent()
        ).data!!

        val peer = detail.configurationPeers.single()
        assertEquals("phone", peer.displayName)
        assertTrue(peer.isConnected)
        assertEquals(1.5, peer.receivedGb, 0.001)
        assertEquals(1.0, peer.sentGb, 0.001)
        assertEquals(2.5, peer.totalGb, 0.001)
        assertEquals("00:01:12", peer.handshake)
        assertEquals("laptop", detail.configurationRestrictedPeers.single().displayName)
        assertFalse(detail.configurationRestrictedPeers.single().isConnected)
    }

    @Test
    fun `an unnamed peer falls back to its public key`() {
        val peer = json.decodeFromString(
            WgDashboardPeer.serializer(),
            """{"id":"ABCDEFGHIJKLMNOPQRSTUVWXYZ","name":"  "}"""
        )

        assertEquals("ABCDEFGHIJKL", peer.displayName)
    }

    @Test
    fun `a peer that never connected reports no handshake`() {
        val peer = json.decodeFromString(
            WgDashboardPeer.serializer(),
            """{"id":"KEY","latest_handshake":"No Handshake"}"""
        )

        assertNull(peer.handshake)
    }

    @Test
    fun `the overview totals every tunnel`() {
        val overview = WgDashboardOverview(
            version = "v4.2.3",
            configurations = listOf(
                WgDashboardConfiguration(
                    name = "wg0",
                    status = true,
                    connectedPeers = 2,
                    totalPeers = 4,
                    dataUsage = WgDashboardDataUsage(total = 10.0)
                ),
                WgDashboardConfiguration(
                    name = "wg1",
                    status = false,
                    connectedPeers = 0,
                    totalPeers = 2,
                    dataUsage = WgDashboardDataUsage(total = 2.5)
                )
            )
        )

        assertEquals(1, overview.activeTunnels)
        assertEquals(2, overview.connectedPeers)
        assertEquals(6, overview.totalPeers)
        assertEquals(12.5, overview.totalGb, 0.001)
    }
}
