package com.homelab.app.ui.proxmox

import com.homelab.app.domain.model.ServiceInstance
import java.net.URI

/**
 * Helpers for the embedded Proxmox noVNC console.
 *
 * The console is the regular Proxmox web UI running inside a WebView, so it authenticates
 * exactly like a browser does: through the `PVEAuthCookie` session ticket issued by
 * `/api2/json/access/ticket`. Neither an API token nor the short-lived ticket returned by
 * `vncproxy` is accepted there - the vncproxy ticket is only the password for the VNC
 * websocket itself.
 */
object ProxmoxConsoleSupport {

    /**
     * Returns the session ticket that has to be presented as `PVEAuthCookie`, or `null`
     * when the instance cannot open the web console (API-token authentication).
     */
    fun authCookie(instance: ServiceInstance?): String? =
        instance?.token?.takeIf { it.isNotBlank() }

    /** True when the instance authenticates with an API token instead of a login ticket. */
    fun usesApiToken(instance: ServiceInstance?): Boolean =
        !instance?.apiKey.isNullOrBlank()

    /**
     * The origin the cookie has to be stored for, e.g. `https://pve.example.com:8006`.
     * Returns `null` for URLs without a scheme or authority, which cannot carry a cookie.
     */
    fun cookieOrigin(baseUrl: String): String? {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: return null
        val authority = uri.authority?.takeIf { it.isNotBlank() } ?: return null
        return "$scheme://$authority"
    }

    /** URL of the Proxmox noVNC page for one guest. */
    fun consoleUrl(baseUrl: String, node: String, vmid: Int, isQemu: Boolean): String {
        val root = baseUrl.trimEnd('/')
        return "$root/?console=${if (isQemu) "kvm" else "lxc"}&novnc=1&vmid=$vmid&node=$node&resize=off"
    }

    /** The `Set-Cookie` style value handed to [android.webkit.CookieManager]. */
    fun cookieValue(authCookie: String, baseUrl: String): String {
        val secure = if (baseUrl.trim().startsWith("https", ignoreCase = true)) "; Secure" else ""
        return "PVEAuthCookie=$authCookie; Path=/$secure"
    }
}
