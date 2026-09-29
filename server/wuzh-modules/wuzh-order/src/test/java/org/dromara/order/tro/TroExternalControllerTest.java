package org.dromara.order.tro;

import org.dromara.order.config.TroProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@Tag("local")
class TroExternalControllerTest {
    @Test
    void supplementRequiresApiKeyAndAcceptsCaseListString() throws Exception {
        TroCaseService service = mock(TroCaseService.class);
        TroProperties properties = new TroProperties();
        properties.setApiKey("tro-secret");
        MockMvc mockMvc = standaloneSetup(new TroExternalController(service, properties)).build();
        String body = """
            {"caseNoFormat":"26-cv-1,26-cv-2","plaintiff":"Holder",
             "plaintiffCn":"作者","plaintiffNames":["Holder","作者"]}
            """;
        mockMvc.perform(post("/open-api/tro/cases/supplement")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/open-api/tro/cases/supplement")
            .header("X-API-Key", "tro-secret").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        verify(service).supplement(any(TroExternalDtos.Supplement.class));
    }
    @Test
    void rejectsMissingApiKey() throws Exception {
        TroCaseService service = mock(TroCaseService.class);
        TroProperties properties = new TroProperties();
        properties.setApiKey("tro-secret");
        MockMvc mockMvc = standaloneSetup(new TroExternalController(service, properties)).build();

        mockMvc.perform(post("/open-api/tro/cases/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plaintiffName\":\"Omega SA\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
        verifyNoInteractions(service);
    }

    @Test
    void returnsMatchedCasesWithJoinedNames() throws Exception {
        TroCaseService service = mock(TroCaseService.class);
        when(service.externalQuery(any())).thenReturn(List.of(
            new TroExternalDtos.Response("Omega SA，Omega中文", "Omega SA", "26-cv-26647")));
        TroProperties properties = new TroProperties();
        properties.setApiKey("tro-secret");
        MockMvc mockMvc = standaloneSetup(new TroExternalController(service, properties)).build();

        mockMvc.perform(post("/open-api/tro/cases/query")
                .header("X-API-Key", "tro-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"plaintiffName\":\"Omega SA\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].plaintiffNamesStr").value("Omega SA，Omega中文"))
            .andExpect(jsonPath("$.data[0].plaintiff").value("Omega SA"))
            .andExpect(jsonPath("$.data[0].caseNoFormat").value("26-cv-26647"));
    }

    @Test
    void acceptsCaseNumberArray() throws Exception {
        TroCaseService service = mock(TroCaseService.class);
        TroProperties properties = new TroProperties();
        properties.setApiKey("tro-secret");
        MockMvc mockMvc = standaloneSetup(new TroExternalController(service, properties)).build();

        mockMvc.perform(post("/open-api/tro/cases/query")
                .header("X-API-Key", "tro-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"caseNoFormat\":[\"26-cv-1\",\"26-cv-2\"]}"))
            .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<TroExternalDtos.Query> query = org.mockito.ArgumentCaptor.forClass(TroExternalDtos.Query.class);
        verify(service).externalQuery(query.capture());
        assertThat(query.getValue().caseNoFormat()).isEqualTo(List.of("26-cv-1", "26-cv-2"));
    }
}
