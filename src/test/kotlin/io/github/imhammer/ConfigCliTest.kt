package io.github.imhammer

import io.github.imhammer.cli.ConfigCli
import io.github.imhammer.config.ConfigManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConfigCliTest {

    private val testFile = File("test-cli-config.json")
    private val originalOut = System.out
    private val outContent = ByteArrayOutputStream()

    @BeforeTest
    fun setup() {
        if (testFile.exists()) {
            testFile.delete()
        }
        System.setOut(PrintStream(outContent))
    }

    @AfterTest
    fun cleanup() {
        System.setOut(originalOut)
        if (testFile.exists()) {
            testFile.delete()
        }
    }

    @Test
    fun testShowConfig() {
        val manager = ConfigManager(testFile)
        manager.loadOrCreate(nonInteractive = true)

        val cli = ConfigCli(manager)
        cli.handleArgs(listOf("show"))

        val output = outContent.toString()
        assertTrue(output.contains("MSM AGENT - CONFIGURAÇÕES ATUAIS"))
        assertTrue(output.contains("Porta HTTP e WS"))
    }

    @Test
    fun testSetConfigUpdatesValues() {
        val manager = ConfigManager(testFile)
        manager.loadOrCreate(nonInteractive = true)

        val cli = ConfigCli(manager)
        cli.handleArgs(listOf("set", "--name", "production-host-01", "--port", "9876", "--pairing", "off", "--token", "custom_test_token"))

        val updatedManager = ConfigManager(testFile)
        val config = updatedManager.getOrLoad()

        assertEquals("production-host-01", config.name)
        assertEquals(9876, config.port)
        assertFalse(config.pairingEnabled)
        assertEquals("custom_test_token", config.sdkToken)
    }

    @Test
    fun testSetInvalidPortIsRejected() {
        val manager = ConfigManager(testFile)
        val initialConfig = manager.loadOrCreate(nonInteractive = true)
        val initialPort = initialConfig.port

        val cli = ConfigCli(manager)
        cli.handleArgs(listOf("set", "--port", "999999"))

        val updatedManager = ConfigManager(testFile)
        val config = updatedManager.getOrLoad()
        assertEquals(initialPort, config.port)

        val output = outContent.toString()
        assertTrue(output.contains("Porta inválida"))
    }

    @Test
    fun testRegenerateTokenFlag() {
        val manager = ConfigManager(testFile)
        val initialConfig = manager.loadOrCreate(nonInteractive = true)
        val oldToken = initialConfig.sdkToken

        val cli = ConfigCli(manager)
        cli.handleArgs(listOf("set", "--regenerate-token"))

        val updatedManager = ConfigManager(testFile)
        val config = updatedManager.getOrLoad()
        assertNotEquals(oldToken, config.sdkToken)
        assertTrue(config.sdkToken.startsWith("msm_sdk_"))
    }

    @Test
    fun testTokenSubcommand() {
        val manager = ConfigManager(testFile)
        manager.loadOrCreate(nonInteractive = true)

        val cli = ConfigCli(manager)
        cli.handleArgs(listOf("token", "regenerate"))

        val output = outContent.toString()
        assertTrue(output.contains("Novo token do SDK gerado"))
    }

    @Test
    fun testAgentInfoPrinterWithoutPriorLoadDoesNotThrow() {
        val manager = ConfigManager(testFile)
        // Note: loadOrCreate is NOT called explicitly beforehand.
        // It must not throw IllegalStateException.
        io.github.imhammer.cli.AgentInfoPrinter.printInfo(manager)

        val output = outContent.toString()
        assertTrue(output.contains("MSM AGENT - INFORMAÇÕES DE ARQUIVOS"))
        assertTrue(output.contains("Token do SDK"))
    }
}
