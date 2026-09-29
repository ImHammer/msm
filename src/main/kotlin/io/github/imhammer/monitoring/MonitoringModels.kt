package io.github.imhammer.monitoring

import kotlinx.serialization.Serializable

@Serializable
enum class InstanceSource {
    SDK,
    HOST
}

@Serializable
enum class SectorType {
    COUNTER,
    GAUGE,
    LOG_STREAM,
    STATUS
}

@Serializable
enum class ValueType {
    PERCENTAGE,
    CURRENCY,
    BYTES,
    STATUS,
    NUMBER,
    LOG_LINE,
    BOOLEAN,
    TEXT
}

@Serializable
enum class SectorCategory {
    INFRASTRUCTURE,
    BUSINESS,
    PERFORMANCE,
    LOGS_ERRORS,
    CUSTOM
}

@Serializable
data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val message: String,
    val details: String? = null,
    val category: SectorCategory = SectorCategory.CUSTOM
)

@Serializable
data class ProviderSectorDefinition(
    val id: String,
    val name: String,
    val description: String = "",
    val defaultEnabled: Boolean = true,
    val type: SectorType = SectorType.GAUGE,
    val valueType: ValueType = ValueType.NUMBER,
    val unit: String? = null
)

/**
 * Representa um dado / métrica / log individual.
 * Pode residir diretamente dentro de uma [MonitoringInstance] (caso não use setores)
 * ou dentro de um [Sector] específico.
 */
@Serializable
data class MonitoringData(
    val id: String,
    var name: String,
    var type: SectorType = SectorType.GAUGE,
    var valueType: ValueType = ValueType.TEXT,
    var category: SectorCategory = SectorCategory.CUSTOM,
    var unit: String? = null,
    var lastValue: String? = null,
    var details: String? = null,
    var lastUpdatedAt: Long = System.currentTimeMillis(),
    val logs: MutableList<LogEntry> = mutableListOf()
)

/**
 * Representa uma parte/módulo funcional de uma instância/projeto (ex: "Autenticação", "Pagamentos", "Pedidos").
 * Um setor agrupa N dados ([MonitoringData]).
 */
@Serializable
data class Sector(
    val id: String,
    var name: String,
    var category: SectorCategory = SectorCategory.CUSTOM,
    val data: MutableList<MonitoringData> = mutableListOf(),
    // Propriedades mantidas para retrocompatibilidade quando um setor for tratado como canal único:
    var type: SectorType = SectorType.GAUGE,
    var valueType: ValueType = ValueType.TEXT,
    var unit: String? = null,
    var lastValue: String? = null,
    var details: String? = null,
    var lastUpdatedAt: Long = System.currentTimeMillis()
)

/**
 * Representa uma instância (projeto / processo / microsserviço) hospedada na VPS.
 * Hierarquia: VPS -> Instância -> Setores (opcionais) -> Dados.
 * Suporta tanto envio de dados diretos ([data]) quanto organizados por setores ([sectors]).
 */
@Serializable
data class MonitoringInstance(
    val id: String,
    var name: String,
    val source: InstanceSource,
    val providerId: String? = null,
    val data: MutableList<MonitoringData> = mutableListOf(), // Dados diretos na instância (sem setor)
    val sectors: MutableList<Sector> = mutableListOf(),      // Setores funcionais do projeto
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)

/**
 * Evento emitido em tempo real (via IPC socket ou WebSocket).
 * Se [sectorId] for nulo, refere-se a um dado direto no nível da instância.
 * Se [sectorId] for informado, refere-se ao setor correspondente.
 */
@Serializable
data class DataEvent(
    val instanceId: String,
    val sectorId: String? = null,
    val value: String,
    val dataId: String? = null,
    val name: String? = null,
    val valueType: ValueType = ValueType.TEXT,
    val category: SectorCategory = SectorCategory.CUSTOM,
    val unit: String? = null,
    val details: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Retorna o ID efetivo do dado (usa [dataId], ou [sectorId], ou fallback para "default").
     */
    val effectiveDataId: String
        get() = dataId ?: sectorId ?: "default"
}

typealias SectorEvent = DataEvent
