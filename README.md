<p align="center">
<img src="https://www.link.it/wp-content/uploads/2025/01/logo-govpay.svg" alt="GovPay Logo" width="200"/>
</p>

# GovPay - Porta di accesso al sistema pagoPA - PaForNode Api

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=link-it_govpay-pafornode-api&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=link-it_govpay-pafornode-api)
[![Docker Hub](https://img.shields.io/docker/v/linkitaly/govpay-pafornode-api?label=Docker%20Hub&logo=docker)](https://hub.docker.com/r/linkitaly/govpay-pafornode-api)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://raw.githubusercontent.com/link-it/govpay-pafornode-api/main/LICENSE)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg)](https://spring.io/projects/spring-boot)

## Sommario

govpay-pafornode-api implementa le interfacce SOAP che GovPay espone al Nodo dei Pagamenti di pagoPA in qualità di Ente Creditore, o di intermediario per conto degli enti gestiti. Il Nodo le invoca durante il ciclo di vita di un pagamento per verificare l'avviso, recuperare i dati della posizione debitoria e notificare l'esito tramite la ricevuta telematica.

Il modulo espone le operazioni del modello di pagamento corrente (paForNode):

- `paVerifyPaymentNotice`: verifica di un avviso di pagamento e restituzione dei dati della posizione debitoria;
- `paGetPayment` / `paGetPaymentV2`: attivazione del pagamento e restituzione dei dati di dettaglio, comprese le informazioni contabili di accredito;
- `paSendRT` / `paSendRTV2`: ricezione della ricevuta di pagamento.

## Istruzioni di compilazione

Il progetto utilizza librerie Spring Boot versione 4.1.1 e JDK 21.

Per la compilazione eseguire il seguente comando, verranno eseguiti anche i test.

``` bash
mvn clean install -P [jar|war]
```

Il profilo permette di selezionare il packaging dei progetti (jar o war).

Per l'avvio dell'applicativo come standalone eseguire:

``` bash
mvn spring-boot:run
```

Per sovrascrivere le proprietà definite nel file `application.properties` utilizzare il seguente sistema:

``` bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=[NUOVO_VALORE] ..."
```

### Esecuzione

``` bash
java -jar target/govpay-pafornode-api.jar
```

Le proprietà possono essere sovrascritte anche da riga di comando:

``` bash
java -Dserver.port=[NUOVO_VALORE] -jar target/govpay-pafornode-api.jar
```

## Configurazione

All'interno del file `application.properties` sono definite le seguenti proprietà:

``` bash
# ----------- SPRING SERVLET ------------

server.port=[Porta su cui esporre il servizio in caso di avvio come applicazione standalone]

spring.mvc.servlet.path=[Basepath servizi]

# Abilitazione Endpoint /actuator/health/liveness
management.endpoints.web.base-path=[Basepath dove esporre i servizi di stato applicazione]
```

## Configurazione porta Actuator / Prometheus

Gli endpoint `/actuator/health` e `/actuator/prometheus` rispondono di default sulla **stessa porta**
dell'applicazione (`server.port`). Per esporli su una porta dedicata impostare:

``` bash
management.server.port=[Porta dedicata per gli endpoint actuator]
```

oppure, in ambiente Docker, la variabile d'ambiente equivalente:

``` bash
MANAGEMENT_SERVER_PORT=[Porta dedicata per gli endpoint actuator]
```

Se non valorizzata, gli endpoint restano sulla porta applicativa.

## Configurazione logging

La configurazione del logging è gestita tramite le proprietà definite in `application.properties`:

``` bash
logging.file.name=[Path completo del file di log]
logging.level.it.govpay=[Livello di log: DEBUG, INFO, WARN, ERROR]
```

## Docker

L'immagine Docker è disponibile su Docker Hub: [linkitaly/govpay-pafornode-api](https://hub.docker.com/r/linkitaly/govpay-pafornode-api)

``` bash
docker pull linkitaly/govpay-pafornode-api:latest
```

A ogni push su `main` con versione SNAPSHOT viene pubblicata l'immagine di sviluppo
`linkitaly/govpay-pafornode-api-dev:<versione>`, destinata ai test di integrazione prima del rilascio.

## Reperire il war per il deploy su application server

Il war è prodotto da ogni build della pipeline, insieme al jar. Dove recuperarlo dipende
da cosa è stato costruito:

| Origine | Dove si trova |
|---------|---------------|
| Release (tag) | Allegato alla [release GitHub](https://github.com/link-it/govpay-pafornode-api/releases) come `govpay-pafornode-api-<tag>.war` |
| Qualsiasi build | Artifact `govpay-pafornode-api` della run di GitHub Actions (retention 90 giorni) |

Per costruire il war in locale, senza passare dalla pipeline:

``` bash
mvn clean install -P war
# prodotto in target/govpay-pafornode-api.war
```

## Pipeline CI

La pipeline GitHub Actions (`.github/workflows/maven.yml`) esegue:

- build di war e jar, test, report di copertura JaCoCo e analisi OWASP Dependency-Check;
- analisi SonarCloud e verifica delle licenze delle dipendenze di terze parti;
- scansione OSV e generazione dell'SBOM CycloneDX, con upload su Dependency-Track;
- su push su `main` con versione SNAPSHOT, pubblicazione dell'immagine Docker di sviluppo;
- su tag, creazione della release GitHub (war, jar e report) e pubblicazione dell'immagine Docker.

Il tag deve coincidere con la versione del `pom.xml`, che non deve essere una SNAPSHOT.

Il workflow `.github/workflows/refresh-owasp-db.yml` aggiorna ogni notte la cache del database NVD
usata da OWASP Dependency-Check.
