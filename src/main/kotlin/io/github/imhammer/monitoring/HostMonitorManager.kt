package io.github.imhammer.monitoring

import io.github.imhammer.config.ConfigManager
import io.github.imhammer.config.MonitoredTargetConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class HostMonitorManager(
    private val configManager: ConfigManager,
    private val instanceManager: InstanceManager
) {

    private val providers = ConcurrentHashMap<String, HostMonitorProvider>()
    private var scheduler: ScheduledExecutorService? = null

    init {
        // Registra os provedores padrão
        registerProvider(Pm2MonitorProvider())
    }

    fun registerProvider(provider: HostMonitorProvider) {
        providers[provider.id.lowercase()] = provider
    }

    fun getProviders(): List<HostMonitorProvider> {
        return providers.values.toList()
    }

    fun getProvider(id: String): HostMonitorProvider? {
        return providers[id.lowercase()]
    }

    fun getAvailableSectors(providerId: String): List<ProviderSectorDefinition> {
        return providers[providerId.lowercase()]?.getAvailableSectors() ?: emptyList()
    }

    fun getConfiguredMonitors(): List<MonitoredTargetConfig> {
        return configManager.config.monitors.toList()
    }

    data class AddMonitorResult(
        val success: Boolean,
        val message: String,
        val instancesFound: List<MonitoringInstance> = emptyList(),
        val warning: String? = null
    )

    fun addMonitorDetailed(
        providerId: String,
        target: String,
        sectors: List<String>? = null
    ): AddMonitorResult {
        val cleanProviderId = providerId.trim().lowercase()
        val cleanTarget = target.trim()

        val provider = providers[cleanProviderId]
            ?: return AddMonitorResult(
                success = false,
                message = "Provedor '$cleanProviderId' não encontrado. Digite 'monitor providers' para ver os suportados."
            )

        // Se foram especificados setores, valida se pertencem ao provedor
        val cleanSectors = sectors?.map { it.trim().lowercase() }?.filter { it.isNotBlank() }
        if (!cleanSectors.isNullOrEmpty()) {
            val supported = provider.getAvailableSectors().map { it.id.lowercase() }
            if (supported.isNotEmpty()) {
                val invalid = cleanSectors.filter { !supported.contains(it) }
                if (invalid.isNotEmpty()) {
                    return AddMonitorResult(
                        success = false,
                        message = "Setor(es) inválido(s) para '$cleanProviderId': ${invalid.joinToString(", ")}. Setores disponíveis: ${supported.joinToString(", ")}."
                    )
                }
            }
        }

        val monitors = configManager.config.monitors
        val existing = monitors.firstOrNull { it.providerId == cleanProviderId && it.target == cleanTarget }

        if (existing == null) {
            monitors.add(
                MonitoredTargetConfig(
                    providerId = cleanProviderId,
                    target = cleanTarget,
                    enabled = true,
                    enabledSectors = cleanSectors
                )
            )
        } else {
            existing.enabled = true
            existing.enabledSectors = cleanSectors
        }
        configManager.save()

        // Executa poll imediato para registrar os processos com os setores filtrados
        val instances = provider.pollInstances(setOf(cleanTarget))
        val filteredInstances = mutableListOf<MonitoringInstance>()
        for (inst in instances) {
            val filtered = filterInstanceSectors(inst, cleanSectors)
            instanceManager.registerOrUpdateInstance(filtered)
            filteredInstances.add(filtered)
        }

        val warning = if (instances.isEmpty()) {
            provider.lastError ?: "O provedor '$cleanProviderId' executou, mas não retornou nenhum processo ativo para o alvo '$cleanTarget'."
        } else null

        val sectorsMsg = if (!cleanSectors.isNullOrEmpty()) {
            " (Setores ativos: ${cleanSectors.joinToString(", ")})"
        } else " (Todos os setores ativos)"

        return AddMonitorResult(
            success = true,
            message = "Monitor '$cleanProviderId' para o alvo '$cleanTarget' ativado e salvo!$sectorsMsg",
            instancesFound = filteredInstances,
            warning = warning
        )
    }

    fun addMonitor(providerId: String, target: String, sectors: List<String>? = null): Boolean {
        return addMonitorDetailed(providerId, target, sectors).success
    }

    fun setMonitorSectors(providerId: String, target: String, sectors: List<String>?): Boolean {
        val cleanProviderId = providerId.trim().lowercase()
        val cleanTarget = target.trim()
        val provider = providers[cleanProviderId] ?: return false

        val cleanSectors = sectors?.map { it.trim().lowercase() }?.filter { it.isNotBlank() }
        if (!cleanSectors.isNullOrEmpty()) {
            val supported = provider.getAvailableSectors().map { it.id.lowercase() }
            if (supported.isNotEmpty() && cleanSectors.any { !supported.contains(it) }) {
                return false
            }
        }

        val monitors = configManager.config.monitors
        var updated = false

        if (cleanTarget.isBlank() || cleanTarget == "all") {
            val matching = monitors.filter { it.providerId == cleanProviderId }
            for (m in matching) {
                m.enabledSectors = cleanSectors
                updated = true
            }
        } else {
            val targetMonitor = monitors.firstOrNull { it.providerId == cleanProviderId && it.target == cleanTarget }
            if (targetMonitor != null) {
                targetMonitor.enabledSectors = cleanSectors
                updated = true
            }
        }

        if (updated) {
            configManager.save()
            pollAll()
        }

        return updated
    }

    fun filterInstanceSectors(instance: MonitoringInstance, enabledSectors: List<String>?): MonitoringInstance {
        if (enabledSectors == null) return instance
        val allowed = enabledSectors.map { it.lowercase().trim() }.toSet()
        val filteredSectors = instance.sectors.filter { allowed.contains(it.id.lowercase().trim()) }.toMutableList()
        val filteredData = instance.data.filter { allowed.contains(it.id.lowercase().trim()) }.toMutableList()
        return instance.copy(
            sectors = filteredSectors,
            data = filteredData,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun findMatchingConfig(monitors: List<MonitoredTargetConfig>, instance: MonitoringInstance): MonitoredTargetConfig? {
        val simpleName = instance.id.substringAfter(":").trim()
        val specific = monitors.firstOrNull {
            it.target.equals(simpleName, ignoreCase = true) || it.target.equals(instance.id, ignoreCase = true)
        }
        if (specific != null) return specific
        return monitors.firstOrNull { it.target.equals("all", ignoreCase = true) }
    }

    fun removeMonitor(providerId: String, target: String): Boolean {
        val cleanProviderId = providerId.trim().lowercase()
        val cleanTarget = target.trim()

        val removed = configManager.config.monitors.removeIf {
            it.providerId == cleanProviderId && (it.target == cleanTarget || cleanTarget == "all")
        }

        if (removed) {
            configManager.save()
            if (cleanTarget == "all") {
                instanceManager.removeInstancesByProvider(cleanProviderId)
            } else {
                instanceManager.removeInstance("$cleanProviderId:$cleanTarget")
            }
        }

        return removed
    }

    fun start() {
        if (scheduler != null) return

        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "host-monitor-poller").apply { isDaemon = true }
        }

        // Executa imediatamente e depois a cada 5 segundos
        scheduler?.scheduleWithFixedDelay({
            try {
                pollAll()
            } catch (_: Exception) {
                // Previne crash do thread agendado
            }
        }, 1, 5, TimeUnit.SECONDS)
    }

    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    fun pollAll() {
        val monitors = configManager.config.monitors.filter { it.enabled }
        if (monitors.isEmpty()) return

        for (provider in providers.values) {
            val monitorsForProvider = monitors.filter { it.providerId.equals(provider.id, ignoreCase = true) }
            val targetsForProvider = monitorsForProvider
                .map { it.target }
                .toSet()

            if (targetsForProvider.isNotEmpty()) {
                val instances = provider.pollInstances(targetsForProvider)
                for (inst in instances) {
                    val matchingConfig = findMatchingConfig(monitorsForProvider, inst)
                    val sectorsToUse = matchingConfig?.enabledSectors
                        ?: configManager.config.providerDefaultSectors[provider.id]
                    val filtered = filterInstanceSectors(inst, sectorsToUse)
                    instanceManager.registerOrUpdateInstance(filtered)
                }
            }
        }
    }
}
