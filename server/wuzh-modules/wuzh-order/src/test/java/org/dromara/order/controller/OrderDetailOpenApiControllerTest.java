package org.dromara.order.controller;

import org.dromara.order.config.OrderProperties;
import org.dromara.order.domain.OrderDetailDtos;
import org.dromara.order.service.OrderDetailService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@Tag("local")
class OrderDetailOpenApiControllerTest {

    @Test
    void rejectsMissingApiKeyWithHttp401() throws Exception {
        OrderDetailService service = mock(OrderDetailService.class);
        OrderProperties properties = new OrderProperties();
        properties.setApiKey("test-secret");
        MockMvc mockMvc = standaloneSetup(new OrderDetailOpenApiController(service, properties)).build();

        mockMvc.perform(post("/open-api/order-details/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsWrongApiKeyWithHttp401() throws Exception {
        OrderDetailService service = mock(OrderDetailService.class);
        OrderProperties properties = new OrderProperties();
        properties.setApiKey("test-secret");
        MockMvc mockMvc = standaloneSetup(new OrderDetailOpenApiController(service, properties)).build();

        mockMvc.perform(post("/open-api/order-details/batch")
                .header("X-API-Key", "wrong-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[]"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));

        verifyNoInteractions(service);
    }

    @Test
    void importsWithValidApiKey() throws Exception {
        OrderDetailService service = mock(OrderDetailService.class);
        when(service.importBatch(anyList())).thenReturn(new OrderDetailDtos.ImportResult(1, 1, 0));
        OrderProperties properties = new OrderProperties();
        properties.setApiKey("test-secret");
        MockMvc mockMvc = standaloneSetup(new OrderDetailOpenApiController(service, properties)).build();

        mockMvc.perform(post("/open-api/order-details/batch")
                .header("X-API-Key", "test-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    [{
                      "parentOrderSn":"PARENT-1",
                      "relatedGoodsInfoForAggregation":[{
                        "orderSn":"CHILD-1",
                        "parentOrderSn":"PARENT-1",
                        "skuQuantity":1
                      }]
                    }]
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.received").value(1))
            .andExpect(jsonPath("$.data.created").value(1))
            .andExpect(jsonPath("$.data.updated").value(0));
    }
}
