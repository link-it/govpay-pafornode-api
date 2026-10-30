#!/bin/bash

##############################################################################
# GovPay PAFORNODE API - Script di Entrypoint
#
# Interfacce SOAP paForNode invocate dal Nodo dei Pagamenti pagoPA.
# Supporta la nomenclatura delle variabili di govpay-docker (GOVPAY_DB_*) e
# le mappa sulle property dell'applicazione.
##############################################################################

set -e

# Nessun tracing (set -x): stamperebbe in chiaro la password del database
# esportata piu' avanti. Lo stderr resta sul container, compreso quello della JVM.

# Funzioni di logging
log_info() { echo -e "\033[0;32m[INFO]\033[0m $(date '+%Y-%m-%d %H:%M:%S') - $1"; }
log_warn() { echo -e "\033[1;33m[WARN]\033[0m $(date '+%Y-%m-%d %H:%M:%S') - $1"; }
log_error() { echo -e "\033[0;31m[ERROR]\033[0m $(date '+%Y-%m-%d %H:%M:%S') - $1"; }

log_info "========================================"
log_info "Avvio Servizio GovPay PAFORNODE API"
log_info "========================================"

##############################################################################
# Database
##############################################################################

GOVPAY_DS_JDBC_LIBS=${GOVPAY_DS_JDBC_LIBS:-/opt/jdbc-drivers}
GOVPAY_PAFORNODE_MIN_POOL=${GOVPAY_PAFORNODE_MIN_POOL:-2}
GOVPAY_PAFORNODE_MAX_POOL=${GOVPAY_PAFORNODE_MAX_POOL:-5}

if [ -n "${GOVPAY_DB_TYPE}" -a -n "${GOVPAY_DB_SERVER}" -a -n "${GOVPAY_DB_USER}" -a -n "${GOVPAY_DB_NAME}" ]
then
    [ -n "${GOVPAY_DB_PASSWORD}" ] || log_warn "La variabile GOVPAY_DB_PASSWORD non è stata impostata."
    log_info "Sanity check variabili obbligatorie ... ok."

    # Estrazione host e porta
    IFS=':' read -r DB_HOST DB_PORT <<< "${GOVPAY_DB_SERVER}"
    [ -z "${DB_PORT}" ] && case "${GOVPAY_DB_TYPE}" in
        postgresql) DB_PORT=5432 ;;
        mysql|mariadb) DB_PORT=3306 ;;
        oracle) DB_PORT=1521 ;;
        sqlserver) DB_PORT=1433 ;;
    esac

    # Costruzione URL JDBC
    case "${GOVPAY_DB_TYPE}" in
        postgresql)
            DATASOURCE_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${GOVPAY_DB_NAME}"
            DATASOURCE_DRIVER="org.postgresql.Driver"
            JPA_DIALECT="org.hibernate.dialect.PostgreSQLDialect"
            ;;
        mysql|mariadb)
            DATASOURCE_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${GOVPAY_DB_NAME}"
            DATASOURCE_DRIVER="com.mysql.cj.jdbc.Driver"
            JPA_DIALECT="org.hibernate.dialect.MySQLDialect"
            ;;
        oracle)
            if [ "${GOVPAY_ORACLE_JDBC_URL_TYPE:-servicename}" == "servicename" ]; then
                DATASOURCE_URL="jdbc:oracle:thin:@//${DB_HOST}:${DB_PORT}/${GOVPAY_DB_NAME}"
            else
                DATASOURCE_URL="jdbc:oracle:thin:@${DB_HOST}:${DB_PORT}:${GOVPAY_DB_NAME}"
            fi
            DATASOURCE_DRIVER="oracle.jdbc.OracleDriver"
            JPA_DIALECT="org.hibernate.dialect.OracleDialect"
            [ -n "${ORACLE_TNS_ADMIN}" ] && JAVA_OPTS="${JAVA_OPTS} -Doracle.net.tns_admin=${ORACLE_TNS_ADMIN}"
            ;;
        sqlserver)
            DATASOURCE_URL="jdbc:sqlserver://${DB_HOST}:${DB_PORT};databaseName=${GOVPAY_DB_NAME}"
            DATASOURCE_DRIVER="com.microsoft.sqlserver.jdbc.SQLServerDriver"
            JPA_DIALECT="org.hibernate.dialect.SQLServerDialect"
            ;;
        *)
            log_error "GOVPAY_DB_TYPE non supportato: ${GOVPAY_DB_TYPE}"
            exit 1
            ;;
    esac

    # Aggiunta parametri di connessione (separatore ';' per SQL Server)
    if [ -n "${GOVPAY_DS_CONN_PARAM}" ]; then
        if [ "${GOVPAY_DB_TYPE}" == "sqlserver" ]; then
            DATASOURCE_URL="${DATASOURCE_URL};${GOVPAY_DS_CONN_PARAM}"
        else
            DATASOURCE_URL="${DATASOURCE_URL}?${GOVPAY_DS_CONN_PARAM}"
        fi
    fi

    # Mappatura sulle variabili lette da application.properties
    export DATASOURCE_URL DATASOURCE_DRIVER JPA_DIALECT
    export DATASOURCE_USERNAME="${GOVPAY_DB_USER}"
    export DATASOURCE_PASSWORD="${GOVPAY_DB_PASSWORD}"
