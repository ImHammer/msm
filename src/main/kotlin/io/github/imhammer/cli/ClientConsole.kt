package io.github.imhammer.cli

import io.github.imhammer.config.ConfigManager
import io.github.imhammer.config.MonitoredTargetConfig
import io.github.imhammer.config.PairedDevice
import io.github.imhammer.monitoring.MonitoringInstance
import io.github.imhammer.pairing.PairingRequest
import io.github.imhammer.AdminActionResult
import io.github.imhammer.AgentStatusResponse
import io.github.imhammer.ProviderInfoDto
import io.github.imhammer.SystemStatsResponse
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Scanner

class ClientConsole(private val configManager: ConfigManager) {

    // Códigos ANSI para estilização no terminal
    private val cyan = "\u001B[36m"
    private val green = "\u001B[32m"
    private val yellow = "\u001B[33m"
    private val red = "\u001B[31m"
    private val bold = "\u001B[1m"
    private val reset = "\u001B[0m"
    private val gray = "\u001B[90m"

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss")
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(1500))
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val baseUrl: String
        get() = "http://127.0.0.1:${configManager.config.port}"

    private var isInteractive = false

    fun isDaemonAlive(): Boolean {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/status"))
                .timeout(Duration.ofMillis(1200))
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            resp.statusCode() == 200
        } catch (_: Exception) {
            false
        }
    }

    fun start() {
        isInteractive = true
        printBanner()
        val scanner = Scanner(System.`in`)
        var isRunning = true

        while (isRunning) {
            try {
                print("${cyan}msm@${configManager.config.name}${reset} > ")
                if (!scanner.hasNextLine()) {
                    Thread.sleep(500)
                    continue
                }
                val line = scanner.nextLine().trim()
                if (line.isEmpty()) continue

                val parts = line.split("\\s+".toRegex())
                val cmd = parts[0].lowercase()
                val args = parts.drop(1)

                if (cmd in listOf("exit", "quit", "disconnect", "sair")) {
                    println("\n${green}✓ Desconectado do console do MSM Agent.${reset}")
                    println("O serviço continua ativo e monitorando em segundo plano no systemd.\n")
                    isRunning = false
                    break
                }

                handleCommand(cmd, args)
            } catch (_: NoSuchElementException) {
                break
            } catch (e: Exception) {
                println("${red}Erro ao executar comando: ${e.message}${reset}")
            }
        }
    }

    fun handleCommand(command: String, args: List<String>) {
        when (command.lowercase()) {
            "help", "?" -> printHelp()
            "info" -> AgentInfoPrinter.printInfo(configManager)
            "config" -> ConfigCli(configManager).handleArgs(args)
            "status" -> printStatus()
            "system", "stats" -> printSystemStats()
            "instances", "insts" -> printInstances()
            "pairing" -> handlePairing(args)
            "devices" -> handleDevices(args)
            "monitor" -> handleMonitor(args)
            "token", "sdk-token" -> handleToken(args)
            "clear", "cls" -> clearScreen()
            "exit", "quit", "stop" -> {
                println("\n${yellow}Dica: No modo cliente conectado, use 'exit' para fechar a sessão.${reset}")
                println("Para parar o serviço da VPS por completo, use: '${bold}sudo msm service stop${reset}'\n")
            }
            else -> println("${yellow}Comando desconhecido: '$command'. Digite 'help' para ver os disponíveis.${reset}")
        }
    }

    private fun printBanner() {
        println()
        println("${cyan}${bold}======================================================${reset}")
        println("${cyan}${bold}      MSM AGENT - CONSOLE DE CONTROLE (ATTACH)        ${reset}")
        println("${cyan}${bold}======================================================${reset}")
        println("Conectado ao Agent: ${bold}${configManager.config.name}${reset} | Porta: ${bold}${configManager.config.port}${reset}")
        println("Status: ${green}ONLINE (Serviço ativo em segundo plano)${reset}")
        println("Digite '${bold}help${reset}' para listar os comandos. Digite '${bold}exit${reset}' para sair do console.\n")
    }

    private fun printHelp() {
        println()
        println("${bold}Comandos disponíveis:${reset}")
        println("  ${cyan}status${reset}                               - Exibe o estado geral do Agent e resumo.")
        println("  ${cyan}stats${reset} | ${cyan}system${reset}                        - Exibe uso atual de CPU e Memória da VPS.")
        println("  ${cyan}instances${reset}                            - Lista todas as instâncias e setores monitorados.")
        println("  ${cyan}info${reset}                                 - Exibe caminhos dos arquivos (config, logs, sockets) e status.")
        println("  ${cyan}config [show|set|edit]${reset}               - Exibe ou altera configurações (porta, nome, token).")
        println()
        println("  ${cyan}pairing requests${reset}                     - Lista solicitações pendentes de vinculação do app.")
        println("  ${cyan}pairing accept <código|id>${reset}           - Aprova a conexão do celular.")
        println("  ${cyan}pairing reject <código|id>${reset}           - Recusa uma solicitação pendente.")
        println("  ${cyan}pairing on${reset} / ${cyan}pairing off${reset}               - Habilita ou desabilita novas vinculações.")
        println()
        println("  ${cyan}devices list${reset}                         - Lista todos os aparelhos celulares autorizados.")
        println("  ${cyan}devices revoke <id>${reset}                  - Revoga e cancela o acesso de um dispositivo.")
        println()
        println("  ${cyan}monitor providers${reset}                    - Lista provedores e setores fornecidos.")
        println("  ${cyan}monitor list${reset}                         - Lista alvos configurados e setores ativos.")
        println("  ${cyan}monitor add <provedor> <alvo> [-s <setores>]${reset} - Monitora processos (ex: 'monitor add pm2 all -s status,cpu').")
        println("  ${cyan}monitor sectors <provedor> [alvo] [setores]${reset}  - Consulta ou define setores ativos do provedor.")
        println("  ${cyan}monitor remove <provedor> <alvo>${reset}     - Remove um alvo do monitoramento.")
        println()
        println("  ${cyan}token${reset}                                - Exibe o token do SDK.")
        println("  ${cyan}clear${reset}                                - Limpa o terminal.")
        println("  ${cyan}exit${reset}                                 - Desconecta do console (o Agent continuará rodando).")
        println()
    }

    private fun printStatus() {
        val statusJson = httpGet("/api/status") ?: return
        val systemJson = httpGet("/api/system")

        val status = try { json.decodeFromString<AgentStatusResponse>(statusJson) } catch (_: Exception) { null }
        val system = systemJson?.let { try { json.decodeFromString<SystemStatsResponse>(it) } catch (_: Exception) { null } }

        if (status == null) {
            println("${red}Falha ao obter status do Agent.${reset}")
            return
        }

        val pairingStatus = if (status.pairingEnabled) "${green}ATIVADO${reset}" else "${red}DESATIVADO${reset}"

        println()
        println("${bold}--- Status do Agent (Serviço Ativo) ---${reset}")
        println("  Nome:                 ${cyan}${status.name}${reset}")
        println("  Porta:                ${status.port}")
        println("  Modo Vinculação:      $pairingStatus")
        println("  Dispositivos Pareados: ${status.devicesCount}")
        println("  Solicitações na Fila:  ${if (status.pendingPairingCount > 0) "${yellow}${status.pendingPairingCount}${reset}" else "0"}")
        println("  Monitores de Host:    ${status.monitorsCount}")
        println("  Instâncias Ativas:    ${status.instancesCount}")

        if (system != null) {
            val cpu = String.format("%.2f", system.cpu)
            val memUsedMb = system.memory.used / (1024 * 1024)
            val memTotalMb = system.memory.total / (1024 * 1024)
            val memPercent = String.format("%.1f", system.memory.percentage)
            println("  Uso de CPU:           $cpu%")
            println("  Uso de Memória:       $memUsedMb MB / $memTotalMb MB ($memPercent%)")
        }
        println()
    }

    private fun printSystemStats() {
        val systemJson = httpGet("/api/system") ?: return
        val system = try { json.decodeFromString<SystemStatsResponse>(systemJson) } catch (_: Exception) { null }
        if (system == null) {
            println("${red}Falha ao obter métricas de sistema.${reset}")
            return
        }

        val cpu = String.format("%.2f", system.cpu)
        val memUsedMb = system.memory.used / (1024 * 1024)
        val memTotalMb = system.memory.total / (1024 * 1024)
        val memPercent = String.format("%.1f", system.memory.percentage)

        println()
        println("${bold}--- Métricas do Sistema ---${reset}")
        println("  Processador (CPU):    $cpu%")
        println("  Memória RAM:          $memUsedMb MB / $memTotalMb MB ($memPercent%)")
        println()
    }

    private fun printInstances() {
        val instancesJson = httpGet("/api/instances") ?: return
        val instances = try { json.decodeFromString<List<MonitoringInstance>>(instancesJson) } catch (_: Exception) { emptyList() }

        if (instances.isEmpty()) {
            println("Nenhuma instância de monitoramento ativa no momento.")
            println("Você pode adicionar um monitor de host com '${bold}monitor add pm2 all${reset}' ou conectar uma aplicação via SDK.")
            return
        }

        println()
        println("${bold}--- Instâncias Monitoradas Ativas (${instances.size}) ---${reset}")
        for (inst in instances) {
            val sourceBadge = if (inst.source.name == "HOST") "${cyan}[HOST: ${inst.providerId}]${reset}" else "${green}[SDK]${reset}"
            println("  ${bold}${inst.name}${reset} (ID: ${inst.id}) $sourceBadge")

            var hasContent = false

            // 1. Dados Diretos na Instância
            if (inst.data.isNotEmpty()) {
                hasContent = true
                println("    ${yellow}[Dados Diretos]:${reset}")
                for (d in inst.data) {
                    val unitStr = if (!d.unit.isNullOrBlank()) " ${d.unit}" else ""
                    val valueStr = (d.lastValue ?: "(sem dados)") + unitStr
                    println("      • ${bold}${d.name}${reset} (${d.id}) [${d.valueType}]: ${green}$valueStr${reset}")
                }
            }

            // 2. Setores da Instância
            if (inst.sectors.isNotEmpty()) {
                hasContent = true
                println("    ${yellow}[Setores]:${reset}")
                for (sector in inst.sectors) {
                    println("      ▸ ${bold}Setor: ${sector.name}${reset} (ID: ${sector.id}) [${sector.category}]")
                    if (sector.data.isNotEmpty()) {
                        for (d in sector.data) {
                            val unitStr = if (!d.unit.isNullOrBlank()) " ${d.unit}" else ""
                            val valueStr = (d.lastValue ?: "(sem dados)") + unitStr
                            println("          • ${bold}${d.name}${reset} (${d.id}) [${d.valueType}]: ${green}$valueStr${reset}")
                        }
                    } else if (sector.lastValue != null) {
                        val unitStr = if (!sector.unit.isNullOrBlank()) " ${sector.unit}" else ""
                        println("          • Valor: ${green}${sector.lastValue}$unitStr${reset} [${sector.valueType}]")
                    } else {
                        println("          (Nenhum dado registrado neste setor)")
                    }
                }
            }

            if (!hasContent) {
                println("    (Nenhum dado ou setor cadastrado)")
            }

            println("  ---------------------------------------------")
        }
        println()
    }

    private fun handlePairing(args: List<String>) {
        if (args.isEmpty()) {
            println("Uso do comando pairing:")
            println("  ${cyan}pairing requests${reset}                     - Lista solicitações pendentes.")
            println("  ${cyan}pairing accept <código|id>${reset}           - Aprova a solicitação.")
            println("  ${cyan}pairing reject <código|id>${reset}           - Recusa a solicitação.")
            println("  ${cyan}pairing on${reset} / ${cyan}pairing off${reset}               - Habilita/desabilita novas vinculações.")
            return
        }

        when (args[0].lowercase()) {
            "requests", "reqs", "fila" -> {
                val requestsJson = httpGet("/api/admin/pairing/requests") ?: return
                val requests = try { json.decodeFromString<List<PairingRequest>>(requestsJson) } catch (_: Exception) { emptyList() }

                if (requests.isEmpty()) {
                    println("Nenhuma solicitação de vinculação pendente no momento.")
                    return
                }

                println()
                println("${bold}--- Solicitações de Vinculação Pendentes (${requests.size}) ---${reset}")
                val now = System.currentTimeMillis()
                for (req in requests) {
                    val remainingSeconds = 300 - ((now - req.requestedAt) / 1000)
                    val timeStr = if (remainingSeconds > 0) "${remainingSeconds}s restantes" else "Expirando..."
                    println("  Código OTP:   ${green}${bold}${req.code}${reset}")
                    println("  Dispositivo:  ${cyan}${req.deviceName}${reset}")
                    println("  ID:           ${req.id}")
                    println("  Tempo:        $timeStr")
                    println("  ---------------------------------------------")
                }
                println("Para aprovar: '${bold}pairing accept <código>${reset}' | Para recusar: '${bold}pairing reject <código>${reset}'\n")
            }
            "accept", "aprovar" -> {
                if (args.size < 2) {
                    println("${yellow}Uso: pairing accept <código-otp ou id>${reset}")
                    return
                }
                val query = args[1]
                val resultJson = httpPost("/api/admin/pairing/accept", """{"query":"$query"}""") ?: return
                val result = try { json.decodeFromString<AdminActionResult>(resultJson) } catch (_: Exception) { null }
                if (result?.success == true) {
                    println("${green}✓ ${result.message}${reset}")
                } else {
                    println("${red}✗ ${result?.message ?: "Falha ao aprovar vinculação."}${reset}")
                }
            }
            "reject", "recusar" -> {
                if (args.size < 2) {
                    println("${yellow}Uso: pairing reject <código-otp ou id>${reset}")
                    return
                }
                val query = args[1]
                val resultJson = httpPost("/api/admin/pairing/reject", """{"query":"$query"}""") ?: return
                val result = try { json.decodeFromString<AdminActionResult>(resultJson) } catch (_: Exception) { null }
                if (result?.success == true) {
                    println("${yellow}✓ ${result.message}${reset}")
                } else {
                    println("${red}✗ ${result?.message ?: "Falha ao recusar solicitação."}${reset}")
                }
            }
            "on", "enable" -> {
                val resultJson = httpPost("/api/admin/pairing/toggle", """{"enabled":true}""") ?: return
                val result = try { json.decodeFromString<AdminActionResult>(resultJson) } catch (_: Exception) { null }
                println("${green}✓ ${result?.message ?: "Pareamento ATIVADO."}${reset}")
            }
            "off", "disable" -> {
                val resultJson = httpPost("/api/admin/pairing/toggle", """{"enabled":false}""") ?: return
                val result = try { json.decodeFromString<AdminActionResult>(resultJson) } catch (_: Exception) { null }
                println("${yellow}✓ ${result?.message ?: "Pareamento DESATIVADO."}${reset}")
            }
            else -> println("${yellow}Subcomando de pairing desconhecido: '${args[0]}'. Use: requests, accept, reject, on, off.${reset}")
        }
    }

    private fun handleDevices(args: List<String>) {
        val sub = if (args.isEmpty()) "list" else args[0].lowercase()

        when (sub) {
            "list", "ls" -> {
                val devicesJson = httpGet("/api/admin/devices") ?: return
                val devices = try { json.decodeFromString<List<PairedDevice>>(devicesJson) } catch (_: Exception) { emptyList() }

                if (devices.isEmpty()) {
                    println("Nenhum dispositivo móvel vinculado ainda.")
                    println("Ative a vinculação com 'pairing on' e solicite acesso pelo app MSM.")
                    return
                }

                println()
                println("${bold}--- Dispositivos Vinculados (${devices.size}) ---${reset}")
                for (dev in devices) {
                    val pairedDate = dateFormat.format(Date(dev.pairedAt))
                    val lastSeenDate = dateFormat.format(Date(dev.lastSeenAt))
                    println("  Nome:         ${cyan}${bold}${dev.name}${reset}")
                    println("  ID:           ${dev.id}")
                    println("  Vinculado em: $pairedDate")
                    println("  Último visto: $lastSeenDate")
                    println("  ---------------------------------------------")
                }
                println("Para revogar o acesso: '${bold}devices revoke <id>${reset}'\n")
            }
            "revoke", "remove" -> {
                if (args.size < 2) {
                    println("${yellow}Uso: devices revoke <id-do-dispositivo>${reset}")
                    return
                }
                val targetId = args[1]
                val resultJson = httpDelete("/api/admin/devices/$targetId") ?: return
                val result = try { json.decodeFromString<AdminActionResult>(resultJson) } catch (_: Exception) { null }
                if (result?.success == true) {
                    println("${green}✓ ${result.message}${reset}")
                } else {
                    println("${red}✗ ${result?.message ?: "Falha ao revogar dispositivo."}${reset}")
                }
            }
            else -> println("${yellow}Subcomando desconhecido: '$sub'. Use 'devices list' ou 'devices revoke <id>'.${reset}")
        }
    }

    private fun handleMonitor(args: List<String>) {
        if (args.isEmpty()) {
            println("Uso do comando monitor:")
            println("  ${cyan}monitor providers${reset}                 - Exibe provedores de host suportados.")
            println("  ${cyan}monitor list${reset}                      - Lista os alvos vigiados atualmente.")
            println("  ${cyan}monitor add <provedor> <all|alvo>${reset} - Monitora processos ou recursos.")
            println("  ${cyan}monitor remove <provedor> <alvo>${reset}  - Deixa de monitorar um recurso.")
            println("  ${cyan}monitor diag <provedor>${reset}           - Diagnóstico detalhado de conexão.")
            return
        }

        when (args[0].lowercase()) {
            "providers", "provs" -> {
                val provsJson = httpGet("/api/admin/monitors/providers") ?: return
                val provs = try { json.decodeFromString<List<ProviderInfoDto>>(provsJson) } catch (_: Exception) { emptyList() }
                println()
                println("${bold}--- Provedores de Monitoramento Disponíveis ---${reset}")
                for (p in provs) {
                    val statusStr = if (p.isAvailable) "${green}Disponível no sistema${reset}" else "${yellow}Não detectado no PATH (modo dev simulado habilitado)${reset}"
                    println("  - ${bold}${p.id}${reset}: ${p.displayName} [$statusStr]")
                    if (p.availableSectors.isNotEmpty()) {
                        println("    ${gray}Setores suportados:${reset}")
                        for (s in p.availableSectors) {
                            val unitStr = if (!s.unit.isNullOrBlank()) " (${s.unit})" else ""
                            println("      • ${cyan}${s.id}${reset}: ${s.name}$unitStr ${gray}- ${s.description}${reset}")
                        }
                    }
                }
                println()
            }
            "list", "ls" -> {
                val monitorsJson = httpGet("/api/admin/monitors") ?: return
                val monitors = try { json.decodeFromString<List<MonitoredTargetConfig>>(monitorsJson) } catch (_: Exception) { emptyList() }
                if (monitors.isEmpty()) {
                    println("Nenhum monitor de host configurado.")
                    println("Exemplo para monitorar o PM2: '${bold}monitor add pm2 all${reset}'")
                    return
                }

                println()
                println("${bold}--- Monitores de Host Configurados (${monitors.size}) ---${reset}")
                for (m in monitors) {
                    val sectorsDesc = if (!m.enabledSectors.isNullOrEmpty()) {
                        "${yellow}${m.enabledSectors!!.joinToString(", ")}${reset} (restrito)"
                    } else {
                        "${green}Todos os setores ativos${reset}"
                    }
                    println("  Provedor: ${cyan}${m.providerId}${reset} | Alvo: ${bold}${m.target}${reset} | Ativo: ${if (m.enabled) "${green}Sim${reset}" else "${red}Não${reset}"} | Setores: $sectorsDesc")
                }
                println()
            }
            "add" -> {
                if (args.size < 3) {
                    println("${yellow}Uso: monitor add <provedor> <all|alvo> [--sectors s1,s2]${reset}")
                    println("Exemplo: monitor add pm2 all")
                    println("Exemplo: monitor add pm2 all --sectors status,cpu")
                    println("Exemplo: monitor add pm2 api-checkout -s status,cpu,memory")
                    return
                }
                val providerId = args[1].lowercase()
                val target = args[2]

                var sectorsList: List<String>? = null
                val sectorsFlagIndex = args.indexOfFirst { it == "--sectors" || it == "-s" }
                if (sectorsFlagIndex != -1 && sectorsFlagIndex + 1 < args.size) {
                    sectorsList = args[sectorsFlagIndex + 1].split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
                }

                if (sectorsList == null && isInteractive) {
                    val provsJson = httpGet("/api/admin/monitors/providers")
                    val provs = if (provsJson != null) {
                        try { json.decodeFromString<List<ProviderInfoDto>>(provsJson) } catch (_: Exception) { emptyList() }
                    } else emptyList()
                    val p = provs.firstOrNull { it.id.equals(providerId, ignoreCase = true) }
                    if (p != null && p.availableSectors.isNotEmpty()) {
                        println()
                        println("Setores disponíveis para o provedor '${cyan}$providerId${reset}':")
                        p.availableSectors.forEachIndexed { idx, s ->
                            println("  [${idx + 1}] ${bold}${s.id}${reset} - ${s.name} ${gray}(${s.description})${reset}")
                        }
                        print("Deseja ativar todos os setores acima? [S/n]: ")
                        val scanner = Scanner(System.`in`)
                        val choice = if (scanner.hasNextLine()) scanner.nextLine().trim() else ""
                        if (choice.equals("n", ignoreCase = true) || choice.equals("nao", ignoreCase = true) || choice.equals("não", ignoreCase = true)) {
                            print("Digite os setores que deseja ativar separados por vírgula (ex: status,cpu): ")
                            val input = if (scanner.hasNextLine()) scanner.nextLine().trim() else ""
                            if (input.isNotBlank()) {
                                sectorsList = input.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
                            }
                        }
                    }
                }

                print("Registrando monitor para '$providerId'...")
                val sectorsJsonPart = if (sectorsList != null) {
                    val arr = sectorsList.joinToString(",") { "\"$it\"" }
                    ""","sectors":[$arr]"""
                } else ""
                val payload = """{"providerId":"$providerId","target":"$target"$sectorsJsonPart}"""
                val resultJson = httpPost("/api/admin/monitors/add", payload) ?: return
                println()
                val result = try { json.decodeFromString<AdminActionResult>(resultJson) } catch (_: Exception) { null }
                if (result?.success == true) {
                    println("${green}✓ ${result.message}${reset}")
                } else {
                    println("${red}✗ ${result?.message ?: "Falha ao adicionar monitor."}${reset}")
                }
            }
            "sectors", "setores" -> {
                if (args.size < 2) {
                    println("${yellow}Uso: monitor sectors <provedor> [alvo] [setores_separados_por_virgula]${reset}")
                    println("Exemplo: monitor sectors pm2 (exibe setores do pm2)")
                    println("Exemplo: monitor sectors pm2 all status,cpu (define apenas status e cpu)")
                    println("Exemplo: monitor sectors pm2 all --all (habilita todos os setores)")
                    return
                }
                val providerId = args[1].lowercase()

                if (args.size == 2) {
                    val provsJson = httpGet("/api/admin/monitors/providers") ?: return
                    val provs = try { json.decodeFromString<List<ProviderInfoDto>>(provsJson) } catch (_: Exception) { emptyList() }
                    val p = provs.firstOrNull { it.id.equals(providerId, ignoreCase = true) }
                    if (p == null) {
                        println("${red}Provedor '$providerId' não encontrado.${reset}")
                        return
                    }
                    println()
                    println("${bold}--- Setores Fornecidos pelo Provedor '$providerId' ---${reset}")
                    for (s in p.availableSectors) {
                        val unitStr = if (!s.unit.isNullOrBlank()) " (${s.unit})" else ""
                        println("  • ${cyan}${bold}${s.id}${reset}: ${s.name}$unitStr")
                        println("    ${gray}${s.description}${reset}")
                    }

                    val monitorsJson = httpGet("/api/admin/monitors")
                    val monitors = if (monitorsJson != null) {
                        try { json.decodeFromString<List<MonitoredTargetConfig>>(monitorsJson) } catch (_: Exception) { emptyList() }
                    } else emptyList()
                    val configured = monitors.filter { it.providerId == providerId }
                    if (configured.isNotEmpty()) {
                        println()
                        println("${bold}Monitores configurados para '$providerId':${reset}")
                        for (c in configured) {
                            val secStr = if (!c.enabledSectors.isNullOrEmpty()) "${yellow}${c.enabledSectors!!.joinToString(", ")}${reset}" else "${green}Todos ativos${reset}"
                            println("  Alvo '${bold}${c.target}${reset}': $secStr")
                        }
                    }
                    println()
                    println("Para alterar os setores de um alvo: '${bold}monitor sectors $providerId <alvo> <setores>${reset}'")
                    println("Exemplo: '${bold}monitor sectors $providerId all status,cpu${reset}'\n")
                    return
                }

                val target = args[2]
                val sectorsArg = if (args.size > 3) args[3] else ""
                val sectorsList: List<String>? = when {
                    sectorsArg.equals("--all", ignoreCase = true) || sectorsArg.equals("all", ignoreCase = true) -> null
                    sectorsArg.isNotBlank() -> sectorsArg.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
                    target.contains(",") -> target.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
                    else -> null
                }
                val effectiveTarget = if (target.contains(",")) "all" else target

                val sectorsJsonPart = if (sectorsList != null) {
                    val arr = sectorsList.joinToString(",") { "\"$it\"" }
                    ""","sectors":[$arr]"""
                } else ""
                val payload = """{"providerId":"$providerId","target":"$effectiveTarget"$sectorsJsonPart}"""
                val resultJson = httpPost("/api/admin/monitors/sectors", payload) ?: return
                val result = try { json.decodeFromString<AdminActionResult>(resultJson) } catch (_: Exception) { null }
                if (result?.success == true) {
                    println("${green}✓ ${result.message}${reset}")
                } else {
                    println("${red}✗ ${result?.message ?: "Falha ao atualizar setores."}${reset}")
                }
            }
            "remove", "rm" -> {
                if (args.size < 3) {
                    println("${yellow}Uso: monitor remove <provedor> <alvo>${reset}")
                    return
                }
                val providerId = args[1].lowercase()
                val target = args[2]
                val resultJson = httpPost("/api/admin/monitors/remove", """{"providerId":"$providerId","target":"$target"}""") ?: return
                val result = try { json.decodeFromString<AdminActionResult>(resultJson) } catch (_: Exception) { null }
                if (result?.success == true) {
                    println("${green}✓ ${result.message}${reset}")
                } else {
                    println("${red}✗ ${result?.message ?: "Falha ao remover monitor."}${reset}")
                }
            }
            "diag", "check" -> {
                val providerId = if (args.size > 1) args[1].lowercase() else "pm2"
                val diag = httpGet("/api/admin/monitors/diag/$providerId")
                if (diag != null) {
                    println()
                    println(diag)
                    println()
                } else {
                    println("${red}Falha ao obter diagnóstico do provedor '$providerId'.${reset}")
                }
            }
            else -> println("${yellow}Subcomando desconhecido: '${args[0]}'. Use: providers, list, add, sectors, remove, diag.${reset}")
        }
    }

    private fun handleToken(args: List<String>) {
        val token = configManager.config.sdkToken
        println()
        println("${bold}--- Token de Acesso do SDK (IPC) ---${reset}")
        println("  Token: ${green}${bold}$token${reset}")
        println()
        println("  Utilize este token nos seus projetos para autenticar no socket:")
        println("  ${cyan}MSM_TOKEN=$token${reset}")
        println()
        println("  Para alterar ou gerar um novo token, use:")
        println("  ${cyan}msm config set --regenerate-token --restart${reset}\n")
    }

    private fun clearScreen() {
        print("\u001B[H\u001B[2J")
        System.out.flush()
    }

    private fun httpGet(path: String): String? {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl$path"))
                .header("X-MSM-Secret", configManager.config.secretKey)
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() in 200..299) resp.body() else null
        } catch (e: Exception) {
            println("${red}Erro de conexão com o Agent ($baseUrl): ${e.message}${reset}")
            null
        }
    }

    private fun httpPost(path: String, jsonBody: String): String? {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl$path"))
                .header("Content-Type", "application/json")
                .header("X-MSM-Secret", configManager.config.secretKey)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            resp.body()
        } catch (e: Exception) {
            println("${red}Erro de conexão com o Agent ($baseUrl): ${e.message}${reset}")
            null
        }
    }

    private fun httpDelete(path: String): String? {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl$path"))
                .header("X-MSM-Secret", configManager.config.secretKey)
                .timeout(Duration.ofSeconds(5))
                .DELETE()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            resp.body()
        } catch (e: Exception) {
            println("${red}Erro de conexão com o Agent ($baseUrl): ${e.message}${reset}")
            null
        }
    }
}
