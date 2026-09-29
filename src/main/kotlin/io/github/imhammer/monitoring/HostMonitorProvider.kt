package io.github.imhammer.monitoring

interface HostMonitorProvider {
    val id: String
    val displayName: String
    fun isAvailable(): Boolean
    fun listAvailableTargets(): List<String>
    fun getAvailableSectors(): List<ProviderSectorDefinition> = emptyList()
    fun pollInstances(targets: Set<String>): List<MonitoringInstance>
    fun getDiagnosticInfo(): String = "Diagnóstico não suportado para este provedor."
    val lastError: String? get() = null
}
