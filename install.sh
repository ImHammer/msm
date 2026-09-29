#!/usr/bin/env bash
# ==============================================================================
#  MSM Agent - Script de Instalação Rápida e Automatizada (One-Liner)
#  Compatível com: Ubuntu, Debian, CentOS, AlmaLinux, Rocky Linux, Fedora, Arch
# ==============================================================================

set -e

# Cores para saída formatada
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # Sem cor

# Configurações padrão
INSTALL_DIR="/opt/msm"
BIN_LINK="/usr/local/bin/msm"
SERVICE_FILE="/etc/systemd/system/msm.service"
SOCKET_DIR="/run/msm"
TMPFILES_CONF="/etc/tmpfiles.d/msm.conf"
GITHUB_REPO="imhammer/msm"
DEFAULT_PORT="9000"
IS_UPDATE=false
WAS_RUNNING=false

check_existing_installation() {
    if [ -d "$INSTALL_DIR" ] && [ -f "$INSTALL_DIR/msm-config.json" ]; then
        IS_UPDATE=true
    fi
}

print_banner() {
    clear 2>/dev/null || true
    echo -e "${CYAN}${BOLD}"
    echo "  ███╗   ███╗███████╗███╗   ███╗    █████╗  ██████╗ ███████╗███╗   ██╗████████╗"
    echo "  ████╗ ████║██╔════╝████╗ ████║   ██╔══██╗██╔════╝ ██╔════╝████╗  ██║╚══██╔══╝"
    echo "  ██╔████╔██║███████╗██╔████╔██║   ███████║██║  ███╗█████╗  ██╔██╗ ██║   ██║   "
    echo "  ██║╚██╔╝██║╚════██║██║╚██╔╝██║   ██╔══██║██║   ██║██╔══╝  ██║╚██╗██║   ██║   "
    echo "  ██║ ╚═╝ ██║███████║██║ ╚═╝ ██║   ██║  ██║╚██████╔╝███████╗██║ ╚████║   ██║   "
    echo "  ╚═╝     ╚═╝╚══════╝╚═╝     ╚═╝   ╚═╝  ╚═╝ ╚═════╝ ╚══════╝╚═╝  ╚═══╝   ╚═╝   "
    echo -e "${NC}"
    if [ "$IS_UPDATE" = true ]; then
        echo -e "${BOLD}Assistente de Atualização Automática do MSM Monitoring Agent${NC}"
        echo -e "Configurações existentes serão preservadas em: $INSTALL_DIR/msm-config.json"
    else
        echo -e "${BOLD}Assistente Universal de Instalação do MSM Monitoring Agent${NC}"
    fi
    echo -e "======================================================================\n"
}

log_info() {
    echo -e "${CYAN}[INFO]${NC} $1"
}

log_ok() {
    echo -e "${GREEN}[OK]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[AVISO]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERRO]${NC} $1"
}

# 1. Verifica se está sendo executado como root
check_root() {
    if [ "$EUID" -ne 0 ]; then
        log_error "Este instalador requer privilégios de administrador (root)."
        echo -e "Por favor, execute novamente utilizando sudo:\n"
        echo -e "  ${BOLD}sudo bash $0${NC}\n"
        exit 1
    fi
}

# 2. Detecta o gerenciador de pacotes da distribuição Linux
detect_package_manager() {
    if command -v apt-get >/dev/null 2>&1; then
        PKG_MANAGER="apt"
    elif command -v dnf >/dev/null 2>&1; then
        PKG_MANAGER="dnf"
    elif command -v yum >/dev/null 2>&1; then
        PKG_MANAGER="yum"
    elif command -v pacman >/dev/null 2>&1; then
        PKG_MANAGER="pacman"
    elif command -v apk >/dev/null 2>&1; then
        PKG_MANAGER="apk"
    else
        PKG_MANAGER="unknown"
    fi
}

