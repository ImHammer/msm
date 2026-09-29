package io.github.imhammer

import io.github.imhammer.config.ConfigManager
import io.github.imhammer.pairing.PairingManager
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutingIntegrationTest {

    private val testFile = File("test-routing-msm-config.json")
    private lateinit var configManager: ConfigManager
    private lateinit var pairingManager: PairingManager

    @BeforeTest
    fun setup() {
        if (testFile.exists()) {
            testFile.delete()
        }
        configManager = ConfigManager(testFile)
        configManager.loadOrCreate(nonInteractive = true)
        pairingManager = PairingManager(configManager)
    }

    @AfterTest
    fun cleanup() {
        if (testFile.exists()) {
            testFile.delete()
        }
    }

    @Test
    fun testGetStatus() = testApplication {
        application {
            configureSerialization()
            configureWebsockets()
            configureRouting(configManager, pairingManager)
        }

        val response = client.get("/api/status")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("online"))
    }

    @Test
    fun testGetSystemStats() = testApplication {
        application {
            configureSerialization()
            configureWebsockets()
            configureRouting(configManager, pairingManager)
        }

        val response = client.get("/api/system")
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(text.contains("cpu"))
        assertTrue(text.contains("memory"))
        assertTrue(text.contains("used"))
    }

    @Test
    fun testGetInstances() = testApplication {
        val instanceManager = io.github.imhammer.monitoring.InstanceManager()
        instanceManager.registerOrUpdateInstance(
            io.github.imhammer.monitoring.MonitoringInstance(
                id = "test-api",
                name = "API de Teste",
                source = io.github.imhammer.monitoring.InstanceSource.SDK
            )
        )

        application {
            configureSerialization()
            configureWebsockets()
            configureRouting(configManager, pairingManager, instanceManager = instanceManager)
        }

        val response = client.get("/api/instances")
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(text.contains("test-api"))
        assertTrue(text.contains("API de Teste"))
    }

    @Test
    fun testPairingRequestAndStatusFlow() = testApplication {
        application {
            configureSerialization()
            configureWebsockets()
            configureRouting(configManager, pairingManager)
        }

        // 1. Enviar solicitação de pareamento
        val reqResponse = client.post("/api/pairing/request") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"deviceName":"iPhone do Teste"}""")
        }
        assertEquals(HttpStatusCode.Created, reqResponse.status)
        val responseBody = reqResponse.bodyAsText()
        assertTrue(responseBody.contains("requestId"))
        assertTrue(responseBody.contains("code"))

        // 2. Extrair código e aprovar via PairingManager (simulando comando no terminal)
        val requests = pairingManager.getPendingRequests()
        assertEquals(1, requests.size)
        val code = requests[0].code
        val approvedDevice = pairingManager.acceptPairing(code)
        assertTrue(approvedDevice != null)

        // 3. Consultar status da solicitação pelo endpoint
        val statusResponse = client.get("/api/pairing/status/${requests[0].id}")
        assertEquals(HttpStatusCode.OK, statusResponse.status)
        val statusBody = statusResponse.bodyAsText()
        assertTrue(statusBody.contains("APPROVED"))
        assertTrue(statusBody.contains(approvedDevice.token))
    }

    @Test
    fun testPairingDisabledReturnsForbidden() = testApplication {
        pairingManager.isPairingEnabled = false

        application {
            configureSerialization()
            configureWebsockets()
            configureRouting(configManager, pairingManager)
        }

        val reqResponse = client.post("/api/pairing/request") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"deviceName":"Dispositivo Bloqueado"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, reqResponse.status)
    }

    @Test
    fun testAdminPairingRequestsAndAcceptFlow() = testApplication {
        val hostMonitorManager = io.github.imhammer.monitoring.HostMonitorManager(configManager, io.github.imhammer.monitoring.InstanceManager())
        application {
            configureSerialization()
            configureWebsockets()
            configureRouting(configManager, pairingManager, hostMonitorManager = hostMonitorManager)
        }

        // 1. Solicita pareamento pelo app
        client.post("/api/pairing/request") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"deviceName":"Android Teste"}""")
        }

        // 2. Consulta fila de solicitações via endpoint administrativo
        val reqsResp = client.get("/api/admin/pairing/requests")
        assertEquals(HttpStatusCode.OK, reqsResp.status)
        assertTrue(reqsResp.bodyAsText().contains("Android Teste"))

        // 3. Extrai código e aprova via endpoint administrativo
        val requests = pairingManager.getPendingRequests()
        assertEquals(1, requests.size)
        val code = requests[0].code

        val acceptResp = client.post("/api/admin/pairing/accept") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"query":"$code"}""")
        }
        assertEquals(HttpStatusCode.OK, acceptResp.status)
        assertTrue(acceptResp.bodyAsText().contains("aprovado com sucesso"))

        // 4. Verifica listagem de dispositivos autorizados
        val devicesResp = client.get("/api/admin/devices")
        assertEquals(HttpStatusCode.OK, devicesResp.status)
        assertTrue(devicesResp.bodyAsText().contains("Android Teste"))
    }

    @Test
    fun testAdminMonitorRoutesWithSectors() = testApplication {
        val hostMonitorManager = io.github.imhammer.monitoring.HostMonitorManager(configManager, io.github.imhammer.monitoring.InstanceManager())
        application {
            configureSerialization()
            configureWebsockets()
            configureRouting(configManager, pairingManager, hostMonitorManager = hostMonitorManager)
        }

        // 1. Consulta provedores e seus setores suportados
        val provsResp = client.get("/api/admin/monitors/providers")
        assertEquals(HttpStatusCode.OK, provsResp.status)
        val provsBody = provsResp.bodyAsText()
        assertTrue(provsBody.contains("pm2"))
        assertTrue(provsBody.contains("status"))
        assertTrue(provsBody.contains("cpu"))

        // 2. Adiciona monitor com setores específicos (status, cpu)
        val addResp = client.post("/api/admin/monitors/add") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"providerId":"pm2","target":"all","sectors":["status","cpu"]}""")
        }
        assertEquals(HttpStatusCode.OK, addResp.status)
        assertTrue(addResp.bodyAsText().contains("ativado e salvo"))

        // 3. Verifica que a listagem de monitores reflete os setores
        val listResp = client.get("/api/admin/monitors")
        assertEquals(HttpStatusCode.OK, listResp.status)
        val listBody = listResp.bodyAsText()
        assertTrue(listBody.contains("enabledSectors"))
        assertTrue(listBody.contains("status"))
        assertTrue(listBody.contains("cpu"))

        // 4. Atualiza os setores via endpoint /api/admin/monitors/sectors
        val updateResp = client.post("/api/admin/monitors/sectors") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"providerId":"pm2","target":"all","sectors":["status"]}""")
        }
        assertEquals(HttpStatusCode.OK, updateResp.status)
        assertTrue(updateResp.bodyAsText().contains("atualizados com sucesso"))
    }
}
