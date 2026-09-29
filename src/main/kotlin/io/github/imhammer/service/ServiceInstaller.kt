package io.github.imhammer.service

import java.io.File
import java.util.concurrent.TimeUnit

class ServiceInstaller {

    private val isLinux = System.getProperty("os.name").lowercase().contains("linux")

    fun isSupported(): Boolean = isLinux

    fun install(): Boolean {
        if (!isLinux) {
            println("\u001B[33mAviso: A instalação de serviço via systemd é destinada a servidores Linux (VPS).\u001B[0m")
            println("No seu ambiente atual (${System.getProperty("os.name")}), execute o Agent diretamente com:")
            println("  ./gradlew run  (ou java -jar msm.jar)")
            return false
        }

        try {
            val jarFile = File(ServiceInstaller::class.java.protectionDomain.codeSource.location.toURI())
            val parentDir = jarFile.parentFile
            val rootDir = parentDir?.parentFile
            val binScript = if (rootDir != null) File(rootDir, "bin/msm") else null

            val execCommand = when {
                binScript != null && binScript.exists() -> "${binScript.absolutePath} --non-interactive"
                jarFile.extension == "jar" -> "java -jar ${jarFile.absolutePath} --non-interactive"
                else -> "${jarFile.absolutePath} --non-interactive"
            }

            val workingDir = when {
                binScript != null && binScript.exists() -> rootDir!!.absolutePath
                jarFile.parentFile != null -> jarFile.parentFile.absolutePath
                else -> File(".").absolutePath
            }

            val serviceContent = """
                [Unit]
                Description=MSM Monitoring Agent Host
                After=network.target

                [Service]
                Type=simple
                ExecStart=$execCommand
                WorkingDirectory=$workingDir
                Restart=always
                RestartSec=5
                User=root

                [Install]
                WantedBy=multi-user.target
            """.trimIndent()

            val serviceFile = File("/etc/systemd/system/msm.service")
            serviceFile.writeText(serviceContent)

            runProcess("systemctl", "daemon-reload")
            runProcess("systemctl", "enable", "--now", "msm")

            println("\u001B[32m✓ Serviço 'msm.service' instalado e iniciado com sucesso no systemd!\u001B[0m")
            println("Executável configurado: $execCommand")
            println("Para verificar o status: 'systemctl status msm'")
            return true
        } catch (e: Exception) {
            println("\u001B[31mErro ao instalar o serviço systemd: ${e.message}\u001B[0m")
            println("Certifique-se de executar com privilégios de root (sudo).")
            return false
        }
    }

    fun uninstall(): Boolean {
        if (!isLinux) {
            println("Comando 'service uninstall' disponível apenas em ambientes Linux.")
            return false
        }
        return try {
            runProcess("systemctl", "disable", "--now", "msm")
            val serviceFile = File("/etc/systemd/system/msm.service")
            if (serviceFile.exists()) {
                serviceFile.delete()
            }
            runProcess("systemctl", "daemon-reload")
            println("\u001B[32m✓ Serviço 'msm.service' desativado e removido do systemd com sucesso.\u001B[0m")
            true
        } catch (e: Exception) {
            println("\u001B[31mErro ao desinstalar serviço systemd: ${e.message}\u001B[0m")
            false
        }
    }

    fun status() {
        if (!isLinux) {
            println("Comando 'service status' disponível apenas em ambientes Linux.")
            return
        }
        val output = runProcessWithOutput("systemctl", "status", "msm")
        println(output)
    }

    fun start(): Boolean {
        if (!isLinux) {
            println("\u001B[33mAviso: O gerenciamento de serviço systemd só está disponível em servidores Linux.\u001B[0m")
            return false
        }
        val success = runProcess("systemctl", "start", "msm")
        if (success) {
            println("\u001B[32m✓ Serviço 'msm' iniciado com sucesso via systemctl!\u001B[0m")
        } else {
            println("\u001B[31m✗ Falha ao iniciar serviço 'msm'. Verifique permissões (sudo) ou consulte 'systemctl status msm'.\u001B[0m")
        }
        return success
    }

    fun stop(): Boolean {
        if (!isLinux) {
            println("\u001B[33mAviso: O gerenciamento de serviço systemd só está disponível em servidores Linux.\u001B[0m")
            return false
        }
        val success = runProcess("systemctl", "stop", "msm")
        if (success) {
            println("\u001B[32m✓ Serviço 'msm' parado com sucesso via systemctl!\u001B[0m")
        } else {
            println("\u001B[31m✗ Falha ao parar serviço 'msm'. Verifique permissões (sudo).\u001B[0m")
        }
        return success
    }

    fun restart(): Boolean {
        if (!isLinux) {
            println("\u001B[33mAviso: O gerenciamento de serviço systemd só está disponível em servidores Linux.\u001B[0m")
            return false
        }
        println("Reiniciando serviço 'msm' no systemd...")
        val success = runProcess("systemctl", "restart", "msm")
        if (success) {
            println("\u001B[32m✓ Serviço 'msm' reiniciado com sucesso no systemd!\u001B[0m")
        } else {
            println("\u001B[31m✗ Falha ao reiniciar serviço 'msm'. Verifique se possui permissão de root (sudo) ou execute 'journalctl -u msm -e'.\u001B[0m")
        }
        return success
    }

    private fun runProcess(vararg args: String): Boolean {
        return try {
            val proc = ProcessBuilder(*args).start()
            proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun runProcessWithOutput(vararg args: String): String {
        return try {
            val proc = ProcessBuilder(*args).redirectErrorStream(true).start()
            proc.waitFor(5, TimeUnit.SECONDS)
            proc.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            "Erro ao consultar serviço: ${e.message}"
        }
    }
}
