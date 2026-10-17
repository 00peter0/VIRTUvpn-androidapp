package com.wireguard.android.util

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class NetworkSecurityConfigTest {
    private fun config() = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(File("src/main/res/xml/network_security_config.xml"))

    @Test
    fun cleartextExceptionsExactlyMatchAllocatedTerminalGateways() {
        val document = config()
        val configs = document.getElementsByTagName("domain-config")
        val allowed = mutableListOf<String>()
        for (i in 0 until configs.length) {
            val element = configs.item(i) as Element
            if (element.getAttribute("cleartextTrafficPermitted") != "true") continue
            val domains = element.getElementsByTagName("domain")
            for (j in 0 until domains.length) {
                val domain = domains.item(j) as Element
                assertEquals("false", domain.getAttribute("includeSubdomains"))
                allowed += domain.textContent.trim()
            }
        }
        // VCS Single server allocator reserves 254; S2S uses source 10.N.1.1.
        val expected = (2..253).map { "10.75.$it.1" } + (77..254).map { "10.$it.1.1" }
        assertEquals(expected.toSet(), allowed.toSet())
        assertEquals(expected.size, allowed.size) // No duplicate/legacy parallel list.
        assertEquals("false", (document.getElementsByTagName("base-config").item(0) as Element)
            .getAttribute("cleartextTrafficPermitted"))
    }

    @Test
    fun vcsApiRemainsHttpsOnlyAndCertificatePinned() {
        val document = config()
        val domains = document.getElementsByTagName("domain")
        val api = (0 until domains.length).map { domains.item(it) as Element }
            .single { it.textContent.trim() == "vcs.virtucomputing.com" }
        assertEquals("true", api.getAttribute("includeSubdomains"))
        val config = api.parentNode as Element
        assertEquals("false", config.getAttribute("cleartextTrafficPermitted"))
        val pins = config.getElementsByTagName("pin")
        assertEquals(setOf(
            "SUlPcqGLUPz4ZSoKEB+aUtORwa5VlMr7QQGthRGqzQs=",
            "s/tdAOmUzd8syaTuqfgGvFcn6DzA5Cmb+Vby1ST+U3Y=",
            "sCkq5UWXjg+7mKu9lMhhYF5bGLsy7VI/UNW3tccdR7w=",
            "diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI="
        ), (0 until pins.length).map { pins.item(it).textContent.trim() }.toSet())
        assertEquals("2028-08-15", (config.getElementsByTagName("pin-set").item(0) as Element)
            .getAttribute("expiration"))
    }

    @Test
    fun terminalExceptionsDoNotChangeSecuredBrowserPolicy() {
        for (host in listOf("10.75.2.1", "10.75.253.1", "10.77.1.1", "10.254.1.1", "example.com")) {
            assertFalse(SecureBrowserUrlPolicy.isAllowed("http", host, isTopLevel = true))
            assertFalse(SecureBrowserUrlPolicy.isAllowed("ws", host, isTopLevel = false))
        }
    }
}
