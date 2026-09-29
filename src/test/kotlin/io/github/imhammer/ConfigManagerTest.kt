package io.github.imhammer

import io.github.imhammer.config.ConfigManager
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigManagerTest {

    private val testFile = File("test-msm-config.json")

    @BeforeTest
    fun setup() {
        if (testFile.exists()) {
            testFile.delete()
        }
    }

    @AfterTest
    fun cleanup() {
        if (testFile.exists()) {
            testFile.delete()
        }
    }

    @Test
    fun testLoadOrCreateDefaults() {
        val manager = ConfigManager(testFile)
        val config = manager.loadOrCreate(nonInteractive = true)

        assertTrue(testFile.exists())
        assertEquals(9000, config.port)
        assertTrue(config.pairingEnabled)
        assertTrue(config.name.isNotEmpty())
        assertTrue(config.secretKey.isNotEmpty())
        assertTrue(config.devices.isEmpty())
    }

    @Test
    fun testSaveAndReloadConfig() {
        val manager = ConfigManager(testFile)
        val config = manager.loadOrCreate(nonInteractive = true)

        config.name = "vps-homolog-01"
        config.port = 9050
        config.pairingEnabled = false
        manager.save()

        // Recarrega em nova instância
        val reloadedManager = ConfigManager(testFile)
        val reloadedConfig = reloadedManager.loadOrCreate(nonInteractive = true)

        assertEquals("vps-homolog-01", reloadedConfig.name)
        assertEquals(9050, reloadedConfig.port)
        assertEquals(false, reloadedConfig.pairingEnabled)
    }
}
