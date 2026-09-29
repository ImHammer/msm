# MSM Agent - Guia de Instalação, Deploy e Prova de Teste na VPS

Este guia fornece o passo a passo completo para empacotar o **MSM Agent**, transferi-lo para a sua VPS Linux, realizar a primeira configuração, instalar como serviço de segundo plano (`systemd`), inspecionar os arquivos com o comando `info` e realizar uma prova de teste prática de ponta a ponta.

---

## ⚡ 1. Instalação Automática em 1 Linha (Recomendado)

Se você já tem acesso SSH à sua VPS, o instalador automatizado cuida de tudo: instala o Java 21, baixa o MSM Agent, configura `/opt/msm`, cria o atalho global `msm` e inicializa o serviço no `systemd`:

```bash
curl -fsSL https://raw.githubusercontent.com/imhammer/msm/main/install.sh | sudo bash
```

*(Ou se você clonou o repositório ou transferiu o arquivo na VPS, basta rodar: `sudo bash install.sh`).*

---

## 2. Pré-Requisitos (Para Instalação Manual)

### Na sua máquina local (Desenvolvimento)
- Java JDK 21+ instalado.
- Código do projeto no repositório.

### Na sua VPS (Servidor Linux)
- Sistema Operacional Linux (Ubuntu, Debian, CentOS, AlmaLinux, Rocky ou Fedora).
- Java 21 (JRE ou JDK):
  ```bash
  # No Ubuntu / Debian:
  sudo apt update && sudo apt install openjdk-21-jre-headless -y

  # No CentOS / AlmaLinux / Rocky:
  sudo dnf install java-21-openjdk-headless -y
  ```
- Porta da API liberada no firewall (Padrão: **`9000`** TCP):
  ```bash
  # Se utilizar UFW (Ubuntu/Debian):
  sudo ufw allow 9000/tcp
  ```

---

## 2. Passo a Passo: Gerando o Pacote de Instalação

Na raiz do projeto na sua máquina de desenvolvimento, execute:

```bash
# No Windows (PowerShell):
./gradlew.bat distZip

# No Linux / macOS:
./gradlew distZip
```

Esse comando gera o arquivo zip da distribuição pronta em:
**`build/distributions/msm-1.0.0-SNAPSHOT.zip`** (~20 MB, contendo o script executável `bin/msm` e todas as bibliotecas necessárias).

---

## 3. Transferindo para a sua VPS

Envie o arquivo gerado para a sua VPS usando `scp`:

```bash
scp build/distributions/msm-1.0.0-SNAPSHOT.zip usuario@IP_DA_SUA_VPS:/home/usuario/
```
*(Substitua `usuario` e `IP_DA_SUA_VPS` pelos dados do seu servidor).*

---

## 4. Instalação e Execução na VPS

Acesse a sua VPS via SSH:
```bash
ssh usuario@IP_DA_SUA_VPS
```

### 4.1. Descompactando o pacote
```bash
unzip msm-1.0.0-SNAPSHOT.zip
cd msm-1.0.0-SNAPSHOT
chmod +x bin/msm
```

### 4.2. Primeira Execução e Setup Inicial
Execute o Agent pela primeira vez para realizar a configuração guiada:
```bash
./bin/msm
```

Você verá o assistente:
```
======================================================
   MSM Agent - Assistente de Configuração Inicial     
======================================================
Nenhum arquivo 'msm-config.json' encontrado.
Vamos realizar a configuração básica do seu Agent na VPS.

-> Nome do Host/Agent [vps-producao-01]: 
-> Porta da API e WebSocket [9000]: 
```
1. Pressione `Enter` para aceitar os padrões ou digite o nome e porta desejados.
2. O Agent salvará `msm-config.json` e abrirá o console interativo:
   ```
   msm@vps-producao-01 > 
   ```
3. Digite **`exit`** para sair momentaneamente.

---

## 5. Instalando como Serviço Automático (`systemd`)

Para que o Agent continue rodando mesmo após você fechar o SSH e inicie sozinho se a VPS for reiniciada, use o auto-instalador embutido:

```bash
sudo ./bin/msm service install
```

O próprio executável criará o arquivo `/etc/systemd/system/msm.service`, recarregará o systemd e iniciará o Agent em background!

