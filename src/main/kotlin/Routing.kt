package io.github.imhammer

import io.github.imhammer.config.ConfigManager
import io.github.imhammer.monitoring.InstanceManager
import io.github.imhammer.monitoring.SectorEvent
import io.github.imhammer.monitoring.DataEvent
import io.github.imhammer.monitoring.MonitoringInstance
import io.github.imhammer.pairing.PairingManager
import io.github.imhammer.pairing.PairingStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

import io.github.imhammer.config.MonitoredTargetConfig
import io.github.imhammer.config.PairedDevice
import io.github.imhammer.monitoring.HostMonitorManager

@Serializable
data class PairingRequestBody(
    val deviceName: String = "Mobile Device",
    val clientInfo: String? = null
)

@Serializable
data class PairingResponse(
    val requestId: String,
    val code: String,
    val expiresInSeconds: Long,
    val status: String
)

@Serializable
data class PairingStatusResponse(
    val status: String,
    val deviceId: String? = null,
    val token: String? = null
)

@Serializable
data class AgentStatusResponse(
    val name: String,
    val status: String,
    val port: Int,
    val pairingEnabled: Boolean,
    val version: String = "1.0.0",
    val devicesCount: Int = 0,
    val pendingPairingCount: Int = 0,
    val monitorsCount: Int = 0,
    val instancesCount: Int = 0
)

@Serializable
data class ErrorResponse(
    val error: String
)

@Serializable
data class SystemStatsResponse(
    val cpu: Double,
    val memory: MemoryStats
)

@Serializable
data class AdminQueryRequest(
    val query: String = ""
)

@Serializable
data class AdminToggleRequest(
    val enabled: Boolean = true
)

@Serializable
data class AdminMonitorRequest(
    val providerId: String,
    val target: String,
    val sectors: List<String>? = null
)

@Serializable
data class AdminActionResult(
    val success: Boolean,
    val message: String
)

@Serializable
data class ProviderInfoDto(
    val id: String,
    val displayName: String,
    val isAvailable: Boolean,
    val availableSectors: List<io.github.imhammer.monitoring.ProviderSectorDefinition> = emptyList()
)

private fun ApplicationCall.isLocalOrAdmin(configManager: ConfigManager): Boolean {
    val host = request.local.remoteAddress
    val secret = request.headers["X-MSM-Secret"]
    val isLocal = host in listOf("127.0.0.1", "0:0:0:0:0:0:0:1", "::1", "localhost")
    val matchesSecret = !secret.isNullOrBlank() && secret == configManager.config.secretKey
    return isLocal || matchesSecret
}