elif [ -n "${DATASOURCE_URL}" ]
then
    # Configurazione diretta con le variabili dell'applicazione (DATASOURCE_*, JPA_DIALECT)
    log_info "Database configurato con DATASOURCE_URL."
else
    log_error "Sanity check variabili obbligatorie ... fallito."
    log_error "Devono essere impostate almeno le seguenti variabili:
GOVPAY_DB_TYPE: ${GOVPAY_DB_TYPE}
GOVPAY_DB_SERVER: ${GOVPAY_DB_SERVER}
GOVPAY_DB_NAME: ${GOVPAY_DB_NAME}
GOVPAY_DB_USER: ${GOVPAY_DB_USER}
(in alternativa DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD)
"
    exit 1
fi

export SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE="${GOVPAY_PAFORNODE_MIN_POOL}"
export SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE="${GOVPAY_PAFORNODE_MAX_POOL}"

##############################################################################
# Configurazione porta server
##############################################################################

if [ -z "${SERVER_PORT}" ]; then
    SERVER_PORT="10004"
    export SERVER_PORT
fi

##############################################################################
# Configurazione Memoria JVM (Percentuale RAM)
##############################################################################

JAVA_OPTS="${JAVA_OPTS:-}"
DEFAULT_MAX_RAM_PERCENTAGE=80

JVM_MEMORY_OPTS="-XX:MaxRAMPercentage=${GOVPAY_PAFORNODE_JVM_MAX_RAM_PERCENTAGE:-${DEFAULT_MAX_RAM_PERCENTAGE}}"
[ -n "${GOVPAY_PAFORNODE_JVM_INITIAL_RAM_PERCENTAGE}" ] && JVM_MEMORY_OPTS="$JVM_MEMORY_OPTS -XX:InitialRAMPercentage=${GOVPAY_PAFORNODE_JVM_INITIAL_RAM_PERCENTAGE}"
[ -n "${GOVPAY_PAFORNODE_JVM_MIN_RAM_PERCENTAGE}" ] && JVM_MEMORY_OPTS="$JVM_MEMORY_OPTS -XX:MinRAMPercentage=${GOVPAY_PAFORNODE_JVM_MIN_RAM_PERCENTAGE}"
[ -n "${GOVPAY_PAFORNODE_JVM_MAX_METASPACE_SIZE}" ] && JVM_MEMORY_OPTS="$JVM_MEMORY_OPTS -XX:MaxMetaspaceSize=${GOVPAY_PAFORNODE_JVM_MAX_METASPACE_SIZE}"
[ -n "${GOVPAY_PAFORNODE_JVM_MAX_DIRECT_MEMORY_SIZE}" ] && JVM_MEMORY_OPTS="$JVM_MEMORY_OPTS -XX:MaxDirectMemorySize=${GOVPAY_PAFORNODE_JVM_MAX_DIRECT_MEMORY_SIZE}"

JAVA_OPTS="${JAVA_OPTS} ${JVM_MEMORY_OPTS}"
export JAVA_OPTS

##############################################################################
# Riepilogo Configurazione
##############################################################################

log_info "========================================"
log_info "Riepilogo Configurazione"
log_info "========================================"
log_info "Database: ${DATASOURCE_URL}"
log_info "Pool: ${SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE}/${SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE}"
log_info "Porta Server: ${SERVER_PORT}"
log_info "Java: MaxRAMPercentage=${GOVPAY_PAFORNODE_JVM_MAX_RAM_PERCENTAGE:-${DEFAULT_MAX_RAM_PERCENTAGE}}%"
log_info "========================================"

##############################################################################
# Avvio Applicazione
##############################################################################

JAR_FILE=$(find /opt/govpay-pafornode -name "*.jar" -type f | head -n 1)

if [ -z "${JAR_FILE}" ]; then
    log_error "Nessun file JAR trovato in /opt/govpay-pafornode"
    exit 1
fi

log_info "JDBC Drivers: ${GOVPAY_DS_JDBC_LIBS}"
log_info "Avvio: ${JAR_FILE}"
log_info "========================================"

# I driver JDBC non sono nel jar: li carica il PropertiesLauncher da LOADER_PATH
export LOADER_PATH="${GOVPAY_DS_JDBC_LIBS}"
exec java ${JAVA_OPTS} -jar "${JAR_FILE}"
