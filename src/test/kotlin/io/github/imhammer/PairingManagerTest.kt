package io.github.imhammer

import io.github.imhammer.config.ConfigManager
import io.github.imhammer.pairing.PairingManager
import io.github.imhammer.pairing.PairingStatus
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingManagerTest {

    private val testFile = File("test-pairing-msm-config.json")
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
    fun testRequestPairingSuccess() {
        pairingManager.isPairingEnabled = true
        val request = pairingManager.requestPairing("iPhone 15 Pro", "iOS 18")

        assertNotNull(request)
        assertEquals("iPhone 15 Pro", request.deviceName)
        assertEquals(6, request.code.length)
        assertEquals(PairingStatus.PENDING, request.status)

        val pending = pairingManager.getPendingRequests()
        assertEquals(1, pending.size)
        assertEquals(request.id, pending[0].id)
    }

    @Test
    fun testRequestPairingDisabled() {
        pairingManager.isPairingEnabled = false
        val request = pairingManager.requestPairing("Android Phone")

        assertNull(request)
        assertTrue(pairingManager.getPendingRequests().isEmpty())
    }

    @Test
    fun testAcceptPairingWithCode() {
        pairingManager.isPairingEnabled = true
        val request = pairingManager.requestPairing("MacBook Monitor")
        assertNotNull(request)

        val pairedDevice = pairingManager.acceptPairing(request.code)
        assertNotNull(pairedDevice)
        assertEquals("MacBook Monitor", pairedDevice.name)
        assertTrue(pairedDevice.token.isNotEmpty())

        // A solicitação deve ter sido marcada como APPROVED
        val status = pairingManager.getRequestStatus(request.id)
        assertNotNull(status)
        assertEquals(PairingStatus.APPROVED, status.status)
        assertEquals(pairedDevice.token, status.approvedToken)
        assertEquals(pairedDevice.id, status.approvedDeviceId)

        // Fila pendente deve estar limpa
        assertTrue(pairingManager.getPendingRequests().isEmpty())

        // Dispositivo deve estar na lista e token válido
        val devices = pairingManager.getPairedDevices()
        assertEquals(1, devices.size)
        assertEquals(pairedDevice.id, devices[0].id)
        assertTrue(pairingManager.isTokenValid(pairedDevice.token))
    }

    @Test
    fun testRejectPairing() {
        pairingManager.isPairingEnabled = true
        val request = pairingManager.requestPairing("Dispositivo Desconhecido")
        assertNotNull(request)

        val rejected = pairingManager.rejectPairing(request.code)
        assertTrue(rejected)

        val status = pairingManager.getRequestStatus(request.id)
        assertNotNull(status)
        assertEquals(PairingStatus.REJECTED, status.status)
        assertTrue(pairingManager.getPendingRequests().isEmpty())
    }

    @Test
    fun testRevokeDevice() {
        pairingManager.isPairingEnabled = true
        val request = pairingManager.requestPairing("Galaxy Tab")
        assertNotNull(request)

        val device = pairingManager.acceptPairing(request.code)
        assertNotNull(device)
        assertTrue(pairingManager.isTokenValid(device.token))

        // Revoga dispositivo
        val revoked = pairingManager.revokeDevice(device.id)
        assertTrue(revoked)

        // Token não deve ser mais válido
        assertFalse(pairingManager.isTokenValid(device.token))
        assertTrue(pairingManager.getPairedDevices().isEmpty())
    }
}