@JvmOverloads
fun Application.configureRouting(
    configManager: ConfigManager = ConfigManager(),
    pairingManager: PairingManager = PairingManager(configManager),
    systemMonitor: SystemMonitor = SystemMonitor(),
    instanceManager: InstanceManager = InstanceManager(),
    hostMonitorManager: HostMonitorManager? = null
) {
    routing {
        get("/") {
            call.respondText("MSM Agent is active and running.")
        }

        get("/api/status") {
            val config = configManager.config
            val devices = pairingManager.getPairedDevices()
            val pending = pairingManager.getPendingRequests()
            val monitors = hostMonitorManager?.getConfiguredMonitors() ?: config.monitors
            val instances = instanceManager.getInstances()
            call.respond(
                AgentStatusResponse(
                    name = config.name,
                    status = "online",
                    port = config.port,
                    pairingEnabled = pairingManager.isPairingEnabled,
                    devicesCount = devices.size,
                    pendingPairingCount = pending.size,
                    monitorsCount = monitors.size,
                    instancesCount = instances.size
                )
            )
        }

        get("/api/system") {
            val cpu = systemMonitor.getCpuUsage()
            val memory = systemMonitor.getMemoryUsage()
            call.respond(
                SystemStatsResponse(
                    cpu = cpu,
                    memory = memory
                )
            )
        }

        // Rotas administrativas protegidas (Console CLI Local / Attach)
        route("/api/admin") {
            get("/pairing/requests") {
                if (!call.isLocalOrAdmin(configManager)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Acesso não autorizado."))
                    return@get
                }
                call.respond(pairingManager.getPendingRequests())
            }

            post("/pairing/accept") {
                if (!call.isLocalOrAdmin(configManager)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Acesso não autorizado."))
                    return@post
                }
                val req = try { call.receive<AdminQueryRequest>() } catch (_: Exception) { AdminQueryRequest() }
                val approved = pairingManager.acceptPairing(req.query)
                if (approved != null) {
                    call.respond(AdminActionResult(true, "Dispositivo '${approved.name}' (ID: ${approved.id}) aprovado com sucesso!"))
                } else {
                    call.respond(HttpStatusCode.NotFound, AdminActionResult(false, "Nenhuma solicitação pendente encontrada para '${req.query}'."))
                }
            }

            post("/pairing/reject") {
                if (!call.isLocalOrAdmin(configManager)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Acesso não autorizado."))
                    return@post
                }
                val req = try { call.receive<AdminQueryRequest>() } catch (_: Exception) { AdminQueryRequest() }
                val rejected = pairingManager.rejectPairing(req.query)
                if (rejected) {
                    call.respond(AdminActionResult(true, "Solicitação '${req.query}' recusada."))
                } else {
                    call.respond(HttpStatusCode.NotFound, AdminActionResult(false, "Nenhuma solicitação pendente encontrada para '${req.query}'."))
                }
            }

            post("/pairing/toggle") {
                if (!call.isLocalOrAdmin(configManager)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Acesso não autorizado."))
                    return@post
                }
                val req = try { call.receive<AdminToggleRequest>() } catch (_: Exception) { AdminToggleRequest() }
                pairingManager.isPairingEnabled = req.enabled
                call.respond(AdminActionResult(true, "Modo de vinculação ${if (req.enabled) "ATIVADO" else "DESATIVADO"} com sucesso."))
            }

            get("/devices") {
                if (!call.isLocalOrAdmin(configManager)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Acesso não autorizado."))
                    return@get
                }
                call.respond(pairingManager.getPairedDevices())
            }

            delete("/devices/{id}") {
                if (!call.isLocalOrAdmin(configManager)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Acesso não autorizado."))
                    return@delete
                }
                val id = call.parameters["id"] ?: ""
                val revoked = pairingManager.revokeDevice(id)
                if (revoked) {
                    call.respond(AdminActionResult(true, "Dispositivo '$id' revogado com sucesso."))
                } else {
                    call.respond(HttpStatusCode.NotFound, AdminActionResult(false, "Dispositivo '$id' não encontrado."))
                }
            }

            get("/monitors") {
                if (!call.isLocalOrAdmin(configManager)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Acesso não autorizado."))
                    return@get
                }
                val monitors = hostMonitorManager?.getConfiguredMonitors() ?: configManager.config.monitors
                call.respond(monitors)
            }

            get("/monitors/providers") {
                if (!call.isLocalOrAdmin(configManager)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Acesso não autorizado."))
                    return@get
                }
                val provs = hostMonitorManager?.getProviders()?.map {
                    ProviderInfoDto(it.id, it.displayName, it.isAvailable(), it.getAvailableSectors())
                } ?: emptyList()
                call.respond(provs)
            }

            post("/monitors/add") {
                if (!call.isLocalOrAdmin(configManager)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Acesso não autorizado."))
                    return@post
                }
                val req = try { call.receive<AdminMonitorRequest>() } catch (_: Exception) { AdminMonitorRequest("", "") }
                if (hostMonitorManager != null) {
                    val result = hostMonitorManager.addMonitorDetailed(req.providerId, req.target, req.sectors)
                    call.respond(AdminActionResult(result.success, result.message + (result.warning?.let { " ($it)" } ?: "")))
                } else {
                    call.respond(HttpStatusCode.InternalServerError, AdminActionResult(false, "HostMonitorManager não inicializado."))
                }
            }

            post("/monitors/sectors") {
                if (!call.isLocalOrAdmin(configManager)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Acesso não autorizado."))
                    return@post
                }
                val req = try { call.receive<AdminMonitorRequest>() } catch (_: Exception) { AdminMonitorRequest("", "") }
                if (hostMonitorManager != null) {
                    val updated = hostMonitorManager.setMonitorSectors(req.providerId, req.target, req.sectors)
                    if (updated) {
                        val sectorsDesc = req.sectors?.joinToString(", ") ?: "todos os disponíveis"
                        call.respond(AdminActionResult(true, "Setores do monitor '${req.providerId}:${req.target}' atualizados com sucesso para: [$sectorsDesc]."))
                    } else {
                        call.respond(HttpStatusCode.NotFound, AdminActionResult(false, "Monitor ou provedor não encontrado ou setores inválidos."))
                    }
                } else {
                    call.respond(HttpStatusCode.InternalServerError, AdminActionResult(false, "HostMonitorManager não inicializado."))
                }
            }

            post("/monitors/remove") {
                if (!call.isLocalOrAdmin(configManager)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Acesso não autorizado."))
                    return@post
                }
                val req = try { call.receive<AdminMonitorRequest>() } catch (_: Exception) { AdminMonitorRequest("", "") }
                if (hostMonitorManager != null) {
                    val removed = hostMonitorManager.removeMonitor(req.providerId, req.target)
                    if (removed) {
                        call.respond(AdminActionResult(true, "Monitor '${req.providerId}' para '${req.target}' removido com sucesso."))
                    } else {
                        call.respond(HttpStatusCode.NotFound, AdminActionResult(false, "Monitor não encontrado."))
                    }
                } else {
                    call.respond(HttpStatusCode.InternalServerError, AdminActionResult(false, "HostMonitorManager não inicializado."))
                }
            }

            get("/monitors/diag/{provider}") {
                if (!call.isLocalOrAdmin(configManager)) {
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("Acesso não autorizado."))
                    return@get
                }
                val providerId = call.parameters["provider"] ?: "pm2"
                val provider = hostMonitorManager?.getProvider(providerId)
                if (provider != null) {
                    call.respondText(provider.getDiagnosticInfo())
                } else {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Provedor '$providerId' não encontrado."))
                }
            }
        }

        // Endpoint para listar instâncias e setores ativos (PM2 + SDK)
        get("/api/instances") {
            call.respond(instanceManager.getInstances())
        }

        // Endpoint para o aplicativo mobile solicitar pareamento
        post("/api/pairing/request") {
            if (!pairingManager.isPairingEnabled) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("A vinculação de novos dispositivos está temporariamente desativada neste Agent.")
                )
                return@post
            }

            val body = try {
                call.receive<PairingRequestBody>()
            } catch (_: Exception) {
                PairingRequestBody()
            }

            val request = pairingManager.requestPairing(body.deviceName, body.clientInfo)
            if (request == null) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Não foi possível iniciar a vinculação.")
                )
                return@post
            }

            // Exibe notificação no console em tempo real
            println("\n\u001B[33m[Pairing]\u001B[0m Nova solicitação recebida de '\u001B[36m${request.deviceName}\u001B[0m' | Código OTP: \u001B[32m\u001B[1m${request.code}\u001B[0m")
            print("msm@${configManager.config.name} > ")

            call.respond(
                HttpStatusCode.Created,
                PairingResponse(
                    requestId = request.id,
                    code = request.code,
                    expiresInSeconds = 300,
                    status = request.status.name
                )
            )
        }

        // Endpoint para o aplicativo consultar o status da sua solicitação
        get("/api/pairing/status/{requestId}") {
            val requestId = call.parameters["requestId"]
            if (requestId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("ID de solicitação ausente."))
                return@get
            }

            val request = pairingManager.getRequestStatus(requestId)
            if (request == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    PairingStatusResponse(status = PairingStatus.EXPIRED.name)
                )
                return@get
            }

            call.respond(
                PairingStatusResponse(
                    status = request.status.name,
                    deviceId = request.approvedDeviceId,
                    token = request.approvedToken
                )
            )
        }

        // Conexão WebSocket protegida para dispositivos vinculados
        webSocket("/ws") {
            val token = call.parameters["token"]
                ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()

            if (token.isNullOrBlank() || !pairingManager.isTokenValid(token)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Token de dispositivo não fornecido, inválido ou revogado."))
                return@webSocket
            }

            val device = pairingManager.getDeviceByToken(token)
            val deviceName = device?.name ?: "Dispositivo Desconhecido"

            // Notifica conexão estabelecida
            send("""{"type":"connected","agent":"${configManager.config.name}","device":"$deviceName"}""")

            // Envia catálogo inicial de instâncias
            val initialInstancesJson = Json.encodeToString(instanceManager.getInstances())
            send("""{"type":"instances","instances":$initialInstancesJson}""")

            // Registra ouvinte para repassar atualizações de catálogo de instâncias em tempo real
            val instanceListener: (MonitoringInstance) -> Unit = {
                try {
                    val instancesJson = Json.encodeToString(instanceManager.getInstances())
                    launch {
                        send("""{"type":"instances","instances":$instancesJson}""")
                    }
                } catch (_: Exception) {}
            }
            instanceManager.addInstanceListener(instanceListener)

            // Registra ouvinte para repassar eventos ao vivo do SDK
            val eventListener: (DataEvent) -> Unit = { event ->
                try {
                    val eventJson = Json.encodeToString(event)
                    launch {
                        send("""{"type":"data_event","event":$eventJson}""")
                        if (!event.sectorId.isNullOrBlank()) {
                            send("""{"type":"sector_event","event":$eventJson}""")
                        }
                    }
                } catch (_: Exception) {}
            }
            instanceManager.addEventListener(eventListener)

            try {
                while (true) {
                    // Se o dispositivo foi revogado durante a conexão ativa, desconecta imediatamente
                    if (!pairingManager.isTokenValid(token)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "O acesso deste dispositivo foi revogado."))
                        break
                    }

                    delay(5000)

                    val cpu = systemMonitor.getCpuUsage()
                    val memory = systemMonitor.getMemoryUsage()
                    val instancesJson = Json.encodeToString(instanceManager.getInstances())

                    val message = """
                        {
                            "type":"stats",
                            "cpu":$cpu,
                            "memory":{
                                "used":${memory.used},
                                "total":${memory.total},
                                "percentage":${memory.percentage}
                            },
                            "instances":$instancesJson
                        }
                    """.trimIndent()

                    send(message)
                }
            } finally {
                instanceManager.removeEventListener(eventListener)
                instanceManager.removeInstanceListener(instanceListener)
            }
        }
    }
}