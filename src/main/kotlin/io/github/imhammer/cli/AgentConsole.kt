package io.github.imhammer.cli

import io.github.imhammer.SystemMonitor
import io.github.imhammer.config.ConfigManager
import io.github.imhammer.monitoring.HostMonitorManager
import io.github.imhammer.monitoring.InstanceManager
import io.github.imhammer.pairing.PairingManager
import io.ktor.server.engine.EmbeddedServer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Scanner

class AgentConsole(
    private val configManager: ConfigManager,
    private val pairingManager: PairingManager,
    private val systemMonitor: SystemMonitor,
    private val hostMonitorManager: HostMonitorManager,
    private val instanceManager: InstanceManager,
    private val server: EmbeddedServer<*, *>
) {

    // Códigos ANSI para estilização no terminal
    private val cyan = "\u001B[36m"
    private val green = "\u001B[32m"
    private val yellow = "\u001B[33m"
    private val red = "\u001B[31m"
    private val bold = "\u001B[1m"
    private val reset = "\u001B[0m"
    private val gray = "\u001B[90m"

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss")
    private var isRunning = true

    fun start() {
        printBanner()
        val scanner = Scanner(System.`in`)

        while (isRunning) {
            try {
                print("${cyan}msm@${configManager.config.name}${reset} > ")
                if (!scanner.hasNextLine()) {
                    Thread.sleep(1000)
                    continue
                }
                val line = scanner.nextLine().trim()
                if (line.isEmpty()) continue

                handleCommand(line)
            } catch (_: NoSuchElementException) {
                Thread.sleep(1000)
            } catch (e: Exception) {
                println("${red}Erro ao executar comando: ${e.message}${reset}")
            }
        }
    }

    private fun handleCommand(input: String) {
        val parts = input.split("\\s+".toRegex())
        val command = parts[0].lowercase()
        val args = parts.drop(1)

        when (command) {
            "help", "?" -> printHelp()
            "info" -> AgentInfoPrinter.printInfo(configManager)
            "config" -> handleConfigCommand(args)
            "token", "sdk-token" -> handleTokenCommand(args)
            "status" -> printStatus()
            "system", "stats" -> printSystemStats()
            "pairing" -> handlePairingCommand(args)
            "devices" -> handleDevicesCommand(args)
            "monitor" -> handleMonitorCommand(args)
            "instances", "insts" -> handleInstancesCommand(args)
            "clear", "cls" -> clearScreen()
            "update" -> handleUpdateCommand(args)
            "exit", "stop", "quit" -> stopAgent()
            else -> println("${yellow}Comando desconhecido: '$command'. Digite 'help' para ver os comandos disponíveis.${reset}")
        }
    }

    private fun printBanner() {
        println()
        println("${cyan}${bold}======================================================${reset}")
        println("${cyan}${bold}            MSM AGENT - MONITORING HOST               ${reset}")
        println("${cyan}${bold}======================================================${reset}")
        println("Host: ${bold}${configManager.config.name}${reset} | Porta: ${bold}${configManager.config.port}${reset}")
        val pairingStatus = if (pairingManager.isPairingEnabled) "${green}ATIVADO${reset}" else "${red}DESATIVADO${reset}"
        println("Vinculação de novos dispositivos: $pairingStatus")
        val monitorsCount = hostMonitorManager.getConfiguredMonitors().size
        val instancesCount = instanceManager.getInstances().size
        println("Monitores configurados: ${bold}$monitorsCount${reset} | Instâncias ativas: ${bold}$instancesCount${reset}")
        println("Digite '${bold}help${reset}' para listar os comandos interativos em tempo de execução.\n")
    }

    private fun printHelp() {
        println()
        println("${bold}Comandos disponíveis:${reset}")
        println("  ${cyan}status${reset}                               - Exibe o estado geral do Agent e resumo.")
        println("  ${cyan}config [show|set|edit]${reset}               - Exibe ou edita configurações (nome, porta, pareamento).")
        println("  ${cyan}info${reset}                                 - Exibe caminhos dos arquivos (config, logs, sockets) e remoção.")
        println("  ${cyan}token [regenerate]${reset}                   - Exibe ou regenera o token de autenticação dos SDKs.")
        println("  ${cyan}stats${reset} | ${cyan}system${reset}                        - Exibe uso atual de CPU e Memória da VPS.")
        println("  ${cyan}instances${reset}                            - Lista todas as instâncias e setores monitorados.")
        println()
        println("  ${cyan}monitor providers${reset}                    - Lista provedores e setores fornecidos.")
        println("  ${cyan}monitor list${reset}                         - Lista alvos de monitoramento e setores ativos.")
        println("  ${cyan}monitor add <provedor> <alvo> [-s <setores>]${reset} - Adiciona um alvo (ex: 'monitor add pm2 all -s status,cpu').")
        println("  ${cyan}monitor sectors <provedor> [alvo] [setores]${reset}  - Consulta ou define setores ativos do provedor.")
        println("  ${cyan}monitor remove <provedor> <alvo>${reset}     - Remove um alvo do monitoramento.")
        println()
        println("  ${cyan}pairing on${reset}                           - Habilita a vinculação de novos dispositivos.")
        println("  ${cyan}pairing off${reset}                          - Desabilita novas vinculações.")
        println("  ${cyan}pairing requests${reset}                     - Lista solicitações pendentes de vinculação.")
        println("  ${cyan}pairing accept <código|id>${reset}           - Aprova uma solicitação de vinculação.")
        println("  ${cyan}pairing reject <código|id>${reset}           - Recusa/descarta uma solicitação.")
        println()
        println("  ${cyan}devices list${reset}                         - Lista todos os dispositivos pareados.")
        println("  ${cyan}devices revoke <id>${reset}                  - Revoga e remove o acesso de um dispositivo.")
        println()
        println("  ${cyan}clear${reset}                                - Limpa o console.")
        println("  ${cyan}exit${reset} | ${cyan}stop${reset}                          - Encerra o Agent e para o servidor.")
        println()
    }

    private fun printStatus() {
        val config = configManager.config
        val pairingStatus = if (pairingManager.isPairingEnabled) "${green}ATIVADO${reset}" else "${red}DESATIVADO${reset}"
        val devices = pairingManager.getPairedDevices()
        val pending = pairingManager.getPendingRequests()
        val instances = instanceManager.getInstances()
        val monitors = hostMonitorManager.getConfiguredMonitors()

        println()
        println("${bold}--- Status do Agent ---${reset}")
        println("  Nome:                 ${cyan}${config.name}${reset}")
        println("  Porta:                ${config.port}")
        println("  Modo Vinculação:      $pairingStatus")
        println("  Dispositivos Pareados: ${devices.size}")
        println("  Solicitações na Fila:  ${if (pending.isNotEmpty()) "${yellow}${pending.size}${reset}" else "0"}")
        println("  Monitores de Host:    ${monitors.size}")
        println("  Instâncias Ativas:    ${instances.size}")

        val cpu = String.format("%.2f", systemMonitor.getCpuUsage())
        val mem = systemMonitor.getMemoryUsage()
        val memUsedMb = mem.used / (1024 * 1024)
        val memTotalMb = mem.total / (1024 * 1024)
        val memPercent = String.format("%.1f", mem.percentage)
        println("  Uso de CPU:           $cpu%")
        println("  Uso de Memória:       $memUsedMb MB / $memTotalMb MB ($memPercent%)")
        println()
    }

    private fun printSystemStats() {
        val cpu = String.format("%.2f", systemMonitor.getCpuUsage())
        val mem = systemMonitor.getMemoryUsage()
        val memUsedMb = mem.used / (1024 * 1024)
        val memTotalMb = mem.total / (1024 * 1024)
        val memPercent = String.format("%.1f", mem.percentage)

        println()
        println("${bold}--- Métricas do Sistema ---${reset}")
        println("  Processador (CPU):    $cpu%")
        println("  Memória RAM:          $memUsedMb MB / $memTotalMb MB ($memPercent%)")
        println()
    }

    private fun handleMonitorCommand(args: List<String>) {
        if (args.isEmpty()) {
            println("Uso do comando monitor:")
            println("  ${cyan}monitor providers${reset}                 - Exibe provedores de host suportados.")
            println("  ${cyan}monitor list${reset}                      - Lista os alvos vigiados atualmente.")
            println("  ${cyan}monitor add <provedor> <all|alvo>${reset} - Monitora processos ou recursos.")
            println("  ${cyan}monitor remove <provedor> <alvo>${reset}  - Deixa de monitorar um recurso.")
            println("  ${cyan}monitor diag <provedor>${reset}           - Diagnóstico detalhado de conexão com o provedor.")
            return
        }

        when (args[0].lowercase()) {
            "providers", "provs" -> {
                println()
                println("${bold}--- Provedores de Monitoramento Disponíveis ---${reset}")
                for (p in hostMonitorManager.getProviders()) {
                    val statusStr = if (p.isAvailable()) "${green}Disponível no sistema${reset}" else "${yellow}Não detectado no PATH (modo dev simulado habilitado)${reset}"
                    println("  - ${bold}${p.id}${reset}: ${p.displayName} [$statusStr]")
                    val avail = p.getAvailableSectors()
                    if (avail.isNotEmpty()) {
                        println("    ${gray}Setores suportados:${reset}")
                        for (s in avail) {
                            val unitStr = if (!s.unit.isNullOrBlank()) " (${s.unit})" else ""
                            println("      • ${cyan}${s.id}${reset}: ${s.name}$unitStr ${gray}- ${s.description}${reset}")
                        }
                    }
                }
                println()
            }
            "list", "ls" -> {
                val monitors = hostMonitorManager.getConfiguredMonitors()
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
                    println("${yellow}Uso: monitor add <provedor> <all|nome_do_alvo> [--sectors s1,s2]${reset}")
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

                val provider = hostMonitorManager.getProvider(providerId)
                val available = provider?.getAvailableSectors() ?: emptyList()

                if (sectorsList == null && available.isNotEmpty()) {
                    println()
                    println("Setores disponíveis para o provedor '${cyan}$providerId${reset}':")
                    available.forEachIndexed { idx, s ->
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

                print("Verificando e registrando processos do provedor '$providerId'...")
                val result = hostMonitorManager.addMonitorDetailed(providerId, target, sectorsList)
                println()

                if (result.success) {
                    println("${green}✓ ${result.message}${reset}")
                    if (result.instancesFound.isNotEmpty()) {
                        println("${green}✓ ${result.instancesFound.size} instância(s) encontrada(s) e registrada(s):${reset}")
                        for (inst in result.instancesFound) {
                            val status = inst.sectors.firstOrNull { it.id == "status" }?.lastValue ?: "UNKNOWN"
                            val cpu = inst.sectors.firstOrNull { it.id == "cpu" }?.lastValue ?: "0"
                            val mem = inst.sectors.firstOrNull { it.id == "memory" }?.lastValue?.toLongOrNull() ?: 0L
                            val memMb = String.format("%.1f", mem / (1024.0 * 1024.0))
                            println("  • ${bold}${inst.id}${reset} (Status: $status | CPU: $cpu% | RAM: $memMb MB)")
                        }
                        println("Digite '${bold}instances${reset}' para visualizar todas as instâncias em detalhes.")
                    } else {
                        println("${yellow}⚠ Aviso: O monitor foi salvo, mas nenhuma instância ativa foi retornada pelo PM2 para '$target'.${reset}")
                        if (result.warning != null) {
                            println("  Detalhes: ${result.warning}")
                        }
                        println("  Dicas para resolver:")
                        println("  1. Verifique se o PM2 está com processos rodando via '${bold}pm2 list${reset}' no terminal.")
                        println("  2. Os processos rodam sob outro usuário (ex: 'ubuntu' vs 'root')?")
                        println("  3. Digite '${bold}monitor diag $providerId${reset}' para ver o diagnóstico completo do provedor.")
                    }
                } else {
                    println("${red}✗ ${result.message}${reset}")
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
                val provider = hostMonitorManager.getProvider(providerId)
                if (provider == null) {
                    println("${red}Provedor '$providerId' não encontrado.${reset}")
                    return
                }
                val avail = provider.getAvailableSectors()

                if (args.size == 2) {
                    println()
                    println("${bold}--- Setores Fornecidos pelo Provedor '$providerId' ---${reset}")
                    for (s in avail) {
                        val unitStr = if (!s.unit.isNullOrBlank()) " (${s.unit})" else ""
                        println("  • ${cyan}${bold}${s.id}${reset}: ${s.name}$unitStr")
                        println("    ${gray}${s.description}${reset}")
                    }

                    val configured = hostMonitorManager.getConfiguredMonitors().filter { it.providerId == providerId }
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

                val updated = hostMonitorManager.setMonitorSectors(providerId, effectiveTarget, sectorsList)
                if (updated) {
                    val msg = if (sectorsList.isNullOrEmpty()) "todos os setores disponíveis" else sectorsList.joinToString(", ")
                    println("${green}✓ Setores do monitor '$providerId' para o alvo '$effectiveTarget' atualizados com sucesso: [$msg].${reset}")
                } else {
                    println("${red}✗ Não foi possível atualizar os setores. Verifique se o alvo '$effectiveTarget' existe e se os setores informados são válidos.${reset}")
                }
            }
            "remove", "rm", "delete" -> {
                if (args.size < 3) {
                    println("${yellow}Uso: monitor remove <provedor> <alvo>${reset}")
                    return
                }
                val providerId = args[1].lowercase()
                val target = args[2]

                val removed = hostMonitorManager.removeMonitor(providerId, target)
                if (removed) {
                    println("${green}✓ Monitor '$providerId' para o alvo '$target' removido com sucesso.${reset}")
                } else {
                    println("${red}✗ Monitor '$providerId' com alvo '$target' não encontrado.${reset}")
                }
            }
            "diag", "diagnose", "check" -> {
                val providerId = if (args.size > 1) args[1].lowercase() else "pm2"
                val provider = hostMonitorManager.getProvider(providerId)
                if (provider == null) {
                    println("${red}✗ Provedor '$providerId' não encontrado.${reset}")
                    return
                }
                println()
                println(provider.getDiagnosticInfo())
                println()
            }
            else -> println("${yellow}Subcomando desconhecido: '${args[0]}'. Use: providers, list, add, remove, diag.${reset}")
        }
    }

    private fun handleInstancesCommand(args: List<String>) {
        val instances = instanceManager.getInstances()
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

            // 1. Dados Diretos na Instância (sem setor)
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

    private fun handlePairingCommand(args: List<String>) {
        if (args.isEmpty()) {
            val status = if (pairingManager.isPairingEnabled) "${green}ATIVADO${reset}" else "${red}DESATIVADO${reset}"
            println("Modo de vinculação está atualmente: $status")
            println("Use: 'pairing on', 'pairing off', 'pairing requests', 'pairing accept <código>' ou 'pairing reject <código>'")
            return
        }

        when (args[0].lowercase()) {
            "on", "enable", "ligar" -> {
                pairingManager.isPairingEnabled = true
                println("${green}✓ Modo de vinculação ATIVADO. Novos dispositivos podem enviar solicitações.${reset}")
            }
            "off", "disable", "desligar" -> {
                pairingManager.isPairingEnabled = false
                println("${yellow}✓ Modo de vinculação DESATIVADO. Novas solicitações serão bloqueadas.${reset}")
            }
            "requests", "reqs", "fila" -> {
                val requests = pairingManager.getPendingRequests()
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
                    println("${yellow}Uso: pairing accept <código-otp ou id-da-solicitação>${reset}")
                    return
                }
                val query = args[1]
                val device = pairingManager.acceptPairing(query)
                if (device != null) {
                    println("${green}✓ Dispositivo '${device.name}' (ID: ${device.id}) aprovado com sucesso!${reset}")
                    println("Token de acesso gerado e disponibilizado para o dispositivo.")
                } else {
                    println("${red}✗ Nenhuma solicitação pendente encontrada para '$query'.${reset}")
                }
            }
            "reject", "recusar" -> {
                if (args.size < 2) {
                    println("${yellow}Uso: pairing reject <código-otp ou id-da-solicitação>${reset}")
                    return
                }
                val query = args[1]
                val rejected = pairingManager.rejectPairing(query)
                if (rejected) {
                    println("${yellow}✓ Solicitação '$query' recusada.${reset}")
                } else {
                    println("${red}✗ Nenhuma solicitação pendente encontrada para '$query'.${reset}")
                }
            }
            else -> println("${yellow}Subcomando de pairing desconhecido: '${args[0]}'. Use: on, off, requests, accept, reject.${reset}")
        }
    }

    private fun handleDevicesCommand(args: List<String>) {
        val subCommand = if (args.isEmpty()) "list" else args[0].lowercase()

        when (subCommand) {
            "list", "ls" -> {
                val devices = pairingManager.getPairedDevices()
                if (devices.isEmpty()) {
                    println("Nenhum dispositivo móvel vinculado ainda.")
                    println("Ative a vinculação com 'pairing on' e vincule seu aparelho pelo app.")
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
            "revoke", "remove", "remover" -> {
                if (args.size < 2) {
                    println("${yellow}Uso: devices revoke <id-do-dispositivo>${reset}")
                    return
                }
                val targetId = args[1]
                val revoked = pairingManager.revokeDevice(targetId)
                if (revoked) {
                    println("${green}✓ Dispositivo '$targetId' revogado com sucesso! O acesso foi cancelado.${reset}")
                } else {
                    println("${red}✗ Dispositivo com ID '$targetId' não encontrado.${reset}")
                }
            }
            else -> println("${yellow}Subcomando de devices desconhecido: '$subCommand'. Use 'devices list' ou 'devices revoke <id>'.${reset}")
        }
    }

    private fun handleTokenCommand(args: List<String>) {
        if (args.isNotEmpty() && args[0].lowercase() == "regenerate") {
            val newToken = configManager.regenerateSdkToken()
            println()
            println("${green}✓ Novo token do SDK gerado com sucesso e salvo em '${configManager.configFile.name}':${reset}")
            println("  ${bold}$newToken${reset}")
            println("  Atualize a variável de ambiente MSM_TOKEN nos seus projetos.")
            println()
            return
        }

        val token = configManager.config.sdkToken
        println()
        println("${bold}--- Token de Acesso do SDK (IPC) ---${reset}")
        println("  Token: ${green}${bold}$token${reset}")
        println()
        println("  Utilize este token nos seus projetos (Node.js, etc.) para autenticar no socket:")
        println("  ${cyan}MSM_TOKEN=$token${reset}")
        println()
        println("  Para gerar um novo token: '${bold}token regenerate${reset}'")
        println()
    }

    private fun handleConfigCommand(args: List<String>) {
        val configCli = ConfigCli(configManager)
        configCli.handleArgs(args)
    }

    private fun handleUpdateCommand(args: List<String>) {
        println()
        println("${cyan}Para atualizar o MSM Agent na sua VPS para a versão mais recente:${reset}")
        println("  1. Saia deste console digitando '${bold}exit${reset}'")
        println("  2. No terminal da VPS, execute:")
        println("     ${cyan}${bold}sudo msm update${reset}")
        println("  O instalador baixará a versão mais recente, preservará suas configurações e reiniciará o serviço automaticamente.\n")
    }

    private fun clearScreen() {
        print("\u001B[H\u001B[2J")
        System.out.flush()
    }

    private fun stopAgent() {
        println("\n${yellow}Encerrando o MSM Agent...${reset}")
        isRunning = false
        hostMonitorManager.stop()
        try {
            server.stop(1000, 2000)
            println("${green}Servidor parado com sucesso.${reset}")
        } catch (e: Exception) {
            println("${red}Erro ao parar o servidor: ${e.message}${reset}")
        }
        System.exit(0)
    }
}
