/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
                path.startsWith("/virtuvpn-router/ignore") -> {
                    VpnRouterManager.allowGuestPortalBypass(client.inetAddress.hostAddress.orEmpty())
                    respondHtml(client.getOutputStream(), ignoredHtml(context))
                }
                path.startsWith("/virtuvpn-router/secure-browser") -> respondRedirect(
                    client.getOutputStream(),
                    "virtuvpn://secure-browser"
                )
                path.startsWith("/virtuvpn-router/install") -> respondRedirect(
                    client.getOutputStream(),
                    "/api/mobile/android/apk/install"
                )
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

    private fun writeResponse(output: OutputStream, status: String, contentType: String, body: String) {
        val bytes = body.toByteArray()
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
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>VirtuVPN Router</title>
              <style>
                :root{color-scheme:dark;background:#06121f;color:#f8fafc;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
                *{box-sizing:border-box}body{margin:0;min-height:100vh;background:#06121f}
                .wrap{max-width:760px;margin:0 auto;padding:32px 18px}
                .brand{display:flex;align-items:center;gap:12px;margin-bottom:28px}
                .logo{width:42px;height:42px;border-radius:10px;background:#0ea5e9;display:grid;place-items:center;font-weight:800;color:white}
                h1{font-size:28px;line-height:1.15;margin:0 0 12px}
                p{color:#cbd5e1;line-height:1.55;margin:0 0 18px}
                .panel{border:1px solid #1e3a5f;background:#0b1b2e;border-radius:8px;padding:20px;margin:18px 0}
                .steps{padding-left:20px;color:#dbeafe;line-height:1.6}
                .actions{display:grid;gap:10px;margin-top:22px}
                a,button{font:inherit;border:0;border-radius:8px;padding:13px 16px;text-align:center;text-decoration:none}
                .primary{background:#38bdf8;color:#031827;font-weight:700}
                .secondary{background:#122945;color:#e0f2fe;border:1px solid #25638f}
                .danger{background:transparent;color:#cbd5e1;text-decoration:underline}
                .check{display:flex;gap:10px;align-items:flex-start;color:#cbd5e1;font-size:14px}
                .check input{margin-top:3px}
                .muted{font-size:13px;color:#94a3b8}
              </style>
            </head>
            <body>
              <main class="wrap">
                <div class="brand"><div class="logo">V</div><strong>VirtuVPN Router</strong></div>
                <h1>Browse this hotspot safely</h1>
                <p>This WiFi is routed through the VPN router. For the safest browsing, use VirtuVPN Secure Browser so local browser data such as WebRTC local IP is not exposed by a regular browser.</p>
                <section class="panel">
                  <ol class="steps">
                    <li>Install or open VirtuVPN on this device.</li>
                    <li>Open Secure Browser inside VirtuVPN.</li>
                    <li>Use Secure Browser for leak tests, banking, admin panels, and sensitive browsing.</li>
                  </ol>
                </section>
                <div class="actions">
                  <a class="primary" href="/virtuvpn-router/secure-browser">Open Secure Browser</a>
                  <a class="secondary" href="/virtuvpn-router/install">Install VirtuVPN</a>
                  <form method="get" action="/virtuvpn-router/ignore">
                    <label class="check"><input required type="checkbox"> <span>I understand this regular browser can expose local browser data. I do not need Secure Browser on this device.</span></label>
                    <button class="danger" type="submit">Continue with regular browser</button>
                  </form>
                </div>
                <p class="muted">Original request: ${next}</p>
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun ignoredHtml(context: Context): String =
        """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>VirtuVPN Router</title><style>
            body{margin:0;background:#06121f;color:#f8fafc;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;display:grid;place-items:center;min-height:100vh}
            div{max-width:520px;padding:24px;text-align:center}button{border:0;border-radius:8px;background:#38bdf8;color:#031827;font-weight:700;padding:12px 18px}
            p{color:#cbd5e1}
            </style></head><body><div><h1>OK</h1><p>This browser will continue without VirtuVPN Secure Browser prompts on this hotspot session.</p><button onclick="location.href='http://example.com'">OK</button></div></body></html>
        """.trimIndent()

    private fun escape(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
