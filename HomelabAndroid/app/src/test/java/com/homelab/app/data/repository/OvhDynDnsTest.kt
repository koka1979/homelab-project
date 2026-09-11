package com.homelab.app.data.repository

import com.homelab.app.data.remote.TlsClientSelector
import com.homelab.app.domain.dyndns.DynDnsAddresses
import com.homelab.app.domain.dyndns.DynDnsRecordType
import com.homelab.app.domain.dyndns.DynDnsUpdateOutcome
import com.homelab.app.domain.dyndns.DynDnsUpdateReport
import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.util.ServiceType
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OvhDynDnsTest {

    private fun parse(body: String?, code: Int = 200, type: DynDnsRecordType = DynDnsRecordType.IPV4) =
        parseDynDnsResponse(type = type, code = code, body = body, address = "1.2.3.4")

    @Test
    fun `good means the record now points at the address the server echoes`() {
        val outcome = parse("good 203.0.113.7\n")

        assertTrue(outcome is DynDnsUpdateOutcome.Updated)
        assertEquals("203.0.113.7", (outcome as DynDnsUpdateOutcome.Updated).address)
    }

    @Test
    fun `nochg is a success, not a failure`() {
        // OVH answers nochg when the record already holds the address; sending it again counts
        // as abuse, so this must not be treated as an error that triggers a retry.
        val outcome = parse("nochg 203.0.113.7")

        assertTrue(outcome is DynDnsUpdateOutcome.Unchanged)
        assertEquals("203.0.113.7", (outcome as DynDnsUpdateOutcome.Unchanged).address)
    }

    @Test
    fun `an answer without an address falls back to the address that was sent`() {
        val outcome = parse("good")

        assertEquals("1.2.3.4", (outcome as DynDnsUpdateOutcome.Updated).address)
    }

    @Test
    fun `every refusal keeps its keyword`() {
        val cases = mapOf(
            "badauth" to "badauth",
            "nohost" to "nohost",
            "notfqdn" to "notfqdn",
            "abuse" to "abuse",
            "badsys" to "badsys",
            "badagent" to "badagent",
            "dnserr" to "dnserr",
            "911" to "911"
        )

        cases.forEach { (body, expected) ->
            val outcome = parse(body)
            assertTrue("$body should fail", outcome is DynDnsUpdateOutcome.Failed)
            assertEquals(expected, (outcome as DynDnsUpdateOutcome.Failed).code)
            assertTrue("$body needs a readable message", outcome.message.isNotBlank())
        }
    }

    @Test
    fun `an http 401 without a body is still recognised as a rejected password`() {
        val outcome = parse(body = "", code = 401)

        assertEquals("badauth", (outcome as DynDnsUpdateOutcome.Failed).code)
    }

    @Test
    fun `the message of a missing record names the record type`() {
        val outcome = parse("nohost", type = DynDnsRecordType.IPV6)

        assertTrue((outcome as DynDnsUpdateOutcome.Failed).message.contains("AAAA"))
    }

    @Test
    fun `an unexpected body is reported instead of being read as success`() {
        val outcome = parse("<html>Service Unavailable</html>", code = 503)

        assertTrue(outcome is DynDnsUpdateOutcome.Failed)
        assertTrue((outcome as DynDnsUpdateOutcome.Failed).message.contains("503"))
    }

    @Test
    fun `an empty answer is a failure, not a silent success`() {
        val outcome = parse(null)

        assertTrue(outcome is DynDnsUpdateOutcome.Failed)
        assertEquals("unknown", (outcome as DynDnsUpdateOutcome.Failed).code)
    }

    @Test
    fun `an address is only accepted for its own family`() {
        assertTrue(matchesFamily("203.0.113.7", DynDnsRecordType.IPV4))
        assertFalse(matchesFamily("203.0.113.7", DynDnsRecordType.IPV6))
        assertTrue(matchesFamily("2001:db8::1", DynDnsRecordType.IPV6))
        assertFalse(matchesFamily("2001:db8::1", DynDnsRecordType.IPV4))
    }

    @Test
    fun `anything that is not a literal address is rejected`() {
        // Guards against an echo service answering with an error page: a host name here would
        // be published as the record's address.
        assertFalse(matchesFamily("", DynDnsRecordType.IPV4))
        assertFalse(matchesFamily("service unavailable", DynDnsRecordType.IPV4))
        assertFalse(matchesFamily("example.com", DynDnsRecordType.IPV4))
        assertFalse(matchesFamily("example.com", DynDnsRecordType.IPV6))
        assertFalse(matchesFamily("999.1.1.1", DynDnsRecordType.IPV4))
        assertFalse(matchesFamily("1.2.3", DynDnsRecordType.IPV4))
        assertFalse(matchesFamily("1.2.3.4.5", DynDnsRecordType.IPV4))
        assertFalse(matchesFamily("<html>", DynDnsRecordType.IPV6))
        // An IPv4-mapped literal is an IPv4 address wearing IPv6 syntax - it has no business in
        // an AAAA record, and the JVM reads it as IPv4 too.
        assertFalse(matchesFamily("::ffff:203.0.113.7", DynDnsRecordType.IPV6))
    }

    @Test
    fun `the host name is read from the instance url in either spelling`() {
        fun instance(url: String) = ServiceInstance(
            id = "dyn-1",
            type = ServiceType.OVH_DYNDNS,
            label = "Home",
            url = url
        )

        assertEquals("home.example.com", hostnameOf(instance("https://home.example.com")))
        assertEquals("home.example.com", hostnameOf(instance("https://home.example.com/")))
        assertEquals("home.example.com", hostnameOf(instance("home.example.com")))
        assertNull(hostnameOf(instance("   ")))
    }

    @Test
    fun `an unchanged address is not sent again`() = runTest {
        // OVH blocks a host name that keeps receiving the same address, so a run that has
        // nothing new to say must not touch the network at all.
        val repository = OvhDynDnsRepository(mockk<TlsClientSelector>(relaxed = true))
        val instance = ServiceInstance(
            id = "dyn-1",
            type = ServiceType.OVH_DYNDNS,
            label = "Home",
            url = "https://home.example.com",
            username = "example.com-dyn",
            password = "secret"
        )

        val report = repository.update(
            instance = instance,
            addresses = DynDnsAddresses(ipv4 = "203.0.113.7", ipv6Error = "no IPv6"),
            types = DynDnsRecordType.entries.toSet(),
            lastSent = mapOf(DynDnsRecordType.IPV4 to "203.0.113.7")
        )

        val ipv4 = report.outcomes.single { it.type == DynDnsRecordType.IPV4 }
        val ipv6 = report.outcomes.single { it.type == DynDnsRecordType.IPV6 }
        assertTrue(ipv4 is DynDnsUpdateOutcome.Unchanged)
        // A family without an address on this network keeps the reason it was skipped for.
        assertTrue(ipv6 is DynDnsUpdateOutcome.Skipped)
        assertEquals("no IPv6", (ipv6 as DynDnsUpdateOutcome.Skipped).reason)
        assertTrue(report.succeeded)
    }

    @Test
    fun `an instance without credentials is refused before any request`() = runTest {
        val repository = OvhDynDnsRepository(mockk<TlsClientSelector>(relaxed = true))
        val instance = ServiceInstance(
            id = "dyn-1",
            type = ServiceType.OVH_DYNDNS,
            label = "Home",
            url = "https://home.example.com"
        )

        try {
            repository.update(
                instance = instance,
                addresses = DynDnsAddresses(ipv4 = "203.0.113.7"),
                types = setOf(DynDnsRecordType.IPV4)
            )
            junit.framework.TestCase.fail("expected missing credentials to be refused")
        } catch (error: DynDnsException) {
            assertTrue(error.message!!.isNotBlank())
        }
    }

    @Test
    fun `a report counts as successful only when no family failed`() {
        val mixed = DynDnsUpdateReport(
            hostname = "home.example.com",
            outcomes = listOf(
                DynDnsUpdateOutcome.Updated(DynDnsRecordType.IPV4, "203.0.113.7"),
                DynDnsUpdateOutcome.Failed(DynDnsRecordType.IPV6, "nohost", "no AAAA record")
            )
        )
        val skipped = DynDnsUpdateReport(
            hostname = "home.example.com",
            outcomes = listOf(
                DynDnsUpdateOutcome.Updated(DynDnsRecordType.IPV4, "203.0.113.7"),
                DynDnsUpdateOutcome.Skipped(DynDnsRecordType.IPV6, "no IPv6 on this network")
            )
        )

        assertFalse(mixed.succeeded)
        assertEquals(1, mixed.failures.size)
        // A family the network simply does not have must not make the run look failed.
        assertTrue(skipped.succeeded)
    }
}
