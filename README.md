# 🛰️ MSM Agent (Micro Server Monitor)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-purple.svg)](https://kotlinlang.org/)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.org/)
[![Node.js SDK](https://img.shields.io/badge/npm-@imhammer/msm--node-CB3837?logo=npm)](https://www.npmjs.com/package/@imhammer/msm-node)

O **MSM Agent** é um daemon de monitoramento e telemetria para servidores Linux e VPS, projetado para transmitir métricas de infraestrutura, logs em tempo real e dados de negócios diretamente para o **aplicativo mobile (Android)** com altíssima performance e zero complicação.

---

## ✨ Recursos Principais

- ⚡ **Ingestão Ultrarrápida via Unix Domain Socket**: Suas aplicações (Node.js, Python, etc.) comunicam métricas localmente via IPC com latência submilisegundo e autenticação por token.
- 🔭 **Monitoramento de Host Extensível (PM2)**: Inspeciona e vigia processos do PM2 automaticamente com controle fino de setores (`status`, `cpu`, `memory`, `restarts`).
- 📱 **Pareamento Mobile Seguro**: Vinculação de dispositivos via código OTP de 6 dígitos temporário e controle total de permissões pela CLI.
- 📡 **Streaming em Tempo Real via WebSocket**: Métricas, eventos de negócio e logs chegam instantaneamente no app mobile.
- 💻 **CLI Inteligente com Console Attach**: Acesso interativo aos comandos (`msm status`, `msm pairing`, `msm monitor`) sem risco de conflito de portas com o serviço em segundo plano.
- 🔄 **Auto-atualizador Embutido**: Atualize o Agent na VPS para a versão mais recente com um único comando: `sudo msm update`.

---

## 📦 SDKs Oficiais Disponíveis

Integre suas aplicações diretamente ao MSM Agent usando os SDKs oficiais:

| Linguagem / Ecossistema | Pacote | Repositório | Status |
| :--- | :--- | :--- | :--- |
| **Node.js / TypeScript** | [`@imhammer/msm-node`](https://www.npmjs.com/package/@imhammer/msm-node) | [github.com/imhammer/msm-node](https://github.com/imhammer/msm-node) | 🟢 Estável (v1.0.0) |
| **Python** | `msm-python` | *Em desenvolvimento* | 🟡 Em breve |
| **Go** | `msm-go` | *Em desenvolvimento* | 🟡 Em breve |

### Instalação rápida do SDK Node.js:
```bash
npm install @imhammer/msm-node
```
Consulte o [README do SDK Node.js](https://github.com/imhammer/msm-node) para ver exemplos completos com Express, Fastify e NestJS.

---

## 🚀 Instalação Rápida na VPS

Instale e configure o MSM Agent automaticamente como serviço permanente (`systemd`) executando:

```bash
curl -fsSL https://raw.githubusercontent.com/imhammer/msm/main/install.sh | bash
```

O instalador cuida de:
1. Detectar dependências (Java 21).
2. Instalar o Agent em `/opt/msm`.
3. Criar o serviço de inicialização automática no `systemd` (`msm.service`).
4. Criar o link simbólico global `/usr/local/bin/msm`.

---

## 🛠️ Comandos Mais Usados na CLI

Depois de instalado, você pode gerenciar tudo direto pelo comando `msm`:

```bash
# 1. Obter o token de autenticação para configurar nos seus projetos Node/SDK:
msm token

# 2. Monitorar processos do PM2 escolhendo os setores enviados ao celular:
msm monitor add pm2 all -s status,cpu

# 3. Listar solicitações pendentes de conexão do aplicativo mobile:
msm pairing requests

# 4. Aprovar a conexão do celular digitando o código exibido na tela do app:
msm pairing accept 123456

# 5. Ver resumo de saúde do servidor (CPU, RAM, instâncias):
msm status

# 6. Abrir o console interativo conectado ao daemon:
msm

# 7. Atualizar o MSM Agent para a versão mais recente no futuro:
sudo msm update
```

Para ver o manual completo de comandos coloridos, execute `msm help` ou consulte a [Referência Completa de Comandos](docs/COMMANDS.md).

---

## 🏛️ Arquitetura do Sistema

```
                    ┌────────────────────────┐
                    │  App Mobile (Android)  │
                    └───────────▲────────────┘
                                │ WebSocket (Porta 9000)
                                │ (Criptografado / Token OTP)
                                ▼
                    ┌────────────────────────┐
                    │       MSM AGENT        │
                    │   (/opt/msm via systemd│
                    └──────▲──────────▲──────┘
                           │          │
   Unix Domain Socket IPC  │          │ Polling do Host
  (/run/msm/events.sock)   │          │ (pm2 jlist)
                           ▼          ▼
             ┌─────────────────┐  ┌─────────────┐
             │  Suas Apps Node │  │  PM2 / Host │
             │ (@imhammer/     │  │  Processos  │
             │   msm-node)     │  └─────────────┘
             └─────────────────┘
```

---

## 📚 Documentação Técnica

- 📖 [Referência Completa de Comandos da CLI (docs/COMMANDS.md)](docs/COMMANDS.md)
- ⚙️ [Guia de Instalação e Atualização na VPS (docs/INSTALLATION.md)](docs/INSTALLATION.md)
- 📱 [Documentação da API REST & WebSocket para o App (docs/APP_API.md)](docs/APP_API.md)
- 🔌 [Especificação do Protocolo IPC para Criação de SDKs (docs/SDK_SPEC.md)](docs/SDK_SPEC.md)

---

## 📜 Licença

Distribuído sob a licença [MIT](LICENSE).
