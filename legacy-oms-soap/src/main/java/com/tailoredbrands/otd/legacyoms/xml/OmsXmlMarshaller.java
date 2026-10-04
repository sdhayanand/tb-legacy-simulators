package com.tailoredbrands.otd.legacyoms.xml;

import com.tailoredbrands.legacy.oms.xml.ObjectFactory;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.springframework.stereotype.Component;

import javax.xml.transform.Result;
import java.io.StringReader;
import java.io.StringWriter;

/**
 * Thin JAXB wrapper around the xjc-generated package. {@link JAXBContext} is thread-safe and expensive,
 * so one instance is shared; {@link Marshaller}s are created per call.
 */
@Component
public class OmsXmlMarshaller {

    public static final String NAMESPACE = "http://tailoredbrands.com/legacy/oms/v1";

    private final JAXBContext context;

    public OmsXmlMarshaller() {
        try {
            this.context = JAXBContext.newInstance(ObjectFactory.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot create JAXBContext for " + ObjectFactory.class.getPackageName(), e);
        }
    }

    /** Marshal a root element object (one annotated with {@code @XmlRootElement}) to a pretty-printed string. */
    public String toXml(Object rootElement) {
        try {
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            StringWriter writer = new StringWriter();
            marshaller.marshal(rootElement, writer);
            return writer.toString();
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot marshal " + rootElement.getClass().getSimpleName(), e);
        }
    }

    /** Marshal into an arbitrary {@link Result} (used to fill SOAP fault detail). */
    public void marshal(Object rootElement, Result result) {
        try {
            context.createMarshaller().marshal(rootElement, result);
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot marshal " + rootElement.getClass().getSimpleName(), e);
        }
    }

    public <T> T fromXml(String xml, Class<T> type) {
        try {
            Unmarshaller unmarshaller = context.createUnmarshaller();
            Object result = unmarshaller.unmarshal(new StringReader(xml));
            if (result instanceof jakarta.xml.bind.JAXBElement<?> element) {
                result = element.getValue();
            }
            return type.cast(result);
        } catch (JAXBException e) {
            throw new IllegalArgumentException("Cannot unmarshal " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