# 3. Garante ferramentas utilitárias básicas (curl, unzip, tar)
install_base_tools() {
    log_info "Verificando ferramentas básicas (curl, unzip, tar)..."
    local to_install=""

    command -v curl >/dev/null 2>&1 || to_install="$to_install curl"
    command -v unzip >/dev/null 2>&1 || to_install="$to_install unzip"
    command -v tar >/dev/null 2>&1 || to_install="$to_install tar"

    if [ -n "$to_install" ]; then
        log_info "Instalando dependências ausentes:$to_install..."
        case "$PKG_MANAGER" in
            apt)
                apt-get update -qq
                apt-get install -y -qq $to_install >/dev/null
                ;;
            dnf)
                dnf install -y -q $to_install >/dev/null
                ;;
            yum)
                yum install -y -q $to_install >/dev/null
                ;;
            pacman)
                pacman -Sy --noconfirm $to_install >/dev/null
                ;;
            apk)
                apk add --no-cache $to_install >/dev/null
                ;;
            *)
                log_warn "Gerenciador de pacotes desconhecido. Certifique-se de ter 'curl' e 'unzip' instalados."
                ;;
        esac
    fi
    log_ok "Ferramentas básicas prontas."
}

# 4. Verifica e instala o Java 21+ automaticamente caso necessário
ensure_java_21() {
    log_info "Verificando ambiente Java..."
    local need_install=true

    if command -v java >/dev/null 2>&1; then
        local java_version
        java_version=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
        if [ -n "$java_version" ] && [ "$java_version" -ge 21 ] 2>/dev/null; then
            log_ok "Java $java_version já instalado no sistema."
            need_install=false
        else
            log_warn "Versão atual do Java ($java_version) é inferior a 21. Instalando Java 21 JRE..."
        fi
    else
        log_info "Java não detectado no sistema. Instalando Java 21 JRE..."
    fi

    if [ "$need_install" = true ]; then
        case "$PKG_MANAGER" in
            apt)
                apt-get update -qq
                if apt-cache show openjdk-21-jre-headless >/dev/null 2>&1; then
                    apt-get install -y -qq openjdk-21-jre-headless >/dev/null
                else
                    log_info "Instalando OpenJDK 21 via Eclipse Temurin..."
                    apt-get install -y -qq wget apt-transport-https gnupg >/dev/null
                    wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /etc/apt/trusted.gpg.d/adoptium.gpg
                    echo "deb https://packages.adoptium.net/artifactory/deb $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | tee /etc/apt/sources.list.d/adoptium.list
                    apt-get update -qq
                    apt-get install -y -qq temurin-21-jre >/dev/null
                fi
                ;;
            dnf)
                dnf install -y -q java-21-openjdk-headless >/dev/null
                ;;
            yum)
                yum install -y -q java-21-openjdk-headless >/dev/null
                ;;
            pacman)
                pacman -Sy --noconfirm jre21-openjdk-headless >/dev/null
                ;;
            apk)
                apk add --no-cache openjdk21-jre-headless >/dev/null
                ;;
            *)
                log_error "Não foi possível instalar o Java 21 automaticamente nesta distribuição."
                echo "Por favor, instale o Java 21 manualmente e execute este instalador novamente."
                exit 1
                ;;
        esac

        if command -v java >/dev/null 2>&1; then
            log_ok "Java 21 instalado com sucesso!"
        else
            log_error "Falha ao verificar a instalação do Java 21."
            exit 1
        fi
    fi
}

