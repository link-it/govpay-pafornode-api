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
import org.springframework.ws.wsdl.wsdl11.SimpleWsdl11Definition;
import org.springframework.ws.wsdl.wsdl11.Wsdl11Definition;

import java.util.List;

import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentRes;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentV2Request;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaGetPaymentV2Response;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaVerifyPaymentNoticeReq;
import it.gov.pagopa.pagopa_api.pa.pafornode.PaVerifyPaymentNoticeRes;
import it.govpay.pafornode.gde.OperationIdEndpointInterceptor;

/**
 * Espone {@code paForNode.wsdl} (gia' presente in risorse, pubblicato da pagoPA) cosi' com'e':
 * {@link SimpleWsdl11Definition} serve il file esistente, non lo ricostruisce da un singolo
 * XSD come farebbe {@code DefaultWsdl11Definition} — il contratto deve restare bit-per-bit
 * quello pubblicato, il Nodo dei Pagamenti lo valida. Unica differenza dal file di pagoPA:
 * il {@code soap:address} punta al path reale del servizio ({@code /ws}); host e porta sono
 * riscritti con quelli della richiesta ({@code transformWsdlLocations}).
 *
 * <p>Il bean {@code paForNode} e' esposto su {@code /ws/paForNode.wsdl} (nome del bean =
 * nome del file, per convenzione di {@link MessageDispatcherServlet}).</p>
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
		return new ServletRegistrationBean<>(servlet, "/ws/*");
	}

	@Bean(name = "paForNode")
	public Wsdl11Definition paForNodeWsdl() {
		SimpleWsdl11Definition wsdl11Definition = new SimpleWsdl11Definition();
		wsdl11Definition.setWsdl(new ClassPathResource("xsd/paForNode.wsdl"));
		return wsdl11Definition;
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
		// setClassesToBeBound, non setContextPaths: le classi generate da XJC per
		// paForNode.xsd non hanno @XmlRootElement (il root e' dichiarato nello schema solo
		// come <element>, non <complexType> con binding diretto) — Jaxb2Marshaller#supports
		// riconosce una classe come marshallabile anche senza @XmlRootElement solo se e'
		// elencata esplicitamente qui, non per il solo fatto di stare nel context path.
		marshaller.setClassesToBeBound(PaVerifyPaymentNoticeReq.class, PaVerifyPaymentNoticeRes.class,
				PaGetPaymentReq.class, PaGetPaymentRes.class, PaGetPaymentV2Request.class,
				PaGetPaymentV2Response.class);
		// Jaxb2Marshaller#supports(Class) di default rifiuta una classe priva di
		// @XmlRootElement PRIMA ancora di controllare classesToBeBound (vedi
		// Jaxb2Marshaller#supportsInternal, verificato sul sorgente 7.0.10): e' questo,
		// non la configurazione del Marshaller in se', la causa di "No adapter for
		// endpoint" — classesToBeBound non basta se checkForXmlRootElement resta true.
		marshaller.setCheckForXmlRootElement(false);
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
