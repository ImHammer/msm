package io.github.imhammer.pairing

import io.github.imhammer.config.ConfigManager
import io.github.imhammer.config.PairedDevice
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class PairingStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED
}

@Serializable
data class PairingRequest(
    val id: String,
    val code: String,
    val deviceName: String,
    val clientInfo: String? = null,
    val requestedAt: Long = System.currentTimeMillis(),
    var status: PairingStatus = PairingStatus.PENDING,
    var approvedToken: String? = null,
    var approvedDeviceId: String? = null
)

class PairingManager(private val configManager: ConfigManager) {

    private val secureRandom = SecureRandom()
    private val pendingRequests = ConcurrentHashMap<String, PairingRequest>()
    private val requestExpiryMillis = 5 * 60 * 1000L // 5 minutos

    var isPairingEnabled: Boolean
        get() = configManager.config.pairingEnabled
        set(value) {
            configManager.config.pairingEnabled = value
            configManager.save()
        }

    fun requestPairing(deviceName: String, clientInfo: String? = null): PairingRequest? {
        if (!isPairingEnabled) return null

        cleanExpiredRequests()

        val id = UUID.randomUUID().toString()
        val code = String.format("%06d", secureRandom.nextInt(1_000_000))
        val request = PairingRequest(
            id = id,
            code = code,
            deviceName = deviceName.trim().ifEmpty { "Dispositivo Sem Nome" },
            clientInfo = clientInfo
        )

        pendingRequests[id] = request
        return request
    }

    fun getRequestStatus(requestId: String): PairingRequest? {
        cleanExpiredRequests()
        return pendingRequests[requestId]
    }

    fun getPendingRequests(): List<PairingRequest> {
        cleanExpiredRequests()
        return pendingRequests.values.filter { it.status == PairingStatus.PENDING }.sortedBy { it.requestedAt }
    }

    fun acceptPairing(query: String): PairedDevice? {
        cleanExpiredRequests()
        val target = pendingRequests.values.firstOrNull {
            it.status == PairingStatus.PENDING && (it.code == query.trim() || it.id.startsWith(query.trim()))
        } ?: return null

        val deviceId = UUID.randomUUID().toString()
        val token = generateSecureToken()
        val now = System.currentTimeMillis()

        val pairedDevice = PairedDevice(
            id = deviceId,
            name = target.deviceName,
            token = token,
            pairedAt = now,
            lastSeenAt = now
        )

        // Salva nas configurações
        configManager.config.devices.add(pairedDevice)
        configManager.save()

        // Marca como aprovado na solicitação para o cliente receber o token
        target.status = PairingStatus.APPROVED
        target.approvedToken = token
        target.approvedDeviceId = deviceId

        return pairedDevice
    }

    fun rejectPairing(query: String): Boolean {
        cleanExpiredRequests()
        val target = pendingRequests.values.firstOrNull {
            it.status == PairingStatus.PENDING && (it.code == query.trim() || it.id.startsWith(query.trim()))
        } ?: return false

        target.status = PairingStatus.REJECTED
        return true
    }

    fun getPairedDevices(): List<PairedDevice> {
        return configManager.config.devices.toList()
    }

    fun revokeDevice(deviceId: String): Boolean {
        val trimmed = deviceId.trim()
        val removed = configManager.config.devices.removeIf { it.id == trimmed || it.id.startsWith(trimmed) }
        if (removed) {
            configManager.save()
        }
        return removed
    }

    fun getDeviceByToken(token: String): PairedDevice? {
        val cleanToken = token.trim()
        val device = configManager.config.devices.firstOrNull { it.token == cleanToken }
        if (device != null) {
            device.lastSeenAt = System.currentTimeMillis()
            configManager.save()
        }
        return device
    }

    fun isTokenValid(token: String): Boolean {
        return getDeviceByToken(token) != null
    }

    private fun cleanExpiredRequests() {
        val now = System.currentTimeMillis()
        val expiredIds = pendingRequests.filter { (_, req) ->
            (now - req.requestedAt) > requestExpiryMillis
        }.keys

        for (id in expiredIds) {
            pendingRequests.remove(id)
        }
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
