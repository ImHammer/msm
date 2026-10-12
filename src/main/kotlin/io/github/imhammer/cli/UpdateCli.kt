package io.github.imhammer.cli

import java.io.File

class UpdateCli {

    private val cyan = "\u001B[36m"
    private val green = "\u001B[32m"
    private val yellow = "\u001B[33m"
    private val red = "\u001B[31m"
    private val bold = "\u001B[1m"
    private val reset = "\u001B[0m"

    fun handleArgs(args: List<String>) {
        val isLinux = System.getProperty("os.name").lowercase().contains("linux")
        if (!isLinux) {
            println("${yellow}Aviso: A atualização automática de sistema via CLI é destinada a servidores Linux (VPS).${reset}")
            println("No seu ambiente atual (${System.getProperty("os.name")}), atualize o código via git ou baixe a nova release.")
            return
        }

        println()
        println("${cyan}${bold}======================================================${reset}")
        println("${cyan}${bold}         MSM AGENT - ATUALIZAÇÃO AUTOMÁTICA           ${reset}")
        println("${cyan}${bold}======================================================${reset}")
        println("Iniciando verificação e download da versão mais recente...")
        println()

        val localScript = File("/opt/msm/install.sh")
        val forceDownload = if (args.contains("--force") || args.contains("-f")) "MSM_FORCE_DOWNLOAD=true " else ""
        val customUrl = args.indexOf("--url").let { idx ->
            if (idx != -1 && idx + 1 < args.size) "MSM_DOWNLOAD_URL='${args[idx + 1]}' " else ""
        }

        val updateCommand = when {
            localScript.exists() -> "${forceDownload}${customUrl}bash /opt/msm/install.sh"
            else -> "${forceDownload}${customUrl}curl -fsSL https://raw.githubusercontent.com/imhammer/msm/main/install.sh | bash"
        }

        try {
            val proc = ProcessBuilder("bash", "-c", updateCommand)
                .inheritIO()
                .start()
            val exitCode = proc.waitFor()
            if (exitCode == 0) {
                println("\n${green}${bold}✓ MSM Agent atualizado com sucesso!${reset}\n")
            } else {
                println("\n${red}✗ Ocorreu uma falha durante o processo de atualização (código: $exitCode).${reset}")
                println("Certifique-se de executar o comando com privilégios de administrador: '${bold}sudo msm update${reset}'\n")
            }
        } catch (e: Exception) {
            println("${red}Erro ao executar atualização: ${e.message}${reset}")
        }
    }
}
