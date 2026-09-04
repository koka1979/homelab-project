package com.homelab.app.ui.proxmox

import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.util.ServiceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxmoxConsoleSupportTest {

    private fun instance(token: String = "", apiKey: String? = null) = ServiceInstance(
        id = "pve-1",
        type = ServiceType.PROXMOX,
        label = "Proxmox",
        url = "https://pve.example.com:8006",
        token = token,
        apiKey = apiKey
    )

    @Test
    fun `session ticket is used as auth cookie`() {
        assertEquals("PVE:root@pam:64F0", ProxmoxConsoleSupport.authCookie(instance(token = "PVE:root@pam:64F0")))
    }

    @Test
    fun `api token instances have no auth cookie`() {
        val apiTokenInstance = instance(apiKey = "root@pam!app=uuid")
        assertNull(ProxmoxConsoleSupport.authCookie(apiTokenInstance))
        assertTrue(ProxmoxConsoleSupport.usesApiToken(apiTokenInstance))
    }

    @Test
    fun `ticket instances are not reported as api token`() {
        assertFalse(ProxmoxConsoleSupport.usesApiToken(instance(token = "PVE:root@pam:64F0")))
    }

    @Test
    fun `missing instance yields no auth cookie`() {
        assertNull(ProxmoxConsoleSupport.authCookie(null))
    }

    @Test
    fun `cookie origin keeps scheme host and port`() {
        assertEquals(
            "https://pve.example.com:8006",
            ProxmoxConsoleSupport.cookieOrigin("https://pve.example.com:8006/")
        )
    }

    @Test
    fun `cookie origin is null without a host`() {
        assertNull(ProxmoxConsoleSupport.cookieOrigin(""))
        assertNull(ProxmoxConsoleSupport.cookieOrigin("/?console=kvm&novnc=1"))
    }

    @Test
    fun `cookie value is marked secure only for https`() {
        assertEquals(
            "PVEAuthCookie=TICKET; Path=/; Secure",
            ProxmoxConsoleSupport.cookieValue("TICKET", "https://pve.example.com:8006")
        )
        assertEquals(
            "PVEAuthCookie=TICKET; Path=/",
            ProxmoxConsoleSupport.cookieValue("TICKET", "http://192.168.1.10:8006")
        )
    }

    @Test
    fun `console url targets the noVNC page of the guest`() {
        val vm = ProxmoxVncTicketData(
            ticket = "PVEVNC:1234",
            authCookie = "PVE:root@pam:64F0",
            port = 5900,
            baseUrl = "https://pve.example.com:8006",
            node = "pve",
            vmid = 101,
            isQemu = true
        )
        assertEquals(
            "https://pve.example.com:8006/?console=kvm&novnc=1&vmid=101&node=pve&resize=off",
            vm.buildConsoleUrl()
        )
        assertEquals(
            "https://pve.example.com:8006/?console=lxc&novnc=1&vmid=101&node=pve&resize=off",
            vm.copy(isQemu = false).buildConsoleUrl()
        )
    }
}
