package io.github.imhammer.monitoring

import io.github.imhammer.config.ConfigManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.concurrent.thread

@Serializable
data class SdkDataPayload(
    val id: String,
    val name: String? = null,
    val type: String = "GAUGE",
    val valueType: String = "TEXT",
    val category: String? = null,
    val unit: String? = null
)

@Serializable
data class SdkSectorPayload(
    val id: String,
    val name: String,
    val category: String = "CUSTOM",
    val data: List<SdkDataPayload>? = null,
    val type: String = "GAUGE",
    val valueType: String = "TEXT",
    val unit: String? = null
)

@Serializable
data class SdkMessage(
    val action: String,
    val token: String? = null,                 // Token de autenticação do SDK
    val instanceId: String? = null,
    val name: String? = null,
    val sectorId: String? = null,              // Opcional: nulo indica dado direto na instância
    val dataId: String? = null,                // ID do dado/métrica
    val value: String? = null,
    val valueType: String? = null,
    val category: String? = null,
    val unit: String? = null,
    val details: String? = null,
    val data: List<SdkDataPayload>? = null,    // Dados diretos na instância
    val sectors: List<SdkSectorPayload>? = null // Setores da instância
)

@Serializable
data class SdkResponse(
    val status: String,
    val message: String? = null
)

