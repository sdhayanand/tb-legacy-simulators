package com.tailoredbrands.otd.legacyoms.xml;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.GregorianCalendar;

/**
 * Conversions between {@code java.time} and the {@link XMLGregorianCalendar} values that xjc generates
 * for {@code xs:date} / {@code xs:dateTime}.
 */
public final class XmlDates {

    private static final DatatypeFactory FACTORY;

    static {
        try {
            FACTORY = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("No JAXP DatatypeFactory available", e);
        }
    }

    private XmlDates() {
    }

    public static LocalDate toLocalDate(XMLGregorianCalendar cal) {
        if (cal == null) {
            return null;
        }
        return LocalDate.of(cal.getYear(), cal.getMonth(), cal.getDay());
    }

    public static XMLGregorianCalendar fromLocalDate(LocalDate date) {
        if (date == null) {
            return null;
        }
        return FACTORY.newXMLGregorianCalendarDate(
                date.getYear(), date.getMonthValue(), date.getDayOfMonth(), DatatypeConstants.FIELD_UNDEFINED);
    }

    public static XMLGregorianCalendar fromInstant(Instant instant) {
        if (instant == null) {
            return null;
        }
        GregorianCalendar gc = GregorianCalendar.from(instant.atZone(ZoneOffset.UTC));
        return FACTORY.newXMLGregorianCalendar(gc);
    }
}
