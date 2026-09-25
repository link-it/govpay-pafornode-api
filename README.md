# govpay-pafornode-api

govpay-api-pagopa implementa le interfacce SOAP che GovPay espone al Nodo dei Pagamenti di pagoPA in qualità di Ente Creditore, o di intermediario per conto degli enti gestiti. Il Nodo le invoca durante il ciclo di vita di un pagamento per verificare l'avviso, recuperare i dati della posizione debitoria e notificare l'esito tramite la ricevuta telematica.

Il modulo espone le operazioni del modello di pagamento corrente (paForNode):

- paVerifyPaymentNotice: verifica di un avviso di pagamento e restituzione dei dati della posizione debitoria;
- paGetPayment / paGetPaymentV2: attivazione del pagamento e restituzione dei dati di dettaglio, comprese le informazioni contabili di accredito;
- paSendRT / paSendRTV2: ricezione della ricevuta di pagamento;
