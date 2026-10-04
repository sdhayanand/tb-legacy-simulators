package com.tailoredbrands.otd.legacyoms;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OmsAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsSeededOrders() throws Exception {
        mockMvc.perform(get("/admin/orders").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].orderNbr").isString())
                .andExpect(jsonPath("$[0].lines", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    void getsOneOrderAndMovesItsStatus() throws Exception {
        mockMvc.perform(get("/admin/orders/1402-260928-956003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderType").value("X"))
                .andExpect(jsonPath("$.eventDate").value("2026-11-16"));

        mockMvc.perform(put("/admin/orders/1402-260928-956003/status").param("value", "released"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        mockMvc.perform(get("/admin/orders/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void exportProducesSchemaValidOrdersExtract() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/export").param("date", "2026-10-01"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Order-Count", "9"))
                .andReturn();

        String xml = result.getResponse().getContentAsString();
        assertThat(xml).contains("<Orders").contains("ExtractDate=\"2026-10-01\"").contains("Count=\"9\"");

        var schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(new StreamSource(getClass().getClassLoader().getResourceAsStream("xsd/oms.xsd")));
        schema.newValidator().validate(new StreamSource(new StringReader(xml)));
    }

    @Test
    void exportToGcsIsSkippedWithoutCredentials() throws Exception {
        mockMvc.perform(post("/admin/export-to-gcs")
                        .param("date", "2026-10-01")
                        .param("bucket", "tb-otd-nonexistent-bucket-for-tests"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("SKIPPED"))
                .andExpect(jsonPath("$.orderCount").value(9));
    }
}
