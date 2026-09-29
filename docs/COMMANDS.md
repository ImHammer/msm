# 📖 MSM Agent - Referência Completa de Comandos da CLI

Este documento contém o guia definitivo com todos os comandos da **CLI do MSM Agent**, tanto para execução direta no terminal do Linux/VPS (gerenciamento como serviço systemd) quanto comandos internos do console interativo.

---

## 🚀 1. Comandos de Terminal / Sistema Operacional (VPS)

Esses comandos são executados diretamente no terminal da sua máquina ou VPS através do binário `./bin/msm` (ou `msm` se estiver no PATH):

| Comando | Descrição |
| :--- | :--- |
| **`msm`** | Conecta-se instantaneamente ao **console interativo do Agent** em execução na VPS (modo Attach). Se o serviço estiver parado, ajuda a iniciá-lo. |
| **`msm status`** | Consulta e exibe o status atual do Agent e da VPS diretamente no terminal. |
| **`msm instances`** | Lista todas as instâncias e setores monitorados (PM2 + SDK) diretamente pelo terminal. |
| **`msm pairing requests`** | Lista as solicitações pendentes de conexão do celular com seus códigos OTP de 6 dígitos. |
| **`msm pairing accept <código>`** | **Aprova** na hora o pareamento do celular sem precisar entrar no console interativo. |
| **`msm pairing reject <código>`** | Recusa/descarta uma solicitação de pareamento. |
| **`msm monitor list`** | Lista os alvos de monitoramento e os setores ativos em cada um. |
| **`msm monitor add <prov> <alvo> [-s <setores>]`** | Adiciona monitoramento escolhendo os setores enviados ao mobile (ex: `-s status,cpu`). |
| **`msm monitor sectors <prov> [alvo] [setores]`** | Consulta ou altera os setores ativos enviados ao app mobile. |
| **`msm config`** / **`msm config show`** | Exibe as configurações atuais (porta, nome, token, pareamento e caminhos). |
| **`msm config edit`** | Abre o assistente interativo para editar qualquer configuração e opcionalmente reiniciar o serviço. |
| **`msm config set [opções]`** | Altera configurações diretamente via flags (`--name`, `--port`, `--pairing`, `--token`, `--regenerate-token`, `--restart`). |
| **`sudo msm service install`** | **Instala o Agent como serviço permanente no `systemd`** da VPS e inicia imediatamente em segundo plano. |
| **`sudo msm service status`** | Consulta o status em tempo real do serviço systemd (`systemctl status msm`). |
| **`sudo msm service restart`** | Reinicia o serviço do MSM Agent em segundo plano. |
| **`sudo msm service stop`** | Para a execução do serviço systemd. |
| **`sudo msm service start`** | Inicia o serviço systemd se estiver parado. |
| **`sudo msm update`** | **Atualiza o MSM Agent para a versão mais recente**, preserva todas as configurações e reinicia o serviço no systemd. |
| **`msm info`** | Inspeciona e imprime os caminhos absolutos de todos os arquivos utilizados pelo Agent (configuração, logs, sockets Unix, systemd). |
| **`msm --non-interactive`** | Executa o Agent como daemon/processo sem console interativo (usado internamente pelo systemd). |

### 🛠️ Exemplos do Comando `msm config set`
```bash
# Alterar a porta para 9500 e reiniciar o serviço automaticamente:
msm config set --port 9500 --restart

# Alterar o nome da VPS:
msm config set --name servidor-producao

# Desativar o pareamento de novos aparelhos:
msm config set --pairing off

# Gerar um novo token de autenticação para os SDKs e reiniciar o serviço:
msm config set --regenerate-token -r
```

---

## 💻 2. Comandos do Console Interativo (`msm@vps >`)

Quando você inicia o Agent diretamente com `./bin/msm`, ele entra no console interativo. Abaixo estão os comandos que você pode digitar diretamente nele:

