package it.govpay.pafornode.config;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.ws.transport.http.WsdlDefinitionHandlerAdapter;

import it.govpay.pafornode.gde.SoapTransportUtils;

/**
 * Issue #10, finding #3 di revisione (2026-11-05): il file statico {@code paForNode.wsdl} ha
 * un {@code soap:address} fisso, assoluto, sul path del canale Nodo ({@code
 * http://.../ws}) — il comportamento di default di {@code WsdlDefinitionHandlerAdapter
 * #transformLocation} (non sovrascritto per un location assoluto) riscrive solo
 * schema/host/porta, mai il path originale NE' il context path (quest'ultimo viene
 * anteposto solo per un location RELATIVO, verificato sui sorgenti di {@code
 * LocationTransformerObjectSupport} — il nostro e' assoluto). Un client che scarica il WSDL
 * dal canale interno (via {@code ?wsdl} o {@code /internal/PagamentiTelematiciCCPservice
 * .wsdl}) riceveva quindi un {@code soap:address} che punta comunque a {@code /ws} — dove,
 * dopo il fix del finding #2 (canale e metodo di autenticazione devono corrispondere), le
 * sue credenziali Basic/LDAP verrebbero rifiutate.
 *
 * <p>Qui si applica PRIMA la trasformazione standard (schema/host/porta,
 * {@code super.transformLocation}), poi, SOLO se il risultato termina esattamente con
 * {@code /ws}, si ricostruisce il path completo inserendo SEMPRE il context path (assente
 * per default dalla trasformazione standard su un location assoluto come il nostro, su
 * ENTRAMBI i canali — non solo quando si corregge verso il canale interno) seguito dal path
 * giusto per il canale da cui il WSDL e' stato richiesto — cosi' il WSDL scaricato da
 * ciascun canale indica sempre se stesso come indirizzo del servizio, context path incluso,
 * un solo file statico, nessuna duplicazione.
 *
 * <p>Bug di revisione corretto 2026-11-05 (due giri): la prima versione sostituiva il solo
 * suffisso senza inserire il context path, perso con un deployment non alla radice (es.
 * {@code server.servlet.context-path=/pafornode}) — invisibile nei test, che deployano
 * sempre alla radice. La seconda versione inseriva il context path solo quando correggeva
 * verso il canale interno, lasciando il canale Nodo (nessuna sostituzione di path, solo
 * schema/host/porta dalla trasformazione standard) ancora privo di context path — stesso
 * difetto, meta' corretto. Ora il context path si inserisce SEMPRE, per entrambi i canali.</p>
 *
 * <p>Registrato con il nome di bean che {@code MessageDispatcherServlet} si aspetta
 * ({@code wsdlDefinitionHandlerAdapter}, vedi {@code WsConfig}) — la libreria lo recupera per
 * nome durante l'inizializzazione, non serve altro wiring.</p>
 */
public class PaForNodeWsdlDefinitionHandlerAdapter extends WsdlDefinitionHandlerAdapter {

	private static final String PATH_NODO = "/ws";

	@Override
	protected String transformLocation(String location, HttpServletRequest request) {
		String transformed = super.transformLocation(location, request);
		if (!transformed.endsWith(PATH_NODO)) {
			return transformed;
		}
		String schemaHostPorta = transformed.substring(0, transformed.length() - PATH_NODO.length());
		String path = SoapTransportUtils.isPathCanaleInterno(request) ? SoapTransportUtils.PATH_CANALE_INTERNO
				: PATH_NODO;
		return schemaHostPorta + request.getContextPath() + path;
	}
}