# 5. Obtém o pacote ZIP do MSM Agent (localmente ou via download)
get_msm_package() {
    TMP_DIR=$(mktemp -d)
    ZIP_TARGET="$TMP_DIR/msm.zip"

    # Prioridade 1: Verifica se o script está rodando na pasta onde já há o arquivo zip compilado
    local local_zip=""
    if [ -f "build/distributions/msm-1.0.0-SNAPSHOT.zip" ]; then
        local_zip="build/distributions/msm-1.0.0-SNAPSHOT.zip"
    elif [ -f "../build/distributions/msm-1.0.0-SNAPSHOT.zip" ]; then
        local_zip="../build/distributions/msm-1.0.0-SNAPSHOT.zip"
    elif ls msm-*.zip 1> /dev/null 2>&1; then
        local_zip=$(ls msm-*.zip | head -n 1)
    fi

    if [ -n "$local_zip" ] && [ -f "$local_zip" ]; then
        log_info "Utilizando pacote local encontrado: $local_zip"
        cp "$local_zip" "$ZIP_TARGET"
        return 0
    fi

    # Prioridade 2: URL informada via variável de ambiente MSM_DOWNLOAD_URL
    if [ -n "$MSM_DOWNLOAD_URL" ]; then
        log_info "Baixando MSM Agent de: $MSM_DOWNLOAD_URL..."
        curl -fsSL "$MSM_DOWNLOAD_URL" -o "$ZIP_TARGET"
        return 0
    fi

    # Prioridade 3: Download da última release do GitHub
    log_info "Consultando a versão mais recente no repositório GitHub ($GITHUB_REPO)..."
    local download_url=""
    download_url=$(curl -fsSL "https://api.github.com/repos/$GITHUB_REPO/releases/latest" 2>/dev/null | grep -o 'https://[^" ]*msm-[^" ]*\.zip' | head -n 1 || true)

    if [ -z "$download_url" ]; then
        download_url="https://github.com/$GITHUB_REPO/releases/latest/download/msm-1.0.0-SNAPSHOT.zip"
    fi

    log_info "Baixando pacote do Agent ($download_url)..."
    if ! curl -fsSL "$download_url" -o "$ZIP_TARGET"; then
        log_error "Não foi possível baixar o pacote do MSM Agent automaticamente."
        echo -e "\nDica: Você pode gerar o pacote na sua máquina com './gradlew distZip' e transferi-lo para esta VPS,"
        echo -e "ou definir a URL direta com: 'MSM_DOWNLOAD_URL=https://... sudo bash install.sh'"
        exit 1
    fi

    log_ok "Download concluído com sucesso."
}

