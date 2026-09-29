package io.github.imhammer.cli

import io.github.imhammer.config.ConfigManager
import java.io.File

object AgentInfoPrinter {

    fun printInfo(configManager: ConfigManager? = null, socketFile: File? = null) {
        val cyan = "\u001B[36m"
        val green = "\u001B[32m"
        val yellow = "\u001B[33m"
        val bold = "\u001B[1m"
        val reset = "\u001B[0m"

        val workDir = File(System.getProperty("user.dir")).absoluteFile
        val manager = configManager ?: ConfigManager()
        val cfgFile = manager.configFile
        val baseDir = if (cfgFile.parentFile != null && cfgFile.parentFile.exists()) {
            cfgFile.parentFile
        } else {
            workDir
        }

        val logFile = if (File(baseDir, "msm.log").exists()) {
            File(baseDir, "msm.log")
        } else {
            File(workDir, "msm.log")
        }

        val isLinux = System.getProperty("os.name").lowercase().contains("linux")
        val sock = socketFile ?: if (isLinux) {
            val runDir = File("/run/msm")
            if (runDir.exists()) File(runDir, "events.sock") else File(System.getProperty("user.home"), ".msm/events.sock")
        } else {
            File(workDir, ".msm/events.sock")
        }

        val serviceFile = File("/etc/systemd/system/msm.service")
        val jarLocation = try {
            File(AgentInfoPrinter::class.java.protectionDomain.codeSource.location.toURI()).absolutePath
        } catch (_: Exception) {
            "Desconhecido"
        }

        val configStatus = if (cfgFile.exists()) "${green}Existe${reset}" else "${yellow}Não criado${reset}"
        val logStatus = if (logFile.exists()) "${green}Existe (${logFile.length() / 1024} KB)${reset}" else "${yellow}Ainda não gerado${reset}"
        val sockStatus = if (sock.exists()) "${green}Ativo${reset}" else "${yellow}Inativo${reset}"
        val serviceStatus = if (serviceFile.exists()) "${green}Instalado${reset}" else "${yellow}Não instalado${reset}"

        val sdkToken = try {
            if (cfgFile.exists()) {
                manager.config.sdkToken
            } else {
                "(não criado)"
            }
        } catch (_: Exception) {
            "(não definido)"
        }

        println()
        println("${cyan}${bold}======================================================${reset}")
        println("${cyan}${bold}         MSM AGENT - INFORMAÇÕES DE ARQUIVOS          ${reset}")
        println("${cyan}${bold}======================================================${reset}")

        println("  • ${bold}Configuração:${reset}      ${cyan}${cfgFile.absolutePath}${reset} [$configStatus]")
        println("  • ${bold}Token do SDK:${reset}       ${green}$sdkToken${reset}")
        println("  • ${bold}Arquivo de Log:${reset}     ${cyan}${logFile.absolutePath}${reset} [$logStatus]")
        println("  • ${bold}Socket Unix (SDK):${reset}  ${cyan}${sock.absolutePath}${reset} [$sockStatus]")
        println("  • ${bold}Serviço systemd:${reset}   ${cyan}${serviceFile.absolutePath}${reset} [$serviceStatus]")
        println("  • ${bold}Binário / Jar:${reset}     ${cyan}$jarLocation${reset}")
        println("  • ${bold}Diretório Base:${reset}    ${cyan}${baseDir.absolutePath}${reset}")
        println()
        println("${bold}Dados do Ambiente de Execução:${reset}")
        println("  • Sistema Operacional: ${System.getProperty("os.name")} (${System.getProperty("os.arch")})")
        println("  • Java Runtime:        ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
        println("  • PID do Processo:     ${ProcessHandle.current().pid()}")
        println()
        println("${bold}Instruções para Remoção / Limpeza Completa:${reset}")
        println("  Caso deseje desinstalar ou limpar todos os dados do Agent no servidor:")
        if (isLinux) {
            println("    ${yellow}1. Parar e remover o serviço do sistema:${reset}")
            println("       sudo systemctl disable --now msm && sudo rm -f /etc/systemd/system/msm.service && sudo systemctl daemon-reload")
        }
        println("    ${yellow}2. Remover configuração, sockets e histórico de logs:${reset}")
        println("       rm -f \"${cfgFile.absolutePath}\" \"${logFile.absolutePath}\" \"${sock.absolutePath}\"")
        println()
    }
}
