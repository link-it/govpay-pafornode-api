package it.govpay.pafornode;

import it.govpay.pendenze.entity.Debitore;
import it.govpay.pendenze.entity.OpzionePagamento;
import it.govpay.pendenze.entity.PdDebitore;
import it.govpay.pendenze.entity.PosizioneDebitoria;
import it.govpay.pendenze.model.TipoSoggetto;

/** Costruzione delle posizioni debitorie di test: debitori della posizione e opzioni di ciascun debitore. */
public final class PosizioniDiTest {

	private PosizioniDiTest() {
	}

	/** Aggiunge il debitore alla posizione e restituisce il suo collegamento, a cui si aggiungono le opzioni. */
	public static PdDebitore aggiungiDebitore(PosizioneDebitoria posizione, Debitore debitore) {
		PdDebitore pdDebitore = new PdDebitore();
		pdDebitore.setDebitore(debitore);
		pdDebitore.setPosizione(posizione.getPdDebitoriInclusiRimossi().size());
		posizione.addPdDebitore(pdDebitore);
		return pdDebitore;
	}

	/** Aggiunge l'opzione al primo debitore della posizione, creando un debitore di prova se non ce n'e' ancora uno. */
	public static void aggiungiOpzione(PosizioneDebitoria posizione, OpzionePagamento opzione) {
		if (posizione.getPdDebitoriInclusiRimossi().isEmpty()) {
			Debitore debitore = new Debitore();
			debitore.setTipo(TipoSoggetto.F);
			debitore.setIdentificativo("RSSMRA80A01H501U");
			debitore.setAnagrafica("Mario Rossi");
			aggiungiDebitore(posizione, debitore);
		}
		posizione.getPdDebitoriInclusiRimossi().get(0).addOpzionePagamento(opzione);
	}
}
