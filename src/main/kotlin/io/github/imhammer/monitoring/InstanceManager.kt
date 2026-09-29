package io.github.imhammer.monitoring

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class InstanceManager {

    private val instances = ConcurrentHashMap<String, MonitoringInstance>()
    private val eventListeners = CopyOnWriteArrayList<(SectorEvent) -> Unit>()
    private val instanceListeners = CopyOnWriteArrayList<(MonitoringInstance) -> Unit>()

    fun registerOrUpdateInstance(instance: MonitoringInstance): MonitoringInstance {
        val existing = instances[instance.id]
        val result = if (existing != null) {
            existing.updatedAt = System.currentTimeMillis()
            if (instance.name.isNotBlank() && (existing.name == existing.id || instance.name != instance.id)) {
                existing.name = instance.name
            }

            // Se a nova instância veio de HOST ou com dados definidos, remove os dados que não estão no novo schema
            if (instance.source == InstanceSource.HOST || instance.data.isNotEmpty()) {
                existing.data.retainAll { d -> instance.data.any { it.id.equals(d.id, ignoreCase = true) } }
            }

            // Mescla dados diretos da instância
            for (newData in instance.data) {
                val oldData = existing.data.firstOrNull { it.id.equals(newData.id, ignoreCase = true) }
                if (oldData != null) {
                    if (newData.name.isNotBlank() && (oldData.name == oldData.id || newData.name != newData.id)) {
                        oldData.name = newData.name
                    }
                    oldData.type = newData.type
                    oldData.valueType = newData.valueType
                    oldData.category = newData.category
                    if (newData.unit != null) {
                        oldData.unit = newData.unit
                    }
                    if (newData.lastValue != null) {
                        oldData.lastValue = newData.lastValue
                        oldData.lastUpdatedAt = newData.lastUpdatedAt
                    }
                    if (newData.details != null) {
                        oldData.details = newData.details
                    }
                } else {
                    existing.data.add(newData)
                }
            }

            // Se a nova instância veio de HOST ou com setores definidos, remove os setores que não estão no novo schema
            if (instance.source == InstanceSource.HOST || instance.sectors.isNotEmpty()) {
                existing.sectors.retainAll { s -> instance.sectors.any { it.id.equals(s.id, ignoreCase = true) } }
            }

            // Mescla setores e seus respectivos dados
            for (newSector in instance.sectors) {
                val oldSector = existing.sectors.firstOrNull { it.id == newSector.id }
                if (oldSector != null) {
                    if (newSector.name.isNotBlank() && (oldSector.name == oldSector.id || newSector.name != newSector.id)) {
                        oldSector.name = newSector.name
                    }
                    oldSector.category = newSector.category
                    if (newSector.lastValue != null) {
                        oldSector.lastValue = newSector.lastValue
                        oldSector.lastUpdatedAt = newSector.lastUpdatedAt
                    }
                    if (newSector.details != null) {
                        oldSector.details = newSector.details
                    }

                    // Remove métricas do setor que não estão no novo schema
                    if (newSector.data.isNotEmpty()) {
                        oldSector.data.retainAll { d -> newSector.data.any { it.id == d.id } }
                    }

                    for (newSectorData in newSector.data) {
                        val oldSectorData = oldSector.data.firstOrNull { it.id == newSectorData.id }
                        if (oldSectorData != null) {
                            if (newSectorData.name.isNotBlank() && (oldSectorData.name == oldSectorData.id || newSectorData.name != newSectorData.id)) {
                                oldSectorData.name = newSectorData.name
                            }
                            oldSectorData.type = newSectorData.type
                            oldSectorData.valueType = newSectorData.valueType
                            oldSectorData.category = newSectorData.category
                            if (newSectorData.unit != null) {
                                oldSectorData.unit = newSectorData.unit
                            }
                            if (newSectorData.lastValue != null) {
                                oldSectorData.lastValue = newSectorData.lastValue
                                oldSectorData.lastUpdatedAt = newSectorData.lastUpdatedAt
                            }
                            if (newSectorData.details != null) {
                                oldSectorData.details = newSectorData.details
                            }
                        } else {
                            oldSector.data.add(newSectorData)
                        }
                    }
                } else {
                    existing.sectors.add(newSector)
                }
            }
            existing
        } else {
            instances[instance.id] = instance
            instance
        }

        notifyInstanceListeners(result)
        return result
    }

    fun getInstances(): List<MonitoringInstance> {
        return instances.values.sortedBy { it.name }
    }

    fun getInstance(id: String): MonitoringInstance? {
        return instances[id]
    }

    fun removeInstance(id: String): Boolean {
        return instances.remove(id) != null
    }

    fun removeInstancesByProvider(providerId: String) {
        val toRemove = instances.values.filter { it.providerId == providerId }.map { it.id }
        for (id in toRemove) {
            instances.remove(id)
        }
    }

    fun pushEvent(event: DataEvent): Boolean {
        val instance = instances[event.instanceId] ?: return false
        val now = event.timestamp
        val dataKey = event.effectiveDataId

        val finalEvent: DataEvent = if (!event.sectorId.isNullOrBlank()) {
            // Caso 1: Evento enviado para um SETOR específico
            var sector = instance.sectors.firstOrNull { it.id == event.sectorId }
            if (sector == null) {
                sector = Sector(
                    id = event.sectorId,
                    name = event.sectorId,
                    category = event.category,
                    lastValue = event.value,
                    details = event.details,
                    lastUpdatedAt = now
                )
                instance.sectors.add(sector)
            } else {
                sector.lastValue = event.value
                if (event.details != null) {
                    sector.details = event.details
                }
                sector.lastUpdatedAt = now
            }

            // Atualiza ou insere o dado dentro do setor
            var dataItem = sector.data.firstOrNull { it.id == dataKey }
            if (dataItem == null) {
                dataItem = MonitoringData(
                    id = dataKey,
                    name = event.name ?: dataKey,
                    valueType = event.valueType,
                    category = event.category,
                    unit = event.unit,
                    lastValue = event.value,
                    details = event.details,
                    lastUpdatedAt = now
                )
                sector.data.add(dataItem)
            } else {
                dataItem.lastValue = event.value
                if (event.details != null) {
                    dataItem.details = event.details
                }
                dataItem.lastUpdatedAt = now
            }

            if (dataItem.valueType == ValueType.LOG_LINE || dataItem.type == SectorType.LOG_STREAM) {
                val entry = LogEntry(
                    timestamp = now,
                    message = event.value,
                    details = event.details,
                    category = dataItem.category
                )
                dataItem.logs.add(entry)
                if (dataItem.logs.size > 100) {
                    dataItem.logs.removeAt(0)
                }
            }

            event.copy(
                name = event.name ?: dataItem.name,
                valueType = dataItem.valueType,
                category = dataItem.category,
                unit = event.unit ?: dataItem.unit
            )
        } else {
            // Caso 2: Evento enviado DIRETAMENTE para a INSTÂNCIA (sem setor)
            var dataItem = instance.data.firstOrNull { it.id == dataKey }
            if (dataItem == null) {
                dataItem = MonitoringData(
                    id = dataKey,
                    name = event.name ?: dataKey,
                    valueType = event.valueType,
                    category = event.category,
                    unit = event.unit,
                    lastValue = event.value,
                    details = event.details,
                    lastUpdatedAt = now
                )
                instance.data.add(dataItem)
            } else {
                dataItem.lastValue = event.value
                if (event.details != null) {
                    dataItem.details = event.details
                }
                dataItem.lastUpdatedAt = now
            }

            if (dataItem.valueType == ValueType.LOG_LINE || dataItem.type == SectorType.LOG_STREAM) {
                val entry = LogEntry(
                    timestamp = now,
                    message = event.value,
                    details = event.details,
                    category = dataItem.category
                )
                dataItem.logs.add(entry)
                if (dataItem.logs.size > 100) {
                    dataItem.logs.removeAt(0)
                }
            }

            event.copy(
                name = event.name ?: dataItem.name,
                valueType = dataItem.valueType,
                category = dataItem.category,
                unit = event.unit ?: dataItem.unit
            )
        }

        instance.updatedAt = finalEvent.timestamp
        notifyEventListeners(finalEvent)
        return true
    }

    fun addEventListener(listener: (SectorEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (SectorEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    fun addInstanceListener(listener: (MonitoringInstance) -> Unit) {
        instanceListeners.add(listener)
    }

    fun removeInstanceListener(listener: (MonitoringInstance) -> Unit) {
        instanceListeners.remove(listener)
    }

    private fun notifyEventListeners(event: SectorEvent) {
        for (listener in eventListeners) {
            try {
                listener(event)
            } catch (e: Exception) {
                // Previne que erros em um listener interrompam os demais
            }
        }
    }

    private fun notifyInstanceListeners(instance: MonitoringInstance) {
        for (listener in instanceListeners) {
            try {
                listener(instance)
            } catch (e: Exception) {
                // Previne que erros em um listener interrompam os demais
            }
        }
    }
}
