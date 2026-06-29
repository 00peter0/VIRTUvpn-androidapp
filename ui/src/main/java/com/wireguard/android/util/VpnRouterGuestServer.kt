/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.wireguard.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

object VpnRouterGuestServer {
    const val PORT = 8787
    private const val TAG = "VirtuVPN/GuestServer"
    private const val INSTALL_URL = "https://vcs.virtucomputing.com/api/mobile/android/apk/guest"
    private const val SECURE_BROWSER_URL = "virtuvpn://secure-browser"
    private const val WEBSITE_URL = "https://vcs.virtucomputing.com/"
    private const val REGULAR_BROWSER_BYPASS_MS = 30 * 60 * 1000L
    private const val SECURE_WEB_VALIDATION_MS = 30 * 60 * 1000L
    private var serverJob: Job? = null
    private var serverScope: CoroutineScope? = null
    private val secureWebClients = ConcurrentHashMap<String, Long>()
    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start(context: Context, scope: CoroutineScope) {
        if (serverJob?.isActive == true) return
        val appContext = context.applicationContext
        serverScope = scope
        serverJob = scope.launch(Dispatchers.IO) {
            runCatching {
                ServerSocket(PORT, 16, InetAddress.getByName("0.0.0.0")).use { socket ->
                    serverSocket = socket
                    while (isActive) {
                        val client = runCatching { socket.accept() }.getOrNull() ?: continue
                        launch { handleClient(appContext, client) }
                    }
                }
            }.onFailure {
                if (isActive) Log.e(TAG, "Guest server stopped", it)
            }
            serverSocket = null
        }
    }

    fun stop() {
        serverSocket?.close()
        serverJob?.cancel()
        serverJob = null
        serverScope = null
        serverSocket = null
    }

