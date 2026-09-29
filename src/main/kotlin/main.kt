package io.github.imhammer

import io.github.imhammer.cli.AgentConsole
import io.github.imhammer.config.ConfigManager
import io.github.imhammer.monitoring.EventSocketServer
import io.github.imhammer.monitoring.HostMonitorManager
import io.github.imhammer.monitoring.InstanceManager
import io.github.imhammer.pairing.PairingManager
import io.github.imhammer.service.ServiceInstaller
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

import io.github.imhammer.cli.ConfigCli

fun main(args: Array<String>) {
    // 1. Trata ajuda rápida da CLI
    if (args.isNotEmpty() && (args[0].lowercase() in listOf("help", "--help", "-h", "?"))) {
        printCliHelp()
        return
    }

    // 2. Trata subcomando de informações de arquivos e recursos
    if (args.isNotEmpty() && args[0].lowercase() == "info") {
        val configManager = ConfigManager()
        io.github.imhammer.cli.AgentInfoPrinter.printInfo(configManager)
        return
    }

    // 3. Trata subcomandos de configuração ('config ...')
    if (args.isNotEmpty() && args[0].lowercase() == "config") {
        val configCli = ConfigCli()
        configCli.handleArgs(args.drop(1))
        return
    }

    // 4. Trata subcomandos do auto-instalador de serviço (se invocado com 'service ...')
    if (args.isNotEmpty() && args[0].lowercase() == "service") {
        val installer = ServiceInstaller()
        val subCommand = if (args.size > 1) args[1].lowercase() else "status"
        when (subCommand) {
            "install" -> installer.install()
            "uninstall", "remove" -> installer.uninstall()
            "status" -> installer.status()
            "start" -> installer.start()
            "stop" -> installer.stop()
            "restart" -> installer.restart()
            else -> println("Subcomando de serviço desconhecido: '$subCommand'. Use: install, uninstall, status, start, stop, restart.")
        }
        return
    }

    // 5. Trata subcomando de atualização automática ('update')
    if (args.isNotEmpty() && args[0].lowercase() == "update") {
        val updateCli = io.github.imhammer.cli.UpdateCli()
        updateCli.handleArgs(args.drop(1))
        return
    }

    val nonInteractive = args.contains("--non-interactive") || args.contains("-y") || args.contains("daemon")

    val configManager = ConfigManager()
    val clientConsole = io.github.imhammer.cli.ClientConsole(configManager)
    val isDaemonRunning = clientConsole.isDaemonAlive()

    // 6. Comandos diretos da CLI caso o Agent já esteja rodando em segundo plano (ex: 'msm status', 'msm pairing accept 123', 'msm instances')
    if (args.isNotEmpty() && !nonInteractive) {
        val directCommand = args[0].lowercase()
        if (directCommand in listOf("status", "stats", "system", "instances", "insts", "pairing", "devices", "monitor", "token")) {
            if (isDaemonRunning) {
                clientConsole.handleCommand(directCommand, args.drop(1))
                return
            } else {
                println("\u001B[33mAviso: O serviço MSM Agent não está ativo no momento.\u001B[0m")
                println("Inicie o serviço com: '\u001B[1msudo msm service start\u001B[0m' ou execute '\u001B[1mmsm\u001B[0m' para iniciar interativamente.")
                return
            }
        }
    }

    // 7. Se o usuário executou apenas 'msm' (ou 'msm console' / 'attach') no terminal
    if (!nonInteractive && (args.isEmpty() || (args.size == 1 && args[0].lowercase() in listOf("console", "attach")))) {
        if (isDaemonRunning) {
            // O serviço já está ativo no systemd: conecta o Console Cliente ao invés de tentar abrir uma segunda porta!
            clientConsole.start()
            return
        }

        // Se o serviço estiver instalado no Linux mas parado, pergunta se deseja iniciar
        val installer = ServiceInstaller()
        if (installer.isSupported()) {
            println("\u001B[33mAviso: O serviço 'msm.service' não está ativo em segundo plano.\u001B[0m")
            print("Deseja iniciar o serviço no systemd agora? [S/n]: ")
            val scanner = java.util.Scanner(System.`in`)
            val choice = if (scanner.hasNextLine()) scanner.nextLine().trim().lowercase() else ""
            if (choice.isEmpty() || choice == "s" || choice == "sim" || choice == "y" || choice == "yes") {
                installer.start()
                Thread.sleep(2000)
                if (clientConsole.isDaemonAlive()) {
                    clientConsole.start()
                    return
                }
            }
        }
    }

    // 8. Se foram passados argumentos desconhecidos no modo interativo, avisa o usuário
    if (args.isNotEmpty() && !nonInteractive) {
        println("\u001B[31mComando desconhecido: '${args[0]}'\u001B[0m")
        println("Execute '\u001B[36mmsm help\u001B[0m' para ver a lista de comandos disponíveis.")
        return
    }

    // 9. Carrega ou executa o assistente de primeira configuração para inicialização do Daemon
    val config = configManager.loadOrCreate(nonInteractive = nonInteractive)

    // 9. Inicializa gerenciadores, monitoramento e ingestão
    val pairingManager = PairingManager(configManager)
    val systemMonitor = SystemMonitor()
    val instanceManager = InstanceManager()

    val hostMonitorManager = HostMonitorManager(configManager, instanceManager)
    hostMonitorManager.start()

    val eventSocketServer = EventSocketServer(instanceManager, configManager = configManager)
    eventSocketServer.start()

    // 10. Inicializa o servidor Ktor
    val server = embeddedServer(Netty, port = config.port) {
        configureSerialization()
        configureWebsockets()
        configureRouting(configManager, pairingManager, systemMonitor, instanceManager, hostMonitorManager)
    }

    server.start(wait = false)

    // Registra gancho de desligamento seguro
    Runtime.getRuntime().addShutdownHook(Thread {
        hostMonitorManager.stop()
        eventSocketServer.stop()
        try {
            server.stop(1000, 2000)
        } catch (_: Exception) {}
    })

    // 11. Execução em segundo plano ou console interativo standalone
    if (nonInteractive) {
        println("MSM Agent ativo em segundo plano na porta ${config.port} (Host: ${config.name}).")
        Thread.currentThread().join()
    } else {
        val console = AgentConsole(
            configManager = configManager,
            pairingManager = pairingManager,
            systemMonitor = systemMonitor,
            hostMonitorManager = hostMonitorManager,
            instanceManager = instanceManager,
            server = server
        )
        console.start()
    }
}

