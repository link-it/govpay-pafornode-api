<p align="center">
<img src="https://www.link.it/wp-content/uploads/2025/01/logo-govpay.svg" alt="GovPay Logo" width="200"/>
</p>

# GovPay - Porta di accesso al sistema pagoPA - PaForNode Api

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=link-it_govpay-pafornode-api&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=link-it_govpay-pafornode-api)
[![Docker Hub](https://img.shields.io/docker/v/linkitaly/govpay-pafornode-api?label=Docker%20Hub&logo=docker)](https://hub.docker.com/r/linkitaly/govpay-pafornode-api)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://raw.githubusercontent.com/link-it/govpay-pafornode-api/main/LICENSE)
[![Spring Boot](https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Fraw.githubusercontent.com%2Flink-it%2Fgovpay-bom%2Fmain%2Fpom.xml&query=%2F%2F*%5Blocal-name()%3D'spring-boot.version'%5D&label=Spring%20Boot&color=brightgreen)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)

Interfacce SOAP paForNode che il Nodo dei Pagamenti pagoPA invoca su GovPay, come Ente Creditore o intermediario degli enti gestiti, per verificare un avviso, attivare il pagamento e consegnare la ricevuta.

## Sommario

Il Nodo dei Pagamenti chiama questo servizio durante il ciclo di vita di un pagamento. Il servizio legge e aggiorna le pendenze nel database di GovPay (modello v3 delle pendenze, tramite [govpay-common-pendenze](https://github.com/link-it/govpay-common-pendenze)), registra le RPT e le ricevute e accoda le notifiche verso le applicazioni dell'ente.

Operazioni esposte (contratto in [src/main/resources/xsd/paForNode.wsdl](src/main/resources/xsd/paForNode.wsdl), namespace `http://pagopa-api.pagopa.gov.it/pa/paForNode.xsd`):

| Operazione | Elemento della richiesta | Descrizione |
|------------|--------------------------|-------------|
| `paVerifyPaymentNotice` | `paVerifyPaymentNoticeReq` | Verifica un avviso e restituisce importo e causale della posizione debitoria. Se la pendenza non è in GovPay, la acquisisce dal gestionale dell'ente con l'operazione di verifica |
| `paGetPayment` | `paGetPaymentReq` | Attiva il pagamento: crea la RPT e restituisce i dati di dettaglio, compresi i conti di accredito |
| `paGetPaymentV2` | `paGetPaymentV2Request` | Come `paGetPayment`, con i dati contabili e i metadati della versione 2 |
| `paSendRT` | `paSendRTReq` | Riceve la ricevuta di pagamento e registra l'esito della pendenza |
| `paSendRTV2` | `paSendRTV2Request` | Come `paSendRT`, per le ricevute della versione 2 |

L'operazione `paDemandPaymentNotice`, presente nel WSDL di pagoPA, **non è supportata**: la richiesta riceve HTTP 404.

Tutte le operazioni rispondono su un unico endpoint, `POST /ws`: Spring Web Services sceglie l'operazione dall'elemento radice del body SOAP. Il WSDL è pubblicato in `GET /ws/paForNode.wsdl` (con autenticazione), con l'indirizzo del servizio (`soap:address`) riscritto su host e porta della richiesta: `<host>/ws`. Gli errori applicativi non sono SOAP fault: la risposta ha `outcome` `KO` e un elemento `fault` con i codici `PAA_*` delle specifiche pagoPA. Le richieste che non sono messaggi SOAP ricevono un errore HTTP: 415 se il Content-Type non è `text/xml` o `application/soap+xml`, 400 se l'XML è malformato o non ha un `Envelope`, 405 per i metodi diversi da POST.

### Autenticazione

L'autenticazione usa [govpay-common-auth](https://github.com/link-it/govpay-common-auth) e si abilita **un metodo per installazione**, a seconda di come il Nodo raggiunge il servizio:

| Metodo | Proprietà da abilitare | Quando |
|--------|------------------------|--------|
| SSL | `govpay.auth.ssl.enabled=true` | Il TLS con certificato client è terminato dall'applicazione (comportamento del vecchio `api-pagopa` del core) |
| SSL_HEADER | `govpay.auth.ssl-header.enabled=true` | Il TLS è terminato da un reverse proxy o API gateway, che inoltra il certificato client in un header |
| HEADER | `govpay.auth.header.enabled=true` | Il principal è già autenticato a monte e inoltrato in un header (usato dai test) |

Nessun metodo è abilitato di default: finché non se ne abilita uno, ogni richiesta su `/ws/**` è rifiutata. Il principal ricavato (dal subject del certificato o dall'header) deve corrispondere al campo `principal` di un intermediario abilitato nella tabella `intermediari`; ogni operazione verifica poi che l'`idBrokerPA` della richiesta sia proprio quell'intermediario. Solo `GET` su `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` e `/actuator/prometheus` restano accessibili senza autenticazione.

## Integrazione con GovPay

| Componente | Quando serve | Obbligatorio | Ruolo |
|------------|--------------|--------------|-------|
| Database di GovPay | runtime | sì | Schema del modello v3 delle pendenze (GovPay 3.11.0 o successivo), creato dagli script di installazione di GovPay: il servizio non crea lo schema e all'avvio lo valida (`ddl-auto=validate`). Legge le anagrafiche (`intermediari`, `stazioni`, `domini`, `uo`, `applicazioni`, `iban_accredito`, `tipi_versamento`, `tipi_vers_domini`, `tipi_tributo`, `tributi`, `utenze`, `utenze_domini`, `connettori`, `configurazione`); legge e aggiorna le pendenze (`versamenti`, `singoli_versamenti`, `opzioni_pagamento`, `soggetti_debitori`, `documenti`); scrive `rpt`, `pagamenti`, `rendicontazioni`, `notifiche`, `promemoria`, `notifiche_app_io` |
| [govpay-bom](https://github.com/link-it/govpay-bom) | build | sì | Parent Maven: versioni delle dipendenze e dei plugin |
| [govpay-common](https://github.com/link-it/govpay-common) | build | sì | Entità e repository delle anagrafiche, connettori HTTP letti dalla tabella `connettori`, configurazione da database, invio degli eventi al GDE, transaction id e correlation id nei log, livelli di log da database |
| [govpay-common-pendenze](https://github.com/link-it/govpay-common-pendenze) | build | sì | Modello ed entità della pendenza (v3), creazione e aggiornamento delle posizioni debitorie, generazione degli IUV |
| [govpay-common-auth](https://github.com/link-it/govpay-common-auth) | build | sì | Catena di autenticazione Spring Security (SSL, SSL_HEADER, HEADER) |
| [govpay-ec-client](https://github.com/link-it/govpay-ec-client) | build | sì | Client REST dell'operazione di verifica delle pendenze (`getAvviso`) esposta dal gestionale dell'ente |
| Nodo dei Pagamenti pagoPA | runtime | sì | Chiama le operazioni paForNode; si autentica con certificato client |
| Gestionale dell'ente (verifica) | runtime | no | Chiamato in `paVerifyPaymentNotice` e `paGetPayment` per acquisire o aggiornare una pendenza, con il connettore di verifica dell'applicazione (tabella `connettori`) |
| Piattaforma SEND (PagoPA) | runtime | no | Chiamata in `paGetPayment` per aggiungere il costo di notifica: `GET /delivery/v2.3/price/{paTaxId}/{noticeCode}` con il connettore indicato in `domini.cod_connettore_send` |
| [govpay-gde-api](https://github.com/link-it/govpay-gde-api) | runtime | no | Riceve gli eventi delle operazioni (componente `API_PAGOPA`) tramite il connettore `govpay_gde_api`, secondo la policy `giornale_eventi` della tabella `configurazione` |
| [govpay-notify-batch](https://github.com/link-it/govpay-notify-batch) | runtime | no | Spedisce alle applicazioni dell'ente le ricevute accodate nella tabella `notifiche` (tipo `RICEVUTA`); le notifiche di tipo `ATTIVAZIONE`, accodate in `paGetPayment` per le applicazioni con connettore `REST_1`, non sono spedite dal batch |
| Core GovPay, [govpay-mail-batch](https://github.com/link-it/govpay-mail-batch), [govpay-appio-batch](https://github.com/link-it/govpay-appio-batch) | runtime | no | Spediscono i promemoria della ricevuta accodati in `promemoria` (email) e `notifiche_app_io` (App IO): oggi il core, in prospettiva i due batch, ancora in fase di avvio |

Il servizio sostituisce l'API `api-pagopa` del core GovPay; la configurazione dell'intermediario e della stazione verso pagoPA è descritta nella [documentazione di GovPay](https://govpay.readthedocs.io/it/3.10.x/integrazione/index.html).

## Compilazione

Requisiti: JDK 21 e Maven 3.8.1 o successivo.

```bash
mvn clean install -P jar     # fat jar, per esecuzione standalone e container (profilo di default)
mvn clean install -P war     # war, per il deploy su application server
```

Il profilo sceglie il packaging; il risultato è in `target/govpay-pafornode-api.jar` o `target/govpay-pafornode-api.war`. La build esegue i test e l'analisi OWASP Dependency-Check delle dipendenze, configurata in govpay-bom; per escluderla, per esempio in sviluppo:

```bash
mvn clean install -P jar -Dowasp.phase=none
```

## Configurazione

Le proprietà si impostano in `application.properties`, da riga di comando (`-D<proprietà>=<valore>`) o con le variabili d'ambiente. Molte proprietà hanno una variabile d'ambiente dedicata, indicata nelle tabelle; per le altre valgono le variabili equivalenti di Spring Boot (es. `logging.level.it.govpay` → `LOGGING_LEVEL_IT_GOVPAY`, `govpay.auth.ssl.enabled` → `GOVPAY_AUTH_SSL_ENABLED`).

### Server

| Proprietà | Variabile d'ambiente | Default | Descrizione |
|-----------|----------------------|---------|-------------|
| `server.port` | `SERVER_PORT` | `10004` | Porta HTTP del servizio |
| `server.error.whitelabel.enabled` | - | `true` | Pagina di errore di default di Spring Boot |
| `spring.main.banner-mode` | - | `off` | Banner di Spring Boot all'avvio |

Per terminare il TLS nell'applicazione (metodo SSL) si usano le proprietà `server.ssl.*` di Spring Boot, con `server.ssl.client-auth=need` per richiedere il certificato client.

### Database

| Proprietà | Variabile d'ambiente | Default | Descrizione |
|-----------|----------------------|---------|-------------|
| `spring.datasource.url` | `DATASOURCE_URL` | - | URL JDBC del database di GovPay (obbligatoria, in alternativa a `spring.datasource.jndi-name`) |
| `spring.datasource.jndi-name` | - | - | Nome JNDI del datasource, per il deploy del war su application server |
| `spring.datasource.username` | `DATASOURCE_USERNAME` | - | Utente del database |
| `spring.datasource.password` | `DATASOURCE_PASSWORD` | - | Password del database |
| `spring.datasource.driver-class-name` | `DATASOURCE_DRIVER` | `org.postgresql.Driver` | Classe del driver JDBC (es. `oracle.jdbc.OracleDriver`, `com.mysql.cj.jdbc.Driver`, `com.microsoft.sqlserver.jdbc.SQLServerDriver`, `org.h2.Driver`) |
| `spring.jpa.database-platform` | `JPA_DIALECT` | `org.hibernate.dialect.PostgreSQLDialect` | Dialetto Hibernate (es. `org.hibernate.dialect.OracleDialect`, `MySQLDialect`, `SQLServerDialect`, `H2Dialect`) |
| `spring.jpa.hibernate.ddl-auto` | `HIBERNATE_DDL_AUTO` | `validate` | Gestione dello schema: lasciare `validate` o `none`, lo schema è di GovPay |
| `spring.jpa.open-in-view` | - | `false` | Sessione JPA limitata al servizio, non estesa alla vista |
| `spring.datasource.hikari.minimum-idle` | - | `10` (`2` nel container, `GOVPAY_PAFORNODE_MIN_POOL`) | Connessioni minime inattive del pool |
| `spring.datasource.hikari.maximum-pool-size` | - | `10` (`5` nel container, `GOVPAY_PAFORNODE_MAX_POOL`) | Connessioni massime del pool |
| `govpay.pendenze.fuso-orario` | - | `Europe/Rome` | Fuso orario con cui govpay-common-pendenze interpreta le date del database (colonne `TIMESTAMP` senza fuso) |

I driver JDBC non sono inclusi nel jar: vedi [Esecuzione](#esecuzione).

### Operazioni paForNode

| Proprietà | Variabile d'ambiente | Default | Descrizione |
|-----------|----------------------|---------|-------------|
| `govpay.pafornode.attualizzazione.send-retention` | `SEND_RETENTION` | `PT1H` | Tempo minimo, in formato ISO 8601, tra due richieste a SEND del costo di notifica per la stessa pendenza |
| `govpay.pafornode.attualizzazione.mandatoria` | `ATTUALIZZAZIONE_MANDATORIA` | `false` | Se `true`, `paGetPayment` su una pendenza scaduta che non si può aggiornare (né dal gestionale né da SEND) risponde `PAA_PAGAMENTO_SCADUTO`; se `false` procede con i dati presenti |
| `govpay.pafornode.numero-giorni-validita-pendenza` | - | - | Giorni dalla creazione della pendenza usati come `dueDate` della RPT, quando la pendenza non ha né data di validità né data di scadenza dell'avviso. Se non impostata la `dueDate` è `2999-12-31` |
| `govpay.pafornode.sonda-nav` | - | - | Numeri avviso, separati da virgola, usati dalle sonde di pagoPA: `paVerifyPaymentNotice` risponde subito `PAA_PAGAMENTO_SCONOSCIUTO`, senza altri controlli |

### Autenticazione

Proprietà di [govpay-common-auth](https://github.com/link-it/govpay-common-auth) usate da questo servizio. Va abilitato un solo metodo tra SSL, SSL_HEADER e HEADER (vedi [Autenticazione](#autenticazione)).

| Proprietà | Default | Descrizione |
|-----------|---------|-------------|
| `govpay.auth.ssl.enabled` | `false` | Abilita il metodo SSL (certificato client nella connessione TLS) |
| `govpay.auth.ssl.subject-principal-regex` | `^(.*)$` | Espressione regolare applicata al subject del certificato per ricavare il principal (default: tutto il subject DN) |
| `govpay.auth.ssl-header.enabled` | `false` | Abilita il metodo SSL_HEADER (certificato client inoltrato in un header) |
| `govpay.auth.ssl-header.principal-header-name` | `X-SSL-Client-Cert` | Header che contiene il certificato |
| `govpay.auth.ssl-header.url-decode` | `false` | Decodifica URL del contenuto dell'header |
| `govpay.auth.ssl-header.base64-decode` | `false` | Decodifica Base64 del contenuto dell'header |
| `govpay.auth.ssl-header.hex-decode` | `false` | Decodifica esadecimale del contenuto dell'header |
| `govpay.auth.ssl-header.replace-characters-enabled` | `false` | Sostituzione di caratteri nel PEM prima della decodifica (es. nginx con `$ssl_client_escaped_cert`) |
| `govpay.auth.ssl-header.replace-source` | - | Caratteri da sostituire (`\t`, `\r`, `\n`, `\r\n`, `\s`) |
| `govpay.auth.ssl-header.replace-dest` | - | Caratteri sostitutivi |
| `govpay.auth.header.enabled` | `false` | Abilita il metodo HEADER (principal in un header) |
| `govpay.auth.header.principal-header-names` | `X-Pre-Auth-User` | Header da cui leggere il principal, in ordine di precedenza |
| `govpay.auth.public-chain.enabled` | `true` | Permette l'accesso senza autenticazione ai path elencati sotto |
| `govpay.auth.public-chain.permit-all-paths[0].path` | `/actuator/health` | Path accessibile senza autenticazione |
| `govpay.auth.public-chain.permit-all-paths[0].methods[0]` | `GET` | Metodo HTTP ammesso sul path |
| `govpay.auth.public-chain.permit-all-paths[1].path` | `/actuator/prometheus` | Path accessibile senza autenticazione |
| `govpay.auth.public-chain.permit-all-paths[1].methods[0]` | `GET` | Metodo HTTP ammesso sul path |
| `govpay.auth.public-chain.permit-all-paths[2].path` | `/actuator/health/liveness` | Path accessibile senza autenticazione (probe di liveness) |
| `govpay.auth.public-chain.permit-all-paths[2].methods[0]` | `GET` | Metodo HTTP ammesso sul path |
| `govpay.auth.public-chain.permit-all-paths[3].path` | `/actuator/health/readiness` | Path accessibile senza autenticazione (probe di readiness) |
| `govpay.auth.public-chain.permit-all-paths[3].methods[0]` | `GET` | Metodo HTTP ammesso sul path |

Header di sicurezza (`govpay.auth.headers.*`), firewall (`govpay.auth.firewall.*`) e CORS (`govpay.auth.cors.*`) sono descritti nel README di [govpay-common-auth](https://github.com/link-it/govpay-common-auth#configurazione). Gli altri metodi della libreria (BASIC, FORM, LDAP, API_KEY, OAUTH2, SPID, SESSION) non sono previsti per questo servizio.

### Giornale degli Eventi e chiamate in uscita

L'indirizzo del GDE e dei servizi chiamati (gestionale dell'ente, SEND) non è una proprietà: è nei connettori della tabella `connettori` del database (per il GDE il connettore `govpay_gde_api`), descritti nel README di [govpay-common](https://github.com/link-it/govpay-common#connettori-http).

| Proprietà | Default | Descrizione |
|-----------|---------|-------------|
| `app.gde.async` | `true` | Invio asincrono degli eventi al GDE; `false` li invia nel thread della richiesta |
| `app.gde.pool.core-size` | `2` | Thread minimi del pool di invio degli eventi |
| `app.gde.pool.max-size` | `4` | Thread massimi del pool di invio degli eventi |
| `app.gde.pool.queue-capacity` | `100` | Eventi in coda; oltre questo limite l'evento è scartato con un warning, senza bloccare la risposta al Nodo |
| `govpay.client.cache.enabled` | `false` | Cache in memoria dei connettori; se disabilitata ogni chiamata rilegge il connettore dal database |
| `govpay.client.async.core-pool-size` | `10` | Thread minimi del pool delle chiamate HTTP asincrone di govpay-common |
| `govpay.client.async.max-pool-size` | `50` | Thread massimi del pool delle chiamate HTTP asincrone |
| `govpay.client.async.queue-capacity` | `100` | Capacità della coda del pool |
| `govpay.client.async.thread-name-prefix` | `async-http-` | Prefisso del nome dei thread del pool |

### Logging

| Proprietà | Variabile d'ambiente | Default | Descrizione |
|-----------|----------------------|---------|-------------|
| `logging.file.name` | `LOGGING_FILE_NAME` | - (solo console) | File di log; nel container `/var/log/govpay-pafornode/govpay-pafornode-api.log` |
| `logging.level.it.govpay` | `LOGGING_LEVEL_IT_GOVPAY` | `INFO` | Livello di log dei package GovPay |
| `logging.pattern.level` | - | `%5p [%X{transactionId:-},%X{correlationId:-}]` | Aggiunge a ogni riga transaction id e correlation id (header `X-Correlation-ID` o `X-Request-ID`, restituiti anche in risposta) |
| `govpay.logging.livelli-dinamici.enabled` | - | `true` | Livelli di log letti dalla chiave `log_level` della tabella `configurazione`, modificabili senza riavvio |
| `govpay.logging.livelli-dinamici.intervallo-refresh` | `LOG_LEVEL_REFRESH_INTERVAL` | `60s` | Intervallo di rilettura dei livelli di log dal database; `0` disattiva la rilettura |
| `govpay.logging.livelli-dinamici.ritardo-iniziale` | - | `60s` | Ritardo della prima rilettura dopo quella di avvio |
| `govpay.logging.livelli-dinamici.logger-gestiti[0]` | - | `it.govpay` | Prefissi dei logger modificabili da database |

Le altre proprietà della tracciatura (`govpay.logging.tracciatura.*`) sono descritte nel README di [govpay-common](https://github.com/link-it/govpay-common#tracciatura-dei-log).

### Monitoraggio

| Proprietà | Variabile d'ambiente | Default | Descrizione |
|-----------|----------------------|---------|-------------|
| `management.server.port` | `MANAGEMENT_SERVER_PORT` | porta del servizio | Porta dedicata agli endpoint actuator |
| `management.endpoints.web.base-path` | - | `/actuator` | Base path degli endpoint actuator |
| `management.endpoints.web.exposure.include` | `MANAGEMENT_ENDPOINTS` | `health,prometheus` | Endpoint actuator esposti |
| `management.endpoint.health.probes.enabled` | - | `true` | Espone `/actuator/health/liveness` e `/actuator/health/readiness` |
| `management.health.livenessState.enabled` | - | `true` | Stato di liveness nell'health |
| `management.health.readinessState.enabled` | - | `true` | Stato di readiness nell'health |
| `management.metrics.distribution.percentiles-histogram.http.server.requests` | - | `true` | Istogrammi delle latenze, per aggregare i percentili tra più istanze |
| `management.metrics.distribution.slo.http.server.requests` | - | `100ms,500ms,1s,2s,5s` | Soglie esplicite di latenza, compresa quella SLA pagoPA di 2s |
| `management.metrics.tags.application` | - | `govpay-pafornode-api` | Tag che identifica il servizio nelle metriche |
| `govpay.metrics.enabled` | - | `false` | Metriche custom di govpay-common |

## Esecuzione

### Driver JDBC

I driver JDBC **non sono inclusi** nel fat jar e vanno forniti a runtime. Creare una directory (es. `jdbc-drivers/`) e copiarvi il driver del database utilizzato:

| Database   | Driver |
|------------|--------|
| PostgreSQL | [postgresql-42.7.14.jar](https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.14/postgresql-42.7.14.jar) |
| MySQL      | [mysql-connector-j-26.10.0.jar](https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/26.10.0/mysql-connector-j-26.10.0.jar) |
| Oracle     | [ojdbc11-23.26.3.0.0.jar](https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc11/23.26.3.0.0/ojdbc11-23.26.3.0.0.jar) |
| SQL Server | [mssql-jdbc-13.6.1.jre11.jar](https://repo1.maven.org/maven2/com/microsoft/sqlserver/mssql-jdbc/13.6.1.jre11/mssql-jdbc-13.6.1.jre11.jar) |
| H2         | [h2-2.5.252.jar](https://repo1.maven.org/maven2/com/h2database/h2/2.5.252/h2-2.5.252.jar) |

### Jar standalone

Il jar usa `PropertiesLauncher`: la proprietà `loader.path` (o la variabile `LOADER_PATH`) indica la directory dei driver JDBC. Configurazione minima per PostgreSQL, con autenticazione del Nodo tramite certificato inoltrato da un reverse proxy:

```bash
java -Dloader.path=./jdbc-drivers \
     -Dspring.datasource.url=jdbc:postgresql://localhost:5432/govpay \
     -Dspring.datasource.username=govpay \
     -Dspring.datasource.password=<password> \
     -Dgovpay.auth.ssl-header.enabled=true \
     -jar target/govpay-pafornode-api.jar
```

Per gli altri database impostare anche `spring.datasource.driver-class-name` e `spring.jpa.database-platform`.

### War su application server

Il war non contiene i driver JDBC e usa il datasource dell'application server, indicato con `spring.datasource.jndi-name` (es. `java:comp/env/it.govpay.datasource`); il metodo di autenticazione si abilita come per il jar. Dove trovarlo:

| Origine | Dove si trova |
|---------|---------------|
| Release (tag) | Allegato alla [release GitHub](https://github.com/link-it/govpay-pafornode-api/releases) come `govpay-pafornode-api-<tag>.war` |
| Qualsiasi build | Artifact `govpay-pafornode-api` della run di GitHub Actions (retention 90 giorni) |
| Build locale | `mvn clean install -P war`, in `target/govpay-pafornode-api.war` |

## Esecuzione con Docker

L'immagine è pubblicata su Docker Hub come [linkitaly/govpay-pafornode-api](https://hub.docker.com/r/linkitaly/govpay-pafornode-api) (release) e `linkitaly/govpay-pafornode-api-dev` (sviluppo, a ogni push su `main` con versione SNAPSHOT). Il servizio risponde sulla porta `10004`.

I driver JDBC non sono nell'immagine: si montano in `/opt/jdbc-drivers` (directory indicata da `GOVPAY_DS_JDBC_LIBS`), da cui l'entrypoint li carica con `loader.path`. L'entrypoint costruisce URL JDBC, driver e dialetto dalle variabili `GOVPAY_DB_*`; il metodo di autenticazione si abilita con la variabile della proprietà (es. `GOVPAY_AUTH_SSL_HEADER_ENABLED=true`, vedi [Configurazione](#configurazione)).

```bash
docker run -d --name govpay-pafornode-api -p 10004:10004 \
  -v ./jdbc-drivers:/opt/jdbc-drivers \
  -e GOVPAY_DB_TYPE=postgresql \
  -e GOVPAY_DB_SERVER=postgres:5432 \
  -e GOVPAY_DB_NAME=govpay \
  -e GOVPAY_DB_USER=govpay \
  -e GOVPAY_DB_PASSWORD=<password> \
  -e GOVPAY_AUTH_SSL_HEADER_ENABLED=true \
  linkitaly/govpay-pafornode-api
```

In alternativa alle `GOVPAY_DB_*` si possono passare direttamente `DATASOURCE_URL`, `DATASOURCE_USERNAME`, `DATASOURCE_PASSWORD`, `DATASOURCE_DRIVER` e `JPA_DIALECT`.

Variabili d'ambiente dell'immagine, oltre a quelle dell'applicazione:

| Variabile | Obbligatoria | Default | Descrizione |
|-----------|--------------|---------|-------------|
| `GOVPAY_DB_TYPE` | sì (se manca `DATASOURCE_URL`) | - | Tipo di database: `postgresql`, `mysql`, `mariadb`, `oracle`, `sqlserver` |
| `GOVPAY_DB_SERVER` | sì (se manca `DATASOURCE_URL`) | - | Server del database, `host` o `host:porta` (porta di default del database se omessa) |
| `GOVPAY_DB_NAME` | sì (se manca `DATASOURCE_URL`) | - | Nome del database (per Oracle, service name o SID) |
| `GOVPAY_DB_USER` | sì (se manca `DATASOURCE_URL`) | - | Utente del database |
| `GOVPAY_DB_PASSWORD` | sì (se manca `DATASOURCE_URL`) | - | Password del database |
| `GOVPAY_DS_CONN_PARAM` | no | - | Parametri aggiunti all'URL JDBC (es. `zeroDateTimeBehavior=convertToNull` per MySQL) |
| `GOVPAY_DS_JDBC_LIBS` | no | `/opt/jdbc-drivers` | Directory dei driver JDBC |
| `GOVPAY_ORACLE_JDBC_URL_TYPE` | no | `servicename` | Formato dell'URL Oracle: `servicename` o `sid` |
| `ORACLE_TNS_ADMIN` | no | - | Directory dei file TNS di Oracle |
| `GOVPAY_PAFORNODE_MIN_POOL` | no | `2` | Connessioni minime inattive del pool |
| `GOVPAY_PAFORNODE_MAX_POOL` | no | `5` | Connessioni massime del pool |
| `SERVER_PORT` | no | `10004` | Porta HTTP del servizio |
| `GOVPAY_PAFORNODE_JVM_MAX_RAM_PERCENTAGE` | no | `80` | `-XX:MaxRAMPercentage` della JVM |
| `GOVPAY_PAFORNODE_JVM_INITIAL_RAM_PERCENTAGE` | no | - | `-XX:InitialRAMPercentage` della JVM |
| `GOVPAY_PAFORNODE_JVM_MIN_RAM_PERCENTAGE` | no | - | `-XX:MinRAMPercentage` della JVM |
| `GOVPAY_PAFORNODE_JVM_MAX_METASPACE_SIZE` | no | - | `-XX:MaxMetaspaceSize` della JVM |
| `GOVPAY_PAFORNODE_JVM_MAX_DIRECT_MEMORY_SIZE` | no | - | `-XX:MaxDirectMemorySize` della JVM |
| `JAVA_OPTS` | no | - | Opzioni aggiuntive della JVM |
| `LOGGING_FILE_NAME` | no | `/var/log/govpay-pafornode/govpay-pafornode-api.log` | File di log |
| `TZ` | no | `Europe/Rome` | Fuso orario del container |

Costruzione dell'immagine in locale (`docker/build_image.sh`) e scansione delle vulnerabilità sono descritte in [docker/SCANSIONE.md](docker/SCANSIONE.md).

## Monitoraggio

Gli endpoint actuator rispondono di default sulla porta del servizio e non richiedono autenticazione:

```bash
curl http://localhost:10004/actuator/health       # stato, usato anche dalle probe dei container
curl http://localhost:10004/actuator/prometheus   # metriche in formato Prometheus
```

Per le probe dei container sono disponibili anche `/actuator/health/liveness` e `/actuator/health/readiness`, anch'essi senza autenticazione. Per spostare gli endpoint su una porta dedicata impostare `management.server.port` (nel container `MANAGEMENT_SERVER_PORT`). L'endpoint `loggers`, che permette di cambiare i livelli di log a caldo, non è esposto di default: abilitarlo solo insieme a una porta dedicata e non raggiungibile dall'esterno, per esempio `MANAGEMENT_SERVER_PORT=9090` e `MANAGEMENT_ENDPOINTS=health,prometheus,loggers`.

## Pipeline CI

La pipeline GitHub Actions (`.github/workflows/maven.yml`) esegue:

- build di war e jar, test, report di copertura JaCoCo e analisi OWASP Dependency-Check;
- analisi SonarCloud e verifica delle licenze delle dipendenze di terze parti;
- scansione OSV e generazione dell'SBOM CycloneDX, con upload su Dependency-Track;
- su push su `main` con versione SNAPSHOT, pubblicazione dell'immagine Docker di sviluppo;
- su tag, creazione della release GitHub (war, jar e report) e pubblicazione dell'immagine Docker.

Il tag deve coincidere con la versione del `pom.xml`, che non deve essere una SNAPSHOT. Il workflow `.github/workflows/refresh-owasp-db.yml` aggiorna ogni notte la cache del database NVD usata da OWASP Dependency-Check.

## Client SEND

`SendClient` (package `it.govpay.pafornode.send`) chiama la sola operazione `GET /delivery/v2.3/price/{paTaxId}/{noticeCode}` del [contratto SEND NotificationPriceV23](https://github.com/pagopa/pn-delivery/blob/main/docs/openapi/api-external-b2b-pa-bundle.yaml). Usa `ConnettoreService` di govpay-common per autenticazione, TLS, timeout, correlation id, registrazione nel GDE e metriche con tag `connettore`.

```java
long importoInCentesimi = sendClient.recuperaImportoNotifica(
    dominio.getCodConnettoreSend(), dominio.getCodDominio(), numeroAvviso);
```

- Il connettore è quello indicato nella colonna `domini.cod_connettore_send`.
- L'URL del connettore è la base del servizio (compreso l'eventuale prefisso del proxy), senza il path dell'operazione; lo slash finale è gestito.
- `noticeCode` è il numero avviso completo, **non** lo IUV.
- Il risultato è `totalPrice`, in **centesimi**, comprensivo di `paFee` e IVA; non `partialPrice`. Zero è un valore valido.
- Gli errori HTTP e di trasporto sono propagati come eccezioni Spring, con status e body. Un importo mancante, negativo o non intero è un errore, non un costo zero.
- Il client non aggiorna la pendenza e non ripete le chiamate: la conversione in euro e la registrazione con `registraImportoSend` spettano al servizio chiamante (`AttualizzazionePendenzaService`).

## Licenza

GovPay PaForNode Api è distribuito con licenza [GNU General Public License v3.0](LICENSE).
