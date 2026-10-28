# Migrazione govpay-pafornode-api a v3-nativa

Documento di lavoro, stesso spirito e stesse convenzioni di `proposta-modello-nativo-v3.md`
(`govpay-common-pendenze`): ogni decisione verificata contro il codice legacy reale prima di
essere presa, punti aperti tracciati esplicitamente, mai decisi silenziosamente.

## 0. Contesto e perimetro

`govpay-pafornode-api` implementa le interfacce SOAP che GovPay espone al Nodo dei Pagamenti
pagoPA in qualità di Ente Creditore: il contratto `paForNode` (WSDL/XSD già presenti in
risorse). Cinque operazioni (confermato dal README e dall'XSD):

- `paVerifyPaymentNotice` — verifica avviso, restituisce i dati della posizione debitoria;
- `paGetPayment` / `paGetPaymentV2` — attivazione pagamento, dati di dettaglio e contabili;
- `paSendRT` / `paSendRTV2` — ricezione ricevuta telematica.

Allo stato iniziale (clonato il 2026-10-28) il repository è uno scheletro vuoto: solo
`Application.java` (bootstrap Spring Boot, DataSource/JPA esplicitamente esclusi finché non
c'è una base dati configurata) e le risorse XSD/WSDL. Nessun endpoint implementato.

**Decisioni di scope confermate con il committente (2026-10-28)**:
- Accesso dati tramite `govpay-common-pendenze` (stesso modello v3-nativo di
  `govpay-pendenze-api`), non accesso diretto al DB legacy.
- Prima operazione: `paVerifyPaymentNotice`.
- Fedeltà completa fin dalla v1, non scope ridotto: inclusa l'acquisizione live della
  pendenza dall'applicazione gestionale proprietaria (protocollo "Verifica") quando non
  ancora presente in `govpay-common-pendenze`, l'autenticazione/autorizzazione del
  chiamante, e la tracciatura eventi/diagnostica equivalente al legacy.
- Documento di design mantenuto, stesso formato di `proposta-modello-nativo-v3.md`.

**Implementazione legacy di riferimento**: `wars/api-pagopa/src/main/java/it/govpay/web/ws/
PagamentiTelematiciCCPImpl.java` (1685 righe). La classe implementa anche operazioni SPC/CCP
più vecchie (`paaAttivaRPT`/`paaVerificaRPT`/`paaInviaRT`, header `IntestazionePPT`) e
`paDemandPaymentNotice` — **tutte stubbate** ("Operazione non disponibile", o non presenti
nello scope dichiarato dal README di questo modulo): fuori perimetro, non vanno replicate.

## 1. `paVerifyPaymentNotice` — analisi del legacy (righe 425-708)

Logica di business, al netto del contorno specifico del monolite (vedi §2):

1. Estrae `numeroAvviso` (`qrCode.getNoticeNumber()`) e `codDominio`
   (`qrCode.getFiscalCode()`) dalla richiesta; deriva lo IUV (`IuvUtils.toIuv`).
2. **Check "sonda pagoPA"** (`IuvUtils.isNavSondaPagoPA`): un NAV sintetico usato dal Nodo
   stesso come probe di stand-in. Risponde subito con `PAA_PAGAMENTO_SCONOSCIUTO`, **prima**
   di verificare il dominio (per non far interpretare a Stand-In un `PAA_ID_DOMINIO_ERRATO`
   come disservizio reale — commento esplicito nel codice).
3. Verifica l'intermediario chiamante (`AnagraficaManager.getIntermediario`) — se non trovato,
   `PAA_ID_INTERMEDIARIO_ERRATO`. Controllo di autorizzazione: il principal autenticato deve
   corrispondere a `intermediario.getPrincipal()` (solo se `isPddAuthEnable()`).
4. Verifica la stazione (`AnagraficaManager.getStazione`) — se non trovata,
   `PAA_STAZIONE_INT_ERRATA`.
5. Verifica che il dominio esista (`AnagraficaManager.getDominio`) — altrimenti
   `PAA_ID_DOMINIO_ERRATO`.
6. Cerca il versamento per `(dominio, iuv)`:
   - **Trovato**: `VersamentoUtils.aggiornaVersamento` (gestisce un'eventuale scadenza —
     attualizzazione). Poi: `ANNULLATO` → fault; già `ESEGUITO`/`ESEGUITO_ALTRO_CANALE` →
     `PAA_PAGAMENTO_DUPLICATO` (con data pagamento e IUR se un pagamento esiste già).
   - **Non trovato**: risolve quale applicazione gestisce quel dominio/IUV
     (`Applicazione.getApplicazioneDominio`) — se nessuna, `PAA_PAGAMENTO_SCONOSCIUTO`.
     Altrimenti **acquisizione live** (`VersamentoUtils.acquisisciVersamento`, §3) dalla
     stessa applicazione, poi le stesse verifiche di scadenza/stato del ramo "trovato".
7. Eccezioni di acquisizione mappate su fault pagoPA specifici: `VersamentoScadutoException`
   → `PAA_PAGAMENTO_SCADUTO`, `VersamentoAnnullatoException` → `PAA_PAGAMENTO_ANNULLATO`,
   `VersamentoDuplicatoException` → `PAA_PAGAMENTO_DUPLICATO`, `VersamentoSconosciutoException`
   → `PAA_PAGAMENTO_SCONOSCIUTO`, `VersamentoNonValidoException`/`ClientException`/
   `GovPayException` → `PAA_SYSTEM_ERROR`.
8. Risposta positiva: `outcome=OK`, `fiscalCodePA`/`companyName` dal dominio, `officeName`
   dall'unità operativa (se non EC e con anagrafica), `paymentDescription` dalla causale
   (`CausaleSemplice`, altrimenti stringa vuota). **Una sola** opzione di pagamento
   (`CtPaymentOptionDescriptionPA`, `options=EQ`, importo esatto — il supporto a opzioni
   multiple è stato sospeso da pagoPA stessa, commento esplicito con riferimento a
   pagopa-api#216), `amount` = importo totale **con eventuali spese SEND**
   (`VersamentoUtils.getImportoTotaleConSend`), `dueDate` da `dataValidita`, `allCCP` da
   `VersamentoUtils.isAllIBANPostali`.

**Mappatura errori → risposta** (`buildRisposta`, righe 1032-1139): dispatcher generico per
tipo di risposta — ogni `NdpException` diventa `outcome=KO` + `CtFaultBean{id=codDominio,
faultCode, faultString, description}` sul campo `fault` del DTO di risposta specifico.
`FaultPa` (`jars/core-beans/.../NdpException.java:69-91`) è un vocabolario di fault **di
protocollo pagoPA** (non specifico di GovPay) — da portare 1:1, non da reinventare.

## 2. Infrastruttura legacy da NON replicare 1:1 (sostituita da equivalenti v3-nativi)

Il codice legacy intreccia la logica di business (§1) con infrastruttura specifica del
monolite openspcoop2, che ha equivalenti già pronti nella nuova generazione:

| Legacy | Equivalente v3-nativo | Note |
|---|---|---|
| `ContextThreadLocal`/`GpContext`/`EventoCtx` | `AbstractGdeService` (`govpay-common`, già usato da `govpay-pendenze-api` via `PendenzeGdeService`) | Verificato: `it.govpay.gde.client.beans.ComponenteEvento` ha già un valore `PAGOPA("API_PAGOPA")` — anticipato, da riusare. |
| `MessaggioDiagnosticoUtils`/`BDConfigWrapper` | logging strutturato (`govpay-common`, transaction id/correlation id, BP-LOG-3) | Nessun "giornale diagnostico" separato nella nuova generazione, solo log strutturati + GDE. |
| `AuthorizationManager`/`GovpayLdapUserDetails` (LDAP) | Spring Security proprio del modulo (stesso principio di `govpay-pendenze-api`, che ha un proprio `security` package — non condiviso da `govpay-common`) | Verificato: `IntermediarioEntity`/`StazioneEntity` **esistono già** in `govpay-common` (tabelle `intermediari`/`stazioni`), con `principal`/`principalOriginale`/`abilitato` sulla prima — stesso modello del legacy. Autenticazione del Nodo da disegnare (§4, punto aperto). |
| `VersamentiBD`/`Versamento` (ORM legacy) | `PosizioneDebitoriaService`/`PendenzaRepository` (`govpay-common-pendenze`) | Stesso principio del wrapper v2 di `govpay-pendenze-api`: leggere/scrivere tramite la libreria condivisa, non un accesso DB proprio. |

## 3. Il protocollo "Verifica" (acquisizione live dall'applicazione gestionale)

Analizzato `VersamentoUtils.acquisisciVersamento` (`jars/core/.../VersamentoUtils.java:368-483`)
e `VerificaClient`/`IVerificaClient` (`jars/core/.../utils/client/VerificaClient.java`,
`jars/core-beans/.../client/IVerificaClient.java`).

- **Non è un protocollo pagoPA**: è un contratto **GovPay-specifico**, REST, verso l'URL
  configurata nel connettore di integrazione dell'applicazione (`applicazione
  .getConnettoreIntegrazione()`, `Connettore.getVersione()` — `GP_REST_01`/`GP_REST_02`
  supportate, `GP_SOAP_03` dichiarata ma non implementata, lancia eccezione).
- Operazioni REST: `verifyPendenza`/`verifyPendenzaMod4` (`GET /pendenze/{idDominio}/{iuv}`,
  verificare path esatto per versione) — risposta deserializzata in `PendenzaVerificata`/
  `StatoPendenzaVerificata` (`it.govpay.ec.v1.beans`, `jars/client-api-ente`), poi convertita
  in `Versamento` legacy (`VersamentoUtils.toVersamentoModel`).
- Eccezioni di dominio mappate 1:1 sui fault pagoPA di `paVerifyPaymentNotice` (§1, punto 7):
  `VersamentoScadutoException`/`Annullato`/`Duplicato`/`Sconosciuto`/`NonValido`.
- Dopo l'acquisizione: se `versamento.isSendAbilitato()`, attualizzazione importo SEND
  (`aggiornaImportoSend`) — stesso principio già portato in `govpay-common-pendenze`
  (`PosizioneDebitoriaService.registraImportoSend`, vedi `proposta-modello-nativo-v3.md`).

**Punto aperto esplicito**: il contratto REST "Verifica" (request/response JSON, versioni
GP_REST_01/02) non è stato ancora letto in dettaglio — da fare prima di implementare il
client in `govpay-pafornode-api`. Verificare anche se `govpay-common`/`govpay-common-pendenze`
hanno già un client HTTP generico riusabile (es. per i connettori ACA/FR/GPD visti altrove)
invece di scriverne uno nuovo da zero.

## 3bis. Il contratto "Verifica" è documentato: `govpay-api-ec` (OpenAPI)

Trovato in `jars/client-api-ente/src/main/resources/`: `govpay-api-ec-v1.yaml` (v1.3.0) e
`v2/govpay-api-ec.yaml` + `govpay-api-schemas.yaml`/`-parameters.yaml`/`-responses.yaml`/
`-examples.yaml` (v2.1.1) — **non è un protocollo ad-hoc**, è un'API REST con OpenAPI
pubblicato (tag "Verifica"/"Verifica pendenze"), esposta dal gestionale, chiamata da GovPay.

- **v1**: `GET /avvisi/{idDomino}/{iuv}` (`getAvviso`) e `GET /pendenze/{idA2A}/{idPendenza}`
  (`verifyPendenza`) → `pendenzaVerificata` (schema flat: `idA2A`/`idPendenza`/`stato`
  (`statoPendenzaVerificata`: `NON_ESEGUITA`/`ANNULLATA`/`SCADUTA`/`DUPLICATA`/`SCONOSCIUTA`,
  5 valori)/`idDominio`/`soggettoPagatore`/`importo`/`causale`/`voci`/...).
- **v2**: stessi path (`getAvviso`: ora `GET /avvisi/{idDominio}/{numeroAvviso}`, non più
  `iuv`), risposta `PendenzaVerificata` **ristretta**: solo `stato`
  (`StatoPendenzaVerificata`, 4 valori — **`DUPLICATA` rimossa**)/`descrizioneStato`/
  `pendenza`, dove **`pendenza` è un `$ref` diretto a `NuovaPendenza`**
  (`govpay-api-schemas.yaml`) — **lo stesso schema v2 che `govpay-pendenze-api` già consuma
  in scrittura** (`NuovaPendenzaMapper.crea`, wrapper v2 di `addPendenza`).

**Implicazione architetturale rilevante**: convertire una `PendenzaVerificata` (v2) in un
comando per `PosizioneDebitoriaService` (`govpay-common-pendenze`) è concettualmente la
**stessa trasformazione** già implementata in `NuovaPendenzaMapper` per il wrapper v2 —
`pendenza` qui è letteralmente una `NuovaPendenza`. Non si riscrive la mappatura da zero: si
verifica se è possibile riusare/duplicare lo stesso pattern (stesso DTO generato dallo stesso
schema OpenAPI, se `govpay-pafornode-api` genera le proprie classi da questi YAML).

**Mod4** (`POST /pendenze/{idDominio}/{idTipoPendenza}`, body custom) non è usato da
`paVerifyPaymentNotice` (serve a `inoltroPendenza`, altro percorso applicativo, fuori scope).

## 3ter. `VersamentoUtils.aggiornaVersamento` (attualizzazione per scadenza, righe 266-364)

Chiamato PRIMA e DOPO l'eventuale acquisizione in `paVerifyPaymentNotice` (una volta sul
versamento trovato, una volta su quello appena acquisito). Logica:

1. Se lo stato non è `NON_ESEGUITO` → no-op (non attualizza mai una pendenza già
   conclusa/annullata).
2. Se `dataScadenza` è passata → `VersamentoScadutoException` immediato, nessun tentativo di
   riacquisizione (la scadenza è un limite assoluto, non riattualizzabile).
3. Altrimenti, se `dataValidita` è passata (ma non la scadenza):
   - applicazione **con** connettore di verifica configurato → riacquisizione live
     (`acquisisciVersamento`, stesso meccanismo di §3), con fallback "uso il dato che ho" per
     quasi tutte le eccezioni, **a meno che** la proprietà
     `isAggiornamentoValiditaMandatorio()` sia `true` (allora l'errore si propaga come fault);
   - applicazione **senza** connettore ma con SEND abilitato → solo attualizzazione importo
     SEND (`aggiornaImportoSend`), niente riacquisizione (le spese SEND dipendono dal
     connettore SEND del *dominio*, non da quello di verifica dell'applicazione);
   - applicazione senza connettore e SEND non abilitato → se mandatorio, `VersamentoScadutoException`;
     altrimenti nessuna azione (si usa il dato esistente, scaduto o no).
4. Se `dataValidita` non è passata → nessuna azione, il dato resta valido.

## 3quater. Autenticazione del Nodo verso l'endpoint pagoPA: **certificato client (X.509)**

Letto `wars/api-pagopa/src/main/resources/spring-sec/api-pagopa-applicationContext-security.xml`
(Spring Security XML legacy). Più varianti sono **templatizzate** nello stesso file (commentate,
pronte da attivare): BASIC, LDAP, SSL/X.509, SSL_HEADER (pre-auth da header impostato da un
proxy upstream che termina il TLS), HEADER, API_KEY, OAUTH2 — tutte condividono lo stesso
schema (`intercept-url` + entry point + `UserDetailsService` che risolve il principal
sull'anagrafica).

**Quella effettivamente attiva per l'endpoint pagoPA** (blocco `<http>` senza `pattern`,
quindi catch-all su tutto tranne `/internal/.*`): **`x509` con `subject-principal-regex`**,
`userDetailServiceUtenzeSSL` → `AutenticazioneUtenzeAnonimeDAO` con `checkSubject=true` —
**mutua autenticazione TLS con certificato client**, non Basic/LDAP. Il principal estratto dal
certificato va confrontato con `intermediario.getPrincipal()` (stesso campo già presente su
`IntermediarioEntity` in `govpay-common`, §2).

**Punto aperto esplicito**: non è ancora chiaro se, nel deployment di produzione attuale, la
verifica TLS avviene **dentro** l'applicazione (truststore Java, terminazione TLS in-app) o se
un **proxy/ingress upstream** termina il TLS e forwarda il subject verificato via header (da
cui il blocco `SSL_HEADER` templatizzato nello stesso file, oggi commentato) — la differenza
cambia molto cosa serve implementare in Spring Boot (gestione diretta di un truststore/keystore
Java, vs. fidarsi di un header e validare solo che provenga dalla rete interna). Da chiarire
con chi gestisce l'infrastruttura prima di disegnare l'autenticazione di questo modulo.

## 4. Punti aperti

1. **Infrastruttura di autenticazione X.509 effettiva** (§3quater): in-app (truststore Java,
   terminazione TLS nel servizio) o header da proxy upstream — da chiarire con chi gestisce
   l'infrastruttura prima di disegnare la sicurezza di questo modulo.
2. **Operazione "Verifica" da usare in v1**: il legacy sceglie `verifyPendenza` o `getAvviso`
   in base a una proprietà di configurazione (`operazioneVerifica`, `Costanti
   .VERIFICA_PENDENZE_GET_AVVISO_OPERATION_ID`/`VERIFY_PENDENZA_OPERATION_ID`) con un default
   che dipende dalla presenza di `iuv`. Per `paVerifyPaymentNotice` (che ha sempre
   `codDominio`+`iuv`, mai `idA2A`+`idPendenza` direttamente) il path naturale è `getAvviso`
   — da confermare se serve replicare anche la configurabilità o se per questa operazione
   specifica `getAvviso` è l'unica scelta sensata.
3. **Versione del contratto EC da implementare prima**: v1 (`govpay-api-ec-v1.yaml`, risposta
   flat, 5 stati inclusa `DUPLICATA`) o v2 (`govpay-api-ec.yaml`, risposta annidata su
   `NuovaPendenza`, 4 stati)? Il client legacy supporta entrambe per applicazione
   configurata; non è chiaro se le applicazioni reali oggi usano prevalentemente v1 o v2.
4. **`IuvUtils.isNavSondaPagoPA`**: da portare in `govpay-common` (o duplicare qui) — non
   presente nel nuovo `it.govpay.common.utils.IuvUtils` (verificato).
5. **Configurazione Spring-WS**: nessun bean `MessageDispatcherServlet`/`DefaultWsdl11Definition`
   ancora presente — da scrivere (il plugin `jaxb2-maven-plugin` per le classi XJC da
   `paForNode.xsd` è già configurato nel `pom.xml`, verificato generando le fonti).
6. **Client HTTP generico riusabile**: verificare se `govpay-common` ha già un client HTTP
   generico (usato altrove per i connettori ACA/FR/GPD/SEND) prima di scriverne uno nuovo per
   il contratto EC.
