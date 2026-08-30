package com.homelab.app.data.remote.dto.unraid

/**
 * GraphQL documents for the official Unraid API (`unraid-api`, Unraid 7.x), served at
 * `<server>/graphql` and authenticated with an API key in the `x-api-key` header.
 *
 * The schema was reorganised between Unraid releases: mutations that live under a namespace
 * today (`docker { start }`) used to be flat root fields (`startContainer`). Every operation is
 * therefore a *list* of candidate documents, tried in order. When the server rejects one with a
 * schema error (unknown field/argument), the next candidate is attempted; any other error is
 * reported to the caller unchanged. Ids are inlined as quoted literals so the document does not
 * have to name a scalar type (`ID` vs `String` vs `PrefixedID`) that differs between releases.
 */
object UnraidGraphQl {

    // ---------- Queries ----------

    val SYSTEM: List<String> = listOf(
        """
        query {
          info {
            os { platform distro release hostname uptime }
            cpu { manufacturer brand cores threads }
            memory { total free used }
            versions { unraid kernel }
          }
        }
        """.trimIndent(),
        """
        query {
          info {
            os { platform distro release uptime }
            versions { unraid }
          }
        }
        """.trimIndent(),
        "query { info { os { platform distro release } } }"
    )

    val ARRAY: List<String> = listOf(
        """
        query {
          array {
            state
            capacity { kilobytes { free used total } disks { free used total } }
            parities { id idx name device size status temp numErrors type }
            disks { id idx name device size status temp numErrors fsSize fsFree fsUsed type }
            caches { id idx name device size status temp numErrors fsSize fsFree fsUsed type }
          }
        }
        """.trimIndent(),
        """
        query {
          array {
            state
            capacity { kilobytes { free used total } }
            disks { id name device size status temp type }
          }
        }
        """.trimIndent(),
        "query { array { state } }"
    )

    val SHARES: List<String> = listOf(
        """
        query {
          shares { name comment free used size }
        }
        """.trimIndent(),
        """
        query {
          shares { name free used }
        }
        """.trimIndent(),
        "query { shares { name } }"
    )

    val DOCKER: List<String> = listOf(
        """
        query {
          docker { containers { id names image state status autoStart } }
        }
        """.trimIndent(),
        """
        query {
          docker { containers { id names image state status } }
        }
        """.trimIndent(),
        "query { docker { containers { id names state } } }"
    )

    val VMS: List<String> = listOf(
        """
        query {
          vms { domain { uuid name state } }
        }
        """.trimIndent()
    )

    val NOTIFICATIONS: List<String> = listOf(
        """
        query {
          notifications {
            overview { unread { info warning alert total } }
            list(filter: { type: UNREAD, offset: 0, limit: 30 }) {
              id title subject description importance timestamp
            }
          }
        }
        """.trimIndent(),
        """
        query {
          notifications { overview { unread { info warning alert total } } }
        }
        """.trimIndent(),
        "query { notifications { overview { unread { total } } } }"
    )

    /** Cheapest document that still proves both connectivity and a valid API key. */
    val PING: List<String> = listOf(
        "query { info { os { platform } } }",
        "query { online }"
    )

    // ---------- Mutations ----------

    fun startContainer(id: String): List<String> = listOf(
        "mutation { docker { start(id: ${quote(id)}) { id state } } }",
        "mutation { startContainer(id: ${quote(id)}) { id state } }"
    )

    fun stopContainer(id: String): List<String> = listOf(
        "mutation { docker { stop(id: ${quote(id)}) { id state } } }",
        "mutation { stopContainer(id: ${quote(id)}) { id state } }"
    )

    /**
     * Only newer builds expose a restart mutation; [UnraidRepository] falls back to a
     * stop-then-start sequence when every candidate here is rejected as unknown.
     */
    fun restartContainer(id: String): List<String> = listOf(
        "mutation { docker { restart(id: ${quote(id)}) { id state } } }"
    )

    fun startVm(id: String): List<String> = listOf(
        "mutation { vm { start(id: ${quote(id)}) } }",
        "mutation { startVm(id: ${quote(id)}) }"
    )

    fun stopVm(id: String): List<String> = listOf(
        "mutation { vm { stop(id: ${quote(id)}) } }",
        "mutation { stopVm(id: ${quote(id)}) }"
    )

    fun pauseVm(id: String): List<String> = listOf(
        "mutation { vm { pause(id: ${quote(id)}) } }",
        "mutation { pauseVm(id: ${quote(id)}) }"
    )

    fun resumeVm(id: String): List<String> = listOf(
        "mutation { vm { resume(id: ${quote(id)}) } }",
        "mutation { resumeVm(id: ${quote(id)}) }"
    )

    fun forceStopVm(id: String): List<String> = listOf(
        "mutation { vm { forceStop(id: ${quote(id)}) } }",
        "mutation { forceStopVm(id: ${quote(id)}) }"
    )

    fun rebootVm(id: String): List<String> = listOf(
        "mutation { vm { reboot(id: ${quote(id)}) } }",
        "mutation { rebootVm(id: ${quote(id)}) }"
    )

    val START_ARRAY: List<String> = listOf(
        "mutation { array { setState(input: { desiredState: START }) { state } } }",
        "mutation { startArray { state } }"
    )

    val STOP_ARRAY: List<String> = listOf(
        "mutation { array { setState(input: { desiredState: STOP }) { state } } }",
        "mutation { stopArray { state } }"
    )

    fun startParityCheck(correcting: Boolean): List<String> = listOf(
        "mutation { parityCheck { start(correct: $correcting) } }",
        "mutation { startParityCheck(correct: $correcting) }"
    )

    val CANCEL_PARITY_CHECK: List<String> = listOf(
        "mutation { parityCheck { cancel } }",
        "mutation { cancelParityCheck }"
    )

    /**
     * Marks a GraphQL error as "this build does not know that field/argument", which is the
     * signal to fall through to the next candidate document rather than surface an error.
     */
    fun isSchemaMismatch(message: String): Boolean {
        val normalized = message.lowercase()
        return SCHEMA_MISMATCH_MARKERS.any { normalized.contains(it) }
    }

    private val SCHEMA_MISMATCH_MARKERS = listOf(
        "cannot query field",
        "unknown field",
        "unknown argument",
        "unknown type",
        "is not defined by type",
        "did you mean",
        "no field named",
        "must not have a selection since type"
    )

    private fun quote(raw: String): String {
        val escaped = raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
