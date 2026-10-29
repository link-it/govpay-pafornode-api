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

Allo stato iniziale (clonato il 2026-10-28) il repository era uno scheletro vuoto: solo
`Application.java` (bootstrap Spring Boot, DataSource/JPA esplicitamente esclusi finché non
c'è una base dati configurata) e le risorse XSD/WSDL. Nessun endpoint implementato.

**Stato di avanzamento (aggiornato al 2026-10-29)**: implementate e testate
`paVerifyPaymentNotice` e `paGetPayment` (V1) — builder `CtPaymentPaBuilder`, persistenza
RPT (`RptAttivazioneService`, con lock pessimistico sulla pendenza e sulle RPT pendenti e
annullamento delle RPT precedenti, risposta ri-letta dall'XML persistito), notifica di
accodamento verso il connettore di integrazione dell'applicazione
(`NotificaAttivazioneService`), confine transazionale dedicato
(`PaGetPaymentActivationService`, separato dall'endpoint non transazionale per non far
sfuggire gli errori di commit alla costruzione della risposta KO), controlli condivisi tra
le due operazioni (`PaForNodeChecks`, incluse le date di validità dell'opzione v3).
Attualizzazione (§3quinquies): tutti e tre i rami — SEND, riacquisizione (pendenza TROVATA
con validità scaduta) e acquisizione (pendenza DEL TUTTO ASSENTE, punto aperto #10) — sono
ora implementati e cablati su `paGetPayment` (`SendClient`/`VerificaClient`/
`PendenzaVerificataMapper`/`ApplicazioneDominioResolver`/`AttualizzazionePendenzaService`).
**`paGetPayment` (V1) è quindi completo per tutti i casi analizzati**, salvo: il punto aperto
#9 (nessuno dei tre rami ancora cablato su `paVerifyPaymentNotice`, che resta
`@Transactional(readOnly = true)` — spostato in fondo ai lavori dell'issue, non blocca più
`paGetPaymentV2`), il punto aperto #11 (spedizione effettiva della notifica di attivazione —
resta solo accodamento), e il punto aperto #1 (autenticazione X.509, infrastruttura/
deployment, fuori scope applicativo).

**`paGetPaymentV2` implementato** (§3septies, 2026-10-29): stessa infrastruttura di
`paGetPayment` (riusata senza modifiche), nuovo `CtPaymentPaV2Builder`/
`PaGetPaymentV2ActivationService`, inclusi i metadati di contabilità/custom per transfer
(mappatura su `DettaglioContabile`, chiavi `RC_*` del Dizionario dei metadata pagoPA —
punto #12 risolto). Resta solo il punto aperto #13 (rischio NPE non corretto su una voce di
bollo senza contabilità propria).

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

**Implicazione architetturale, verificata e risolta (§3quinquies, 2026-10-29)**: convertire
una `PendenzaVerificata` (v2) in un comando per `PosizioneDebitoriaService`
(`govpay-common-pendenze`) è concettualmente parente della trasformazione di
`NuovaPendenzaMapper` per il wrapper v2, ma **non riusabile direttamente**: quella classe
dipende dal proprio tipo generato (`it.govpay.pendenze.v2.api.model.NuovaPendenza`, diverso
da `it.govpay.ec.client.beans.NuovaPendenza` anche se dallo stesso schema OpenAPI) e dalle
entità di sicurezza proprie di quel modulo (autorizzazione per `utenza`, irrilevante qui:
l'acquisizione è avviata da GovPay stesso tramite un connettore configurato, non da un
chiamante HTTP da autorizzare). Scritta una mappatura propria, più piccola
(`PendenzaVerificataMapper`, §3quinquies).

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

## 3quinquies. Piano di implementazione dell'attualizzazione (deciso con il committente, 2026-10-28)

Verificato nel legacy (`PagamentiTelematiciCCPImpl.java:540-610`) che `paGetPayment` ha la
stessa identica struttura a due rami di `paVerifyPaymentNotice` (§1, punto 6): pendenza
trovata → `aggiornaVersamento` (§3ter); pendenza non trovata → `acquisisciVersamento` (§3)
seguita dallo stesso `aggiornaVersamento`. Va quindi implementata una volta, condivisa tra
le due operazioni, non duplicata — da chiudere prima di `paGetPaymentV2` (che nel legacy
condivide la stessa logica in `CtPaymentPAV2Builder`).

**Decisioni**:

- Contratto "Verifica" (§3bis): **solo v2** (risposta annidata su `NuovaPendenza`, 4 stati).
  Non si implementa v1.
- Operazione: **`getAvviso`** (`GET /avvisi/{idDominio}/{numeroAvviso}`) — coerente con
  l'input di `paVerifyPaymentNotice`/`paGetPayment` (sempre `codDominio`+IUV/numeroAvviso,
  mai `idA2A`+`idPendenza` direttamente). Non si implementa la configurabilità
  `verifyPendenza`/`getAvviso` del legacy: per questa operazione `getAvviso` è l'unica
  scelta sensata.
