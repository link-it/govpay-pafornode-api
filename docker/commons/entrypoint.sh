#!/bin/bash

##############################################################################
# GovPay PAFORNODE API - Script di Entrypoint
#
# Interfacce SOAP paForNode invocate dal Nodo dei Pagamenti pagoPA
##############################################################################

set -e

# Debug di esecuzione
exec 6<> /tmp/entrypoint_debug.log
exec 2>&6
set -x

# Funzioni di logging
log_info() { echo -e "\033[0;32m[INFO]\033[0m $(date '+%Y-%m-%d %H:%M:%S') - $1"; }
log_warn() { echo -e "\033[1;33m[WARN]\033[0m $(date '+%Y-%m-%d %H:%M:%S') - $1"; }
log_error() { echo -e "\033[0;31m[ERROR]\033[0m $(date '+%Y-%m-%d %H:%M:%S') - $1"; }

log_info "========================================"
log_info "Avvio Servizio GovPay PAFORNODE API"
log_info "========================================"

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

log_info "Avvio: ${JAR_FILE}"
log_info "========================================"

exec java ${JAVA_OPTS} -jar "${JAR_FILE}"
