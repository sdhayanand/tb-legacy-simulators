package com.tailoredbrands.otd.legacyoms.ws;

import com.tailoredbrands.otd.legacyoms.xml.OmsXmlMarshaller;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ws.config.annotation.EnableWs;
import org.springframework.ws.config.annotation.WsConfigurer;
import org.springframework.ws.server.EndpointInterceptor;
import org.springframework.ws.server.endpoint.adapter.method.MethodArgumentResolver;
import org.springframework.ws.server.endpoint.adapter.method.MethodReturnValueHandler;
import org.springframework.ws.soap.server.endpoint.interceptor.PayloadValidatingInterceptor;
import org.springframework.ws.transport.http.MessageDispatcherServlet;
import org.springframework.ws.wsdl.wsdl11.DefaultWsdl11Definition;
import org.springframework.xml.xsd.SimpleXsdSchema;
import org.springframework.xml.xsd.XsdSchema;

import java.util.List;

/**
 * Spring-WS wiring: contract-first WSDL generated from {@code xsd/oms.xsd}, served at {@code /ws/oms.wsdl}.
 *
 * <p>Every request payload is validated against the XSD before it reaches the endpoint, which is how the
 * real OMS behaves (invalid XML is rejected with a SOAP fault, never half-loaded into Oracle).
 */
@Configuration
@EnableWs
public class WebServiceConfig implements WsConfigurer {

    public static final String NAMESPACE = OmsXmlMarshaller.NAMESPACE;

    @Bean
    public ServletRegistrationBean<MessageDispatcherServlet> messageDispatcherServlet(ApplicationContext context) {
        MessageDispatcherServlet servlet = new MessageDispatcherServlet();
        servlet.setApplicationContext(context);
        servlet.setTransformWsdlLocations(true);
        return new ServletRegistrationBean<>(servlet, "/ws/*");
    }

    /** Bean name "oms" => WSDL published at /ws/oms.wsdl */
    @Bean(name = "oms")
    public DefaultWsdl11Definition omsWsdl(XsdSchema omsSchema) {
        DefaultWsdl11Definition wsdl = new DefaultWsdl11Definition();
        wsdl.setPortTypeName("OmsPort");
        wsdl.setServiceName("LegacyOmsService");
        wsdl.setLocationUri("/ws");
        wsdl.setTargetNamespace(NAMESPACE);
        wsdl.setSchema(omsSchema);
        // Operations are derived from the *Request / *Response element pairs. The shared <Fault> detail
        // element must not be picked up as a per-operation fault message, so use a suffix nothing matches.
        wsdl.setRequestSuffix("Request");
        wsdl.setResponseSuffix("Response");
        wsdl.setFaultSuffix("FaultMessage");
        return wsdl;
    }

    @Bean
    public XsdSchema omsSchema() {
        return new SimpleXsdSchema(new ClassPathResource("xsd/oms.xsd"));
    }

    @Bean
    public PayloadValidatingInterceptor payloadValidatingInterceptor(XsdSchema omsSchema) {
        PayloadValidatingInterceptor interceptor = new PayloadValidatingInterceptor();
        interceptor.setXsdSchema(omsSchema);
        interceptor.setValidateRequest(true);
        interceptor.setValidateResponse(false);
        return interceptor;
    }

    @Override
    public void addInterceptors(List<EndpointInterceptor> interceptors) {
        interceptors.add(payloadValidatingInterceptor(omsSchema()));
    }

    @Override
    public void addArgumentResolvers(List<MethodArgumentResolver> argumentResolvers) {
        // defaults (JAXB @XmlRootElement payload processing) are sufficient
    }

    @Override
    public void addReturnValueHandlers(List<MethodReturnValueHandler> returnValueHandlers) {
        // defaults are sufficient
    }
}
