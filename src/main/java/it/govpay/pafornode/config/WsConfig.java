package it.govpay.pafornode.config;

import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.endpoint.adapter.method.MethodArgumentResolver;
import org.springframework.ws.server.endpoint.adapter.method.MethodReturnValueHandler;
import org.springframework.ws.server.endpoint.adapter.method.MarshallingPayloadMethodProcessor;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.transport.http.WsdlDefinitionHandlerAdapter;
import org.springframework.ws.wsdl.wsdl11.SimpleWsdl11Definition;
import org.springframework.ws.wsdl.wsdl11.Wsdl11Definition;

import java.util.List;

import it.govpay.pafornode.gde.OperationIdEndpointInterceptor;
import it.govpay.pafornode.gde.SoapTransportUtils;

/**
 * Espone {@code paForNode.wsdl} (gia' presente in risorse, pubblicato da pagoPA) cosi' com'e':
 * {@link SimpleWsdl11Definition} serve il file esistente, non lo ricostruisce da un singolo
 * XSD come farebbe {@code DefaultWsdl11Definition} — il contratto deve restare bit-per-bit
 * quello pubblicato, il Nodo dei Pagamenti lo valida. Unica differenza dal file di pagoPA:
 * il {@code soap:address} punta al path reale del servizio ({@code /ws}); host e porta sono
 * riscritti con quelli della richiesta ({@code transformWsdlLocations}).
 *
 * <p>Il bean {@code paForNode} e' esposto su {@code /ws/paForNode.wsdl} (nome del bean =
 * nome del file, per convenzione di {@link MessageDispatcherServlet}) — e, da revisione
 * dell'issue #10 (finding #4, 2026-11-05), anche su {@code GET <path>?wsdl} su entrambi i
 * canali (vedi {@code PaForNodeMessageDispatcherServlet#getWsdlDefinition}), la convenzione
 * che pagoPA/l'issue richiedono davvero — pubblico, non autenticato (vedi {@code
 * application.properties}, {@code govpay.auth.public-chain}).</p>
 *
 * <p>Issue #10: lo stesso servlet risponde anche su {@code /internal/PagamentiTelematiciCCPservice}
 * (canale interno, govpay-rt-batch/govpay-console-api — path esatto richiesto dall'issue, non
 * un'estensione di {@code /ws}) — un secondo URL pattern sullo STESSO bean, non una seconda
 * configurazione: Spring-WS dispatcha per payload-root (SOAP action), non per path, quindi gli
 * stessi {@code @Endpoint} rispondono su entrambi senza duplicare marshaller/interceptor/WSDL.
 * Il canale Nodo resta deliberatamente su {@code /ws} (non rinominato in {@code
 * /PagamentiTelematiciCCPservice} come il testo dell'issue vorrebbe: e' un path GIA' in uso da
 * questo microservizio, non introdotto da questa issue — rinominarlo e' un cambio di contratto
 * da decidere con chi gestisce la configurazione lato Nodo/pagoPA, non da assumere qui; punto
 * aperto). L'autenticazione (BASIC/LDAP vs SSL/SSL_HEADER/HEADER) resta l'unica differenza fra
 * i due canali per le POST, verificata anche a livello di business da {@code
 * PaSendRTService#verificaCanale} (il path deve corrispondere al metodo usato) — vedi {@code
 * PaForNodePrincipalLoader}.</p>
 */
@EnableWs
@Configuration
public class WsConfig implements WsConfigurer {

	private final OperationIdEndpointInterceptor operationIdEndpointInterceptor;

	public WsConfig(OperationIdEndpointInterceptor operationIdEndpointInterceptor) {
		this.operationIdEndpointInterceptor = operationIdEndpointInterceptor;
	}

	@Bean
	public ServletRegistrationBean<MessageDispatcherServlet> messageDispatcherServlet(
			ApplicationContext applicationContext) {
		// Variante che risponde 415/400, non 500, alle richieste non SOAP
		MessageDispatcherServlet servlet = new PaForNodeMessageDispatcherServlet();
		servlet.setApplicationContext(applicationContext);
		servlet.setTransformWsdlLocations(true);
		return new ServletRegistrationBean<>(servlet, "/ws/*", SoapTransportUtils.PATH_CANALE_INTERNO);
	}

	@Bean(name = "paForNode")
	public Wsdl11Definition paForNodeWsdl() {
		SimpleWsdl11Definition wsdl11Definition = new SimpleWsdl11Definition();
		wsdl11Definition.setWsdl(new ClassPathResource("xsd/paForNode.wsdl"));
		return wsdl11Definition;
	}

	/**
	 * Nome di bean richiesto da {@code MessageDispatcherServlet} ({@code
	 * DEFAULT_WSDL_DEFINITION_HANDLER_ADAPTER_BEAN_NAME}, verificato sui sorgenti della
	 * libreria): lo recupera per nome durante l'inizializzazione (altrimenti ne crea uno di
	 * default) e gli applica da solo {@code transformLocations}/{@code
	 * transformSchemaLocations} — non serve impostarli qui. Vedi Javadoc di {@link
	 * PaForNodeWsdlDefinitionHandlerAdapter} (issue #10, finding #3 di revisione).
	 */
	@Bean(name = "wsdlDefinitionHandlerAdapter")
	public WsdlDefinitionHandlerAdapter wsdlDefinitionHandlerAdapter() {
		return new PaForNodeWsdlDefinitionHandlerAdapter();
	}

	/**
	 * Marshalling JAXB dei payload delle operazioni paForNode: senza un {@code Marshaller}/
	 * {@code Unmarshaller} nel contesto, {@code @EnableWs} non registra un adattatore capace
	 * di risolvere parametri/valori di ritorno {@code @RequestPayload}/{@code @ResponsePayload}
	 * tipizzati sulle classi generate da XJC (fallisce con "No adapter for endpoint").
	 */
	@Bean
	public Jaxb2Marshaller marshaller() {
		Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
		// Classi di paForNode.xsd generate in govpay-common-pendenze (xjc:simple, con
		// @XmlRootElement): basta il package, l'ObjectFactory registra tutte le operazioni.
		marshaller.setContextPath("it.gov.pagopa.pagopa_api.pa.pafornode");
		return marshaller;
	}

	/**
	 * Registra esplicitamente il processore di marshalling: {@code @EnableWs} non lo deduce
	 * da solo da un {@code Marshaller}/{@code Unmarshaller} presente nel contesto, va aggiunto
	 * tramite {@link WsConfigurer} (altrimenti "No adapter for endpoint" su ogni metodo con
	 * parametri/valori di ritorno {@code @RequestPayload}/{@code @ResponsePayload}).
	 */
	@Override
	public void addArgumentResolvers(List<MethodArgumentResolver> argumentResolvers) {
		argumentResolvers.add(new MarshallingPayloadMethodProcessor(marshaller()));
	}

	@Override
	public void addReturnValueHandlers(List<MethodReturnValueHandler> returnValueHandlers) {
		returnValueHandlers.add(new MarshallingPayloadMethodProcessor(marshaller()));
	}

	/**
	 * {@link OperationIdEndpointInterceptor} popola gli attributi di richiesta che
	 * {@code it.govpay.pafornode.gde.GdeEventFilter} legge dopo l'esecuzione della catena —
	 * stesso ruolo di un {@code HandlerInterceptor} Spring MVC, qui per {@code @EnableWs}.
	 */
	@Override
	public void addInterceptors(List<EndpointInterceptor> interceptors) {
		interceptors.add(operationIdEndpointInterceptor);
	}
}
