package io.github.imhammer.cli

import io.github.imhammer.config.ConfigManager
import io.github.imhammer.service.ServiceInstaller
import java.util.Scanner
import java.util.UUID

class ConfigCli(private val configManager: ConfigManager = ConfigManager()) {

    private val cyan = "\u001B[36m"
    private val green = "\u001B[32m"
    private val yellow = "\u001B[33m"
    private val red = "\u001B[31m"
    private val bold = "\u001B[1m"
    private val reset = "\u001B[0m"

    fun handleArgs(args: List<String>) {
        if (args.isEmpty()) {
            showConfig()
            return
        }

        when (val sub = args[0].lowercase()) {
            "show", "get", "list", "status" -> showConfig()
            "set" -> setConfig(args.drop(1))
            "edit", "wizard", "interactive" -> editInteractive()
            "restart" -> restartService()
            "token" -> tokenSubcommand(args.drop(1))
            "help", "--help", "-h", "?" -> printHelp()
            else -> {
                // Se o usuário passou diretamente opções como 'msm config --port 9001'
                if (sub.startsWith("-")) {
                    setConfig(args)
                } else {
                    println("${yellow}Subcomando de config desconhecido: '$sub'.${reset}")
                    printHelp()
                }
            }
        }
    }

    fun showConfig() {
        val file = configManager.configFile
        if (!file.exists()) {
            println("${yellow}Aviso: Arquivo de configuração não encontrado em: ${file.absolutePath}${reset}")
            println("Execute '${bold}msm config edit${reset}' para criar e salvar a configuração inicial.")
            return
        }

        val config = configManager.getOrLoad()
        println()
        println("${cyan}${bold}======================================================${reset}")
        println("${cyan}${bold}            MSM AGENT - CONFIGURAÇÕES ATUAIS          ${reset}")
        println("${cyan}${bold}======================================================${reset}")
        println("  • Arquivo no disco:   ${file.absolutePath}")
        println("  • Nome do Host:       ${bold}${config.name}${reset}")
        println("  • Porta HTTP e WS:    ${cyan}${bold}${config.port}${reset}")
        val pairingStr = if (config.pairingEnabled) "${green}ATIVADO (aceita novas conexões)${reset}" else "${red}DESATIVADO${reset}"
        println("  • Pareamento Móvel:   $pairingStr")
        println("  • Token do SDK (IPC): ${yellow}${config.sdkToken}${reset}")
        println("  • Aparelhos pareados: ${config.devices.size} registrado(s)")
        println("  • Monitores de Host:  ${config.monitors.size} configurado(s)")
        println("${cyan}${bold}======================================================${reset}")
        println("Dica: Para alterar parâmetros e reiniciar o serviço automaticamente:")
        println("  ${cyan}msm config set --name <nome> --port <porta> --pairing <on|off> --restart${reset}")
        println("Ou utilize o assistente interativo:")
        println("  ${cyan}msm config edit${reset}\n")
    }