private fun printCliHelp() {
    val cyan = "\u001B[36m"
    val green = "\u001B[32m"
    val yellow = "\u001B[33m"
    val bold = "\u001B[1m"
    val reset = "\u001B[0m"
    val gray = "\u001B[90m"

    println()
    println("${cyan}${bold}======================================================================${reset}")
    println("${cyan}${bold}                 MSM AGENT - MANUAL DA CLI (HELP)                     ${reset}")
    println("${cyan}${bold}======================================================================${reset}")
    println("${bold}Uso:${reset} ${cyan}msm${reset} [comando] [opções]")
    println()
    println("${bold}💻 Console Interativo & Daemon:${reset}")
    println("  ${cyan}${bold}msm${reset}                               - Abre o console interativo conectado ao Agent.")
    println("  ${cyan}msm --non-interactive${reset}               - Executa em segundo plano (usado pelo systemd).")
    println()
    println("${bold}📱 Pareamento com Aplicativo Android:${reset}")
    println("  ${cyan}msm pairing requests${reset}                - Lista solicitações pendentes de conexão.")
    println("  ${cyan}msm pairing accept <código>${reset}          - Aprova a conexão do celular (ex: msm pairing accept 123456).")
    println("  ${cyan}msm pairing reject <código>${reset}          - Recusa uma solicitação pendente.")
    println("  ${cyan}msm pairing on${reset} / ${cyan}msm pairing off${reset}         - Habilita ou desabilita novos pareamentos.")
    println("  ${cyan}msm devices list${reset}                    - Lista todos os aparelhos vinculados ao Agent.")
    println("  ${cyan}msm devices revoke <id>${reset}             - Revoga e bloqueia o acesso de um dispositivo.")
    println()
    println("${bold}📊 Monitoramento de Host & Diagnóstico:${reset}")
    println("  ${cyan}msm status${reset}                          - Exibe resumo geral do Agent e métricas da VPS.")
    println("  ${cyan}msm stats${reset} | ${cyan}msm system${reset}                 - Exibe uso em tempo real de CPU e Memória RAM.")
    println("  ${cyan}msm instances${reset}                       - Lista instâncias, setores e métricas vigiadas.")
    println("  ${cyan}msm monitor providers${reset}               - Lista provedores (pm2, etc.) e setores suportados.")
    println("  ${cyan}msm monitor list${reset}                    - Lista alvos configurados e seus setores ativos.")
    println("  ${cyan}msm monitor add <prov> <alvo> [-s <setores>]${reset} - Monitora processos (ex: msm monitor add pm2 all -s status,cpu).")
    println("  ${cyan}msm monitor sectors <prov> [alvo] [setores]${reset}  - Configura setores enviados ao app mobile.")
    println("  ${cyan}msm monitor remove <prov> <alvo>${reset}     - Remove um alvo do monitoramento.")
    println("  ${cyan}msm info${reset}                            - Exibe localização dos arquivos (config, logs, sockets).")
    println()
    println("${bold}⚙️ Configuração & Tokens:${reset}")
    println("  ${cyan}msm config${reset} / ${cyan}msm config show${reset}         - Exibe as configurações atuais do Agent.")
    println("  ${cyan}msm config edit${reset}                     - Assistente interativo para editar opções.")
    println("  ${cyan}msm config set [opções]${reset}             - Altera valores diretamente via flags:")
    println("       ${gray}--port <porta> --name <nome> --pairing <on|off> --restart${reset}")
    println("  ${cyan}msm config restart${reset}                  - Reinicia o serviço msm no systemd.")
    println("  ${cyan}msm token${reset}                           - Exibe o token de autenticação dos SDKs (IPC).")
    println()
    println("${bold}🔧 Gerenciamento do Serviço (systemd):${reset}")
    println("  ${cyan}sudo msm service status${reset}             - Consulta o status do serviço no systemd.")
    println("  ${cyan}sudo msm service restart${reset}            - Reinicia o serviço em segundo plano.")
    println("  ${cyan}sudo msm service stop${reset}               - Para a execução do serviço.")
    println("  ${cyan}sudo msm service start${reset}              - Inicia o serviço parado.")
    println("  ${cyan}sudo msm update${reset}                     - Atualiza o MSM Agent para a versão mais recente.")
    println()
    println("${bold}💡 Exemplos Rápidos:${reset}")
    println("  ${green}msm monitor add pm2 all -s status,cpu${reset} ${gray}# Monitora PM2 enviando apenas status e CPU ao app${reset}")
    println("  ${green}msm monitor sectors pm2 all status${reset}   ${gray}# Altera setores ativos para enviar apenas status${reset}")
    println("  ${green}msm pairing accept 492018${reset}         ${gray}# Aprova o celular com o código OTP${reset}")
    println("  ${green}msm config set --port 9500 -r${reset}     ${gray}# Altera a porta e reinicia o serviço${reset}")
    println("  ${green}msm status${reset}                        ${gray}# Consulta o status direto do terminal${reset}")
    println("  ${green}msm${reset}                               ${gray}# Abre o console interativo conectado${reset}")
    println("${cyan}${bold}======================================================================${reset}")
    println()
}
