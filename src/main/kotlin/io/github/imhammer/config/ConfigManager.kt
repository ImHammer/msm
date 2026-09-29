package io.github.imhammer.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetAddress
import java.util.Scanner
import java.util.UUID

@Serializable
data class PairedDevice(
    val id: String,
    val name: String,
    val token: String,
    val pairedAt: Long,
    var lastSeenAt: Long
)

@Serializable
data class MonitoredTargetConfig(
    val providerId: String,
    val target: String,
    var enabled: Boolean = true,
    var enabledSectors: List<String>? = null // null = todos os setores padrão do provedor ativos
)

@Serializable
data class AgentConfig(
    var name: String,
    var port: Int = 9000,
    var pairingEnabled: Boolean = true,
    val secretKey: String = UUID.randomUUID().toString(),
    var sdkToken: String = "msm_sdk_" + UUID.randomUUID().toString().replace("-", ""),
    val devices: MutableList<PairedDevice> = mutableListOf(),
    val monitors: MutableList<MonitoredTargetConfig> = mutableListOf(),
    val providerDefaultSectors: MutableMap<String, List<String>> = mutableMapOf()
)

class ConfigManager(val configFile: File = resolveConfigFile()) {

    companion object {
        fun resolveConfigFile(): File {
            // 1. Arquivo local no diretório de execução atual
            val local = File("msm-config.json")
            if (local.exists()) return local

            // 2. Diretório padrão de instalação no Linux (/opt/msm)
            val optConfig = File("/opt/msm/msm-config.json")
            if (optConfig.exists()) return optConfig

            // 3. Diretório relativo ao binário ou JAR em execução
            try {
                val codeSource = ConfigManager::class.java.protectionDomain?.codeSource
                if (codeSource != null) {
                    val jarFile = File(codeSource.location.toURI())
                    val sameDir = File(jarFile.parentFile, "msm-config.json")
                    if (sameDir.exists()) return sameDir

                    val parentDir = jarFile.parentFile?.parentFile
                    if (parentDir != null) {
                        val inParent = File(parentDir, "msm-config.json")
                        if (inParent.exists()) return inParent
                    }
                }
            } catch (_: Exception) {}

            // 4. Se a pasta /opt/msm existir no sistema (ambiente de produção), prioriza lá
            val optDir = File("/opt/msm")
            if (optDir.exists() && optDir.isDirectory) {
                return optConfig
            }

            return local
        }
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var currentConfig: AgentConfig? = null

    val config: AgentConfig
        get() = currentConfig ?: loadOrCreate(nonInteractive = true)

    val configOrNull: AgentConfig?
        get() = currentConfig ?: if (configFile.exists()) {
            try {
                loadOrCreate(nonInteractive = true)
            } catch (_: Exception) {
                null
            }
        } else null

    fun hasConfigFile(): Boolean = configFile.exists()

    fun getOrLoad(): AgentConfig {
        return currentConfig ?: loadOrCreate(nonInteractive = true)
    }

    fun regenerateSdkToken(): String {
        val newToken = "msm_sdk_" + UUID.randomUUID().toString().replace("-", "")
        config.sdkToken = newToken
        save()
        return newToken
    }

    fun loadOrCreate(nonInteractive: Boolean = false): AgentConfig {
        if (configFile.exists()) {
            return try {
                val content = configFile.readText(Charsets.UTF_8)
                val loaded = json.decodeFromString<AgentConfig>(content)
                if (loaded.sdkToken.isBlank()) {
                    loaded.sdkToken = "msm_sdk_" + UUID.randomUUID().toString().replace("-", "")
                }
                currentConfig = loaded
                if (!content.contains("sdkToken")) {
                    save(loaded)
                }
                loaded
            } catch (e: Exception) {
                System.err.println("Erro ao carregar '${configFile.name}': ${e.message}. Criando nova configuração.")
                val fallback = runWizardOrDefaults(nonInteractive)
                save(fallback)
                fallback
            }
        }

        val newConfig = runWizardOrDefaults(nonInteractive)
        save(newConfig)
        return newConfig
    }

    fun save(configToSave: AgentConfig = config) {
        synchronized(this) {
            currentConfig = configToSave
            val text = json.encodeToString(configToSave)
            configFile.writeText(text, Charsets.UTF_8)
        }
    }

    private fun runWizardOrDefaults(nonInteractive: Boolean): AgentConfig {
        val defaultName = try {
            InetAddress.getLocalHost().hostName
        } catch (_: Exception) {
            "msm-vps"
        }
        val defaultPort = 9000

        if (nonInteractive) {
            println("[Config] Modo não interativo ativo. Utilizando configurações padrão: nome=$defaultName, porta=$defaultPort")
            return AgentConfig(name = defaultName, port = defaultPort)
        }

        println()
        println("======================================================")
        println("   MSM Agent - Assistente de Configuração Inicial     ")
        println("======================================================")
        println("Nenhum arquivo 'msm-config.json' encontrado.")
        println("Vamos realizar a configuração básica do seu Agent na VPS.\n")

        val scanner = Scanner(System.`in`)

        print("-> Nome do Host/Agent [$defaultName]: ")
        val inputName = if (scanner.hasNextLine()) scanner.nextLine().trim() else ""
        val chosenName = if (inputName.isNotEmpty()) inputName else defaultName

        print("-> Porta da API e WebSocket [$defaultPort]: ")
        val inputPort = if (scanner.hasNextLine()) scanner.nextLine().trim() else ""
        val chosenPort = inputPort.toIntOrNull() ?: defaultPort

        println("\nConfigurações definidas:")
        println(" - Nome: $chosenName")
        println(" - Porta: $chosenPort")
        println(" - Vinculação ativa: Sim")
        println("Salvando em '${configFile.absolutePath}'...\n")

        return AgentConfig(
            name = chosenName,
            port = chosenPort,
            pairingEnabled = true
        )
    }
}