### Comandos de Gerenciamento do Serviço:
```bash
# Ver status do serviço em execução:
sudo ./bin/msm service status   # (ou sudo systemctl status msm)

# Reiniciar o serviço:
sudo ./bin/msm service restart

# Parar o serviço:
sudo ./bin/msm service stop

# Iniciar o serviço:
sudo ./bin/msm service start

# Desinstalar e remover o serviço do sistema operacional:
sudo ./bin/msm service uninstall
```

---

## 6. Comando `info`: Rastreando Arquivos e Limpeza Total

Para consultar onde estão localizados **todos os arquivos** que o Agent utiliza, basta rodar:

```bash
./bin/msm info
```
*(Esse comando também pode ser executado a qualquer momento dentro do console interativo digitando `info`).*

### Exemplo de Saída:
```
======================================================
         MSM AGENT - INFORMAÇÕES DE ARQUIVOS          
======================================================
Localização dos Arquivos e Recursos do Agent:
  • Configuração:      /home/usuario/msm-1.0.0-SNAPSHOT/msm-config.json [Existe]
  • Arquivo de Log:     /home/usuario/msm-1.0.0-SNAPSHOT/msm.log [Existe (48 KB)]
  • Socket Unix (SDK):  /run/msm/events.sock [Ativo]
  • Serviço systemd:   /etc/systemd/system/msm.service [Instalado]
  • Binário / Jar:     /home/usuario/msm-1.0.0-SNAPSHOT/lib/msm-1.0.0-SNAPSHOT.jar
  • Diretório Base:    /home/usuario/msm-1.0.0-SNAPSHOT

Dados do Ambiente de Execução:
  • Sistema Operacional: Linux (amd64)
  • Java Runtime:        21.0.8 (Ubuntu)
  • PID do Processo:     14820

Instruções para Remoção / Limpeza Completa:
  Caso deseje desinstalar ou limpar todos os dados do Agent no servidor:
    1. Parar e remover o serviço do sistema:
       sudo systemctl disable --now msm && sudo rm -f /etc/systemd/system/msm.service && sudo systemctl daemon-reload
    2. Remover configuração, sockets e histórico de logs:
       rm -f "msm-config.json" "msm.log" "/run/msm/events.sock"
```

---

## 7. Prova de Teste Prática na VPS

Siga este roteiro simples na VPS para testar se tudo está operando 100%:

### Teste 1: Checar se a API está online
No terminal da VPS (ou a partir da sua própria máquina local):
```bash
curl http://localhost:9000/api/status
```
**Resposta esperada:**
```json
{
  "name": "vps-producao-01",
  "status": "online",
  "port": 9000,
  "pairingEnabled": true,
  "version": "1.0.0"
}
```

---

### Teste 2: Consultar Métricas do Sistema
```bash
curl http://localhost:9000/api/system
```
**Resposta esperada:**
```json
{
  "cpu": 5.4,
  "memory": {
    "used": 1258291200,
    "total": 4194304000,
    "percentage": 30.0
  }
}
```

---

### Teste 3: Simular Solicitação de Pareamento de um Celular
Envie um pedido simulando um app mobile:
```bash
curl -X POST http://localhost:9000/api/pairing/request \
  -H "Content-Type: application/json" \
  -d '{"deviceName":"Meu Celular Android"}'
```
**Resposta esperada:**
```json
{
  "requestId": "6a9e1d88-b210-4322-8321-7299a9a01f12",
  "code": "489201",
  "expiresInSeconds": 300,
  "status": "PENDING"
}
```

---

### Teste 4: Aprovar a Vinculação
Abra o console interativo:
```bash
./bin/msm
```
No console, aprove com o código retornado no teste anterior:
```
msm@vps-producao-01 > pairing accept 489201
```
**Saída esperada:**
`✓ Dispositivo 'Meu Celular Android' aprovado com sucesso! Token de acesso gerado.`

Agora, consulte o status com o ID da requisição:
```bash
curl http://localhost:9000/api/pairing/status/6a9e1d88-b210-4322-8321-7299a9a01f12
```
O status será **`APPROVED`** e conterá o token de autenticação gerado!

---

### Teste 5: Testar o Monitoramento de Processos PM2
No console interativo:
```
msm@vps-producao-01 > monitor providers
msm@vps-producao-01 > monitor add pm2 all
msm@vps-producao-01 > instances
```
Você verá os processos do PM2 listados com seus setores (`status`, `cpu`, `memory`, `restarts`).

---

### Teste 6: Verificar Logs da Aplicação
Se quiser auditar as conexões e eventos do Agent:
```bash
tail -f msm.log
```