- Client "Verifica": `it.govpay.pafornode.verifica.VerificaClient` (**fatto**), wrapper
  sottile su `VerificaPendenzeApi.getAvviso(idDominio, numeroAvviso)` di `govpay-ec-client`
  (`org.gov4j.govpay:govpay-ec-client`, generato da OpenAPI, specifica "API Notifica e
  Verifica v2.1.1" — coincide con la decisione "solo v2" sopra), stesso principio di
  `SendClient` (connettore risolto da `ApplicazioneEntity.codConnettoreIntegrazione` via
  `ConnettoreService`, stessa risoluzione già cablata in `NotificaAttivazioneService`, stesso
  campo che il legacy usa per entrambe le direzioni verifica/notifica).
  **Bug trovato e corretto in `govpay-ec-client`** (non in questo repo): il DTO generato
  `NuovaVocePendenza` inizialmente non portava `codEntrata`/`ibanAccredito`/`ibanAppoggio`/
  `tipoBollo`/`hashDocumento`/`provinciaResidenza`/`codiceTassonomicoPagoPA` — campi che nello
  YAML (`v2/govpay-api-schemas.yaml`) sono dentro un `oneOf` **senza discriminator**
  (`TipoRiferimentoVocePendenza`: `RiferimentoEntrata`/`Entrata`/`Bollo`) che il generatore
  legacy appiattisce per eredità ma quello di `govpay-ec-client` no — senza quei campi
  nessuna voce acquisita avrebbe avuto un IBAN risolvibile. Risolto aggiungendo un
  discriminator (`tipoRiferimento`) allo schema, più `GovPayEcApiClient` (nuovo in
  `govpay-ec-client`, sostituisce il semplice `ApiClient` generato) che disabilita la
  discriminazione Jackson in lettura (`@JsonTypeInfo(use = NONE)` via mixin) perché i payload
  reali del gestionale sono flat, senza discriminator — stesso principio di
  `Jaxb2Marshaller.setCheckForXmlRootElement(false)` già usato altrove in questa sessione per
  un problema analogo (classi generate che non corrispondono esattamente a cosa arriva
  davvero sulla rete).
  `PendenzaVerificataMapper` (**fatto**, nuovo in `govpay-pafornode-api`, NON
  `NuovaPendenzaMapper` di `govpay-pendenze-api` — vedi sotto perché) converte la
  `NuovaPendenza` risultante in un `AggiornamentoPendenza` (`PosizioneDebitoriaService
  .aggiornaPendenza`, non `crea`: la pendenza riacquisita esiste già, si aggiorna in loco,
  non si crea una posizione nuova — § 3quinquies sotto per lo scope esatto).
- Connettore SEND: campo dato **in `govpay-common`** — `codConnettoreSend` aggiunto a
  `DominioEntity` (le spese SEND dipendono dal connettore SEND del *dominio*, non da quello
  di verifica dell'applicazione, §3ter), accanto agli altri connettori di dominio già
  presenti lì (`codConnettoreMyPivot`/`codConnettoreSecim`/`codConnettoreGovPay`/
  `codConnettoreHyperSicApk`). **Fatto** (commit `514f074`, 2026-10-28/29).
- Client SEND: **in `govpay-pafornode-api`** (non in `govpay-common`) — correzione rispetto
  alla decisione precedente di questo documento: il dato di configurazione è condiviso
  perché riguarda l'anagrafica del dominio, ma il client che chiama SEND è usato solo da
  questo modulo, quindi non ha bisogno di vivere nella libreria condivisa (stesso principio
  di `govpay-ec-client`, integrazione specifica separata dalla libreria generica).
  Implementato `it.govpay.pafornode.send.SendClient` (**fatto**, scritto a mano — risolve
  il punto aperto #7: un solo endpoint non giustificava la generazione OpenAPI): wrapper
  sottile su `ConnettoreService` (`govpay-common`, già esistente — non nuovo), che fornisce
  risoluzione del connettore, `RestTemplate` con autenticazione/TLS/correlation
  ID/observability già configurati. `HttpClientConfig` (nuovo, in `pafornode-api`) importa
  solo i bean necessari (`AsyncClientConfiguration`/`RestTemplateFactory`/
  `Oauth2ClientCredentialsManager`/`ConnettoreService`) invece di
  `GovPayClientAutoConfiguration` per intero, che registrerebbe anche repository/entity
  pensati per il contesto batch. La persistenza del risultato riusa
  `PosizioneDebitoriaService.registraImportoSend` (`govpay-common-pendenze`), già
  esistente — resta da cablare la chiamata al client dentro `AttualizzazionePendenzaService`
  (retention, conversione in euro, scrittura).
- Orchestrazione: `AttualizzazionePendenzaService` (**fatto**, in `govpay-pafornode-api`,
  package `it.govpay.pafornode.rpt`), stesso principio di `PaForNodeChecks` (pensato per
  essere condiviso, non duplicato) — ma vedi sotto: **cablato solo su `paGetPayment`** (via
  `PaGetPaymentActivationService`, chiamato dopo il lock pessimistico sulla pendenza e prima
  di `PaForNodeChecks.verificaStatoPagabile`), non su `paVerifyPaymentNotice`. Due rami,
  stesso ordine del legacy, entrambi **fatti**:
  - pendenza trovata ma `dataValidita` scaduta, applicazione con connettore di verifica
    configurato (`ApplicazioneEntity.codConnettoreIntegrazione`) → riacquisizione live
    (`VerificaClient.getAvviso` + `PendenzaVerificataMapper` +
    `PosizioneDebitoriaService.aggiornaPendenza`); eccezioni di business
    (`PendenzaVerificata` con stato `ANNULLATA`/`SCADUTA`/`SCONOSCIUTA`) mappate sugli stessi
    `FaultPa` di `PaForNodeChecks`.
  - pendenza (anche appena riacquisita) con SEND abilitato e `codConnettoreSend` configurato
    sul dominio → attualizzazione importo SEND (con retention, via `SendClient` +
    `PosizioneDebitoriaService.registraImportoSend`) — eseguita DOPO l'eventuale
    riacquisizione, stesso ordine del legacy ("dopo l'acquisizione, se sendAbilitato,
    attualizza SEND").
  - **Non implementato**: l'acquisizione di una pendenza DEL TUTTO ASSENTE (non solo con
    validità scaduta) — richiede di risolvere quale applicazione gestisce un dominio/iuv
    quando non c'è ancora una pendenza da cui leggere `idApplicazione` (equivalente di
    `Applicazione.getApplicazioneDominio` del legacy), risoluzione non ancora analizzata.
    Resta `PAA_PAGAMENTO_SCONOSCIUTO`, come oggi. Lo scope di `PendenzaVerificataMapper` è
    comunque già pronto per questo caso (converte una `NuovaPendenza` in dati di pendenza);
    manca solo la risoluzione dell'applicazione e la creazione (vs. aggiornamento) della
    `PosizioneDebitoria`.
  - Non ancora cablato su `paVerifyPaymentNotice`: quell'operazione resta
    `@Transactional(readOnly = true)` nell'endpoint, e scrivere (SEND o riacquisizione) da
    una transazione read-only è a rischio di fallire in produzione (connessione JDBC in
    sola lettura su PostgreSQL) — richiede prima la stessa estrazione in un servizio
    scrivente non read-only già applicata a `paGetPayment`. Non ancora fatto.
- Correzione collaterale (**fatta**): `paVerifyPaymentNotice` non includeva le spese SEND
  nell'`amount` della risposta (`VersamentoUtils.getImportoTotaleConSend` del legacy, §1
  punto 8) — mancava indipendentemente dall'attualizzazione, perché è una pura lettura di
  `pendenza.getSendImportoTotale()`, già eventualmente popolato da un aggiornamento
  precedente. Corretto in `PaForNodeEndpoint.buildRispostaPositiva`.
- Property Spring configurabili (**fatte**): `govpay.pafornode.attualizzazione.send-retention`
  (default `PT1H`, equivalente di `it.govpay.client.send.retention`) e
  `govpay.pafornode.attualizzazione.mandatoria` (default `false`, equivalente di
  `isAggiornamentoValiditaMandatorio()`).

**Punto aperto nuovo**: la mappatura `NuovaPendenza`(govpay-ec-client)→comandi
`PosizioneDebitoriaService`, necessaria per i due casi non implementati sopra. Non è un
riuso diretto di `NuovaPendenzaMapper` di `govpay-pendenze-api`: quella classe dipende dal
proprio modello generato (`it.govpay.pendenze.v2.api.model.NuovaPendenza`, non lo stesso
tipo Java del `NuovaPendenza` generato da `govpay-ec-client`, anche se dallo stesso schema
OpenAPI) e dalle entità di sicurezza proprie di quel modulo (`UtenzaEntity`/
`UtenzaDominioEntity`/...). Richiede una mappatura propria in `govpay-pafornode-api`, o
l'estrazione della logica di mappatura condivisa in `govpay-common-pendenze` — da decidere
prima di implementare i due casi mancanti.

## 3sexies. Verifica di completezza di `paGetPayment` rispetto al legacy (2026-10-29)

Letto per intero `PagamentiTelematiciCCPImpl.paGetPayment` (righe 708-1021, non solo i
frammenti già analizzati) e confrontato punto per punto con l'implementazione attuale.

**Confermato equivalente** (verificato contro il codice legacy, non per assunzione):
- Marca da bollo telematica: `VersamentoUtils.isPendenzaMBT` (`jars/core/.../VersamentoUtils.java:1353-1363`)
  itera le voci e si ferma alla prima con `hashDocumento`+`provinciaResidenza`+`tipoBollo`
  tutti non-null — **identico** a `PaGetPaymentActivationService.contieneMarcaDaBollo`.
- `IuvUtils.isNavSondaPagoPA`/configurazione Spring-WS: vedi punti aperti #4/#5 sopra, già
  risolti (erano solo non ancora verificati nel documento, non mancanti nel codice).
- Mappatura eccezioni → fault (`VersamentoScaduto`/`Annullato`/`Duplicato`/`Sconosciuto`/
  `NonValido`/generica → `PAA_SYSTEM_ERROR`): stessa struttura in `PaForNodeChecks`/
  `NdpException`/il catch-all `RuntimeException` dell'endpoint.
- Annullamento RPT pendenti prima della nuova attivazione, stato finale `RPT_ACCETTATA_NODO`,
  quota SEND nell'importo: tutti verificati nei finding della review precedente.

**Deviazioni deliberate, non bug** (da tenere a mente, non da "correggere" per fedeltà):
- **Notifica solo accodata, non spedita**: il legacy inserisce la notifica E tenta
  l'invio sincrono via thread pool (`ThreadExecutorManager...execute(new
  InviaNotificaThread(...))`, righe 966-969) se `inserisciNotifica` lo richiede. Questa
  generazione si ferma all'accodamento (`NotificaAttivazioneService`, riga `DA_SPEDIRE`) —
  già documentato nel Javadoc di quella classe, nessun processo di spedizione esiste ancora.
- ~~Risposta costruita dall'oggetto in memoria, non ri-analizzata dall'XML persistito~~ —
  **risolto** (2026-10-29): `RptAttivazioneService.deserializzaDati(Rpt)` rilegge
  `rpt.getXmlRpt()` e lo deserializza, stessa garanzia del legacy
  (`JaxbUtils.toPaGetPaymentResRPT`). `PaGetPaymentActivationService` ora usa quello invece
  dell'oggetto `CtPaymentPA` in memoria. Nell'implementare questo e' emerso un bug (lo stesso
  problema di `checkForXmlRootElement` del marshalling, §3quater: senza specificare la
  classe attesa a `unmarshal()`, JAXB non riconosce l'elemento come root valido — risolto
  con la forma a 2 argomenti `unmarshal(Source, Class)`), corretto prima del commit.
- **Lock pessimistico sulla pendenza con re-lettura post-lock** (`entityManager.refresh(...,
  PESSIMISTIC_WRITE)` in `PaGetPaymentActivationService`, con test di regressione dedicato):
  non ha un equivalente diretto in questo punto del legacy (che usa `enableSelectForUpdate`
  più avanti, sulla sola fase di insert RPT) — induriemento aggiunto in questa generazione,
  non una lacuna.

**Non implementato** (sapevamo già, confermato qui non essere sfuggito nient'altro):
acquisizione di una pendenza DEL TUTTO ASSENTE (punto aperto #10); attualizzazione non
cablata su `paVerifyPaymentNotice` (punto aperto #9); infrastruttura di autenticazione X.509
(punto aperto #1, questione di deployment, non di codice applicativo).

## 3septies. `paGetPaymentV2` (implementato, 2026-10-29)

Letto per intero `PagamentiTelematiciCCPImpl.paGetPaymentV2` (righe 1283-1591) e
`CtPaymentPAV2Builder` (`jars/core/.../CtPaymentPAV2Builder.java`, 367 righe) e confrontati
con `paGetPayment`/`CtPaymentPaBuilder` riga per riga.

**Risultato chiave**: `paGetPaymentV2` e' identico a `paGetPayment` fino alla risoluzione/
attualizzazione della pendenza (stesso codice, verificato) — tutta l'infrastruttura già
costruita per V1 (`PaForNodeChecks`, `AttualizzazionePendenzaService`,
`ApplicazioneDominioResolver`, `NotificaAttivazioneService`, lock pessimistico,
annullamento RPT pendenti) è riusata senza modifiche da `PaGetPaymentV2ActivationService`
(nuovo). Le uniche differenze reali, tutte nella costruzione del payload
(`CtPaymentPaV2Builder`, nuovo):

- **Nessun rifiuto preventivo della Marca da Bollo Telematica**: V1 la rifiuta a monte
  (`PAA_SEMANTICA`); V2 la gestisce per singolo transfer — se una voce non ha un IBAN
  risolvibile (ne' da tributo ne' diretto, stessa risoluzione di {@code
  IbanAccreditoResolver}), diventa `CtRichiestaMarcaDaBollo` (`hashDocumento` decodificato da
  base64, `provinciaResidenza`, `tipoBollo` con fallback a `"01"` — unico valore del legacy
  `TipoBollo.IMPOSTA_BOLLO` — se la voce non lo specifica).
- **`companyName` sul singolo transfer** (non solo sul pagamento complessivo), sia nel ramo
  IBAN sia nel ramo bollo — `CtTransferPAV2` ha questo campo, `CtTransferPA` (V1) no.
- **Nuovi campi RPT**: `codCarrello` (da `request.getPaymentNote()`, troncato a 35 caratteri,
  stesso limite del legacy) e versione `SANP_321_V2` invece di `SANP_240` — entrambi i campi
  esistevano già sull'entity `Rpt` (estensione fatta in una sessione precedente, in previsione
  di V2).
- Multibeneficiario: nessuna logica nuova, `IbanAccreditoResolver`/`ContabilitaResolver`
  (V1) già generalizzano correttamente voce-per-voce/dominio-per-dominio.

**Rischio noto, non corretto** (documentato nel Javadoc di `CtPaymentPaV2Builder`):
`transferCategory` si calcola allo stesso modo per ogni transfer, bollo compreso (stessa
chiamata incondizionata del legacy) — una voce di bollo senza `tipoContabilita`/
`codiceContabilita` propri e senza tributo fa fallire con `NullPointerException` (riprodotto
in test, poi corretto nella fixture di test, non nel codice — non verificato se il legacy
stesso vada in NPE nello stesso scenario o se una voce di bollo reale porti sempre questi
valori per altra via).

**Metadati di contabilità/custom per transfer — implementato** (2026-10-29): **non** è un
adattamento del vecchio JSON legacy (`QuotaContabilita`) — il modello v3-nativo lo ha
deliberatamente **sostituito** con il sealed interface `DettaglioContabile`, mappato
direttamente sul Dizionario dei metadata pagoPA ufficiale (sezione "Riconciliazione
contabile", issue #877 dello YAML v3, §14 di `proposta-modello-nativo-v3.md` di
govpay-common-pendenze) — le chiavi `RC_*` sono documentate nel Javadoc di ciascuna
variante di `DettaglioContabile` stesso:

- `CorrispettivoDl118` → `RC_AC`/`RC_CU`/`RC_CAP`/`RC_ACC`/`RC_ART`/`RC_PF5`/`RC_IMP`.
- `IncassoTipico` → `RC_AC`/`RC_CU`/`RC_TI`/`RC_EF`/`RC_ND`/`RC_IMP`.
- `Civilistico` → `RC_AC`/`RC_CU`/`RC_CON`/`RC_COM`/`RC_ND`/`RC_IMP`.
- `SpeseNotifica` → stessa chiave `NOTIFICATION_FEE` della quota SEND automatica (**deciso
  con il committente**: centesimi, intero, stesso formato) — mutuamente esclusive per
  costruzione dei dati, non per logica aggiunta qui: se una voce ha `SpeseNotifica`,
  `pendenza.isSendAbilitato()` è necessariamente `false` (vincolo di
  `ValidatorePosizioneDebitoria`), quindi `sendImportoTotale` resta `null` e la quota
  automatica non scatta mai.
- `Sconosciuto` (`UNKNOWN_ENTRIES`) → entry grezze chiave/valore, pass-through.

`VocePendenza.getDettaglioContabile()` è una **lista** (come il vecchio multi-quota
legacy, ma tipizzata): ogni elemento produce le proprie entry, unite alla quota SEND se
pertinente sullo stesso transfer. 4 nuovi test in `CtPaymentPaV2BuilderTest`.

**Test**: `CtPaymentPaV2BuilderTest` (4 test: IBAN da tributo, voce senza IBAN→bollo,
fallback tipo bollo, quota SEND+metadata) e due nuovi test di integrazione SOAP in
`PaForNodeEndpointTest` (attivazione con successo verificando anche `versione=SANP_321_V2`
sulla riga RPT, fault per pendenza sconosciuta). 73/73 test verdi in tutto il modulo.

## 4. Punti aperti

1. **Infrastruttura di autenticazione X.509 effettiva** (§3quater): in-app (truststore Java,
   terminazione TLS nel servizio) o header da proxy upstream — da chiarire con chi gestisce
   l'infrastruttura prima di disegnare la sicurezza di questo modulo.
2. ~~Operazione "Verifica" da usare~~ — **risolto** (§3quinquies, 2026-10-28): `getAvviso`,
   nessuna configurabilità da replicare.
3. ~~Versione del contratto EC da implementare prima~~ — **risolto** (§3quinquies,
   2026-10-28): solo v2, non si implementa v1.
4. ~~`IuvUtils.isNavSondaPagoPA`~~ — **risolto** (verificato 2026-10-29): il legacy stesso è
   solo `GovpayConfig.getInstance().getNavSondaPagoPA().contains(numeroAvviso)`, una lista
   configurata — equivalente già presente in `PaForNodeEndpoint` (campo `navSondaPagoPA`,
   property `govpay.pafornode.sonda-nav`), stessa semantica.
5. ~~Configurazione Spring-WS~~ — **risolto**: `WsConfig` (`MessageDispatcherServlet`,
   `SimpleWsdl11Definition` che pubblica `paForNode.wsdl` com'è, `Jaxb2Marshaller` con
   `setCheckForXmlRootElement(false)`) già presente e funzionante (i test di integrazione
   inviano envelope SOAP reali).
6. ~~Client HTTP generico riusabile~~ — **risolto**: per "Verifica" si riusa
   `govpay-ec-client`, già generato e buildato in locale; per SEND si riusa
   `ConnettoreService` (`govpay-common`, già esistente), con un client sottile specifico in
   `govpay-pafornode-api` — nessun nuovo client HTTP generico necessario in nessuno dei due
   casi.
7. ~~Client SEND: scritto a mano o generato via OpenAPI?~~ — **risolto** (§3quinquies,
   2026-10-29): scritto a mano (`it.govpay.pafornode.send.SendClient`), un solo endpoint non
   giustificava la generazione OpenAPI.
8. ~~Mappatura `NuovaPendenza` (govpay-ec-client) → comandi `PosizioneDebitoriaService`~~ —
   **risolto** (§3quinquies, 2026-10-29): mappatura propria (`PendenzaVerificataMapper`), non
   condivisa/estratta da `NuovaPendenzaMapper`. Risolto anche un bug collaterale scoperto nel
   frattempo in `govpay-ec-client` stesso (campi del `oneOf` `TipoRiferimentoVocePendenza`
   persi dal generatore, vedi §3bis). Riacquisizione implementata per la pendenza TROVATA ma
   con validità scaduta; resta aperta solo l'acquisizione della pendenza DEL TUTTO ASSENTE
   (punto 10 sotto).
9. **Attualizzazione (SEND e riacquisizione) non cablata su `paVerifyPaymentNotice`**
   (§3quinquies, 2026-10-29): quell'operazione resta `@Transactional(readOnly = true)`;
   scrivere da lì (SEND o riacquisizione, entrambe ora implementate per `paGetPayment`)
   richiede prima la stessa estrazione in un servizio scrivente non read-only già applicata a
   `paGetPayment`. Non ancora deciso se farlo prima o dopo `paGetPaymentV2`.
10. ~~Acquisizione di una pendenza DEL TUTTO ASSENTE~~ — **risolto e implementato**
    (2026-10-29). `Applicazione.getApplicazioneDominio` legacy
    (`jars/core/.../business/Applicazione.java:36-65`) itera TUTTE le applicazioni registrate
    e, per ciascuna, verifica prima l'autorizzazione al dominio
    (`utenza.isAutorizzazioneDominiStar() || utenza.isIdDominioAutorizzato(idDominio)`,
    tabelle `utenze`/`utenze_domini`) poi, se autorizzata, verifica che `applicazioni.reg_exp`
    (regex sul formato dello IUV) faccia match — prima applicazione che soddisfa entrambe
    vince, nessun fallback "tipo versamento di default".

    **Decisione presa con il committente** sulla collocazione (`utenze`/`utenze_domini` non
    hanno equivalente in `govpay-common`, stesso principio già noto per
    `govpay-pendenze-api`/`govpay-console-api` — infrastruttura di sicurezza, non anagrafica
    di dominio, ogni consumer mappa la propria vista):
    - `UtenzaEntity`/`UtenzaDominioEntity` (**fatte**): copia locale in
      `govpay-pafornode-api` (`it.govpay.pafornode.security`), stesso pattern degli altri
      consumer — non condivise in `govpay-common`.
    - Logica di decisione autorizzazione (**fatta**): `AutorizzazioneDominio.autorizzato`
      (nuovo, in **`govpay-common-auth`**) — agnostico, prende in input i due booleani già
      risolti dal chiamante (nessuna dipendenza nuova `govpay-common-auth`→`govpay-common`,
      deliberatamente: la libreria resta generica, stesso principio di
      `GovpayPrincipalLoader`).
    - `ApplicazioneDominioResolver` (**fatto**, in `govpay-pafornode-api`): l'algoritmo di
      RICERCA (itera `ApplicazioneRepository.findAll()`, risolve l'utenza, chiama
      `AutorizzazioneDominio.autorizzato`, poi verifica `regExp`) — resta specifico di
      `pafornode-api`, concettualmente diverso dalla verifica puntuale sopra (qui si cerca
      CHI è autorizzato, non si verifica se un'applicazione già nota lo è). 6 test dedicati.
    - Cablato in `PaGetPaymentActivationService.acquisisciPendenzaAssente`: risolve
      l'applicazione, chiama `VerificaClient.getAvviso`, mappa con il nuovo
      `PendenzaVerificataMapper.creaDatiPosizioneDebitoria` (creazione, non aggiornamento —
      a differenza di `toAggiornamentoPendenza` usato per la riacquisizione) e persiste via
      `PosizioneDebitoriaService.crea`.
    - **Gap di test noto**: nessun test di integrazione end-to-end (SOAP reale) per il
      percorso completo "pendenza assente → acquisita con successo" — la fixture
      richiederebbe anche `TipoVersamentoDominioEntity`/`TipoTributoEntity`/`TributoEntity`,
      non ancora scritta. Coperti solo `ApplicazioneDominioResolver` in isolamento e il
      fault `PAA_PAGAMENTO_SCONOSCIUTO` quando nessuna applicazione fa match (test
      preesistente, ancora verde).
    - **Non replicato**: il fallback "tipo versamento di default" non esiste nemmeno nel
      legacy (nessun fallback), quindi nessuna azione qui; l'ambiguità fra più applicazioni
      che soddisfano entrambe le condizioni non è gestita (vince la prima, come nel legacy).
11. **Spedizione effettiva della notifica di attivazione** (analizzato 2026-10-29): il
    legacy (`InviaNotificaThread`, `jars/core/.../utils/thread/InviaNotificaThread.java`)
    invia la notifica di `ATTIVAZIONE` con l'operazione **`notifyPagamento`** (POST
    `/pagamenti/{codDominio}/{iuv}`, contratto EC **V1**, bean `it.govpay.ec.v1.beans.Notifica`)
    — **non** la stessa operazione `notificaRicevuta` già generata in `govpay-ec-client`
    (quella è per `TipoNotifica.RICEVUTA`, connettore V2, e per V2 l'ATTIVAZIONE non viene
    proprio spedita: `inserisciNotifica` la scarta se il connettore non è `GP_REST_01`).
    Errore di invio → stato resta `DA_SPEDIRE` con backoff quadratico e retry differito via
    un batch dedicato (`Operazioni.spedizioneNotifiche`), non un retry immediato nel thread.
    **Blocco reale**: `govpay-ec-client` oggi genera solo il contratto v2 (`notificaRicevuta`/
    `notificaRendicontazione`/`getAvviso`/`verifyPendenza`) — l'operazione `notifyPagamento`
    (V1, quella che serve per ATTIVAZIONE) non è generata. Implementarla richiederebbe
    aggiungere quel contratto V1 a `govpay-ec-client` (o scrivere un client ad-hoc in
    `pafornode-api`) per un'operazione che il legacy stesso tratta come propria di un
    client "V1" più vecchio rispetto a quello usato per RICEVUTA. **Non ancora deciso con
    il committente**: replicare fedelmente il contratto V1 legacy, o la nuova generazione ha
    un piano diverso per notificare l'attivazione alle applicazioni (es. solo V2/RICEVUTA,
    lasciando l'ATTIVAZIONE come solo accodamento senza spedizione)?
12. ~~Metadati di contabilità/custom per transfer su `paGetPaymentV2`~~ — **risolto**
    (2026-10-29): non un adattamento del JSON legacy, ma la mappatura diretta sul
    Dizionario dei metadata pagoPA ufficiale che il modello v3-nativo già implementa
    (`DettaglioContabile`, chiavi `RC_*` documentate nel suo stesso Javadoc) — vedi
    §3septies per il dettaglio completo.
13. **Rischio NPE su `transferCategory` per una voce di bollo senza contabilità** (§3septies,
    2026-10-29): riprodotto in test, non corretto nel codice (stessa chiamata incondizionata
    del legacy) — non verificato se il legacy stesso vada in NPE nello stesso scenario, o se
    una voce di bollo reale porti sempre `tipoContabilita`/`codiceContabilita` per altra via.
