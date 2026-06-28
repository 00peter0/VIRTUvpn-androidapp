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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

object VpnRouterGuestServer {
    const val PORT = 8787
    private const val TAG = "VirtuVPN/GuestServer"
    private const val INSTALL_URL = "https://vcs.virtucomputing.com/api/mobile/android/apk/guest"
    private const val SECURE_BROWSER_URL = "virtuvpn://secure-browser"
    private var serverJob: Job? = null
    @Volatile
    private var serverSocket: ServerSocket? = null

    fun start(context: Context, scope: CoroutineScope) {
        if (serverJob?.isActive == true) return
        val appContext = context.applicationContext
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
                    VpnRouterManager.allowGuestPortalBypass(client.inetAddress.hostAddress.orEmpty())
                    respondHtml(client.getOutputStream(), ignoredHtml(context))
                }
                path.startsWith("/virtuvpn-router/secure-browser") -> respondRedirect(client.getOutputStream(), SECURE_BROWSER_URL)
                path.startsWith("/virtuvpn-router/install") -> respondRedirect(client.getOutputStream(), INSTALL_URL)
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
            append("Content-Length: ").append(body.toByteArray().size).append("\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
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

    private fun writeResponse(output: OutputStream, status: String, contentType: String, body: String) {
        writeResponse(output, status, contentType, body.toByteArray())
    }

    private fun writeResponse(output: OutputStream, status: String, contentType: String, bytes: ByteArray) {
        val response = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Cache-Control: no-store\r\n")
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
                <p class="copy">This hotspot is routed through VPN. For the safest browsing, use VirtuVPN Secure Browser. Some Android captive browsers block opening apps from this page; if the link stays in the browser, open VirtuVPN manually and tap Secure Browser.</p>
                <div class="actions">
                  <a class="primary" href="$INSTALL_URL">Install or update VirtuVPN</a>
                  <a class="secondary" href="$SECURE_BROWSER_URL">Try opening Secure Browser</a>
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

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
