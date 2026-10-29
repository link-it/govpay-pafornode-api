package it.govpay.pafornode.rpt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import javax.xml.transform.stream.StreamSource;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import it.gov.pagopa.pagopa_api.pa.pafornode.CtPaymentPA;
import it.gov.pagopa.pagopa_api.pa.pafornode.ObjectFactory;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentRes;
import it.gov.pagopa.pagopa_api.xsd.common_types.v1_0.StOutcome;
import it.govpay.common.entity.DominioEntity;
import it.govpay.common.entity.StazioneEntity;
import it.govpay.pendenze.entity.Pendenza;
import it.govpay.pendenze.entity.Rpt;
import it.govpay.pendenze.repository.PendenzaRepository;
import it.govpay.pendenze.repository.RptRepository;

/**
 * Attivazione di una RPT (analisi completa del legacy, {@code CtPaymentPABuilder}/
 * {@code it.govpay.core.business.Rpt#annullaRPTPendenti}, nel design doc): annulla le RPT
 * pendenti sulla stessa coppia dominio/iuv, poi persiste la nuova RPT con l'XML della
 * risposta serializzato (per {@code paGetPayment}, V1 — l'omologo V2 serializza
 * {@code PaGetPaymentV2Response}, non ancora implementato).
 */
@Service
public class RptAttivazioneService {

	/** Codifica legacy di {@code ModelloPagamento.ATTIVATO_PRESSO_PSP}, unico modello gestito. */
	public static final String MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP = "4";

	private static final String VERSIONE_SANP_240 = "SANP_240";

	private final PendenzaRepository pendenzaRepository;
	private final RptRepository rptRepository;
	private final Clock clock;
	private final ObjectFactory objectFactory = new ObjectFactory();
	private final JAXBContext jaxbContext;

	public RptAttivazioneService(PendenzaRepository pendenzaRepository, RptRepository rptRepository, Clock clock) {
		this.pendenzaRepository = pendenzaRepository;
		this.rptRepository = rptRepository;
		this.clock = clock;
		try {
			this.jaxbContext = JAXBContext.newInstance(PaGetPaymentRes.class);
		} catch (JAXBException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Annulla le RPT pendenti sulla stessa coppia dominio/iuv e persiste la nuova RPT
	 * attivata (stato finale {@code RPT_ACCETTATA_NODO}, come nel legacy), con l'XML della
	 * risposta {@code paGetPayment} come corpo.
	 *
	 * <p>Acquisisce prima il lock sulla pendenza stessa ({@code SELECT ... FOR UPDATE}):
	 * il lock sulle sole RPT pendenti (in {@link #annullaPendenti}) non basta a serializzare
	 * due attivazioni concorrenti quando non ce n'e' ancora nessuna — entrambe troverebbero
	 * un risultato vuoto e creerebbero due RPT attive sulla stessa pendenza.</p>
	 */
	@Transactional
	public Rpt attiva(Pendenza pendenza, DominioEntity dominio, StazioneEntity stazione, String ccp,
			CtPaymentPA ctPayment) {
		pendenzaRepository.findByIdForUpdate(pendenza.getId())
				.orElseThrow(() -> new IllegalStateException("Pendenza [" + pendenza.getId() + "] non trovata."));

		annullaPendenti(dominio.getCodDominio(), pendenza.getIuv());

		OffsetDateTime adesso = OffsetDateTime.now(clock);
		Rpt rpt = new Rpt();
		rpt.setIdVersamento(pendenza.getId());
		rpt.setIuv(pendenza.getIuv());
		rpt.setIur(ccp);
		rpt.setCodDominio(dominio.getCodDominio());
		rpt.setCodStazione(stazione.getCodStazione());
		rpt.setCodMsgRichiesta(UUID.randomUUID().toString().replace("-", ""));
		rpt.setVersione(VERSIONE_SANP_240);
		// Stato finale di una attivazione riuscita, non l'intermedio RPT_ATTIVATA: nel legacy
		// la RPT passa a RPT_ACCETTATA_NODO prima del commit (qui non c'e' un invio asincrono
		// separato da attendere, l'intera operazione e' nella stessa transazione).
		rpt.setStato(StatoRpt.RPT_ACCETTATA_NODO.name());
		rpt.setDataMsgRichiesta(adesso);
		rpt.setDataAggiornamentoStato(adesso);
		rpt.setModelloPagamento(MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP);
		rpt.setXmlRpt(serializza(ctPayment));
		return rptRepository.save(rpt);
	}

	/**
	 * Rilegge il {@code CtPaymentPA} dall'XML appena persistito su {@link Rpt#getXmlRpt()},
	 * invece di riusare l'oggetto in memoria passato ad {@link #attiva} — stessa garanzia di
	 * coerenza-con-quanto-scritto del legacy ({@code JaxbUtils.toPaGetPaymentResRPT}).
	 */
	public CtPaymentPA deserializzaDati(Rpt rpt) {
		try {
			Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
			// Le classi generate da paForNode.xsd non hanno @XmlRootElement (il root e'
			// dichiarato nello schema solo come <element>, non <complexType> con binding
			// diretto, stesso motivo di Jaxb2Marshaller#setCheckForXmlRootElement in
			// WsConfig): la forma a 1 argomento di unmarshal() non riconoscerebbe l'elemento
			// come un root valido ("elementi previsti sono (none)"). Specificare la classe
			// attesa fa risolvere l'elemento tramite l'@XmlElementDecl di ObjectFactory.
			JAXBElement<PaGetPaymentRes> risultato = unmarshaller
					.unmarshal(new StreamSource(new ByteArrayInputStream(rpt.getXmlRpt())), PaGetPaymentRes.class);
			return risultato.getValue().getData();
		} catch (JAXBException e) {
			throw new IllegalStateException(e);
		}
	}

	private void annullaPendenti(String codDominio, String iuv) {
		List<Rpt> pendenti = rptRepository.findByCodDominioAndIuvAndStatoInAndModelloPagamento(codDominio, iuv,
				StatoRpt.STATI_PENDENTI, MODELLO_PAGAMENTO_ATTIVATO_PRESSO_PSP);
		if (pendenti.isEmpty()) {
			return;
		}
		OffsetDateTime adesso = OffsetDateTime.now(clock);
		for (Rpt rpt : pendenti) {
			rpt.setStato(StatoRpt.RPT_ANNULLATA.name());
			rpt.setDescrizioneStato("Ricevuta richiesta di un nuovo tentativo di pagamento");
			rpt.setDataAggiornamentoStato(adesso);
		}
		rptRepository.saveAll(pendenti);
	}

	private byte[] serializza(CtPaymentPA ctPayment) {
		PaGetPaymentRes risposta = new PaGetPaymentRes();
		risposta.setOutcome(StOutcome.OK);
		risposta.setData(ctPayment);
		try {
			Marshaller marshaller = jaxbContext.createMarshaller();
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			marshaller.marshal(objectFactory.createPaGetPaymentRes(risposta), output);
			return output.toByteArray();
		} catch (JAXBException e) {
			throw new IllegalStateException(e);
		}
	}
}
