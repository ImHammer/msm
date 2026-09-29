package io.github.imhammer

import kotlinx.serialization.Serializable
import oshi.SystemInfo

class SystemMonitor {

    private val systemInfo = SystemInfo()

    private val cpu = systemInfo.hardware.processor
    private val memory = systemInfo.hardware.memory

    private var previousCpuTicks = cpu.systemCpuLoadTicks

    fun getCpuUsage(): Double {
        val currentCpuTicks = cpu.systemCpuLoadTicks

        val usage = cpu.getSystemCpuLoadBetweenTicks(previousCpuTicks)

        previousCpuTicks = currentCpuTicks

        return usage * 100
    }

    fun getMemoryUsage(): MemoryStats {

        val total = memory.total
        val available = memory.available
        val used = total - available

        return MemoryStats(
            used = used,
            total = total,
            percentage = (used.toDouble() / total) * 100
        )
    }
}

@Serializable
data class MemoryStats(
    val used: Long,
    val total: Long,
    val percentage: Double
)