# 🛰️ Guia Sequencial de Lançamento de Releases do MSM Agent

Este guia detalha o processo completo para lançar novas versões e atualizações do **MSM Agent** no GitHub e distribuí-las para servidores Linux (VPS), garantindo compatibilidade total com o comando de atualização automática `sudo msm update` e a distribuição do aplicativo mobile Android via GitHub Releases.

---

## 🏗️ Como Funciona o Sistema de Atualização do MSM Agent

1. **Repositório GitHub**: `https://github.com/imhammer/msm`
2. **Empacotamento**: O comando Gradle `./gradlew distZip` compila o código Kotlin e gera um arquivo compactado contendo o daemon e todas as bibliotecas necessárias:
   `build/distributions/msm-<versao>.zip`
3. **Distribuição via GitHub Releases**: Ao criar uma release manual no GitHub (ex: tag `v1.0.1`), você anexa esse arquivo `.zip` como um **Asset** da release.
4. **Atualização na VPS (`sudo msm update`)**:
   - O comando consulta a API do GitHub (`/repos/imhammer/msm/releases/latest`).
   - Baixa o arquivo com padrão `msm-*.zip`.
   - Para temporariamente o serviço `systemd` (`systemctl stop msm`).
   - Substitui os binários em `/opt/msm`.
   - **Preserva intactas** as credenciais e configurações existentes (`/opt/msm/msm-config.json` e logs).
   - Reinicia o serviço no `systemd` automaticamente.

---

## 📋 Passo a Passo Sequencial de Release

Siga estas etapas sempre que for lançar uma nova versão do Agent:

### 1. Atualizar a Versão no `build.gradle.kts`
Abra o arquivo [`build.gradle.kts`](../build.gradle.kts) e atualize o campo `version` para a versão final da release (recomenda-se remover o sufixo `-SNAPSHOT` para versões estáveis):

```kotlin
// Exemplo: alterando de 1.0.0-SNAPSHOT para 1.0.1
version = "1.0.1"
```

---

### 2. Executar a Suíte de Testes
Certifique-se de que todos os testes automatizados passam sem falhas:

```bash
# No Windows (PowerShell):
.\gradlew.bat test

# No Linux / macOS:
./gradlew test
```

*(Todos os 22 testes unitários devem passar com status `BUILD SUCCESSFUL`).*

---

### 3. Compilar e Gerar o Pacote ZIP de Distribuição
Gere o pacote executável compactado executando a task `distZip`:

```bash
# No Windows (PowerShell):
.\gradlew.bat distZip

# No Linux / macOS:
./gradlew distZip
```

O arquivo de instalação compactado será gerado em:
```
build/distributions/msm-1.0.1.zip
```
*(Confira se o arquivo existe e tem cerca de ~15MB a ~25MB).*

---

### 4. Commitar e Criar a Tag Git
No terminal, adicione as alterações, faça o commit de release e crie a tag anotada:

```bash
# 1. Commitar a alteração de versão no build.gradle.kts
git add build.gradle.kts
git commit -m "chore(release): v1.0.1"

# 2. Criar a tag correspondente (sempre no formato vX.Y.Z)
git tag -a v1.0.1 -m "Release v1.0.1"

# 3. Enviar os commits e as tags para o repositório remoto
git push origin main --tags
```

---

### 5. Criar a Release no GitHub (Manual)

1. Acesse o repositório no GitHub:
   👉 **`https://github.com/imhammer/msm/releases/new`**
2. **Choose a tag**: Selecione a tag que você acabou de enviar (ex: **`v1.0.1`**).
3. **Release title**: Digite o título da release (ex: `MSM Agent v1.0.1`).
4. **Description**: Descreva as principais novidades, correções ou melhorias desta versão.
5. **Attach binaries by dropping them here (IMPORTANTE / CRÍTICO)**:
   - Arraste e solte o arquivo ZIP gerado no passo 3:
     📂 **`build/distributions/msm-1.0.1.zip`**
   - 📱 **App Android (APK)**:
     Como o aplicativo Android também é distribuído diretamente via GitHub (evitando burocracias da Google Play Store para ferramentas focadas em DevOps/Self-hosted), você pode compilar o APK no Android Studio (`app-release.apk`) e arrastá-lo para cá também (ex: renomeado para `msm-android-v1.0.1.apk`).
6. Clique no botão verde **"Publish release"**.

---

### 6. Atualizar e Validar na VPS

Com a release publicada no GitHub com o `.zip` anexado, a atualização em qualquer VPS já existente é imediata:

1. Conecte-se na sua VPS via SSH:
   ```bash
   ssh seu-usuario@sua-vps
   ```

2. Execute o comando de atualização automática:
   ```bash
   sudo msm update
   ```

3. O que você verá no terminal:
   ```
   ======================================================
            MSM AGENT - ATUALIZAÇÃO AUTOMÁTICA           
   ======================================================
   Iniciando verificação e download da versão mais recente...
   [INFO] Consultando a versão mais recente no repositório GitHub (imhammer/msm)...
   [INFO] Baixando pacote do Agent (https://github.com/imhammer/msm/releases/download/v1.0.1/msm-1.0.1.zip)...
   [OK] Download concluído com sucesso.
   [INFO] Serviço 'msm' em execução detectado. Parando temporariamente...
   [INFO] Extraindo arquivos do MSM Agent...
   [OK] Atualização concluída com sucesso!
   [INFO] Reiniciando serviço 'msm' no systemd...
   [OK] Serviço msm reiniciado com sucesso!
   
   ✓ MSM Agent atualizado com sucesso!
   ```

4. Verifique o status e a versão do daemon:
   ```bash
   msm status
   ```

---

### 7. Preparar o Próximo Ciclo de Desenvolvimento (Opcional)
Para manter o fluxo de boas práticas, você pode incrementar o `build.gradle.kts` para a próxima versão de desenvolvimento:

```kotlin
version = "1.0.2-SNAPSHOT"
```

E commitar no branch `main`:
```bash
git commit -am "chore: bump version to 1.0.2-SNAPSHOT"
git push origin main
```

---

## ⚡ Cheat Sheet: Comandos Rápidos da Release do Agent

Copie e cole este bloco no seu terminal de desenvolvimento local quando for lançar uma nova versão:

```powershell
# 1. Definir versão e testar
.\gradlew.bat test

# 2. Gerar o pacote ZIP
.\gradlew.bat distZip

# 3. Commitar e criar tag
git add .
git commit -m "chore(release): v1.0.1"
git tag -a v1.0.1 -m "Release v1.0.1"
git push origin main --tags

# 4. Abrir a página de releases para fazer o upload do ZIP e do APK:
# Arquivo a enviar: build\distributions\msm-1.0.1.zip
Start-Process "https://github.com/imhammer/msm/releases/new"
```

---

## 📱 Distribuição do App Android via GitHub Releases

Distribuir o aplicativo Android pelo GitHub Releases é a abordagem ideal para ferramentas de monitoramento de infraestrutura própria (DevOps), pois:
- **Sem taxas nem aprovações demoradas**: Não precisa pagar taxa de desenvolvedor do Google Play nem aguardar revisões de dias a cada atualização.
- **Transparência total**: Os usuários e você mesmo baixam o `.apk` direto da fonte oficial vinculada ao código aberto.
- **Centralização**: Quem acessa o repositório do `MSM` encontra no mesmo lugar: o Agent da VPS, o link do SDK Node e o download do aplicativo Android.
- **Atualização manual ou in-app**: Você pode no futuro colocar um checador no app mobile que lê `https://api.github.com/repos/imhammer/msm/releases/latest` e avisa quando há um APK novo para baixar!
