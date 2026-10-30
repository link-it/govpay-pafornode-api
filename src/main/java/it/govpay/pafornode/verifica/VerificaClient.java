package it.govpay.pafornode.verifica;

import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import it.govpay.common.client.model.Connettore;
import it.govpay.common.client.service.ConnettoreService;
import it.govpay.ec.client.GovPayEcApiClient;
import it.govpay.ec.client.api.VerificaPendenzeApi;
import it.govpay.ec.client.beans.PendenzaVerificata;

/**
 * Client della sola operazione "Verifica" {@code getAvviso} (govpay-api-ec v2, §3bis/
 * §3quinquies del design doc). Usa {@link GovPayEcApiClient} (non il semplice {@code
 * ApiClient} generato) perche' i payload reali del gestionale non portano il discriminatore
 * {@code tipoRiferimento} che il generatore altrimenti richiederebbe in deserializzazione.
 *
 * <p>Stesso principio di {@code it.govpay.pafornode.send.SendClient}: usa {@link
 * ConnettoreService} (govpay-common) per autenticazione, TLS, timeout, correlation ID,
 * cattura GDE e observation del connettore configurato sull'applicazione.
 */
@Service
public class VerificaClient {

	private final ConnettoreService connettoreService;

	public VerificaClient(ConnettoreService connettoreService) {
		this.connettoreService = connettoreService;
	}

	public PendenzaVerificata getAvviso(String codConnettoreVerifica, String idDominio, String numeroAvviso) {
		Assert.hasText(codConnettoreVerifica, "codConnettoreVerifica obbligatorio");
		Assert.hasText(idDominio, "idDominio obbligatorio");
		Assert.hasText(numeroAvviso, "numeroAvviso obbligatorio");

		Connettore connettore = connettoreService.getConnettore(codConnettoreVerifica);
		Assert.hasText(connettore.getUrl(), "URL del connettore di verifica obbligatorio");

		GovPayEcApiClient apiClient = new GovPayEcApiClient(connettoreService.getRestTemplate(codConnettoreVerifica));
		apiClient.setBasePath(connettore.getUrl());
		return new VerificaPendenzeApi(apiClient).getAvviso(idDominio, numeroAvviso);
	}
}