    private fun handleClient(context: Context, socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine().orEmpty()
            val path = requestLine.split(' ').getOrNull(1).orEmpty().ifBlank { "/" }
            when {
                path.startsWith("/virtuvpn-router/status") -> respondJson(client.getOutputStream())
                path.startsWith("/brand/virtuvpn-android-icon.png") -> respondIcon(context, client.getOutputStream())
                path.startsWith("/virtuvpn-router/ignore") -> {
                    val clientIp = client.inetAddress.hostAddress.orEmpty()
                    VpnRouterManager.allowGuestPortalBypass(clientIp)
                    serverScope?.launch(Dispatchers.IO) {
                        delay(REGULAR_BROWSER_BYPASS_MS)
                        VpnRouterManager.removeGuestPortalBypass(clientIp)
                    }
                    respondHtml(client.getOutputStream(), ignoredHtml(context))
                }
                path.startsWith("/virtuvpn-router/secure-browser") -> respondRedirect(client.getOutputStream(), SECURE_BROWSER_URL)
                path.startsWith("/virtuvpn-router/secure-web/proxy") -> respondSecureWebProxy(client.getOutputStream(), path)
                path.startsWith("/virtuvpn-router/secure-web") -> {
                    markSecureWebClient(client.inetAddress.hostAddress.orEmpty())
                    respondHtml(client.getOutputStream(), secureWebHtml(context, queryParam(path, "url")))
                }
                path.startsWith("/virtuvpn-router/install") -> respondRedirect(client.getOutputStream(), INSTALL_URL)
                path.startsWith("/virtuvpn-router/site") -> respondRedirect(client.getOutputStream(), secureWebPath(WEBSITE_URL))
                isCaptiveProbe(path) && isSecureWebClient(client.inetAddress.hostAddress.orEmpty()) -> respondNoContent(client.getOutputStream())
                isCaptiveProbe(path) -> respondRedirect(client.getOutputStream(), portalUrl(client))
                else -> respondHtml(client.getOutputStream(), portalHtml(context, path))
            }
        }
    }

    private fun respondJson(output: OutputStream) {
        val body = """{"product":"VirtuVPN","router":true,"guestProtocol":1}"""
        writeResponse(output, "200 OK", "application/json; charset=utf-8", body)
    }

    private fun respondRedirect(output: OutputStream, location: String) {
        val body = "Redirecting"
        val response = buildString {
            append("HTTP/1.1 302 Found\r\n")
            append("Location: ").append(location).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Pragma: no-cache\r\n")
            append("Expires: 0\r\n")
            append("Content-Length: ").append(body.toByteArray().size).append("\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
        }
        output.write(response.toByteArray())
        output.flush()
    }

    private fun respondNoContent(output: OutputStream) {
        val response = buildString {
            append("HTTP/1.1 204 No Content\r\n")
            append("Cache-Control: no-store\r\n")
            append("Pragma: no-cache\r\n")
            append("Expires: 0\r\n")
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(response.toByteArray())
        output.flush()
    }

    private fun respondHtml(output: OutputStream, html: String) {
        writeResponse(output, "200 OK", "text/html; charset=utf-8", html)
    }

    private fun respondIcon(context: Context, output: OutputStream) {
        val icon = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        if (icon == null) {
            writeResponse(output, "404 Not Found", "text/plain; charset=utf-8", "Not found")
            return
        }
        val buffer = ByteArrayOutputStream()
        icon.compress(Bitmap.CompressFormat.PNG, 100, buffer)
        writeResponse(output, "200 OK", "image/png", buffer.toByteArray())
    }

    private fun respondSecureWebProxy(output: OutputStream, path: String) {
        val target = normalizeSecureWebUrl(queryParam(path, "url"))
        if (target == null) {
            writeResponse(output, "400 Bad Request", "text/html; charset=utf-8", secureWebErrorHtml("Enter a valid HTTPS address."))
            return
        }
        if (!isAllowedSecureWebUrl(target)) {
            writeResponse(output, "403 Forbidden", "text/html; charset=utf-8", secureWebErrorHtml("Public HTTP is blocked. Use HTTPS or a private router address."))
            return
        }
        runCatching {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 9000
                readTimeout = 15000
                useCaches = false
                setRequestProperty("User-Agent", "VirtuVPN Router Secure Web")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            }
            val status = connection.responseCode
            val redirect = connection.getHeaderField("Location")
            if (status in 300..399 && !redirect.isNullOrBlank()) {
                val resolved = URI(target).resolve(redirect).toString()
                respondRedirect(output, secureWebProxyPath(resolved))
                connection.disconnect()
                return
            }
            val contentType = connection.contentType ?: "application/octet-stream"
            val stream = if (status >= 400) connection.errorStream ?: connection.inputStream else connection.inputStream
            val bytes = stream.use { it.readBytes() }
            connection.disconnect()
            if (contentType.lowercase(Locale.US).contains("text/html")) {
                val html = bytes.toString(Charsets.UTF_8)
                writeResponse(output, "200 OK", "text/html; charset=utf-8", rewriteSecureWebHtml(target, html))
            } else {
                writeResponse(output, "200 OK", safeContentType(contentType), bytes)
            }
        }.onFailure { error ->
            writeResponse(output, "502 Bad Gateway", "text/html; charset=utf-8", secureWebErrorHtml(error.message ?: "Unable to load page."))
        }
    }

    private fun writeResponse(output: OutputStream, status: String, contentType: String, body: String) {
        writeResponse(output, status, contentType, body.toByteArray())
    }

    private fun writeResponse(output: OutputStream, status: String, contentType: String, bytes: ByteArray) {
        val response = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Pragma: no-cache\r\n")
            append("Expires: 0\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(response.toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun portalHtml(context: Context, path: String): String {
        val next = escape(URLDecoder.decode(path, "UTF-8"))
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover" />
              <title>VirtuVPN Router</title>
              <style>
                :root{color-scheme:dark;--bg:#030607;--panel:#07100d;--line:rgba(255,255,255,.13);--text:#eefdf7;--muted:#8aa49a;--green:#10b981;--cyan:#67e8f9}
                *{box-sizing:border-box} body{margin:0;min-height:100svh;display:grid;place-items:center;background:radial-gradient(circle at 50% 0%,rgba(16,185,129,.18),transparent 36%),linear-gradient(180deg,#07100d 0%,#020403 100%);color:var(--text);font-family:Inter,ui-sans-serif,system-ui,-apple-system,Segoe UI,sans-serif;padding:22px}
                main{width:min(430px,100%);border:1px solid var(--line);border-radius:18px;background:linear-gradient(180deg,rgba(12,25,20,.92),rgba(3,7,6,.96));padding:24px 20px 22px;text-align:center;box-shadow:0 24px 70px rgba(0,0,0,.55),inset 0 1px 0 rgba(255,255,255,.09),inset 0 -2px 0 rgba(0,0,0,.45)}
                .iconButton{width:124px;height:124px;margin:0 auto 18px;border-radius:26px;border:1px solid rgba(110,231,183,.6);background:linear-gradient(180deg,rgba(16,185,129,.28),rgba(3,7,6,.98));display:grid;place-items:center;box-shadow:0 18px 42px rgba(0,0,0,.52),0 0 34px rgba(16,185,129,.22),inset 0 1px 0 rgba(255,255,255,.18),inset 0 -4px 0 rgba(0,0,0,.38);overflow:hidden}
                .iconButton img{width:124px;height:124px;object-fit:cover;display:block;filter:drop-shadow(0 3px 8px rgba(0,0,0,.45))}
                .eyebrow{margin:0 0 6px;text-transform:uppercase;letter-spacing:.18em;color:rgba(110,231,183,.86);font-size:11px;font-weight:800}.title{margin:0;font-size:25px;line-height:1.08;font-weight:850;letter-spacing:0}.copy{margin:12px auto 0;max-width:330px;color:var(--muted);font-size:14px;line-height:1.5}.actions{display:grid;gap:10px;margin-top:20px}.primary,.secondary{display:flex;align-items:center;justify-content:center;min-height:48px;border-radius:13px;text-decoration:none;font-weight:850;font-size:15px;letter-spacing:0}.button{appearance:none;width:100%;cursor:pointer}.primary{background:linear-gradient(180deg,#34d399,#059669);color:#02120b;border:1px solid rgba(167,243,208,.72);box-shadow:0 14px 28px rgba(5,150,105,.24),inset 0 1px 0 rgba(255,255,255,.38),inset 0 -2px 0 rgba(0,0,0,.22)}.secondary{color:#cffafe;border:1px solid rgba(103,232,249,.28);background:rgba(8,145,178,.08);box-shadow:inset 0 1px 0 rgba(255,255,255,.08)}
                .status{margin-top:14px;color:rgba(238,253,247,.45);font-size:12px}.check{display:flex;gap:10px;text-align:left;color:var(--muted);font-size:13px;line-height:1.45;margin:2px 0 0}.check input{width:18px;height:18px;margin:1px 0 0;accent-color:#10b981}.muted{margin-top:10px;color:rgba(238,253,247,.38);font-size:11px;overflow-wrap:anywhere}
              </style>
            </head>
            <body>
              <main>
                <div class="iconButton" aria-hidden="true"><img src="/brand/virtuvpn-android-icon.png" alt="" /></div>
                <p class="eyebrow">VirtuVPN Router</p>
                <h1 class="title">Open secure browsing</h1>
                <p class="copy">This hotspot is routed through VPN. Use Router Secure Web to browse through the router without exposing client DNS or local browser network details to the destination site.</p>
                <div class="actions">
                  <a class="primary" href="/virtuvpn-router/secure-web">Open Router Secure Web</a>
                  <a class="secondary" href="${secureWebPath(WEBSITE_URL)}">vcs.virtucomputing.com</a>
                  <form method="get" action="/virtuvpn-router/ignore">
                    <label class="check"><input required type="checkbox"> <span>I do not need secure browsing and want to use a regular browser on this device.</span></label>
                    <button class="secondary button" type="submit">OK, continue without protection</button>
                  </form>
                </div>
                <div class="status">Router guest protection is active.</div>
                <p class="muted">Original request: ${next}</p>
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun ignoredHtml(context: Context): String =
        """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover" />
              <title>VirtuVPN Router</title>
              <style>
                :root{color-scheme:dark;--line:rgba(255,255,255,.13);--text:#eefdf7;--muted:#8aa49a}
                *{box-sizing:border-box} body{margin:0;min-height:100svh;display:grid;place-items:center;background:radial-gradient(circle at 50% 0%,rgba(16,185,129,.18),transparent 36%),linear-gradient(180deg,#07100d 0%,#020403 100%);color:var(--text);font-family:Inter,ui-sans-serif,system-ui,-apple-system,Segoe UI,sans-serif;padding:22px}
                main{width:min(430px,100%);border:1px solid var(--line);border-radius:18px;background:linear-gradient(180deg,rgba(12,25,20,.92),rgba(3,7,6,.96));padding:24px 20px 22px;text-align:center;box-shadow:0 24px 70px rgba(0,0,0,.55),inset 0 1px 0 rgba(255,255,255,.09),inset 0 -2px 0 rgba(0,0,0,.45)}
                .iconButton{width:124px;height:124px;margin:0 auto 18px;border-radius:26px;border:1px solid rgba(110,231,183,.6);background:linear-gradient(180deg,rgba(16,185,129,.28),rgba(3,7,6,.98));display:grid;place-items:center;box-shadow:0 18px 42px rgba(0,0,0,.52),0 0 34px rgba(16,185,129,.22),inset 0 1px 0 rgba(255,255,255,.18),inset 0 -4px 0 rgba(0,0,0,.38);overflow:hidden}.iconButton img{width:124px;height:124px;object-fit:cover;display:block;filter:drop-shadow(0 3px 8px rgba(0,0,0,.45))}
                .eyebrow{margin:0 0 6px;text-transform:uppercase;letter-spacing:.18em;color:rgba(110,231,183,.86);font-size:11px;font-weight:800}.title{margin:0;font-size:25px;line-height:1.08;font-weight:850;letter-spacing:0}.copy{margin:12px auto 0;max-width:330px;color:var(--muted);font-size:14px;line-height:1.5}.actions{display:grid;gap:10px;margin-top:20px}.primary{display:flex;align-items:center;justify-content:center;min-height:48px;border-radius:13px;text-decoration:none;font-weight:850;font-size:15px;letter-spacing:0;background:linear-gradient(180deg,#34d399,#059669);color:#02120b;border:1px solid rgba(167,243,208,.72);box-shadow:0 14px 28px rgba(5,150,105,.24),inset 0 1px 0 rgba(255,255,255,.38),inset 0 -2px 0 rgba(0,0,0,.22)}
              </style>
            </head>
            <body>
              <main>
                <div class="iconButton" aria-hidden="true"><img src="/brand/virtuvpn-android-icon.png" alt="" /></div>
                <p class="eyebrow">VirtuVPN Router</p>
                <h1 class="title">Regular browser enabled</h1>
                <p class="copy">Secure Browser prompts are disabled for this hotspot session. Continue only for browsing where regular browser privacy is acceptable.</p>
                <div class="actions"><a class="primary" href="http://neverssl.com">OK</a></div>
              </main>
            </body>
            </html>
        """.trimIndent()

    private fun secureWebHtml(context: Context, initialUrl: String?): String {
        val normalized = normalizeSecureWebUrl(initialUrl) ?: GOOGLE_URL
        val safeInitial = escape(normalized)
        val frameSrc = escape(secureWebProxyPath(normalized))
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover" />
              <title>VirtuVPN Secure Browser</title>
              <style>
                :root{color-scheme:dark;--bg:#071018;--button:#101b24;--button2:#142331;--text:#fff;--muted:#8899aa;--line:rgba(255,255,255,.12);--green:#34d399}
                *{box-sizing:border-box} html,body{margin:0;height:100%;overflow:hidden;background:linear-gradient(180deg,#071018 0%,#02060a 100%);color:var(--text);font-family:Inter,ui-sans-serif,system-ui,-apple-system,Segoe UI,sans-serif}
                .app{height:100%;display:flex;flex-direction:column;background:radial-gradient(circle at 50% 0%,rgba(16,185,129,.16),transparent 34%),#071018}
                .top{background:#071018;padding:10px;border-bottom:1px solid var(--line)}
                .bar{display:flex;align-items:center;gap:8px}.url{height:44px;min-width:0;flex:1;border:1px solid var(--line);border-radius:8px;background:linear-gradient(180deg,#172633,#0b141d);color:#fff;padding:0 12px;font-size:14px;outline:none}.url::placeholder{color:#8899aa}
                .btn{height:44px;border:1px solid var(--line);border-radius:8px;background:linear-gradient(180deg,#172633,#0b141d);color:#fff;padding:0 15px;font-size:14px;font-weight:800;text-decoration:none;display:inline-flex;align-items:center;justify-content:center;cursor:pointer;white-space:nowrap}
                .quick{display:flex;gap:8px;overflow-x:auto;padding-top:8px;scrollbar-width:none}.quick::-webkit-scrollbar{display:none}.chip{min-width:96px;height:40px;border:1px solid var(--line);border-radius:8px;background:linear-gradient(180deg,#172633,#0b141d);color:#fff;text-decoration:none;font-size:13px;font-weight:800;display:flex;align-items:center;justify-content:center;padding:0 16px}
                .viewport{position:relative;flex:1;min-height:0;background:#fff}.frame{width:100%;height:100%;border:0;background:#fff}.blocker{position:absolute;inset:0;display:none;place-items:center;text-align:center;padding:28px;background:linear-gradient(180deg,#071018 0%,#02060a 100%);color:#fff}.blocker h1{font-size:22px;margin:0 0 10px}.blocker p{margin:0;color:#afc0cc;font-size:14px;line-height:1.5;max-width:360px}
                .pad{position:absolute;left:50%;bottom:18px;transform:translateX(-50%);display:flex;gap:6px;padding:6px;border:1px solid var(--line);border-radius:10px;background:linear-gradient(180deg,rgba(23,38,51,.94),rgba(7,16,24,.94));box-shadow:0 8px 26px rgba(0,0,0,.36)}.pad button{width:82px;height:40px;border:1px solid var(--line);border-radius:8px;background:linear-gradient(180deg,#172633,#0b141d);color:#fff;font-size:13px;font-weight:800}.status{display:flex;align-items:center;gap:7px;margin-top:8px;color:#afc0cc;font-size:12px}.dot{width:8px;height:8px;border-radius:999px;background:var(--green);box-shadow:0 0 12px rgba(52,211,153,.65)}
                @media(max-width:420px){.btn{padding:0 12px}.save{display:none}.pad button{width:74px}.top{padding:8px}}
              </style>
            </head>
            <body>
              <div class="app">
                <div class="top">
                  <form class="bar" id="goForm">
                    <input class="url" id="urlInput" name="url" value="$safeInitial" placeholder="https://example.com" inputmode="url" autocomplete="off" />
                    <button class="btn" type="submit">Go</button>
                    <button class="btn save" type="button" id="saveButton">Save</button>
                  </form>
                  <div class="quick">
                    <a class="chip" href="/virtuvpn-router/secure-web?url=${urlParam(GOOGLE_URL)}">Google</a>
                    <a class="chip" href="/virtuvpn-router/secure-web?url=${urlParam("https://dnscheck.tools/")}">DNS Check</a>
                    <a class="chip" href="${secureWebPath(WEBSITE_URL)}">vcs.virtucomputing.com</a>
                  </div>
                  <div class="status"><span class="dot"></span><span>Router Secure Web is routed through VPN.</span></div>
                </div>
                <div class="viewport">
                  <iframe class="frame" id="secureFrame" sandbox="allow-forms allow-scripts allow-popups allow-downloads" src="$frameSrc"></iframe>
                  <div class="blocker" id="blocker"><div><h1>Protected connection required</h1><p>Browser traffic is stopped until VPN Router protection is active.</p></div></div>
                  <div class="pad">
                    <button type="button" id="reloadButton">Reload</button>
                    <button type="button" id="backButton">Back</button>
                    <button type="button" id="forwardButton">Forward</button>
                  </div>
                </div>
              </div>
              <script>
                (function(){
                  var input=document.getElementById('urlInput');
                  var frame=document.getElementById('secureFrame');
                  function normalize(value){value=(value||'').trim();if(!value)return '';if(!/^[a-z][a-z0-9+.-]*:\/\//i.test(value))value='https://'+value;return value;}
                  function openUrl(value){var url=normalize(value);if(!url)return;input.value=url;frame.src='/virtuvpn-router/secure-web/proxy?url='+encodeURIComponent(url);try{history.replaceState(null,'','/virtuvpn-router/secure-web?url='+encodeURIComponent(url));}catch(e){}}
                  document.getElementById('goForm').addEventListener('submit',function(e){e.preventDefault();openUrl(input.value);});
                  document.getElementById('reloadButton').addEventListener('click',function(){try{frame.contentWindow.location.reload();}catch(e){frame.src=frame.src;}});
                  document.getElementById('backButton').addEventListener('click',function(){try{frame.contentWindow.history.back();}catch(e){}});
                  document.getElementById('forwardButton').addEventListener('click',function(){try{frame.contentWindow.history.forward();}catch(e){}});
                  document.getElementById('saveButton').addEventListener('click',function(){try{localStorage.setItem('virtuvpn.router.secureWeb.last',input.value);}catch(e){}});
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun secureWebErrorHtml(message: String): String =
        """
            <!doctype html><html><head><meta charset="utf-8"><style>
            body{margin:0;min-height:100vh;display:grid;place-items:center;background:#071018;color:#fff;font-family:system-ui,sans-serif;padding:28px;text-align:center}
            p{color:#afc0cc;max-width:360px;line-height:1.5}
            </style></head><body><main><h1>Secure Web blocked</h1><p>${escape(message)}</p></main></body></html>
        """.trimIndent()

    private fun rewriteSecureWebHtml(baseUrl: String, html: String): String {
        val withProtection = injectSecureWebProtection(html)
        return rewriteHtmlAttributes(baseUrl, withProtection)
    }

    private fun injectSecureWebProtection(html: String): String {
        val script = """
            <script>
            (function(){
              if(window.__virtuvpnRouterSecureWeb)return;
              Object.defineProperty(window,'__virtuvpnRouterSecureWeb',{value:true,configurable:false});
              var blocked=function(){throw new DOMException('WebRTC is disabled by VirtuVPN Router Secure Web','SecurityError');};
              var rejected=function(){return Promise.reject(new DOMException('WebRTC is disabled by VirtuVPN Router Secure Web','SecurityError'));};
              try{Object.defineProperty(window,'RTCPeerConnection',{value:blocked,configurable:false});}catch(e){window.RTCPeerConnection=blocked;}
              try{Object.defineProperty(window,'webkitRTCPeerConnection',{value:blocked,configurable:false});}catch(e){window.webkitRTCPeerConnection=blocked;}
              try{Object.defineProperty(window,'mozRTCPeerConnection',{value:blocked,configurable:false});}catch(e){window.mozRTCPeerConnection=blocked;}
              if(navigator.mediaDevices){try{navigator.mediaDevices.getUserMedia=rejected;}catch(e){} try{navigator.mediaDevices.enumerateDevices=function(){return Promise.resolve([]);};}catch(e){}}
              try{navigator.getUserMedia=function(){};}catch(e){}
              try{navigator.webkitGetUserMedia=function(){};}catch(e){}
              try{navigator.mozGetUserMedia=function(){};}catch(e){}
            })();
            </script>
        """.trimIndent()
        return if (html.contains("<head", ignoreCase = true)) {
            html.replaceFirst(Regex("(?i)<head([^>]*)>"), "<head$1>$script")
        } else {
            "$script$html"
        }
    }

    private fun rewriteHtmlAttributes(baseUrl: String, html: String): String {
        val attrRegex = Regex("""(?i)\b(href|src|action)=["']([^"']+)["']""")
        return attrRegex.replace(html) { match ->
            val attr = match.groupValues[1]
            val value = match.groupValues[2]
            val rewritten = rewriteSecureWebUrl(baseUrl, value)
            """$attr="$rewritten""""
        }
    }

    private fun rewriteSecureWebUrl(baseUrl: String, value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank() ||
            trimmed.startsWith("#") ||
            trimmed.startsWith("data:", ignoreCase = true) ||
            trimmed.startsWith("blob:", ignoreCase = true) ||
            trimmed.startsWith("javascript:", ignoreCase = true) ||
            trimmed.startsWith("mailto:", ignoreCase = true) ||
            trimmed.startsWith("tel:", ignoreCase = true)
        ) return value
        return runCatching {
            val resolved = URI(baseUrl).resolve(trimmed).toString()
            secureWebProxyPath(resolved)
        }.getOrDefault(value)
    }

    private fun normalizeSecureWebUrl(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "https" && scheme != "http") return null
        if (uri.host.isNullOrBlank()) return null
        return uri.toString()
    }

    private fun isAllowedSecureWebUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase(Locale.US)) {
            "https" -> !uri.host.isNullOrBlank()
            "http" -> isPrivateHttpHost(uri.host)
            else -> false
        }
    }

    private fun isPrivateHttpHost(host: String?): Boolean {
        val normalized = host?.trim()?.lowercase(Locale.US)?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (normalized == "localhost" || !normalized.contains('.')) return true
        if (normalized.endsWith(".local") || normalized.endsWith(".lan") || normalized.endsWith(".internal") || normalized.endsWith(".home") || normalized.endsWith(".test") || normalized.endsWith(".vcs")) return true
        return isPrivateIpv4(normalized) || normalized == "::1" || normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe80:")
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val values = host.split('.').map { it.toIntOrNull() ?: return false }
        if (values.size != 4 || values.any { it !in 0..255 }) return false
        return values[0] == 10 ||
            values[0] == 127 ||
            values[0] == 169 && values[1] == 254 ||
            values[0] == 172 && values[1] in 16..31 ||
            values[0] == 192 && values[1] == 168 ||
            values[0] == 100 && values[1] in 64..127
    }

    private fun safeContentType(contentType: String): String {
        val normalized = contentType.substringBefore(';').lowercase(Locale.US)
        return when {
            normalized.startsWith("image/") -> contentType
            normalized == "text/css" -> contentType
            normalized == "application/javascript" || normalized == "text/javascript" -> contentType
            normalized == "application/json" -> contentType
            else -> "application/octet-stream"
        }
    }

    private fun queryParam(path: String, name: String): String? {
        val query = path.substringAfter('?', missingDelimiterValue = "")
        if (query.isBlank()) return null
        return query.split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=')
            if (key != name) return@firstNotNullOfOrNull null
            URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
        }
    }

    private fun secureWebProxyPath(url: String): String =
        "/virtuvpn-router/secure-web/proxy?url=${urlParam(url)}"

    private fun secureWebPath(url: String): String =
        "/virtuvpn-router/secure-web?url=${urlParam(url)}"

    private fun urlParam(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val GOOGLE_URL = "https://www.google.com/"

    private fun isCaptiveProbe(path: String): Boolean {
        val normalized = path.substringBefore('?').lowercase()
        return normalized == "/generate_204" ||
            normalized == "/gen_204" ||
            normalized == "/hotspot-detect.html" ||
            normalized == "/canonical.html" ||
            normalized == "/connecttest.txt" ||
            normalized == "/ncsi.txt" ||
            normalized == "/success.txt" ||
            normalized == "/library/test/success.html" ||
            normalized.endsWith("/generate_204") ||
            normalized.endsWith("/gen_204") ||
            normalized.endsWith("/hotspot-detect.html") ||
            normalized.endsWith("/connecttest.txt") ||
            normalized.endsWith("/ncsi.txt")
    }

    private fun portalUrl(client: Socket): String {
        val address = client.localAddress?.hostAddress?.takeIf { it.isNotBlank() } ?: "192.168.115.1"
        return "http://$address:$PORT/"
    }

    private fun markSecureWebClient(clientIp: String) {
        if (clientIp.isBlank()) return
        secureWebClients[clientIp] = System.currentTimeMillis() + SECURE_WEB_VALIDATION_MS
    }

    private fun isSecureWebClient(clientIp: String): Boolean {
        val expiresAt = secureWebClients[clientIp] ?: return false
        if (expiresAt >= System.currentTimeMillis()) return true
        secureWebClients.remove(clientIp)
        return false
    }
}