# 6. Instala e extrai os arquivos em /opt/msm
install_msm_files() {
    log_info "Preparando diretório de instalação em $INSTALL_DIR..."
    mkdir -p "$INSTALL_DIR"

    # Se o serviço estiver rodando, para antes de substituir os arquivos binários e jars
    if command -v systemctl >/dev/null 2>&1 && systemctl is-active --quiet msm 2>/dev/null; then
        log_info "Serviço 'msm' em execução detectado. Parando temporariamente para atualização segura..."
        systemctl stop msm 2>/dev/null || true
        WAS_RUNNING=true
    fi

    # Preserva configuração e logs caso já existissem de uma instalação anterior
    local backup_config=""
    if [ -f "$INSTALL_DIR/msm-config.json" ]; then
        backup_config=$(cat "$INSTALL_DIR/msm-config.json")
    fi

    log_info "Extraindo arquivos do MSM Agent..."
    unzip -q -o "$ZIP_TARGET" -d "$TMP_DIR/extracted"

    # Localiza a pasta descompactada
    local extracted_dir
    extracted_dir=$(find "$TMP_DIR/extracted" -mindepth 1 -maxdepth 1 -type d | head -n 1)

    if [ -z "$extracted_dir" ] || [ ! -d "$extracted_dir/bin" ]; then
        log_error "Estrutura do pacote ZIP inválida."
        exit 1
    fi

    # Se for atualização, remove libs e bin antigos para garantir que nenhuma versão legada permaneça
    if [ -d "$INSTALL_DIR/lib" ]; then
        rm -rf "$INSTALL_DIR/lib"
    fi
    if [ -d "$INSTALL_DIR/bin" ]; then
        rm -rf "$INSTALL_DIR/bin"
    fi

    # Copia os novos binários e bibliotecas
    cp -r "$extracted_dir"/* "$INSTALL_DIR/"
    chmod +x "$INSTALL_DIR/bin/msm"

    # Salva uma cópia de install.sh em /opt/msm/install.sh para atualizações futuras
    if [ -f "$0" ]; then
        cp "$0" "$INSTALL_DIR/install.sh" 2>/dev/null || true
        chmod +x "$INSTALL_DIR/install.sh" 2>/dev/null || true
    fi

    # Restaura configuração se existia
    if [ -n "$backup_config" ]; then
        echo "$backup_config" > "$INSTALL_DIR/msm-config.json"
        log_ok "Configurações anteriores (msm-config.json) preservadas com sucesso."
    else
        # Cria a configuração padrão inicial caso não exista
        local host_name
        host_name=$(hostname -s 2>/dev/null || echo "vps-msm")
        local host_port="${MSM_PORT:-$DEFAULT_PORT}"
        local generated_token="msm_sdk_$(head /dev/urandom 2>/dev/null | tr -dc a-f0-9 2>/dev/null | head -c 32 || openssl rand -hex 16 2>/dev/null || echo "788bb1c346214aceb5a1d42345462e1b")"

        cat <<EOF > "$INSTALL_DIR/msm-config.json"
{
  "name": "$host_name",
  "port": $host_port,
  "pairingEnabled": true,
  "sdkToken": "$generated_token",
  "devices": [],
  "monitors": []
}
EOF
        log_ok "Arquivo de configuração inicial criado (Host: $host_name, Porta: $host_port)."
    fi

    # Cria o link simbólico global em /usr/local/bin/msm
    ln -sf "$INSTALL_DIR/bin/msm" "$BIN_LINK"
    log_ok "Atalho global atualizado em $BIN_LINK."

    # Prepara a pasta do Unix Domain Socket com permissões completas
    mkdir -p "$SOCKET_DIR"
    chmod 777 "$SOCKET_DIR"
    mkdir -p /etc/tmpfiles.d
    echo "d $SOCKET_DIR 0777 root root -" > "$TMPFILES_CONF"

    # Limpa arquivos temporários
    rm -rf "$TMP_DIR"
    log_ok "Arquivos atualizados com sucesso."
}

# 7. Configura e inicializa o serviço systemd
setup_systemd_service() {
    log_info "Configurando serviço no systemd..."

    cat <<EOF > "$SERVICE_FILE"
[Unit]
Description=MSM Monitoring Agent Host
After=network.target

[Service]
Type=simple
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/bin/msm --non-interactive
Restart=always
RestartSec=5
User=root
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF

    systemctl daemon-reload
    systemctl enable msm >/dev/null 2>&1 || true
    systemctl restart msm

    sleep 2

    if systemctl is-active --quiet msm; then
        if [ "$IS_UPDATE" = true ]; then
            log_ok "Serviço 'msm.service' reiniciado com a nova versão e ativo no sistema!"
        else
            log_ok "Serviço 'msm.service' instalado e ativo em segundo plano!"
        fi
    else
        log_warn "O serviço foi configurado, mas verifique o status com 'systemctl status msm'."
    fi
}

# 8. Exibe resumo final com instruções para o usuário
show_summary() {
    local host_name host_port sdk_token
    host_name=$(grep -o '"name": *"[^"]*"' "$INSTALL_DIR/msm-config.json" | cut -d'"' -f4 || echo "vps")
    host_port=$(grep -o '"port": *[0-9]*' "$INSTALL_DIR/msm-config.json" | tr -dc '0-9' || echo "9000")
    sdk_token=$(grep -o '"sdkToken": *"[^"]*"' "$INSTALL_DIR/msm-config.json" | cut -d'"' -f4 || echo "msm_sdk_...")

    if [ "$IS_UPDATE" = true ]; then
        echo -e "\n${GREEN}${BOLD}======================================================================${NC}"
        echo -e "${GREEN}${BOLD}     🎉 MSM AGENT ATUALIZADO COM SUCESSO!                             ${NC}"
        echo -e "${GREEN}${BOLD}======================================================================${NC}"
        echo -e "  • ${BOLD}Status do Serviço:${NC}  ${GREEN}Ativo e Reiniciado (systemd)${NC}"
        echo -e "  • ${BOLD}Host Identificador:${NC} $host_name"
        echo -e "  • ${BOLD}Porta HTTP & WS:${NC}    ${CYAN}$host_port${NC}"
        echo -e "  • ${BOLD}Token do SDK:${NC}       ${YELLOW}$sdk_token${NC}"
        echo -e "  • ${BOLD}Diretório Base:${NC}     $INSTALL_DIR"
        echo -e "  • ${BOLD}Configurações:${NC}      ${GREEN}Preservadas intactas${NC}"
        echo -e "======================================================================\n"
        echo -e "${BOLD}📌 Dicas e Comandos Úteis:${NC}"
        echo -e "  👉 Ver informações do sistema:     ${CYAN}msm info${NC}"
        echo -e "  👉 Ver ou editar configurações:    ${CYAN}msm config${NC}"
        echo -e "  👉 Abrir o console interativo:     ${CYAN}msm${NC}"
        echo -e "  👉 Ver status do serviço:          ${CYAN}sudo msm service status${NC}"
        echo -e "  👉 Atualizar no futuro:            ${CYAN}sudo msm update${NC}\n"
        return 0
    fi

    echo -e "\n${GREEN}${BOLD}======================================================================${NC}"
    echo -e "${GREEN}${BOLD}     🎉 MSM AGENT INSTALADO E CONFIGURADO COM SUCESSO!               ${NC}"
    echo -e "${GREEN}${BOLD}======================================================================${NC}"
    echo -e "  • ${BOLD}Status do Serviço:${NC}  ${GREEN}Ativo (systemd rodando)${NC}"
    echo -e "  • ${BOLD}Host Identificador:${NC} $host_name"
    echo -e "  • ${BOLD}Porta HTTP & WS:${NC}    ${CYAN}$host_port${NC}"
    echo -e "  • ${BOLD}Socket Unix (SDK):${NC}  $SOCKET_DIR/events.sock"
    echo -e "  • ${BOLD}Token do SDK:${NC}       ${YELLOW}$sdk_token${NC}"
    echo -e "  • ${BOLD}Diretório Base:${NC}     $INSTALL_DIR"
    echo -e "======================================================================\n"

    echo -e "${BOLD}📌 Comandos Rápidos:${NC}"
    echo -e "  👉 Ver ou editar configurações:    ${CYAN}msm config${NC} (ou ${CYAN}msm config edit${NC})"
    echo -e "  👉 Mudar porta e reiniciar:        ${CYAN}msm config set --port 9500 --restart${NC}"
    echo -e "  👉 Abrir o console interativo:     ${CYAN}msm${NC}"
    echo -e "  👉 Ver status do serviço:          ${CYAN}sudo msm service status${NC}"
    echo -e "  👉 Reiniciar serviço do MSM:       ${CYAN}sudo msm service restart${NC}"
    echo -e "  👉 Atualizar o Agent:              ${CYAN}sudo msm update${NC}"
    echo -e "  👉 Ver todos os caminhos:          ${CYAN}msm info${NC}\n"

    echo -e "${BOLD}📱 Para conectar o Aplicativo Android:${NC}"
    echo -e "  1. Abra o app MSM no seu celular e toque em ${CYAN}Vincular VPS${NC}."
    echo -e "  2. Digite: ${BOLD}http://IP_DA_SUA_VPS:$host_port${NC} e toque em Solicitar Acesso."
    echo -e "  3. Aqui na VPS, rode: ${BOLD}msm pairing accept <código>${NC}\n"
}

# Fluxo principal de execução
main() {
    check_root
    check_existing_installation
    print_banner
    detect_package_manager
    install_base_tools
    ensure_java_21
    get_msm_package
    install_msm_files
    setup_systemd_service
    show_summary
}

main "$@"
