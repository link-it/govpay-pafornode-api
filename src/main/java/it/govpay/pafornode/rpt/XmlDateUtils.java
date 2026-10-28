package it.govpay.pafornode.rpt;

import java.time.LocalDate;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/** Conversione di una data verso {@code XMLGregorianCalendar} (solo anno/mese/giorno, come richiesto dai tipi pagoPA). */
public final class XmlDateUtils {

	private XmlDateUtils() {
	}

	public static XMLGregorianCalendar toXmlDate(LocalDate data) {
		if (data == null) {
			return null;
		}
		try {
			return DatatypeFactory.newInstance().newXMLGregorianCalendarDate(data.getYear(), data.getMonthValue(),
					data.getDayOfMonth(), DatatypeConstants.FIELD_UNDEFINED);
		} catch (DatatypeConfigurationException e) {
			throw new IllegalStateException(e);
		}
	}
}