    private fun setConfig(args: List<String>) {
        if (args.isEmpty()) {
            println("${yellow}Nenhum parâmetro informado para 'config set'.${reset}")
            println("Uso: msm config set [--name <nome>] [--port <porta>] [--pairing <on|off>] [--token <token>] [--regenerate-token] [--restart]")
            return
        }

        var newName: String? = null
        var newPort: Int? = null
        var newPairing: Boolean? = null
        var newToken: String? = null
        var regenerateToken = false
        var shouldRestart = false

        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--name", "-n" -> {
                    if (i + 1 < args.size) {
                        newName = args[++i]
                    } else {
                        println("${red}Erro: Flag '$arg' requer um valor de nome.${reset}")
                        return
                    }
                }
                "--port", "-p" -> {
                    if (i + 1 < args.size) {
                        val p = args[++i].toIntOrNull()
                        if (p == null || p !in 1..65535) {
                            println("${red}Erro: Porta inválida '${args[i]}'. A porta deve ser um número inteiro entre 1 e 65535.${reset}")
                            return
                        }
                        newPort = p
                    } else {
                        println("${red}Erro: Flag '$arg' requer o número da porta.${reset}")
                        return
                    }
                }
                "--pairing" -> {
                    if (i + 1 < args.size) {
                        val v = args[++i].lowercase()
                        newPairing = when (v) {
                            "on", "true", "1", "sim", "yes", "enable", "enabled", "ativado" -> true
                            "off", "false", "0", "nao", "não", "no", "disable", "disabled", "desativado" -> false
                            else -> {
                                println("${red}Erro: Valor de pairing inválido '$v'. Use: on/off ou true/false.${reset}")
                                return
                            }
                        }
                    } else {
                        println("${red}Erro: Flag '$arg' requer o valor on ou off.${reset}")
                        return
                    }
                }
                "--token", "-t" -> {
                    if (i + 1 < args.size) {
                        newToken = args[++i]
                    } else {
                        println("${red}Erro: Flag '$arg' requer o token.${reset}")
                        return
                    }
                }
                "--regenerate-token" -> {
                    regenerateToken = true
                }
                "--restart", "-r" -> {
                    shouldRestart = true
                }
                else -> {
                    println("${yellow}Aviso: Parâmetro desconhecido ignorado: '$arg'${reset}")
                }
            }
            i++
        }

        val config = configManager.getOrLoad()
        val changes = mutableListOf<String>()

        if (newName != null && newName != config.name) {
            changes.add("Nome do Host: '${config.name}' -> '$newName'")
            config.name = newName
        }

        if (newPort != null && newPort != config.port) {
            changes.add("Porta: ${config.port} -> $newPort")
            config.port = newPort
        }

        if (newPairing != null && newPairing != config.pairingEnabled) {
            val oldStr = if (config.pairingEnabled) "on" else "off"
            val newStr = if (newPairing) "on" else "off"
            changes.add("Pareamento de novos aparelhos: $oldStr -> $newStr")
            config.pairingEnabled = newPairing
        }

        if (regenerateToken) {
            val genToken = "msm_sdk_" + UUID.randomUUID().toString().replace("-", "")
            changes.add("Token do SDK: regenerado para '$genToken'")
            config.sdkToken = genToken
        } else if (newToken != null && newToken != config.sdkToken) {
            changes.add("Token do SDK: atualizado")
            config.sdkToken = newToken
        }

        if (changes.isNotEmpty()) {
            configManager.save(config)
            println("${green}✓ Configuração atualizada com sucesso em '${configManager.configFile.absolutePath}':${reset}")
            for (c in changes) {
                println("  • $c")
            }
        } else if (!shouldRestart) {
            println("${yellow}Nenhuma alteração foi realizada (valores fornecidos já correspondem aos atuais).${reset}")
        }

        if (shouldRestart) {
            println()
            restartService()
        } else if (changes.isNotEmpty()) {
            println()
            println("${yellow}Dica: Para aplicar as novas configurações ao serviço em execução na VPS, reinicie com:${reset}")
            println("  ${cyan}sudo msm service restart${reset} (ou passe a flag ${bold}--restart${reset} nos próximos comandos)")
        }
    }

    private fun editInteractive() {
        println()
        println("${cyan}${bold}======================================================${reset}")
        println("${cyan}${bold}     MSM AGENT - ASSISTENTE DE EDIÇÃO DE CONFIG      ${reset}")
        println("${cyan}${bold}======================================================${reset}")
        println("Arquivo em edição: ${configManager.configFile.absolutePath}\n")

        val config = configManager.getOrLoad()
        val scanner = Scanner(System.`in`)

        print("-> Nome do Host/Agent [${config.name}]: ")
        val inputName = if (scanner.hasNextLine()) scanner.nextLine().trim() else ""
        val chosenName = if (inputName.isNotEmpty()) inputName else config.name

        print("-> Porta HTTP & WebSocket [${config.port}]: ")
        val inputPort = if (scanner.hasNextLine()) scanner.nextLine().trim() else ""
        val chosenPort = inputPort.toIntOrNull() ?: config.port

        val currentPairingStr = if (config.pairingEnabled) "on" else "off"
        print("-> Permitir pareamento de novos celulares (on/off) [$currentPairingStr]: ")
        val inputPairing = if (scanner.hasNextLine()) scanner.nextLine().trim().lowercase() else ""
        val chosenPairing = when (inputPairing) {
            "on", "sim", "true", "1", "s" -> true
            "off", "nao", "não", "false", "0", "n" -> false
            else -> config.pairingEnabled
        }

        print("-> Deseja regenerar o token do SDK? (s/N) [n]: ")
        val inputRegen = if (scanner.hasNextLine()) scanner.nextLine().trim().lowercase() else ""
        val shouldRegen = inputRegen == "s" || inputRegen == "sim" || inputRegen == "y" || inputRegen == "yes"

        config.name = chosenName
        config.port = chosenPort
        config.pairingEnabled = chosenPairing
        if (shouldRegen) {
            config.sdkToken = "msm_sdk_" + UUID.randomUUID().toString().replace("-", "")
        }

        configManager.save(config)
        println("\n${green}✓ Configurações salvas com sucesso em '${configManager.configFile.absolutePath}'!${reset}")
        println("  • Nome: $chosenName")
        println("  • Porta: $chosenPort")
        println("  • Pareamento: ${if (chosenPairing) "ATIVADO" else "DESATIVADO"}")
        println("  • Token do SDK: ${config.sdkToken}")

        println()
        print("-> Deseja reiniciar o serviço 'msm' no systemd agora para aplicar? [S/n]: ")
        val inputRestart = if (scanner.hasNextLine()) scanner.nextLine().trim().lowercase() else ""
        val doRestart = inputRestart.isEmpty() || inputRestart == "s" || inputRestart == "sim" || inputRestart == "y" || inputRestart == "yes"

        if (doRestart) {
            println()
            restartService()
        } else {
            println("Para reiniciar mais tarde: '${cyan}sudo msm service restart${reset}'\n")
        }
    }

    private fun restartService() {
        val installer = ServiceInstaller()
        installer.restart()
    }

    private fun tokenSubcommand(args: List<String>) {
        if (args.isNotEmpty() && args[0].lowercase() == "regenerate") {
            val newToken = configManager.regenerateSdkToken()
            println("${green}✓ Novo token do SDK gerado com sucesso:${reset}")
            println("  ${bold}$newToken${reset}")
            println("Lembre-se de reiniciar o serviço se necessário: 'sudo msm service restart'")
            return
        }

        val config = configManager.getOrLoad()
        println("Token do SDK atual: ${yellow}${bold}${config.sdkToken}${reset}")
        println("Para gerar um novo: 'msm config token regenerate'")
    }

    fun printHelp() {
        println()
        println("${bold}Uso do comando config:${reset}")
        println("  ${cyan}msm config${reset} / ${cyan}msm config show${reset}              - Exibe as configurações atuais do Agent.")
        println("  ${cyan}msm config edit${reset}                              - Abre o assistente interativo para editar opções.")
        println("  ${cyan}msm config restart${reset}                           - Reinicia o serviço msm no systemd.")
        println("  ${cyan}msm config token [regenerate]${reset}                - Exibe ou regenera o token de autenticação dos SDKs.")
        println()
        println("${bold}Alteração rápida via linha de comando:${reset}")
        println("  ${cyan}msm config set [opções]${reset}")
        println()
        println("${bold}Opções disponíveis para 'set':${reset}")
        println("  ${bold}-n, --name <nome>${reset}                          - Define o nome identificador do Host/Agent.")
        println("  ${bold}-p, --port <porta>${reset}                         - Define a porta HTTP/WebSocket (ex: 9000).")
        println("  ${bold}--pairing <on|off>${reset}                         - Habilita ou desabilita o pareamento de aparelhos.")
        println("  ${bold}-t, --token <token>${reset}                        - Define manualmente o token do SDK.")
        println("  ${bold}--regenerate-token${reset}                         - Gera um novo token seguro aleatório para os SDKs.")
        println("  ${bold}-r, --restart${reset}                              - Reinicia o serviço systemd automaticamente após salvar.")
        println()
        println("${bold}Exemplos práticos:${reset}")
        println("  msm config set --name prod-vps --port 9500 --restart")
        println("  msm config set --pairing off")
        println("  msm config set --regenerate-token -r")
        println()
    }
}
