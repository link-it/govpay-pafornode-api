package it.govpay.pafornode.gde;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Propaga l'esito applicativo del contratto paForNode (fault, incluso {@code
 * PAA_SYSTEM_ERROR}) da {@code PaForNodeEndpoint} a {@link GdeEventFilter}, tramite
 * attributi di richiesta — simmetrico a {@link OperationIdEndpointInterceptor} per
 * operationId/principal.
 * <p>
 * Necessario perché per questo contratto un fault applicativo è sempre restituito con HTTP
 * 200 (risposta normale con {@code outcome=KO} e {@code fault} nel body, mai un SOAP Fault
 * né uno status HTTP di errore — {@code PaForNodeEndpoint} cattura sempre {@code
 * NdpException}/{@code RuntimeException} internamente): lo stato HTTP da solo non basta a
 * {@link GdeEventFilter} per classificare l'esito dell'evento GDE, che resterebbe sempre OK.
 */
public final class GdeFaultContext {

    static final String FAULT_CODE_ATTRIBUTE = "it.govpay.pafornode.gde.faultCode";
    static final String FAULT_DESCRIZIONE_ATTRIBUTE = "it.govpay.pafornode.gde.faultDescrizione";

    private GdeFaultContext() {
    }

    /**
     * @param faultCode  codice del fault pagoPA (es. {@code PAA_SYSTEM_ERROR}), mai null
     *                   quando chiamato dai punti previsti
     * @param descrizione descrizione del fault, può essere null
     */
    public static void segnalaFault(String faultCode, String descrizione) {
        HttpServletRequest request = SoapTransportUtils.currentHttpServletRequest();
        if (request == null) {
            return;
        }
        request.setAttribute(FAULT_CODE_ATTRIBUTE, faultCode);
        request.setAttribute(FAULT_DESCRIZIONE_ATTRIBUTE, descrizione);
    }
}
