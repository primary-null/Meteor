package com.meteor.app.data

import org.junit.Assert.*
import org.junit.Test

class TransportTest {
    private fun envelope(value: String) = "<methodResponse><params><param><value><struct>" +
        "<member><name>Status</name><value>Success</value></member><member><name>Value</name><value>$value</value></member>" +
        "</struct></value></param></params></methodResponse>"
    @Test fun endpointCanonicalization() {
        assertEquals("https://lab.example/", Endpoints.normalize(" LAB.example "))
        assertEquals("https://lab.example:8443/", Endpoints.normalize("https://LAB.example:8443"))
        listOf("http://lab.example", "https://root:secret@lab.example", "https://lab.example/api", "https://lab.example/?x=1", "https://lab.example/#x").forEach {
            assertThrows(IllegalArgumentException::class.java) { Endpoints.normalize(it) }
        }
    }
    @Test fun credentialsAreXmlEscaped() {
        val request = String(XmlRpc.request("session.login_with_password", listOf("a<&", "\"' >")))
        assertTrue(request.contains("a&lt;&amp;"))
        assertTrue(request.contains("&quot;&apos; &gt;"))
        assertThrows(IllegalArgumentException::class.java) { XmlRpc.request("bad<method", emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { XmlRpc.request("login", listOf("\u0000")) }
    }
    @Test fun parsesNestedValuesAndLargeNumbers() {
        val xml = envelope("\n <array><data><value><i8>17179869184</i8></value><value><boolean>1</boolean></value><value><string>A &amp; B</string></value></data></array>\n")
        assertEquals(listOf(17179869184L, true, "A & B"), XmlRpc.response(xml))
        assertEquals("", XmlRpc.response(envelope("")))
    }
    @Test fun scalarsTolerateSurroundingWhitespaceButRejectGarbage() {
        val xml = envelope("<array><data><value><int> 42 </int></value><value><boolean>\n1\n</boolean></value><value><double> 0.5 </double></value></data></array>")
        assertEquals(listOf(42L, true, 0.5), XmlRpc.response(xml))
        listOf("<int>4x</int>", "<double>half</double>", "<boolean>yes</boolean>").forEach {
            assertThrows(IllegalArgumentException::class.java) { XmlRpc.response(envelope(it)) }
        }
    }
    @Test fun failureNeverIncludesServerSensitiveArguments() {
        val response = "<methodResponse><params><param><value><struct><member><name>Status</name><value>Failure</value></member>" +
            "<member><name>ErrorDescription</name><value><array><data><value>SESSION_AUTHENTICATION_FAILED</value><value>secret</value></data></array></value></member></struct></value></param></params></methodResponse>"
        val error = assertThrows(RpcFailure::class.java) { XmlRpc.response(response) }
        assertEquals("SESSION_AUTHENTICATION_FAILED", error.code)
        assertFalse(error.message!!.contains("secret"))
    }
    @Test fun rejectsEntityExpansionAndExcessNesting() {
        assertThrows(IllegalArgumentException::class.java) { XmlRpc.response("<!DOCTYPE x [<!ENTITY x 'x'>]>" + envelope("x")) }
        val nested = (1..35).fold("text") { result, _ -> "<array><data><value>$result</value></data></array>" }
        assertThrows(IllegalArgumentException::class.java) { XmlRpc.response(envelope(nested)) }
        assertThrows(IllegalArgumentException::class.java) { XmlRpc.response(envelope("<boolean>2</boolean>")) }
    }
    @Test fun profilesDoNotPrintSecrets() {
        val profile = Profile("id", "label", "https://lab/", "root", "sensitive-password", "a".repeat(64))
        assertFalse(profile.toString().contains("sensitive-password"))
        assertFalse(profile.toString().contains("https://lab/"))
    }
}
