package com.homelab.app.ui.proxmox
import com.homelab.app.R

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homelab.app.util.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxmoxConsoleScreen(
    node: String,
    vmid: Int,
    isQemu: Boolean,
    onNavigateBack: () -> Unit,
    viewModel: ProxmoxViewModel = hiltViewModel()
) {
    val vncTicketState by viewModel.vncTicketState.collectAsStateWithLifecycle()
    val consoleBrowserUrl by viewModel.consoleBrowserUrl.collectAsStateWithLifecycle()
    var webViewReady by remember { mutableStateOf(false) }
    var loadingError by remember { mutableStateOf<String?>(null) }
    var sslError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val consoleColor = Color(0xFF3F51B5)
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(node, vmid, isQemu) {
        viewModel.fetchVncTicket(node, vmid, isQemu)
    }

    fun openInBrowser(url: String) {
        // The external browser does not share the WebView cookie jar; Proxmox shows its login
        // screen first and opens the console afterwards.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }

    fun retryConsole() {
        viewModel.fetchVncTicket(node, vmid, isQemu)
        webViewReady = false
        loadingError = null
        sslError = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Console - ${if (isQemu) "VM" else "CT"} $vmid") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    consoleBrowserUrl?.let { url ->
                        IconButton(onClick = { openInBrowser(url) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.proxmox_console_open_in_browser)
                            )
                        }
                    }
                    IconButton(onClick = { retryConsole() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = vncTicketState) {
                is UiState.Idle,
                is UiState.Loading,
                is UiState.Offline -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Fetching VNC ticket...", color = Color.Gray)
                        }
                    }
                }
                is UiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = Color.Red,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.proxmox_failed_fetch_vnc_ticket), color = Color.Red, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Text(state.message, color = Color.Gray, fontSize = 14.sp)
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { retryConsole() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Retry")
                            }
                            consoleBrowserUrl?.let { url ->
                                Button(onClick = { openInBrowser(url) }) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.proxmox_console_open_in_browser))
                                }
                            }
                        }
                    }
                }
                is UiState.Success -> {
                    val ticketData = state.data

                    if (sslError) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFF9800),
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("SSL Certificate Error", color = Color(0xFFFF9800), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "The WebView could not verify the server's SSL certificate. This may happen with self-signed certificates.",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Try opening the console in your browser instead.",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(24.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { retryConsole() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Retry")
                                }
                                Button(onClick = { openInBrowser(consoleBrowserUrl ?: ticketData.buildConsoleUrl()) }) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.proxmox_console_open_in_browser))
                                }
                            }
                        }
                    } else if (loadingError != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = Color.Red,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("Failed to load console", color = Color.Red, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(8.dp))
                            Text(loadingError!!, color = Color.Gray, fontSize = 14.sp)
                            Spacer(Modifier.height(24.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { retryConsole() }) {
                                    Text("Retry")
                                }
                                Button(onClick = { openInBrowser(consoleBrowserUrl ?: ticketData.buildConsoleUrl()) }) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.proxmox_console_open_in_browser))
                                }
                            }
                        }
                    } else {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    settings.setSupportZoom(true)
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            loadingError = null
                                            sslError = false
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            webViewReady = true
                                        }

                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?
                                        ) {
                                            super.onReceivedError(view, request, error)
                                            if (request?.isForMainFrame == true) {
                                                loadingError = error?.description?.toString() ?: context.getString(R.string.error_unknown)
                                            }
                                        }

                                        @Suppress("DEPRECATION")
                                        override fun onReceivedSslError(
                                            view: WebView?,
                                            handler: android.webkit.SslErrorHandler?,
                                            error: android.net.http.SslError?
                                        ) {
                                            super.onReceivedSslError(view, handler, error)
                                            sslError = true
                                            handler?.cancel()
                                        }
                                    }

                                    // The cookie has to exist before the first request goes out,
                                    // so the console URL is loaded from the setCookie callback.
                                    val cookieManager = CookieManager.getInstance()
                                    cookieManager.setAcceptCookie(true)
                                    cookieManager.setAcceptThirdPartyCookies(this, true)
                                    val cookieOrigin = ProxmoxConsoleSupport.cookieOrigin(ticketData.baseUrl)
                                    if (cookieOrigin != null) {
                                        cookieManager.setCookie(
                                            cookieOrigin,
                                            ProxmoxConsoleSupport.cookieValue(ticketData.authCookie, ticketData.baseUrl)
                                        ) {
                                            cookieManager.flush()
                                            loadUrl(ticketData.buildConsoleUrl())
                                        }
                                    } else {
                                        loadUrl(ticketData.buildConsoleUrl())
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            update = { webView ->
                                // No-op updates; the WebView state is managed internally
                            }
                        )

                        if (!webViewReady && loadingError == null && !sslError) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(bottom = 100.dp),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = consoleColor.copy(alpha = 0.1f)
                                    )
                                ) {
                                    Text(
                                        "Loading console...",
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        color = consoleColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
