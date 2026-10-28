-- ---------------------------------------------------------------------------
-- Schema di test dell'aggregato pendenza (modello nativo v3).
--
-- Copia 1:1 di src/test/resources/db/schema-pendenze-test.sql di
-- govpay-common-pendenze (stesso principio gia' applicato li' allo schema di
-- govpay-common: "stesso DDL di test, non una definizione indipendente"):
-- questo servizio dipende dalle stesse entita' JPA, quindi ha bisogno delle
-- stesse tabelle per i propri test con ddl-auto=validate. Se le entita'
-- cambiano, riallineare prima quella copia, poi replicare qui.
--
-- Provenienza: nessun DDL di produzione esiste ancora (nessuna migrazione dati
--   scritta finora) — questo schema e' derivato colonna per colonna dalle
--   annotazioni JPA delle entita' di govpay-common-pendenze
--   (it.govpay.pendenze.entity), che restano la fonte di verita'.
--
-- Non sono presenti foreign key verso l'anagrafica (domini, unita' operative,
-- tipi pendenza), che qui non esiste: la libreria mappa quelle colonne come
-- semplici FK Long, senza relazioni JPA (M4 di proposta-modello-nativo-v3.md).
--
-- Tutte le istruzioni sono IF NOT EXISTS: H2 riesegue lo script INIT su OGNI nuova
-- connessione alla stessa URL, non solo alla prima. Con AllocatoreBloccoProgressivoIuv
-- che apre una connessione dedicata (REQUIRES_NEW), una seconda connessione e' normale
-- durante i test; senza IF NOT EXISTS quella riesecuzione fallisce su "already exists" e
-- HikariCP passa 30s a ritentare la creazione della connessione prima di rinunciare.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- Aggregato pendenza: RIUSO delle tabelle legacy (decisione del lead, 2026-09-25,
-- dopo l'analisi in proposta-modello-nativo-v3.md §17) al posto di uno schema v3
-- nativo separato. Principio: minimizzare la differenza strutturale da v2 per
-- ridurre al minimo la migrazione dati e i problemi di retrocompatibilita' —
-- v2 e v3 scrivono le stesse tabelle fisiche.
--
-- documenti/versamenti/singoli_versamenti sono le tabelle legacy REALI (stessa
-- DDL di gov_pay.sql, colonna per colonna) con SOLO aggiunte additive:
--   - documenti: +notifica_send, +data_creazione, +data_ultimo_aggiornamento
--   - versamenti: +id_opzione_pagamento (FK nullable verso la tabella nuova
--     opzioni_pagamento) — cod_rata resta intatto, v2 lo ignora del tutto
--   - singoli_versamenti: nessuna aggiunta. contabilita/metadata sono RIUSATE
--     cosi' come sono anche per VocePendenza.dettaglioContabile: i due formati
--     JSON (vecchio Contabilita/QuotaContabilita di api-ragioneria, nuovo
--     DettaglioContabile) non si sovrappongono su nessuna chiave (tipo vs
--     quote) — la compatibilita' si gestisce a livello applicativo, non qui.
--
-- Importi in DOUBLE PRECISION su versamenti/singoli_versamenti (decisione del
-- lead, 2026-09-25): stessa dichiarazione della produzione, NESSUN ALTER anche
-- se e' un tipo non ideale per il denaro — qui vince esplicitamente "minimizza
-- le variazioni al DB" sul principio project-wide di usare sempre NUMERIC. Le
-- entity mappano questi campi come Double/double, non BigDecimal, per
-- combaciare col tipo di colonna reale. Le tabelle NUOVE (opzioni_pagamento,
-- soggetti_debitori) restano NUMERIC dove serve, non essendo un riuso.
--
-- opzioni_pagamento e soggetti_debitori sono le UNICHE tabelle nuove:
--   - opzioni_pagamento: la macchina a stati (DISPONIBILE/ATTIVATA/ANNULLATA)
--     non esiste in v2 in nessuna forma, nemmeno manuale (verificato: l'unico
--     annullamento legacy e' un'operazione esplicita per singolo versamento,
--     nessuna cascata automatica sui versamenti "fratelli" alternativi) — va
--     tracciata ed eseguita da qualche parte.
--   - soggetti_debitori: TUTTI i debitori, incluso il primo (decisione del
--     lead, 2026-09-25, corregge una proposta precedente che teneva il primo
--     solo su versamenti.debitore_*): il debitore appartiene logicamente al
--     documento, non al singolo versamento, ed e' l'unica fonte di verita' per
--     v3. versamenti.debitore_identificativo/debitore_anagrafica/
--     src_debitore_identificativo restano comunque NOT NULL in produzione ma
--     NON vengono sincronizzati col soggetto di ordine 0 (decisione del lead,
--     2026-09-26, dopo un tentativo intermedio di sincronizzarli davvero, poi
--     scartato): quella lista resta modificabile dopo la creazione (PATCH,
--     sviluppo successivo) e tenerli allineati nel tempo sarebbe complessita'
--     pura. Il motore di pagamento legacy (attivazione RPT, stampa avviso) li
--     legge davvero, ma e' esso stesso parte di cio' che verra' sostituito a
--     fine transizione v3 — fino ad allora resta un gap noto e accettato, non
--     qualcosa che questa libreria compensa fingendo un dato disallineabile.
--     Pendenza.java li valorizza con placeholder fissi ed esplicativi
--     ("VEDERE_SOGGETTI_DEBITORI"/"Vedere tabella soggetti_debitori"); il
--     nullable debitore_tipo resta indefinito.
--
-- tipi_versamento/tipi_vers_domini: anagrafica legacy reale, non piu'
-- modellata da entita' proprie di govpay-common-pendenze (decisione del lead,
-- 2026-09-28, dopo govpay-common#9) — questo servizio usa direttamente
-- TipoVersamentoEntity/TipoVersamentoDominioEntity di govpay-common, che le
-- mappano a piena fedelta' (tutte le colonne del DDL). DDL reale copiato
-- verbatim da gov_pay.sql.
-- ---------------------------------------------------------------------------

CREATE SEQUENCE IF NOT EXISTS seq_documenti start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS seq_versamenti start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS seq_singoli_versamenti start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS seq_opzioni_pagamento start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS seq_soggetti_debitori start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS seq_tipi_versamento start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;
CREATE SEQUENCE IF NOT EXISTS seq_tipi_vers_domini start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

-- DDL reale (gov_pay.sql), mappato a piena fedelta' da TipoVersamentoEntity/
-- TipoVersamentoDominioEntity di govpay-common — vedi nota di testata.
CREATE TABLE IF NOT EXISTS tipi_versamento
(
	cod_tipo_versamento VARCHAR(35) NOT NULL,
	descrizione VARCHAR(255) NOT NULL,
	codifica_iuv VARCHAR(4),
	paga_terzi BOOLEAN NOT NULL DEFAULT false,
	abilitato BOOLEAN NOT NULL,
	bo_form_tipo VARCHAR(35),
	bo_form_definizione TEXT,
	bo_validazione_def TEXT,
	bo_trasformazione_tipo VARCHAR(35),
	bo_trasformazione_def TEXT,
	bo_cod_applicazione VARCHAR(35),
	bo_abilitato BOOLEAN NOT NULL DEFAULT false,
	pag_form_tipo VARCHAR(35),
	pag_form_definizione TEXT,
	pag_form_impaginazione TEXT,
	pag_validazione_def TEXT,
	pag_trasformazione_tipo VARCHAR(35),
	pag_trasformazione_def TEXT,
	pag_cod_applicazione VARCHAR(35),
	pag_abilitato BOOLEAN NOT NULL DEFAULT false,
	avv_mail_prom_avv_abilitato BOOLEAN NOT NULL DEFAULT false,
	avv_mail_prom_avv_pdf BOOLEAN,
	avv_mail_prom_avv_tipo VARCHAR(35),
	avv_mail_prom_avv_oggetto TEXT,
	avv_mail_prom_avv_messaggio TEXT,
	avv_mail_prom_ric_abilitato BOOLEAN NOT NULL DEFAULT false,
	avv_mail_prom_ric_pdf BOOLEAN,
	avv_mail_prom_ric_tipo VARCHAR(35),
	avv_mail_prom_ric_oggetto TEXT,
	avv_mail_prom_ric_messaggio TEXT,
	avv_mail_prom_ric_eseguiti BOOLEAN,
	avv_mail_prom_scad_abilitato BOOLEAN NOT NULL DEFAULT false,
	avv_mail_prom_scad_preavviso INT,
	avv_mail_prom_scad_tipo VARCHAR(35),
	avv_mail_prom_scad_oggetto TEXT,
	avv_mail_prom_scad_messaggio TEXT,
	visualizzazione_definizione TEXT,
	trac_csv_tipo VARCHAR(35),
	trac_csv_header_risposta TEXT,
	trac_csv_template_richiesta TEXT,
	trac_csv_template_risposta TEXT,
	avv_app_io_prom_avv_abilitato BOOLEAN NOT NULL DEFAULT false,
	avv_app_io_prom_avv_tipo VARCHAR(35),
	avv_app_io_prom_avv_oggetto TEXT,
	avv_app_io_prom_avv_messaggio TEXT,
	avv_app_io_prom_ric_abilitato BOOLEAN NOT NULL DEFAULT false,
	avv_app_io_prom_ric_tipo VARCHAR(35),
	avv_app_io_prom_ric_oggetto TEXT,
	avv_app_io_prom_ric_messaggio TEXT,
	avv_app_io_prom_ric_eseguiti BOOLEAN,
	avv_app_io_prom_scad_abilitato BOOLEAN NOT NULL DEFAULT false,
	avv_app_io_prom_scad_preavviso INT,
	avv_app_io_prom_scad_tipo VARCHAR(35),
	avv_app_io_prom_scad_oggetto TEXT,
	avv_app_io_prom_scad_messaggio TEXT,
	id BIGINT DEFAULT nextval('seq_tipi_versamento') NOT NULL,
	CONSTRAINT unique_tipi_versamento_1 UNIQUE (cod_tipo_versamento),
	CONSTRAINT pk_tipi_versamento PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS tipi_vers_domini
(
	codifica_iuv VARCHAR(4),
	paga_terzi BOOLEAN,
	abilitato BOOLEAN,
	bo_form_tipo VARCHAR(35),
	bo_form_definizione TEXT,
	bo_validazione_def TEXT,
	bo_trasformazione_tipo VARCHAR(35),
	bo_trasformazione_def TEXT,
	bo_cod_applicazione VARCHAR(35),
	bo_abilitato BOOLEAN,
	pag_form_tipo VARCHAR(35),
	pag_form_definizione TEXT,
	pag_form_impaginazione TEXT,
	pag_validazione_def TEXT,
	pag_trasformazione_tipo VARCHAR(35),
	pag_trasformazione_def TEXT,
	pag_cod_applicazione VARCHAR(35),
	pag_abilitato BOOLEAN,
	avv_mail_prom_avv_abilitato BOOLEAN,
	avv_mail_prom_avv_pdf BOOLEAN,
	avv_mail_prom_avv_tipo VARCHAR(35),
	avv_mail_prom_avv_oggetto TEXT,
	avv_mail_prom_avv_messaggio TEXT,
	avv_mail_prom_ric_abilitato BOOLEAN,
	avv_mail_prom_ric_pdf BOOLEAN,
	avv_mail_prom_ric_tipo VARCHAR(35),
	avv_mail_prom_ric_oggetto TEXT,
	avv_mail_prom_ric_messaggio TEXT,
	avv_mail_prom_ric_eseguiti BOOLEAN,
	avv_mail_prom_scad_abilitato BOOLEAN,
	avv_mail_prom_scad_preavviso INT,
	avv_mail_prom_scad_tipo VARCHAR(35),
	avv_mail_prom_scad_oggetto TEXT,
	avv_mail_prom_scad_messaggio TEXT,
	visualizzazione_definizione TEXT,
	trac_csv_tipo VARCHAR(35),
	trac_csv_header_risposta TEXT,
	trac_csv_template_richiesta TEXT,
	trac_csv_template_risposta TEXT,
	app_io_api_key VARCHAR(255),
	avv_app_io_prom_avv_abilitato BOOLEAN,
	avv_app_io_prom_avv_tipo VARCHAR(35),
	avv_app_io_prom_avv_oggetto TEXT,
	avv_app_io_prom_avv_messaggio TEXT,
	avv_app_io_prom_ric_abilitato BOOLEAN,
	avv_app_io_prom_ric_tipo VARCHAR(35),
	avv_app_io_prom_ric_oggetto TEXT,
	avv_app_io_prom_ric_messaggio TEXT,
	avv_app_io_prom_ric_eseguiti BOOLEAN,
	avv_app_io_prom_scad_abilitato BOOLEAN,
	avv_app_io_prom_scad_preavviso INT,
	avv_app_io_prom_scad_tipo VARCHAR(35),
	avv_app_io_prom_scad_oggetto TEXT,
	avv_app_io_prom_scad_messaggio TEXT,
	id BIGINT DEFAULT nextval('seq_tipi_vers_domini') NOT NULL,
	id_dominio BIGINT NOT NULL,
	id_tipo_versamento BIGINT NOT NULL,
	CONSTRAINT unique_tipi_vers_domini_1 UNIQUE (id_dominio, id_tipo_versamento),
	CONSTRAINT fk_tvd_id_tipo_versamento FOREIGN KEY (id_tipo_versamento) REFERENCES tipi_versamento(id),
	CONSTRAINT pk_tipi_vers_domini PRIMARY KEY (id)
);

-- Anagrafica unita' operativa (tabella legacy reale "uo"), non ancora su
-- govpay-common insieme ad Applicazione/Dominio (decisione del lead,
-- 2026-09-26) — vedi Javadoc di classe di UnitaOperativa. Nessuna FK reale
-- verso domini (M4, coerente con documenti/versamenti).
CREATE SEQUENCE IF NOT EXISTS seq_uo start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS uo
(
	cod_uo VARCHAR(35) NOT NULL,
	abilitato BOOLEAN NOT NULL,
	uo_codice_identificativo VARCHAR(35),
	uo_denominazione VARCHAR(70),
	uo_indirizzo VARCHAR(70),
	uo_civico VARCHAR(16),
	uo_cap VARCHAR(16),
	uo_localita VARCHAR(35),
	uo_provincia VARCHAR(35),
	uo_nazione VARCHAR(2),
	uo_area VARCHAR(255),
	uo_url_sito_web VARCHAR(255),
	uo_email VARCHAR(255),
	uo_pec VARCHAR(255),
	uo_tel VARCHAR(255),
	uo_fax VARCHAR(255),
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_uo') NOT NULL,
	id_dominio BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_uo_1 UNIQUE (cod_uo, id_dominio),
	-- fk/pk keys constraints
	CONSTRAINT pk_uo PRIMARY KEY (id)
);

-- DDL reale, mappato a piena fedelta' da IbanAccreditoEntity/TipoTributoEntity/
-- TributoEntity di govpay-common: usate da PosizioneDebitoriaMapper per
-- risolvere VocePendenza.idTributo/idIbanAccredito/idIbanAppoggio (decisione
-- del lead, 2026-09-28) — vedi Javadoc di classe di VocePendenza.
CREATE SEQUENCE IF NOT EXISTS seq_iban_accredito start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS iban_accredito
(
	cod_iban VARCHAR(255) NOT NULL,
	bic_accredito VARCHAR(255),
	postale BOOLEAN NOT NULL,
	abilitato BOOLEAN NOT NULL,
	descrizione VARCHAR(255),
	intestatario VARCHAR(255),
	aut_stampa_poste VARCHAR(255),
	id BIGINT DEFAULT nextval('seq_iban_accredito') NOT NULL,
	id_dominio BIGINT NOT NULL,
	CONSTRAINT unique_iban_accredito_1 UNIQUE (cod_iban, id_dominio),
	CONSTRAINT pk_iban_accredito PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS seq_tipi_tributo start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS tipi_tributo
(
	cod_tributo VARCHAR(255) NOT NULL,
	descrizione VARCHAR(255),
	tipo_contabilita VARCHAR(1),
	cod_contabilita VARCHAR(255),
	id BIGINT DEFAULT nextval('seq_tipi_tributo') NOT NULL,
	CONSTRAINT unique_tipi_tributo_1 UNIQUE (cod_tributo),
	CONSTRAINT pk_tipi_tributo PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS seq_tributi start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS tributi
(
	abilitato BOOLEAN NOT NULL,
	tipo_contabilita VARCHAR(1),
	codice_contabilita VARCHAR(255),
	id BIGINT DEFAULT nextval('seq_tributi') NOT NULL,
	id_dominio BIGINT NOT NULL,
	id_iban_accredito BIGINT,
	id_iban_appoggio BIGINT,
	id_tipo_tributo BIGINT NOT NULL,
	CONSTRAINT unique_tributi_1 UNIQUE (id_dominio, id_tipo_tributo),
	CONSTRAINT fk_trb_id_iban_accredito FOREIGN KEY (id_iban_accredito) REFERENCES iban_accredito(id),
	CONSTRAINT fk_trb_id_iban_appoggio FOREIGN KEY (id_iban_appoggio) REFERENCES iban_accredito(id),
	CONSTRAINT fk_trb_id_tipo_tributo FOREIGN KEY (id_tipo_tributo) REFERENCES tipi_tributo(id),
	CONSTRAINT pk_tributi PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS documenti
(
	cod_documento VARCHAR(35) NOT NULL,
	descrizione VARCHAR(1024) NOT NULL,
	-- aggiunte additive per PosizioneDebitoria (assenti nel legacy)
	data_pubblicazione DATE,
	notifica_send BOOLEAN NOT NULL,
	nav_notifica VARCHAR(18),
	data_ultima_modifica_aca TIMESTAMP,
	data_ultima_comunicazione_aca TIMESTAMP,
	data_creazione TIMESTAMP NOT NULL,
	data_ultimo_aggiornamento TIMESTAMP NOT NULL,
	versione BIGINT NOT NULL DEFAULT 0,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_documenti') NOT NULL,
	id_dominio BIGINT NOT NULL,
	id_unita_operativa BIGINT,
	id_applicazione BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_documenti_1 UNIQUE (cod_documento, id_applicazione, id_dominio),
	CONSTRAINT unique_documenti_applicazione UNIQUE (cod_documento, id_applicazione),
	-- fk/pk keys constraints
	-- Nessuna FK verso applicazioni (M4, coerente con id_dominio sopra): a differenza del
	-- legacy reale, che la ha (fk_doc_id_applicazione) — tolta qui per la stessa ragione
	-- per cui id_dominio non l'ha mai avuta in questa libreria.
	CONSTRAINT pk_documenti PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS opzioni_pagamento
(
	id_opzione_pagamento UUID NOT NULL,
	tipologia VARCHAR(35) NOT NULL,
	giorni INT,
	stato VARCHAR(35) NOT NULL,
	versione BIGINT NOT NULL,
	data_inizio_validita DATE,
	data_scadenza DATE,
	data_creazione TIMESTAMP NOT NULL,
	data_ultimo_aggiornamento TIMESTAMP NOT NULL,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_opzioni_pagamento') NOT NULL,
	id_documento BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_opzioni_pagamento_id_opzione UNIQUE (id_opzione_pagamento),
	-- fk/pk keys constraints
	CONSTRAINT fk_opz_id_documento FOREIGN KEY (id_documento) REFERENCES documenti(id),
	CONSTRAINT pk_opzioni_pagamento PRIMARY KEY (id)
);

-- Tutti i debitori della posizione, incluso il primo (ordine 0) — unica fonte
-- di verita' per v3. Vedi nota di testata sulla sincronizzazione verso
-- versamenti.debitore_*.
CREATE TABLE IF NOT EXISTS soggetti_debitori
(
	ordine INT NOT NULL,
	tipo VARCHAR(1) NOT NULL,
	identificativo VARCHAR(35) NOT NULL,
	anagrafica VARCHAR(70),
	indirizzo VARCHAR(70),
	civico VARCHAR(16),
	cap VARCHAR(16),
	localita VARCHAR(35),
	provincia VARCHAR(35),
	nazione VARCHAR(2),
	email VARCHAR(256),
	cellulare VARCHAR(35),
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_soggetti_debitori') NOT NULL,
	id_documento BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_soggetti_debitori_1 UNIQUE (id_documento, ordine),
	-- fk/pk keys constraints
	CONSTRAINT fk_sgd_id_documento FOREIGN KEY (id_documento) REFERENCES documenti(id),
	CONSTRAINT pk_soggetti_debitori PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS versamenti
(
	cod_versamento_ente VARCHAR(35) NOT NULL,
	nome VARCHAR(35),
	importo_totale DOUBLE PRECISION NOT NULL,
	stato_versamento VARCHAR(35) NOT NULL,
	descrizione_stato VARCHAR(255),
	aggiornabile BOOLEAN NOT NULL,
	data_creazione TIMESTAMP NOT NULL,
	data_validita TIMESTAMP,
	data_scadenza TIMESTAMP,
	data_ora_ultimo_aggiornamento TIMESTAMP NOT NULL,
	causale_versamento VARCHAR(1024),
	debitore_tipo VARCHAR(1),
	debitore_identificativo VARCHAR(35) NOT NULL,
	debitore_anagrafica VARCHAR(70) NOT NULL,
	debitore_indirizzo VARCHAR(70),
	debitore_civico VARCHAR(16),
	debitore_cap VARCHAR(16),
	debitore_localita VARCHAR(35),
	debitore_provincia VARCHAR(35),
	debitore_nazione VARCHAR(2),
	debitore_email VARCHAR(256),
	debitore_telefono VARCHAR(35),
	debitore_cellulare VARCHAR(35),
	debitore_fax VARCHAR(35),
	tassonomia_avviso VARCHAR(35),
	tassonomia VARCHAR(35),
	cod_lotto VARCHAR(35),
	cod_anno_tributario VARCHAR(35),
	dati_allegati TEXT,
	anomalie TEXT,
	iuv_versamento VARCHAR(35),
	numero_avviso VARCHAR(35),
	ack BOOLEAN NOT NULL,
	anomalo BOOLEAN NOT NULL,
	divisione VARCHAR(35),
	direzione VARCHAR(35),
	id_sessione VARCHAR(35),
	data_pagamento TIMESTAMP,
	importo_pagato DOUBLE PRECISION NOT NULL,
	importo_incassato DOUBLE PRECISION NOT NULL,
	stato_pagamento VARCHAR(35) NOT NULL,
	iuv_pagamento VARCHAR(35),
	src_iuv VARCHAR(35),
	src_debitore_identificativo VARCHAR(35) NOT NULL,
	-- cod_rata resta per la sola compatibilita' di lettura v2 (decisione del lead,
	-- 2026-09-25): v3 non lo scrive piu' (resta NULL sulle righe che crea), usa invece
	-- numero_rata sotto, dedicata e non ambigua.
	cod_rata VARCHAR(35),
	-- Colonna aggiunta: posizione della pendenza nell'elenco della sua opzione di
	-- pagamento, per qualunque tipologia (non solo PIANO_RATEALE) — la tipologia vera
	-- vive in opzioni_pagamento.tipologia, qui c'e' solo il numero.
	numero_rata INT NOT NULL,
	tipo VARCHAR(35) NOT NULL,
	data_notifica_avviso TIMESTAMP,
	avviso_notificato BOOLEAN,
	proprieta TEXT,
	data_ultima_modifica_aca TIMESTAMP,
	data_ultima_comunicazione_aca TIMESTAMP,
	send_abilitato BOOLEAN NOT NULL,
	send_importo_totale DOUBLE PRECISION,
	send_data_aggiornamento TIMESTAMP,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_versamenti') NOT NULL,
	id_tipo_versamento_dominio BIGINT NOT NULL,
	id_tipo_versamento BIGINT NOT NULL,
	id_dominio BIGINT NOT NULL,
	id_applicazione BIGINT NOT NULL,
	id_documento BIGINT,
	id_opzione_pagamento BIGINT,
	-- unique constraints
	CONSTRAINT unique_versamenti_1 UNIQUE (cod_versamento_ente, id_applicazione),
	-- fk/pk keys constraints
	-- Nessuna FK verso tipi_vers_domini/tipi_versamento/applicazioni (M4, stessa ragione di
	-- documenti sopra) — presenti invece nel legacy reale.
	CONSTRAINT fk_vrs_id_documento FOREIGN KEY (id_documento) REFERENCES documenti(id),
	CONSTRAINT fk_vrs_id_opzione_pagamento FOREIGN KEY (id_opzione_pagamento) REFERENCES opzioni_pagamento(id),
	CONSTRAINT pk_versamenti PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS singoli_versamenti
(
	cod_singolo_versamento_ente VARCHAR(70) NOT NULL,
	stato_singolo_versamento VARCHAR(35) NOT NULL,
	importo_singolo_versamento DOUBLE PRECISION NOT NULL,
	-- Colonne legacy reali, stesso nome: nessuna colonna aggiunta per
	-- RIFERIMENTO_ENTRATA/ENTRATA/tassonomia (decisione del lead, 2026-09-28) —
	-- vedi nota di classe di VocePendenza. tipo_riferimento non esiste ne' e'
	-- mai esistito nel legacy: e' derivato, non una colonna.
	tipo_bollo VARCHAR(2),
	hash_documento VARCHAR(70),
	provincia_residenza VARCHAR(2),
	descrizione_causale_rpt VARCHAR(140),
	tipo_contabilita VARCHAR(1),
	codice_contabilita VARCHAR(255),
	descrizione VARCHAR(256),
	indice_dati INT NOT NULL,
	contabilita TEXT,
	metadata TEXT,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_singoli_versamenti') NOT NULL,
	id_versamento BIGINT NOT NULL,
	id_tributo BIGINT,
	id_iban_accredito BIGINT,
	id_iban_appoggio BIGINT,
	-- Colonna legacy reale, stesso nome: dominio creditore di questa voce, se
	-- diverso da quello della pendenza/posizione (multi-beneficiario pagoPA) —
	-- mai mappata finora, ripristinata il 2026-09-26 (vedi Javadoc di
	-- VocePendenza.idDominio). Nessuna FK reale verso domini (M4, coerente con
	-- documenti/versamenti sopra).
	id_dominio BIGINT,
	-- unique constraints
	CONSTRAINT unique_sng_id_voce UNIQUE (id_versamento, indice_dati),
	-- fk/pk keys constraints
	CONSTRAINT fk_sng_id_versamento FOREIGN KEY (id_versamento) REFERENCES versamenti(id),
	CONSTRAINT pk_singoli_versamenti PRIMARY KEY (id)
);

-- ---------------------------------------------------------------------------
-- Tabella dei progressivi IUV: NON una tabella nuova di questa libreria, ma la
-- stessa tabella fisica ID_MESSAGGIO_RELATIVO gia' scritta in produzione dal
-- generatore IUV legacy (org.openspcoop2.utils.id.serial.IDSerialGenerator,
-- invocato da IuvBD con protocollo="GovPay"). Vedi ProgressivoIuv per l'analisi
-- completa di questa scelta.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS id_messaggio_relativo
(
	counter BIGINT NOT NULL,
	protocollo VARCHAR(255) NOT NULL,
	info_associata VARCHAR(255) NOT NULL,
	ora_registrazione TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
	CONSTRAINT pk_id_messaggio_relativo PRIMARY KEY (protocollo, info_associata)
);

-- ---------------------------------------------------------------------------
-- Anagrafica di govpay-common necessaria a GeneratoreIuvStandard: stesso DDL di
-- test di govpay-common (src/test/resources/schema.sql), non una definizione
-- indipendente. Tutte le tabelle di it.govpay.common.entity sono richieste
-- perche' @EntityScan(basePackageClasses = DominioEntity.class) in
-- PendenzeTestApplication scansiona l'intero package, non le singole classi —
-- incluso it.govpay.common.entity.batch (le tre tabelle standard di Spring
-- Batch qui sotto), che questa libreria non usa ma deve comunque validare.
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS batch_job_instance (
    job_instance_id BIGINT PRIMARY KEY,
    job_name VARCHAR(100) NOT NULL,
    job_key VARCHAR(32) NOT NULL,
    version BIGINT
);

CREATE TABLE IF NOT EXISTS batch_job_execution (
    job_execution_id BIGINT PRIMARY KEY,
    job_instance_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    status VARCHAR(10),
    exit_message VARCHAR(2500),
    version BIGINT,
    exit_code VARCHAR(2500),
    last_updated TIMESTAMP,
    CONSTRAINT fk_batch_job_execution_instance FOREIGN KEY (job_instance_id) REFERENCES batch_job_instance(job_instance_id)
);

CREATE TABLE IF NOT EXISTS batch_job_execution_params (
    job_execution_id BIGINT NOT NULL,
    parameter_name VARCHAR(100) NOT NULL,
    parameter_type VARCHAR(100) NOT NULL,
    parameter_value VARCHAR(2500),
    identifying CHAR(1) NOT NULL,
    CONSTRAINT pk_batch_job_execution_params PRIMARY KEY (job_execution_id, parameter_name),
    CONSTRAINT fk_batch_job_execution_params_exec FOREIGN KEY (job_execution_id) REFERENCES batch_job_execution(job_execution_id)
);

CREATE TABLE IF NOT EXISTS connettori (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cod_connettore VARCHAR(255) NOT NULL,
    cod_proprieta VARCHAR(255) NOT NULL,
    valore VARCHAR(255) NOT NULL,
    CONSTRAINT uk_connettori UNIQUE (cod_connettore, cod_proprieta)
);

CREATE TABLE IF NOT EXISTS configurazione (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    nome VARCHAR(255) NOT NULL,
    -- ConfigurazioneEntity.valore usa @JdbcTypeCode(SqlTypes.LONGVARCHAR), non @Lob: con
    -- ddl-auto=validate un CLOB (usato invece dallo schema.sql di govpay-common, mai
    -- validato li' perche' i loro test usano create-drop) viene rifiutato.
    valore VARCHAR,
    CONSTRAINT uk_configurazione_nome UNIQUE (nome)
);

CREATE TABLE IF NOT EXISTS intermediari (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cod_intermediario VARCHAR(255) NOT NULL,
    cod_connettore_pdd VARCHAR(255),
    cod_connettore_recupero_rt VARCHAR(255),
    cod_connettore_aca VARCHAR(255),
    cod_connettore_gpd VARCHAR(255),
    cod_connettore_fr VARCHAR(255),
    cod_connettore_backoffice_ec VARCHAR(255),
    denominazione VARCHAR(255),
    principal VARCHAR(255),
    principal_originale VARCHAR(255),
    abilitato BOOLEAN NOT NULL,
    CONSTRAINT uk_intermediari_cod UNIQUE (cod_intermediario)
);

CREATE TABLE IF NOT EXISTS stazioni (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cod_stazione VARCHAR(255) NOT NULL,
    password VARCHAR(255),
    abilitato BOOLEAN NOT NULL,
    application_code INTEGER,
    versione VARCHAR(255),
    id_intermediario BIGINT NOT NULL,
    CONSTRAINT uk_stazioni_cod UNIQUE (cod_stazione),
    CONSTRAINT fk_stazioni_intermediario FOREIGN KEY (id_intermediario) REFERENCES intermediari(id)
);

CREATE SEQUENCE IF NOT EXISTS seq_utenze start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS utenze (
    id BIGINT PRIMARY KEY,
    principal VARCHAR(4000) NOT NULL,
    principal_originale VARCHAR(4000) NOT NULL,
    abilitato BOOLEAN NOT NULL DEFAULT true,
    autorizzazione_domini_star BOOLEAN NOT NULL DEFAULT false,
    autorizzazione_tipi_vers_star BOOLEAN NOT NULL DEFAULT false,
    ruoli VARCHAR(512),
    password VARCHAR(255),
    CONSTRAINT uk_utenze_principal UNIQUE (principal)
);

CREATE SEQUENCE IF NOT EXISTS seq_acl start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS acl (
    id BIGINT PRIMARY KEY,
    ruolo VARCHAR(255),
    servizio VARCHAR(255) NOT NULL,
    diritti VARCHAR(255) NOT NULL,
    id_utenza BIGINT,
    CONSTRAINT fk_acl_id_utenza FOREIGN KEY (id_utenza) REFERENCES utenze(id)
);

CREATE SEQUENCE IF NOT EXISTS seq_utenze_tipo_vers start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS utenze_tipo_vers (
    id BIGINT PRIMARY KEY,
    id_utenza BIGINT NOT NULL,
    id_tipo_versamento BIGINT NOT NULL,
    CONSTRAINT fk_utv_id_utenza FOREIGN KEY (id_utenza) REFERENCES utenze(id),
    CONSTRAINT fk_utv_id_tipo_versamento FOREIGN KEY (id_tipo_versamento) REFERENCES tipi_versamento(id)
);

-- Riga con id_dominio oppure id_uo (mai entrambi, M4, nessuna FK reale verso
-- uo per lo stesso motivo della tabella uo stessa) — vedi Javadoc di classe
-- di UtenzaDominioEntity.
CREATE SEQUENCE IF NOT EXISTS seq_utenze_domini start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS utenze_domini (
    id BIGINT PRIMARY KEY,
    id_utenza BIGINT NOT NULL,
    id_dominio BIGINT,
    id_uo BIGINT,
    CONSTRAINT fk_nzd_id_utenza FOREIGN KEY (id_utenza) REFERENCES utenze(id)
);

CREATE TABLE IF NOT EXISTS applicazioni (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cod_applicazione VARCHAR(35) NOT NULL,
    auto_iuv BOOLEAN NOT NULL,
    firma_ricevuta VARCHAR(1) NOT NULL,
    trusted BOOLEAN NOT NULL,
    cod_connettore_integrazione VARCHAR(255),
    cod_applicazione_iuv VARCHAR(3),
    reg_exp VARCHAR(1024),
    id_utenza BIGINT NOT NULL,
    CONSTRAINT uk_applicazioni_cod UNIQUE (cod_applicazione),
    CONSTRAINT uk_applicazioni_id_utenza UNIQUE (id_utenza)
);

CREATE TABLE IF NOT EXISTS domini (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cod_dominio VARCHAR(35) NOT NULL,
    gln VARCHAR(35),
    abilitato BOOLEAN NOT NULL,
    ragione_sociale VARCHAR(70) NOT NULL,
    aux_digit INT NOT NULL DEFAULT 0,
    iuv_prefix VARCHAR(255),
    segregation_code INT,
    -- Mappata da DominioLogoEntity (proiezione della stessa tabella per il solo BLOB del
    -- logo), non da DominioEntity: serve comunque perche' e' nello stesso package scansionato.
    logo VARBINARY(255),
    cbill VARCHAR(255),
    aut_stampa_poste VARCHAR(255),
    cod_connettore_my_pivot VARCHAR(255),
    cod_connettore_secim VARCHAR(255),
    cod_connettore_gov_pay VARCHAR(255),
    cod_connettore_hyper_sic_apk VARCHAR(255),
    intermediato BOOLEAN NOT NULL,
    tassonomia_pago_pa VARCHAR(35),
    scarica_fr BOOLEAN NOT NULL,
    id_stazione BIGINT,
    id_applicazione_default BIGINT,
    CONSTRAINT uk_domini_cod UNIQUE (cod_dominio),
    CONSTRAINT fk_domini_stazione FOREIGN KEY (id_stazione) REFERENCES stazioni(id)
);

-- ---------------------------------------------------------------------------
-- Rpt/pagamenti/fr/rendicontazioni: riuso diretto delle tabelle legacy (decisione
-- del lead, 2026-09-25 — fase 2, stesso principio gia' applicato a
-- documenti/versamenti/singoli_versamenti). Fuori dall'aggregato PosizioneDebitoria
-- (decisione del lead, 2026-09-24): id_versamento su rpt e' una FK piatta (non una
-- relazione JPA), per non ripetere il problema del vecchio "dettaglio pendenza"
-- (centinaia di query per una singola lettura). Nessun vincolo FK reale verso
-- l'anagrafica esterna di govpay-common (M4): fr.id_dominio ha in produzione una FK
-- verso domini(id), qui omessa per coerenza con documenti/versamenti.
-- ---------------------------------------------------------------------------

CREATE SEQUENCE IF NOT EXISTS seq_rpt start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS rpt
(
	iuv VARCHAR(35) NOT NULL,
	ccp VARCHAR(35) NOT NULL,
	cod_dominio VARCHAR(35) NOT NULL,
	cod_stazione VARCHAR(35),
	cod_msg_richiesta VARCHAR(35),
	tipo_versamento VARCHAR(35),
	stato VARCHAR(35),
	descrizione_stato TEXT,
	xml_rt BYTEA,
	xml_rpt BYTEA,
	data_msg_ricevuta TIMESTAMP,
	data_msg_richiesta TIMESTAMP,
	cod_esito_pagamento INT,
	versione VARCHAR(35) NOT NULL,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_rpt') NOT NULL,
	id_versamento BIGINT NOT NULL,
	-- unique constraints
	CONSTRAINT unique_rpt_id_transazione UNIQUE (iuv, ccp, cod_dominio),
	-- fk/pk keys constraints
	CONSTRAINT fk_rpt_id_versamento FOREIGN KEY (id_versamento) REFERENCES versamenti(id),
	CONSTRAINT pk_rpt PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS seq_allegati start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

-- nome/tipo/descrizione/data_creazione sono mappati (lettura e scrittura) da AllegatoEntity
-- (it.govpay.pendenze.v2.pendenza, govpay-pendenze-api): raw_contenuto resta deliberatamente
-- non mappato (gap noto, vedi Javadoc di AllegatoEntity) -- in produzione (PostgreSQL) e' un
-- OID, non un BYTEA come qui in H2, quindi nessun mapping Hibernate semplice sarebbe fedele
-- a entrambi gli ambienti.
CREATE TABLE IF NOT EXISTS allegati
(
	nome VARCHAR(255) NOT NULL,
	tipo VARCHAR(255),
	descrizione VARCHAR(255),
	data_creazione TIMESTAMP NOT NULL,
	raw_contenuto BYTEA,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_allegati') NOT NULL,
	id_versamento BIGINT NOT NULL,
	-- fk/pk keys constraints
	CONSTRAINT fk_all_id_versamento FOREIGN KEY (id_versamento) REFERENCES versamenti(id),
	CONSTRAINT pk_allegati PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS seq_pagamenti start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS pagamenti
(
	cod_dominio VARCHAR(35) NOT NULL,
	iuv VARCHAR(35) NOT NULL,
	iur VARCHAR(35) NOT NULL,
	indice_dati INT NOT NULL DEFAULT 1,
	importo_pagato DOUBLE PRECISION NOT NULL,
	data_acquisizione TIMESTAMP NOT NULL,
	data_pagamento TIMESTAMP NOT NULL,
	stato VARCHAR(35),
	tipo VARCHAR(35) NOT NULL,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_pagamenti') NOT NULL,
	id_rpt BIGINT,
	-- unique constraints
	CONSTRAINT unique_pag_id_riscossione UNIQUE (cod_dominio, iuv, iur, indice_dati),
	-- fk/pk keys constraints
	CONSTRAINT fk_pag_id_rpt FOREIGN KEY (id_rpt) REFERENCES rpt(id),
	CONSTRAINT pk_pagamenti PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS seq_fr start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS fr
(
	id_dominio BIGINT NOT NULL,
	cod_dominio VARCHAR(35) NOT NULL,
	cod_flusso VARCHAR(35) NOT NULL,
	data_ora_flusso TIMESTAMP NOT NULL,
	iur VARCHAR(35) NOT NULL,
	data_acquisizione TIMESTAMP NOT NULL,
	data_regolamento TIMESTAMP,
	cod_psp VARCHAR(35) NOT NULL,
	cod_bic_riversamento VARCHAR(35),
	numero_pagamenti BIGINT,
	importo_totale_pagamenti DOUBLE PRECISION,
	stato VARCHAR(35) NOT NULL,
	revisione BIGINT,
	obsoleto BOOLEAN NOT NULL,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_fr') NOT NULL,
	-- unique constraints
	CONSTRAINT unique_fr_1 UNIQUE (id_dominio, cod_flusso, data_ora_flusso),
	CONSTRAINT unique_fr_2 UNIQUE (id_dominio, cod_flusso, cod_psp, revisione),
	-- fk/pk keys constraints
	CONSTRAINT pk_fr PRIMARY KEY (id)
);

CREATE SEQUENCE IF NOT EXISTS seq_rendicontazioni start 1 increment 1 maxvalue 9223372036854775807 minvalue 1 cache 1 NO CYCLE;

CREATE TABLE IF NOT EXISTS rendicontazioni
(
	iuv VARCHAR(35) NOT NULL,
	iur VARCHAR(35) NOT NULL,
	indice_dati INT,
	importo_pagato DOUBLE PRECISION,
	esito INT,
	data TIMESTAMP,
	stato VARCHAR(35) NOT NULL,
	-- fk/pk columns
	id BIGINT DEFAULT nextval('seq_rendicontazioni') NOT NULL,
	id_fr BIGINT NOT NULL,
	id_singolo_versamento BIGINT,
	-- fk/pk keys constraints
	CONSTRAINT fk_rnd_id_fr FOREIGN KEY (id_fr) REFERENCES fr(id),
	CONSTRAINT fk_rnd_id_singolo_versamento FOREIGN KEY (id_singolo_versamento) REFERENCES singoli_versamenti(id),
	CONSTRAINT pk_rendicontazioni PRIMARY KEY (id)
);
