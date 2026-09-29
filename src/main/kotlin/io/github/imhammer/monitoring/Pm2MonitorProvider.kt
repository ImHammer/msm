package io.github.imhammer.monitoring

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class Pm2MonitorProvider(private val allowMockInDev: Boolean = true) : HostMonitorProvider {

    override val id: String = "pm2"
    override val displayName: String = "PM2 Process Manager"

    override var lastError: String? = null
        private set

    private val logger = LoggerFactory.getLogger(Pm2MonitorProvider::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val isWindows = System.getProperty("os.name").lowercase().contains("windows")

    data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

    override fun isAvailable(): Boolean {
        return try {
            val result = executeCommand(buildPm2Command("-v"), pm2Home = null, timeoutSeconds = 5)
            result.exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    override fun getAvailableSectors(): List<ProviderSectorDefinition> = listOf(
        ProviderSectorDefinition(
            id = "status",
            name = "Status do Processo",
            description = "Estado de execução do processo (ONLINE, STOPPED, ERRORED)",
            defaultEnabled = true,
            type = SectorType.STATUS,
            valueType = ValueType.STATUS
        ),
        ProviderSectorDefinition(
            id = "cpu",
            name = "Uso de CPU",
            description = "Percentual de consumo de processamento",
            defaultEnabled = true,
            type = SectorType.GAUGE,
            valueType = ValueType.PERCENTAGE,
            unit = "%"
        ),
        ProviderSectorDefinition(
            id = "memory",
            name = "Memória RAM",
            description = "Consumo de memória do processo",
            defaultEnabled = true,
            type = SectorType.GAUGE,
            valueType = ValueType.BYTES,
            unit = "bytes"
        ),
        ProviderSectorDefinition(
            id = "restarts",
            name = "Reinicializações (Restarts)",
            description = "Contador de reinicializações e falhas do processo",
            defaultEnabled = true,
            type = SectorType.COUNTER,
            valueType = ValueType.NUMBER,
            unit = "restarts"
        )
    )

    override fun listAvailableTargets(): List<String> {
        val targets = mutableListOf("all")
        val homes = getPrioritizedPm2Homes()
        val targetHome = homes.firstOrNull()

        val result = executeCommand(buildPm2Command("jlist"), targetHome, timeoutSeconds = 10)
        if (result.exitCode != 0) {
            return if (allowMockInDev && !isAvailable()) listOf("all", "mock-api", "mock-worker") else targets
        }

        val rawJson = extractJsonArray(result.stdout) ?: return if (allowMockInDev && !isAvailable()) listOf("all", "mock-api", "mock-worker") else targets

        try {
            val array = json.parseToJsonElement(rawJson).jsonArray
            for (element in array) {
                val name = element.jsonObject["name"]?.jsonPrimitive?.content
                if (!name.isNullOrBlank() && !targets.contains(name)) {
                    targets.add(name)
                }
            }
        } catch (_: Exception) {
            // Em caso de falha de parse, retorna apenas "all"
        }

        return targets
    }

    override fun pollInstances(targets: Set<String>): List<MonitoringInstance> {
        if (targets.isEmpty()) return emptyList()

        val homes = getPrioritizedPm2Homes()
        val allInstances = mutableListOf<MonitoringInstance>()
        val seenInstanceIds = mutableSetOf<String>()

        if (homes.isNotEmpty()) {
            for (home in homes) {
                val instances = queryInstancesFromHome(home, targets)
                for (inst in instances) {
                    if (seenInstanceIds.add(inst.id)) {
                        allInstances.add(inst)
                    }
                }
            }
        } else {
            val instances = queryInstancesFromHome(null, targets)
            allInstances.addAll(instances)
        }

        if (allInstances.isNotEmpty()) {
            lastError = null
            return allInstances
        }

        // Se PM2 não estiver disponível na máquina de dev, usa mock simulado para testes
        if (allowMockInDev && !isAvailable()) {
            return generateMockInstances(targets)
        }

        return emptyList()
    }

    private fun queryInstancesFromHome(pm2Home: File?, targets: Set<String>): List<MonitoringInstance> {
        val result = executeCommand(buildPm2Command("jlist"), pm2Home, timeoutSeconds = 15)
        if (result.exitCode != 0) {
            lastError = "PM2 [${pm2Home?.absolutePath ?: "padrão"}] falhou com código ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}"
            logger.warn(lastError)
            return emptyList()
        }

        val cleanJson = extractJsonArray(result.stdout)
        if (cleanJson == null) {
            if (result.stdout.contains("[]") || result.stdout.trim() == "[]") {
                return emptyList()
            }
            lastError = "PM2 retornou saída que não pôde ser interpretada como JSON. Saída: ${result.stdout.take(300)}"
            logger.warn(lastError)
            return emptyList()
        }

        return parsePm2Json(cleanJson, targets, pm2Home)
    }

    private fun parsePm2Json(jsonString: String, targets: Set<String>, pm2Home: File?): List<MonitoringInstance> {
        val result = mutableListOf<MonitoringInstance>()
        try {
            val array = json.parseToJsonElement(jsonString).jsonArray
            val monitorAll = targets.contains("all")

            for (element in array) {
                val obj = element.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                val pmId = obj["pm_id"]?.jsonPrimitive?.intOrNull?.toString() ?: ""

                val matches = monitorAll ||
                        targets.any { it.equals(name, ignoreCase = true) } ||
                        targets.contains(pmId)

                if (!matches) continue

                val monit = obj["monit"]?.jsonObject
                val pm2Env = obj["pm2_env"]?.jsonObject

                val status = pm2Env?.get("status")?.jsonPrimitive?.content?.uppercase() ?: "UNKNOWN"
                val cpu = monit?.get("cpu")?.jsonPrimitive?.doubleOrNull ?: 0.0
                val memoryBytes = monit?.get("memory")?.jsonPrimitive?.longOrNull ?: 0L
                val restarts = pm2Env?.get("restart_time")?.jsonPrimitive?.intOrNull ?: 0
                val now = System.currentTimeMillis()

                val directMetrics = mutableListOf(
                    MonitoringData(id = "status", name = "Status do Processo", type = SectorType.STATUS, valueType = ValueType.STATUS, category = SectorCategory.INFRASTRUCTURE, lastValue = status, lastUpdatedAt = now),
                    MonitoringData(id = "cpu", name = "Uso de CPU", type = SectorType.GAUGE, valueType = ValueType.PERCENTAGE, category = SectorCategory.INFRASTRUCTURE, unit = "%", lastValue = String.format(java.util.Locale.US, "%.1f", cpu), lastUpdatedAt = now),
                    MonitoringData(id = "memory", name = "Memória RAM", type = SectorType.GAUGE, valueType = ValueType.BYTES, category = SectorCategory.INFRASTRUCTURE, unit = "bytes", lastValue = memoryBytes.toString(), lastUpdatedAt = now),
                    MonitoringData(id = "restarts", name = "Reinicializações (Restarts)", type = SectorType.COUNTER, valueType = ValueType.NUMBER, category = SectorCategory.INFRASTRUCTURE, unit = "restarts", lastValue = "$restarts", lastUpdatedAt = now)
                )

                val instance = MonitoringInstance(
                    id = "pm2:$name",
                    name = "PM2: $name",
                    source = InstanceSource.HOST,
                    providerId = id,
                    data = directMetrics,
                    sectors = mutableListOf(
                        Sector(id = "status", name = "Status do Processo", type = SectorType.STATUS, valueType = ValueType.STATUS, category = SectorCategory.INFRASTRUCTURE, lastValue = status, lastUpdatedAt = now),
                        Sector(id = "cpu", name = "Uso de CPU", type = SectorType.GAUGE, valueType = ValueType.PERCENTAGE, category = SectorCategory.INFRASTRUCTURE, unit = "%", lastValue = String.format(java.util.Locale.US, "%.1f", cpu), lastUpdatedAt = now),
                        Sector(id = "memory", name = "Memória RAM", type = SectorType.GAUGE, valueType = ValueType.BYTES, category = SectorCategory.INFRASTRUCTURE, unit = "bytes", lastValue = memoryBytes.toString(), lastUpdatedAt = now),
                        Sector(id = "restarts", name = "Reinicializações (Restarts)", type = SectorType.COUNTER, valueType = ValueType.NUMBER, category = SectorCategory.INFRASTRUCTURE, unit = "restarts", lastValue = "$restarts", lastUpdatedAt = now)
                    ),
                    updatedAt = now
                )

                result.add(instance)
            }
        } catch (e: Exception) {
            lastError = "Erro ao processar métricas do PM2: ${e.message}"
            logger.warn(lastError)
        }

        return result
    }

    private fun extractJsonArray(rawOutput: String): String? {
        val clean = rawOutput.replace(Regex("\u001B\\[[;\\d]*m"), "").trim()

        var searchIndex = 0
        while (searchIndex < clean.length) {
            val bracketIndex = clean.indexOf('[', searchIndex)
            if (bracketIndex == -1) break

            var nextChar: Char? = null
            for (i in (bracketIndex + 1) until clean.length) {
                val c = clean[i]
                if (!c.isWhitespace()) {
                    nextChar = c
                    break
                }
            }

            if (nextChar == '{' || nextChar == ']') {
                val lastBracket = clean.lastIndexOf(']')
                if (lastBracket > bracketIndex) {
                    val candidate = clean.substring(bracketIndex, lastBracket + 1)
                    try {
                        json.parseToJsonElement(candidate)
                        return candidate
                    } catch (_: Exception) {
                        // Tenta próxima ocorrência se falhar
                    }
                }
            }

            searchIndex = bracketIndex + 1
        }

        return null
    }

    private fun executeCommand(command: List<String>, pm2Home: File?, timeoutSeconds: Long = 15): CommandResult {
        return try {
            val pb = ProcessBuilder(command)
            val resolved = resolvePm2Executable()
            augmentPath(pb, resolved)

            if (pm2Home != null) {
                pb.environment()["PM2_HOME"] = pm2Home.absolutePath
            }

            val process = pb.start()

            val stdoutFuture = CompletableFuture.supplyAsync {
                try { process.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
            }
            val stderrFuture = CompletableFuture.supplyAsync {
                try { process.errorStream.bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Timeout de ${timeoutSeconds}s aguardando execução do comando: ${command.joinToString(" ")}"
                )
            }

            val stdout = try { stdoutFuture.get(1, TimeUnit.SECONDS) } catch (_: Exception) { "" }
            val stderr = try { stderrFuture.get(1, TimeUnit.SECONDS) } catch (_: Exception) { "" }

            CommandResult(
                exitCode = process.exitValue(),
                stdout = stdout.trim(),
                stderr = stderr.trim()
            )
        } catch (e: Exception) {
            CommandResult(
                exitCode = -1,
                stdout = "",
                stderr = "Exceção ao disparar comando '${command.joinToString(" ")}': ${e.message}"
            )
        }
    }

    fun findActivePm2Homes(): List<File> {
        val homes = linkedSetOf<File>()

        val envPm2Home = System.getenv("PM2_HOME")
        if (!envPm2Home.isNullOrBlank()) {
            val dir = File(envPm2Home)
            if (dir.exists() && dir.isDirectory) homes.add(dir)
        }

        val userHome = System.getProperty("user.home")
        if (!userHome.isNullOrBlank()) {
            val userPm2 = File(userHome, ".pm2")
            if (userPm2.exists() && userPm2.isDirectory) homes.add(userPm2)
        }

        if (!isWindows) {
            val rootPm2 = File("/root/.pm2")
            if (rootPm2.exists() && rootPm2.isDirectory) homes.add(rootPm2)

            val homeDir = File("/home")
            if (homeDir.exists() && homeDir.isDirectory) {
                homeDir.listFiles()?.filter { it.isDirectory }?.forEach { userFolder ->
                    val userPm2 = File(userFolder, ".pm2")
                    if (userPm2.exists() && userPm2.isDirectory) {
                        homes.add(userPm2)
                    }
                }
            }
        }

        return homes.toList()
    }

    fun getPrioritizedPm2Homes(): List<File> {
        val homes = findActivePm2Homes()
        return homes.sortedByDescending { home ->
            var score = 0
            if (File(home, "rpc.sock").exists()) score += 10
            if (File(home, "pm2.pid").exists()) score += 5
            if (File(home, "dump.pm2").exists()) score += 2
            score
        }
    }

    private fun findExecutableInPath(name: String): File? {
        val pathEnv = System.getenv("PATH") ?: return null
        val dirs = pathEnv.split(File.pathSeparatorChar)
        for (dir in dirs) {
            val file = File(dir, name)
            if (file.exists() && file.canExecute()) {
                return file
            }
        }
        return null
    }

    private fun findNvmPm2(): List<File> {
        val results = mutableListOf<File>()
        val searchRoots = mutableListOf<File>()
        val userHome = System.getProperty("user.home")
        if (!userHome.isNullOrBlank()) {
            searchRoots.add(File(userHome, ".nvm/versions/node"))
        }
        if (!isWindows) {
            searchRoots.add(File("/root/.nvm/versions/node"))
            val home = File("/home")
            if (home.exists() && home.isDirectory) {
                home.listFiles()?.filter { it.isDirectory }?.forEach { userDir ->
                    searchRoots.add(File(userDir, ".nvm/versions/node"))
                }
            }
        }

        for (root in searchRoots) {
            if (root.exists() && root.isDirectory) {
                root.listFiles()?.filter { it.isDirectory }?.forEach { verDir ->
                    val pm2 = File(verDir, "bin/pm2")
                    if (pm2.exists() && pm2.canExecute()) {
                        results.add(pm2)
                    }
                }
            }
        }
        return results
    }

    private fun resolvePm2Executable(): File? {
        if (isWindows) return null

        findExecutableInPath("pm2")?.let { return it }

        val standardLocations = listOf(
            "/usr/local/bin/pm2",
            "/usr/bin/pm2",
            "/bin/pm2",
            "/opt/nodejs/bin/pm2"
        )
        for (loc in standardLocations) {
            val f = File(loc)
            if (f.exists() && f.canExecute()) return f
        }

        val nvmList = findNvmPm2()
        if (nvmList.isNotEmpty()) return nvmList.first()

        val npmGlobalDirs = listOf(
            File(System.getProperty("user.home"), ".npm-global/bin/pm2"),
            File("/root/.npm-global/bin/pm2")
        )
        for (f in npmGlobalDirs) {
            if (f.exists() && f.canExecute()) return f
        }

        return null
    }

    private fun buildPm2Command(vararg args: String): List<String> {
        if (isWindows) {
            return listOf("cmd.exe", "/c", "pm2") + args.toList()
        }

        val resolved = resolvePm2Executable()
        return if (resolved != null) {
            listOf(resolved.absolutePath) + args.toList()
        } else {
            listOf("bash", "-lc", "pm2 ${args.joinToString(" ")}")
        }
    }

    private fun augmentPath(builder: ProcessBuilder, pm2Executable: File?) {
        val env = builder.environment()
        val currentPath = env["PATH"] ?: System.getenv("PATH") ?: ""
        val extraPaths = mutableListOf<String>()

        pm2Executable?.parentFile?.let { extraPaths.add(it.absolutePath) }
        extraPaths.add("/usr/local/bin")
        extraPaths.add("/usr/bin")
        extraPaths.add("/bin")

        val pathSeparator = File.pathSeparator
        val newPath = (extraPaths + currentPath.split(pathSeparator))
            .distinct()
            .filter { it.isNotBlank() }
            .joinToString(pathSeparator)

        env["PATH"] = newPath
    }

    override fun getDiagnosticInfo(): String {
        val sb = StringBuilder()
        sb.appendLine("--- Diagnóstico Detalhado do Provedor PM2 ---")
        sb.appendLine("  Sistema Operacional:  ${System.getProperty("os.name")} (${System.getProperty("os.arch")})")
        sb.appendLine("  Usuário da JVM:       ${System.getProperty("user.name")} (Home: ${System.getProperty("user.home")})")

        val resolved = resolvePm2Executable()
        sb.appendLine("  Binário resolvido:    ${resolved?.absolutePath ?: "Nenhum binário direto encontrado (fallback para PATH/bash)"}")

        val versionResult = executeCommand(buildPm2Command("-v"), pm2Home = null, timeoutSeconds = 5)
        val versionText = if (versionResult.exitCode == 0) versionResult.stdout else "Falhou (código ${versionResult.exitCode}): ${versionResult.stderr.ifBlank { versionResult.stdout }}"
        sb.appendLine("  Versão do PM2:        $versionText")

        val homes = findActivePm2Homes()
        sb.appendLine("  Diretórios PM2 detectados (${homes.size}):")
        if (homes.isEmpty()) {
            sb.appendLine("    (Nenhum diretório .pm2 encontrado)")
        } else {
            for (h in homes) {
                val hasSocket = File(h, "rpc.sock").exists()
                val hasPid = File(h, "pm2.pid").exists()
                val hasDump = File(h, "dump.pm2").exists()
                sb.appendLine("    - ${h.absolutePath} [rpc.sock: $hasSocket, pm2.pid: $hasPid, dump: $hasDump]")
            }
        }

        val prioritized = getPrioritizedPm2Homes()
        val targetHome = prioritized.firstOrNull()
        sb.appendLine("  PM2_HOME prioritário: ${targetHome?.absolutePath ?: "Padrão"}")

        val jlistResult = executeCommand(buildPm2Command("jlist"), targetHome, timeoutSeconds = 15)
        sb.appendLine("  Teste 'pm2 jlist':    Código ${jlistResult.exitCode} | Tamanho stdout: ${jlistResult.stdout.length} bytes")
        if (jlistResult.stderr.isNotBlank()) {
            sb.appendLine("  Stderr do PM2:        ${jlistResult.stderr.take(300)}")
        }

        val cleanJson = extractJsonArray(jlistResult.stdout)
        if (cleanJson != null) {
            val instances = parsePm2Json(cleanJson, setOf("all"), targetHome)
            sb.appendLine("  Processos detectados (${instances.size}):")
            if (instances.isEmpty()) {
                sb.appendLine("    (Nenhum processo ativo no PM2 retornado para este PM2_HOME)")
            } else {
                for (inst in instances) {
                    val status = inst.sectors.firstOrNull { it.id == "status" }?.lastValue ?: "?"
                    val cpu = inst.sectors.firstOrNull { it.id == "cpu" }?.lastValue ?: "?"
                    val mem = inst.sectors.firstOrNull { it.id == "memory" }?.lastValue?.toLongOrNull() ?: 0L
                    val memMb = String.format("%.1f", mem / (1024.0 * 1024.0))
                    sb.appendLine("    • ${inst.id} (Status: $status | CPU: $cpu% | RAM: $memMb MB)")
                }
            }
        } else {
            sb.appendLine("  Array JSON extraído:  Não foi possível extrair array JSON válido.")
            if (jlistResult.stdout.isNotBlank()) {
                sb.appendLine("  Início da saída bruta: ${jlistResult.stdout.take(300)}")
            }
        }

        if (lastError != null) {
            sb.appendLine("  Último erro capturado: $lastError")
        }

        return sb.toString().trimEnd()
    }

    private fun generateMockInstances(targets: Set<String>): List<MonitoringInstance> {
        val monitorAll = targets.contains("all")
        val mockNames = listOf("mock-api", "mock-worker")
        val now = System.currentTimeMillis()
        val list = mutableListOf<MonitoringInstance>()

        for (name in mockNames) {
            if (monitorAll || targets.contains(name)) {
                val directMetrics = mutableListOf(
                    MonitoringData(id = "status", name = "Status do Processo", type = SectorType.STATUS, valueType = ValueType.STATUS, category = SectorCategory.INFRASTRUCTURE, lastValue = "ONLINE", lastUpdatedAt = now),
                    MonitoringData(id = "cpu", name = "Uso de CPU", type = SectorType.GAUGE, valueType = ValueType.PERCENTAGE, category = SectorCategory.INFRASTRUCTURE, unit = "%", lastValue = "1.2", lastUpdatedAt = now),
                    MonitoringData(id = "memory", name = "Memória RAM", type = SectorType.GAUGE, valueType = ValueType.BYTES, category = SectorCategory.INFRASTRUCTURE, unit = "bytes", lastValue = (85L * 1024 * 1024).toString(), lastUpdatedAt = now),
                    MonitoringData(id = "restarts", name = "Reinicializações (Restarts)", type = SectorType.COUNTER, valueType = ValueType.NUMBER, category = SectorCategory.INFRASTRUCTURE, unit = "restarts", lastValue = "0", lastUpdatedAt = now)
                )

                list.add(
                    MonitoringInstance(
                        id = "pm2:$name",
                        name = "PM2 (Simulado): $name",
                        source = InstanceSource.HOST,
                        providerId = id,
                        data = directMetrics,
                        sectors = mutableListOf(
                            Sector(id = "status", name = "Status do Processo", type = SectorType.STATUS, valueType = ValueType.STATUS, category = SectorCategory.INFRASTRUCTURE, lastValue = "ONLINE", lastUpdatedAt = now),
                            Sector(id = "cpu", name = "Uso de CPU", type = SectorType.GAUGE, valueType = ValueType.PERCENTAGE, category = SectorCategory.INFRASTRUCTURE, unit = "%", lastValue = "1.2", lastUpdatedAt = now),
                            Sector(id = "memory", name = "Memória RAM", type = SectorType.GAUGE, valueType = ValueType.BYTES, category = SectorCategory.INFRASTRUCTURE, unit = "bytes", lastValue = (85L * 1024 * 1024).toString(), lastUpdatedAt = now),
                            Sector(id = "restarts", name = "Reinicializações (Restarts)", type = SectorType.COUNTER, valueType = ValueType.NUMBER, category = SectorCategory.INFRASTRUCTURE, unit = "restarts", lastValue = "0", lastUpdatedAt = now)
                        ),
                        updatedAt = now
                    )
                )
            }
        }
        return list
    }
}
