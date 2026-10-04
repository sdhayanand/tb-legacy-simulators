package com.tailoredbrands.otd.legacyoms.ws;

import com.tailoredbrands.legacy.oms.xml.Fault;
import com.tailoredbrands.otd.legacyoms.service.OmsException;
import com.tailoredbrands.otd.legacyoms.xml.OmsXmlMarshaller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.ws.soap.SoapFault;
import org.springframework.ws.soap.SoapFaultDetail;
import org.springframework.ws.soap.server.endpoint.SoapFaultDefinition;
import org.springframework.ws.soap.server.endpoint.SoapFaultMappingExceptionResolver;

import java.util.Properties;

/**
 * Turns {@link OmsException}s into SOAP 1.1 Client faults (everything else into Server faults) and puts the
 * XSD-defined {@code <Fault>} element into the fault detail so callers can read {@code Code} / {@code OrderNbr}.
 */
@Component
public class OmsFaultExceptionResolver extends SoapFaultMappingExceptionResolver {

    private static final Logger log = LoggerFactory.getLogger(OmsFaultExceptionResolver.class);

    private final OmsXmlMarshaller marshaller;

    public OmsFaultExceptionResolver(OmsXmlMarshaller marshaller) {
        this.marshaller = marshaller;

        SoapFaultDefinition serverFault = new SoapFaultDefinition();
        serverFault.setFaultCode(SoapFaultDefinition.SERVER);
        serverFault.setFaultStringOrReason("Internal OMS error");
        setDefaultFault(serverFault);

        Properties mappings = new Properties();
        // "CLIENT" with no fault string => the exception message becomes the faultstring
        mappings.setProperty(OmsException.class.getName(), "CLIENT");
        setExceptionMappings(mappings);

        setOrder(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    protected void customizeFault(Object endpoint, Exception ex, SoapFault fault) {
        Fault detail = new Fault();
        if (ex instanceof OmsException oms) {
            detail.setCode(oms.getCode());
            detail.setOrderNbr(oms.getOrderNbr());
            log.warn("SOAP client fault code={} orderNbr={} message={}", oms.getCode(), oms.getOrderNbr(), ex.getMessage());
        } else {
            detail.setCode("INTERNAL_ERROR");
            log.error("SOAP server fault", ex);
        }
        detail.setMessage(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());

        SoapFaultDetail soapDetail = fault.addFaultDetail();
        marshaller.marshal(detail, soapDetail.getResult());
    }
}
