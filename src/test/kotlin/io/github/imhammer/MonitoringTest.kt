package io.github.imhammer

import io.github.imhammer.config.ConfigManager
import io.github.imhammer.monitoring.*
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MonitoringTest {

    private val testFile = File("test-monitoring-config.json")
    private lateinit var configManager: ConfigManager
    private lateinit var instanceManager: InstanceManager
    private lateinit var hostMonitorManager: HostMonitorManager

    @BeforeTest
    fun setup() {
        if (testFile.exists()) {
            testFile.delete()
        }
        configManager = ConfigManager(testFile)
        configManager.loadOrCreate(nonInteractive = true)
        instanceManager = InstanceManager()
        hostMonitorManager = HostMonitorManager(configManager, instanceManager)
    }

    @AfterTest
    fun cleanup() {
        hostMonitorManager.stop()
        if (testFile.exists()) {
            testFile.delete()
        }
    }

    @Test
    fun testRegisterInstanceAndPushEvent() {
        val instance = MonitoringInstance(
            id = "ecommerce-api",
            name = "API de Compras",
            source = InstanceSource.SDK,
            sectors = mutableListOf(
                Sector(id = "vendas", name = "Vendas Realizadas", type = SectorType.COUNTER),
                Sector(id = "erros", name = "Falhas", type = SectorType.LOG_STREAM)
            )
        )

        instanceManager.registerOrUpdateInstance(instance)

        val retrieved = instanceManager.getInstance("ecommerce-api")
        assertNotNull(retrieved)
        assertEquals(2, retrieved.sectors.size)

        var eventCaptured: SectorEvent? = null
        instanceManager.addEventListener { eventCaptured = it }

        val pushSuccess = instanceManager.pushEvent(
            SectorEvent(
                instanceId = "ecommerce-api",
                sectorId = "vendas",
                value = "42",
                details = "Pedido #1059 pago com sucesso"
            )
        )

        assertTrue(pushSuccess)
        assertNotNull(eventCaptured)
        assertEquals("42", eventCaptured.value)

        val updatedSector = retrieved.sectors.first { it.id == "vendas" }
        assertEquals("42", updatedSector.lastValue)
    }

    @Test
    fun testHostMonitorAddAndPoll() {
        // Testa adicionar monitor para o provedor pm2
        val added = hostMonitorManager.addMonitor("pm2", "all")
        assertTrue(added)

        val monitors = hostMonitorManager.getConfiguredMonitors()
        assertEquals(1, monitors.size)
        assertEquals("pm2", monitors[0].providerId)
        assertEquals("all", monitors[0].target)

        // Verifica que a configuração foi salva no disco
        val reloadedManager = ConfigManager(testFile)
        val reloadedConfig = reloadedManager.loadOrCreate(nonInteractive = true)
        assertEquals(1, reloadedConfig.monitors.size)
        assertEquals("pm2", reloadedConfig.monitors[0].providerId)

        // Força poll imediato
        hostMonitorManager.pollAll()

        val instances = instanceManager.getInstances()
        // Em dev, o Pm2MonitorProvider gera instâncias simuladas ou reais se houver PM2
        assertTrue(instances.isNotEmpty())
        assertTrue(instances.any { it.providerId == "pm2" })

        // Remove monitor
        val removed = hostMonitorManager.removeMonitor("pm2", "all")
        assertTrue(removed)
        assertTrue(hostMonitorManager.getConfiguredMonitors().isEmpty())
    }

    @Test
    fun testHostMonitorWithCustomSectors() {
        // 1. Adiciona monitor com apenas 2 setores: status e cpu
        val res = hostMonitorManager.addMonitorDetailed("pm2", "all", listOf("status", "cpu"))
        assertTrue(res.success)
        assertNotNull(res.instancesFound)

        val monitors = hostMonitorManager.getConfiguredMonitors()
        assertEquals(1, monitors.size)
        assertEquals(listOf("status", "cpu"), monitors[0].enabledSectors)

        // Força poll
        hostMonitorManager.pollAll()
        val instances = instanceManager.getInstances().filter { it.providerId == "pm2" }
        assertTrue(instances.isNotEmpty())

        // Verifica que CADA instância possui APENAS status e cpu, e NÃO memory ou restarts
        for (inst in instances) {
            val sectorIds = inst.sectors.map { it.id }
            assertTrue(sectorIds.contains("status"))
            assertTrue(sectorIds.contains("cpu"))
            assertTrue(!sectorIds.contains("memory"), "Setor 'memory' deveria estar desativado")
            assertTrue(!sectorIds.contains("restarts"), "Setor 'restarts' deveria estar desativado")
            assertEquals(2, inst.sectors.size)
        }

        // 2. Atualiza dinamicamente os setores para apenas 'status'
        val updated = hostMonitorManager.setMonitorSectors("pm2", "all", listOf("status"))
        assertTrue(updated)

        val instancesAfterUpdate = instanceManager.getInstances().filter { it.providerId == "pm2" }
        for (inst in instancesAfterUpdate) {
            val sectorIds = inst.sectors.map { it.id }
            assertTrue(sectorIds.contains("status"))
            assertTrue(!sectorIds.contains("cpu"), "Setor 'cpu' deveria ter sido podado após atualização")
            assertEquals(1, inst.sectors.size)
        }

        // 3. Testa rejeição de setor inválido
        val invalidRes = hostMonitorManager.addMonitorDetailed("pm2", "all", listOf("status", "setor_inexistente"))
        assertTrue(!invalidRes.success)
        assertTrue(invalidRes.message.contains("setor_inexistente"))
    }

    @Test
    fun testEventSocketServerSdkIngestion() {
        val socketPath = File(".msm/test-events.sock").absolutePath
        val server = EventSocketServer(instanceManager, customSocketPath = socketPath)
        server.start()

        try {
            Thread.sleep(300)
            val channel = java.nio.channels.SocketChannel.open(java.net.UnixDomainSocketAddress.of(server.socketFile.toPath()))
            val writer = java.io.PrintWriter(java.nio.channels.Channels.newOutputStream(channel), true, Charsets.UTF_8)
            val reader = java.io.BufferedReader(java.io.InputStreamReader(java.nio.channels.Channels.newInputStream(channel), Charsets.UTF_8))

            // 1. Registra instância via JSON
            writer.println("""{"action":"REGISTER_INSTANCE","instanceId":"billing-worker","name":"Serviço de Cobrança","sectors":[{"id":"invoices","name":"Faturas","type":"COUNTER"}]}""")
            val registerResp = reader.readLine()
            assertNotNull(registerResp)
            assertTrue(registerResp.contains("OK"))

            val inst = instanceManager.getInstance("billing-worker")
            assertNotNull(inst)
            assertEquals("Serviço de Cobrança", inst.name)

            // 2. Envia evento via JSON
            writer.println("""{"action":"PUSH_EVENT","instanceId":"billing-worker","sectorId":"invoices","value":"15","details":"Fatura #99 processada"}""")
            val pushResp = reader.readLine()
            assertNotNull(pushResp)
            assertTrue(pushResp.contains("OK"))

            val sector = inst.sectors.first { it.id == "invoices" }
            assertEquals("15", sector.lastValue)

            channel.close()
        } catch (e: Exception) {
            println("Aviso do teste de Unix socket (esperado caso AF_UNIX seja desabilitado pelo SO): ${e.message}")
        } finally {
            server.stop()
        }
    }

    @Test
    fun testEventSocketServerAuthentication() {
        val socketPath = File(".msm/test-auth-events.sock").absolutePath
        val server = EventSocketServer(instanceManager, configManager = configManager, customSocketPath = socketPath)
        server.start()

        try {
            Thread.sleep(300)
            val channel = java.nio.channels.SocketChannel.open(java.net.UnixDomainSocketAddress.of(server.socketFile.toPath()))
            val writer = java.io.PrintWriter(java.nio.channels.Channels.newOutputStream(channel), true, Charsets.UTF_8)
            val reader = java.io.BufferedReader(java.io.InputStreamReader(java.nio.channels.Channels.newInputStream(channel), Charsets.UTF_8))

            val correctToken = configManager.config.sdkToken
            assertTrue(correctToken.isNotBlank(), "SDK token deve ser gerado")

            // 1. Tentar registrar instância sem estar autenticado -> deve falhar
            writer.println("""{"action":"REGISTER_INSTANCE","instanceId":"unauth-inst","name":"Sem Auth"}""")
            val unauthResp = reader.readLine()
            assertNotNull(unauthResp)
            assertTrue(unauthResp.contains("ERROR"))
            assertTrue(unauthResp.contains("Acesso não autorizado"))

            // 2. Tentar autenticar com token errado -> deve falhar
            writer.println("""{"action":"AUTH","token":"wrong_token_123"}""")
            val wrongAuthResp = reader.readLine()
            assertNotNull(wrongAuthResp)
            assertTrue(wrongAuthResp.contains("ERROR"))
            assertTrue(wrongAuthResp.contains("Token do SDK inválido"))

            // 3. Autenticar com o token correto -> deve retornar OK
            writer.println("""{"action":"AUTH","token":"$correctToken"}""")
            val correctAuthResp = reader.readLine()
            assertNotNull(correctAuthResp)
            assertTrue(correctAuthResp.contains("OK"))
            assertTrue(correctAuthResp.contains("Autenticado com sucesso"))

            // 4. Agora sim registrar instância -> deve ter sucesso
            writer.println("""{"action":"REGISTER_INSTANCE","instanceId":"auth-inst","name":"Com Auth"}""")
            val okResp = reader.readLine()
            assertNotNull(okResp)
            assertTrue(okResp.contains("OK"))

            val inst = instanceManager.getInstance("auth-inst")
            assertNotNull(inst)
            assertEquals("Com Auth", inst.name)

            channel.close()
        } catch (e: Exception) {
            println("Aviso do teste de Unix socket (esperado caso AF_UNIX seja desabilitado pelo SO): ${e.message}")
        } finally {
            server.stop()
        }
    }

    @Test
    fun testPayloadValidator() {
        // IDs válidos e inválidos
        assertEquals("valid-id_1:api", PayloadValidator.validateId("valid-id_1:api", "id"))
        try {
            PayloadValidator.validateId("invalid id with spaces!", "id")
            assertTrue(false, "Deveria ter falhado com ID contendo espaços")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("caracteres inválidos"))
        }

        // Validação de PERCENTAGE
        assertEquals("75.50", PayloadValidator.validateValue("75.5%", ValueType.PERCENTAGE))
        try {
            PayloadValidator.validateValue("150.0", ValueType.PERCENTAGE)
            assertTrue(false, "Deveria ter falhado com percentual > 100")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("fora da faixa"))
        }

        // Validação de BYTES
        assertEquals("1048576", PayloadValidator.validateValue("1048576", ValueType.BYTES))
        try {
            PayloadValidator.validateValue("-50", ValueType.BYTES)
            assertTrue(false, "Deveria ter falhado com bytes negativo")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("negativo"))
        }

        // Validação de BOOLEAN
        assertEquals("true", PayloadValidator.validateValue("TRUE", ValueType.BOOLEAN))
        try {
            PayloadValidator.validateValue("maybe", ValueType.BOOLEAN)
            assertTrue(false, "Deveria ter falhado com boolean inválido")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("BOOLEAN"))
        }
    }

    @Test
    fun testDirectInstanceDataAndSectorHierarchy() {
        // 1. Cria uma instância de projeto (Gateway de Pagamentos)
        val gateway = MonitoringInstance(
            id = "payment-gateway",
            name = "Gateway de Pagamentos",
            source = InstanceSource.SDK
        )
        instanceManager.registerOrUpdateInstance(gateway)

        // 2. Envia dado DIRETO para a instância (sem setor), como status geral e uptime
        instanceManager.pushEvent(
            DataEvent(
                instanceId = "payment-gateway",
                dataId = "status",
                value = "ONLINE",
                valueType = ValueType.STATUS
            )
        )
        instanceManager.pushEvent(
            DataEvent(
                instanceId = "payment-gateway",
                dataId = "uptime",
                value = "72h",
                valueType = ValueType.TEXT
            )
        )

        // 3. Envia dados organizados por SETORES (ex: setor 'auth' e setor 'transactions')
        instanceManager.pushEvent(
            DataEvent(
                instanceId = "payment-gateway",
                sectorId = "auth",
                dataId = "logins",
                value = "120",
                valueType = ValueType.NUMBER,
                category = SectorCategory.BUSINESS
            )
        )
        instanceManager.pushEvent(
            DataEvent(
                instanceId = "payment-gateway",
                sectorId = "transactions",
                dataId = "faturamento",
                value = "25000.00",
                valueType = ValueType.CURRENCY,
                category = SectorCategory.BUSINESS,
                unit = "BRL"
            )
        )
        instanceManager.pushEvent(
            DataEvent(
                instanceId = "payment-gateway",
                sectorId = "transactions",
                dataId = "logs",
                value = "Transação #9901 aprovada",
                valueType = ValueType.LOG_LINE,
                category = SectorCategory.BUSINESS
            )
        )

        // 4. Valida a estrutura recuperada
        val retrieved = instanceManager.getInstance("payment-gateway")
        assertNotNull(retrieved)

        // Dados diretos na instância
        assertEquals(2, retrieved.data.size)
        val statusData = retrieved.data.first { it.id == "status" }
        assertEquals("ONLINE", statusData.lastValue)
        assertEquals(ValueType.STATUS, statusData.valueType)

        val uptimeData = retrieved.data.first { it.id == "uptime" }
        assertEquals("72h", uptimeData.lastValue)

        // Setores da instância
        assertEquals(2, retrieved.sectors.size)
        val authSector = retrieved.sectors.first { it.id == "auth" }
        assertEquals(1, authSector.data.size)
        assertEquals("120", authSector.data.first().lastValue)

        val txSector = retrieved.sectors.first { it.id == "transactions" }
        assertEquals(2, txSector.data.size)
        val faturamento = txSector.data.first { it.id == "faturamento" }
        assertEquals("25000.00", faturamento.lastValue)
        assertEquals("BRL", faturamento.unit)
        val txLog = txSector.data.first { it.id == "logs" }
        assertEquals("Transação #9901 aprovada", txLog.lastValue)
    }
}