### ⚙️ Geral e Diagnóstico
| Comando | Descrição |
| :--- | :--- |
| **`help`** ou **`?`** | Lista todos os comandos disponíveis no console. |
| **`status`** | Exibe o status geral do Agent (porta, modo de pareamento, aparelhos conectados, instâncias e resumo de CPU/RAM). |
| **`config`** | Exibe as configurações atuais do Agent e arquivo carregado. |
| **`config edit`** | Abre o assistente para alterar nome, porta ou pareamento. |
| **`config set [opções]`** | Altera uma ou mais configurações diretamente no console. |
| **`stats`** ou **`system`** | Exibe as métricas de CPU e Memória RAM da máquina em tempo real. |
| **`info`** | Exibe a localização de todos os arquivos em disco (`msm-config.json`, `msm.log`, `/run/msm/events.sock`). |
| **`clear`** ou **`cls`** | Limpa a tela do terminal. |
| **`exit`**, **`quit`** ou **`stop`** | Encerra o Agent e fecha a API. |

---

### 🔑 Autenticação de SDKs
| Comando | Descrição |
| :--- | :--- |
| **`token`** ou **`sdk-token`** | Exibe o token atual necessário para autenticar SDKs (Node.js, etc.) via Socket Unix ou TCP. |
| **`token regenerate`** | Gera um novo token seguro para os SDKs e atualiza o arquivo de configuração. |

---

### 📱 Pareamento com o Celular (App Android)
| Comando | Descrição |
| :--- | :--- |
| **`pairing on`** | Habilita a vinculação de novos dispositivos móveis. |
| **`pairing off`** | Desabilita temporariamente novas solicitações de pareamento. |
| **`pairing requests`** | Lista as solicitações pendentes de conexão com o código numérico de 6 dígitos. |
| **`pairing accept <código|id>`** | **Aprova** o pareamento do celular (ex: `pairing accept 123456`). |
| **`pairing reject <código|id>`** | **Recusa/descarta** uma solicitação de pareamento pendente. |
| **`devices list`** | Lista todos os aparelhos celulares autorizados e vinculados. |
| **`devices revoke <id>`** | **Revoga na hora** o token e o acesso de um dispositivo celular específico. |

---

### 🔭 Monitoramento de Host e Processos (PM2 / Host)
| Comando | Descrição |
| :--- | :--- |
| **`monitor providers`** | Lista os provedores instalados e suportados na máquina e os setores fornecidos por cada um. |
| **`monitor list`** | Lista todos os processos ou recursos monitorados e os setores ativos de cada um. |
| **`monitor add pm2 all`** | Monitora todos os processos do PM2 (em console interativo, oferece escolha amigável de setores). |
| **`monitor add pm2 all -s status,cpu`** | Monitora o PM2 enviando **apenas** os setores `status` e `cpu` para o app mobile. |
| **`monitor sectors pm2`** | Consulta os setores fornecidos pelo PM2 e o que está ativo em cada alvo. |
| **`monitor sectors pm2 all status,cpu`** | Altera dinamicamente os setores enviados ao mobile para um alvo já configurado. |
| **`monitor sectors pm2 all --all`** | Reativa todos os setores fornecidos pelo provedor. |
| **`monitor remove pm2 <alvo>`** | Interrompe o monitoramento de um processo ou alvo. |
| **`monitor diag pm2`** | Executa um diagnóstico detalhado da comunicação com o daemon do PM2. |

---

### 📦 Instâncias e Métricas Registradas
| Comando | Descrição |
| :--- | :--- |
| **`instances`** ou **`insts`** | Lista todas as instâncias ativas (SDK, Host, PM2) com seus respectivos setores, métricas e últimos valores. |

---

## 🛠️ 3. Resumo Prático: Fluxo Rápido na VPS

```bash
# 1. Extrair o arquivo na VPS:
unzip msm-1.0.0-SNAPSHOT.zip
cd msm-1.0.0-SNAPSHOT
chmod +x bin/msm

# 2. Configurar pela primeira vez:
./bin/msm
# (responda o assistente e digite 'exit')

# 3. Instalar como serviço de segundo plano (systemd):
sudo ./bin/msm service install

# 4. Ver se está rodando:
sudo ./bin/msm service status

# 5. Consultar o token para colocar no seu SDK Node.js:
./bin/msm info
```