class EventSocketServer(
    private val instanceManager: InstanceManager,
    private val configManager: ConfigManager? = null,
    customSocketPath: String? = null
) {

    private val json = Json { ignoreUnknownKeys = true }
    private var serverChannel: ServerSocketChannel? = null
    private var isRunning = false
    val socketFile: File

    private val maxPayloadLength = 65536 // 64 KB por mensagem para prevenção de DoS

    init {
        socketFile = if (!customSocketPath.isNullOrBlank()) {
            File(customSocketPath)
        } else {
            val isLinux = System.getProperty("os.name").lowercase().contains("linux")
            if (isLinux) {
                val runDir = File("/run/msm")
                if (runDir.exists() || runDir.mkdirs()) {
                    File(runDir, "events.sock")
                } else {
                    File(System.getProperty("user.home"), ".msm/events.sock")
                }
            } else {
                File(".msm/events.sock")
            }
        }
    }

    fun start() {
        if (isRunning) return

        try {
            socketFile.parentFile?.mkdirs()
            if (socketFile.exists()) {
                socketFile.delete()
            }

            val address = UnixDomainSocketAddress.of(socketFile.toPath())
            serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply {
                bind(address)
            }

            try {
                socketFile.setReadable(true, false)
                socketFile.setWritable(true, false)
            } catch (_: Exception) {}

            isRunning = true

            thread(name = "msm-events-socket-acceptor", isDaemon = true) {
                while (isRunning) {
                    try {
                        val clientChannel = serverChannel?.accept() ?: break
                        thread(name = "msm-sdk-client-worker", isDaemon = true) {
                            handleClient(clientChannel)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[EventSocketServer] Aviso: Não foi possível iniciar o Unix Domain Socket em '${socketFile.absolutePath}': ${e.message}")
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverChannel?.close()
        } catch (_: Exception) {}
        serverChannel = null

        if (socketFile.exists()) {
            socketFile.delete()
        }
    }

    private fun handleClient(channel: SocketChannel) {
        channel.use { ch ->
            val reader = BufferedReader(InputStreamReader(Channels.newInputStream(ch), Charsets.UTF_8))
            val writer = PrintWriter(Channels.newOutputStream(ch), true, Charsets.UTF_8)
            var isAuthenticated = configManager?.config?.sdkToken.isNullOrBlank()

            while (isRunning) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue

                // Verificação de segurança: Limite de tamanho de linha (Anti-DoS)
                if (line.length > maxPayloadLength) {
                    writer.println(json.encodeToString(SdkResponse("ERROR", "Payload excede o tamanho máximo de 64 KB.")))
                    break
                }

                val (response, authSuccess) = processMessage(line.trim(), isAuthenticated)
                if (authSuccess) {
                    isAuthenticated = true
                }
                writer.println(json.encodeToString(response))
            }
        }
    }

    private fun processMessage(rawMessage: String, currentlyAuthenticated: Boolean): Pair<SdkResponse, Boolean> {
        return try {
            val msg = json.decodeFromString<SdkMessage>(rawMessage)
            val expectedToken = configManager?.config?.sdkToken

            // Verifica se o token foi passado nesta mensagem específica
            val tokenMatches = !expectedToken.isNullOrBlank() && msg.token == expectedToken
            val isAuthNow = currentlyAuthenticated || tokenMatches || expectedToken.isNullOrBlank()

            when (msg.action.uppercase()) {
                "AUTH" -> {
                    if (expectedToken.isNullOrBlank() || msg.token == expectedToken) {
                        Pair(SdkResponse("OK", "Autenticado com sucesso."), true)
                    } else {
                        Pair(SdkResponse("ERROR", "Autenticação rejeitada: Token do SDK inválido."), false)
                    }
                }

                "PING" -> {
                    Pair(SdkResponse("PONG"), isAuthNow)
                }

                else -> {
                    if (!isAuthNow) {
                        return Pair(SdkResponse("ERROR", "Acesso não autorizado: Você deve autenticar primeiro com { action: 'AUTH', token: '...' } ou fornecer o campo 'token'."), false)
                    }

                    when (msg.action.uppercase()) {
                        "REGISTER_INSTANCE" -> {
                            val id = PayloadValidator.validateId(msg.instanceId, "instanceId")
                            val name = PayloadValidator.validateName(msg.name, id)
                            val now = System.currentTimeMillis()

                            // 1. Dados diretos da instância
                            val directData = msg.data?.map { d ->
                                val dataId = PayloadValidator.validateId(d.id, "data.id")
                                val dataName = PayloadValidator.validateName(d.name, dataId)
                                val dataType = try { SectorType.valueOf(d.type.uppercase()) } catch (_: Exception) { SectorType.GAUGE }
                                val valueType = try { ValueType.valueOf(d.valueType.uppercase()) } catch (_: Exception) { ValueType.TEXT }
                                val category = d.category?.let {
                                    try { SectorCategory.valueOf(it.uppercase()) } catch (_: Exception) { null }
                                } ?: SectorCategory.CUSTOM
                                val unit = PayloadValidator.validateUnit(d.unit)
                                MonitoringData(id = dataId, name = dataName, type = dataType, valueType = valueType, category = category, unit = unit, lastUpdatedAt = now)
                            }?.toMutableList() ?: mutableListOf()

                            // 2. Setores da instância (com seus respectivos dados)
                            val sectors = msg.sectors?.map { s ->
                                val sectorId = PayloadValidator.validateId(s.id, "sector.id")
                                val sectorName = PayloadValidator.validateName(s.name, sectorId)
                                val category = try { SectorCategory.valueOf(s.category.uppercase()) } catch (_: Exception) { SectorCategory.CUSTOM }

                                val sectorData = s.data?.map { d ->
                                    val dId = PayloadValidator.validateId(d.id, "sector.data.id")
                                    val dName = PayloadValidator.validateName(d.name, dId)
                                    val dType = try { SectorType.valueOf(d.type.uppercase()) } catch (_: Exception) { SectorType.GAUGE }
                                    val dValueType = try { ValueType.valueOf(d.valueType.uppercase()) } catch (_: Exception) { ValueType.TEXT }
                                    val dCategory = d.category?.let {
                                        try { SectorCategory.valueOf(it.uppercase()) } catch (_: Exception) { null }
                                    } ?: category
                                    val dUnit = PayloadValidator.validateUnit(d.unit)
                                    MonitoringData(id = dId, name = dName, type = dType, valueType = dValueType, category = dCategory, unit = dUnit, lastUpdatedAt = now)
                                }?.toMutableList() ?: mutableListOf()

                                Sector(
                                    id = sectorId,
                                    name = sectorName,
                                    category = category,
                                    data = sectorData,
                                    lastUpdatedAt = now
                                )
                            }?.toMutableList() ?: mutableListOf()

                            val instance = MonitoringInstance(
                                id = id,
                                name = name,
                                source = InstanceSource.SDK,
                                providerId = "sdk",
                                data = directData,
                                sectors = sectors,
                                updatedAt = now
                            )

                            instanceManager.registerOrUpdateInstance(instance)
                            Pair(SdkResponse("OK", "Instância '$id' registrada com sucesso (${directData.size} dados diretos, ${sectors.size} setores)."), isAuthNow)
                        }

                        "PUSH_EVENT" -> {
                            val id = PayloadValidator.validateId(msg.instanceId, "instanceId")

                            val rawSectorId = if (!msg.sectorId.isNullOrBlank()) {
                                PayloadValidator.validateId(msg.sectorId, "sectorId")
                            } else null

                            val rawDataId = msg.dataId ?: rawSectorId
                            val finalDataId = PayloadValidator.validateId(rawDataId, "dataId (ou sectorId)")

                            val existingInstance = instanceManager.getInstance(id)
                            val finalSectorId = if (msg.dataId != null) {
                                rawSectorId
                            } else {
                                if (existingInstance?.sectors?.any { it.id == rawSectorId } == true) {
                                    rawSectorId
                                } else {
                                    null
                                }
                            }

                            val sec = if (finalSectorId != null) {
                                existingInstance?.sectors?.firstOrNull { it.id == finalSectorId }
                            } else null

                            if (existingInstance != null) {
                                if (finalSectorId != null) {
                                    if (sec == null && existingInstance.sectors.isNotEmpty()) {
                                        return Pair(SdkResponse("ERROR", "Setor '$finalSectorId' não existe no registro da instância '$id'."), isAuthNow)
                                    }
                                    if (sec != null && sec.data.isNotEmpty() && sec.data.none { it.id == finalDataId }) {
                                        return Pair(SdkResponse("ERROR", "Métrica '$finalDataId' não existe no setor '$finalSectorId'."), isAuthNow)
                                    }
                                } else {
                                    if (existingInstance.data.isNotEmpty() && existingInstance.data.none { it.id == finalDataId }) {
                                        return Pair(SdkResponse("ERROR", "Métrica direta '$finalDataId' não existe no registro da instância '$id'."), isAuthNow)
                                    }
                                }
                            }

                            val existingData = if (sec != null) {
                                sec.data.firstOrNull { it.id == finalDataId }
                            } else {
                                existingInstance?.data?.firstOrNull { it.id == finalDataId }
                            }

                            val targetValueType = existingData?.valueType ?: (msg.valueType?.let {
                                try { ValueType.valueOf(it.uppercase()) } catch (_: Exception) { null }
                            } ?: ValueType.TEXT)

                            val targetCategory = existingData?.category?.takeIf { it != SectorCategory.CUSTOM }
                                ?: msg.category?.let {
                                    try { SectorCategory.valueOf(it.uppercase()) } catch (_: Exception) { null }
                                }?.takeIf { it != SectorCategory.CUSTOM }
                                ?: sec?.category
                                ?: SectorCategory.CUSTOM

                            val targetUnit = existingData?.unit ?: PayloadValidator.validateUnit(msg.unit)
                            val targetName = existingData?.name ?: msg.name

                            val cleanValue = PayloadValidator.validateValue(msg.value, targetValueType)
                            val cleanDetails = msg.details?.take(4096)

                            val event = DataEvent(
                                instanceId = id,
                                sectorId = finalSectorId,
                                dataId = finalDataId,
                                name = targetName,
                                value = cleanValue,
                                valueType = targetValueType,
                                category = targetCategory,
                                unit = targetUnit,
                                details = cleanDetails,
                                timestamp = System.currentTimeMillis()
                            )

                            val success = instanceManager.pushEvent(event)
                            if (success) {
                                Pair(SdkResponse("OK"), isAuthNow)
                            } else if (existingInstance != null) {
                                Pair(SdkResponse("ERROR", "Métrica ou setor não compatível com o schema registrado."), isAuthNow)
                            } else {
                                val now = System.currentTimeMillis()
                                val newInstance = MonitoringInstance(
                                    id = id,
                                    name = id,
                                    source = InstanceSource.SDK,
                                    providerId = "sdk",
                                    data = mutableListOf(),
                                    sectors = mutableListOf(),
                                    updatedAt = now
                                )
                                instanceManager.registerOrUpdateInstance(newInstance)
                                instanceManager.pushEvent(event)
                                Pair(SdkResponse("OK", "Instância criada dinamicamente e evento registrado."), isAuthNow)
                            }
                        }

                        else -> Pair(SdkResponse("ERROR", "Ação desconhecida: '${msg.action}'"), isAuthNow)
                    }
                }
            }
        } catch (e: IllegalArgumentException) {
            Pair(SdkResponse("ERROR", "Validação rejeitada: ${e.message}"), currentlyAuthenticated)
        } catch (e: Exception) {
            Pair(SdkResponse("ERROR", "Falha ao processar mensagem JSON: ${e.message}"), currentlyAuthenticated)
        }
    }
}
