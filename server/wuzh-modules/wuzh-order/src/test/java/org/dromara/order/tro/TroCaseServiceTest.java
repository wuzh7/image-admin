package org.dromara.order.tro;

import tools.jackson.databind.json.JsonMapper;
import org.dromara.order.mapper.TroCaseMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("local")
class TroCaseServiceTest {
    @Mock
    private TroCaseMapper mapper;

    @Test
    void importsPartialObjectsAndDiscardsUnknownFields() {
        byte[] json = """
            [{"caseNoFormat":" 26-cv-26647 ","unknown":"discard me"},
             {"caseNoFormat":"26-cv-26647","plaintiff":"","caseName":"latest"}]
            """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "cases.json", "application/json", json);

        int count = new TroCaseService(mapper, JsonMapper.builder().build()).importFile(file);

        ArgumentCaptor<TroCase> saved = ArgumentCaptor.forClass(TroCase.class);
        verify(mapper, times(2)).upsert(saved.capture());
        assertThat(count).isEqualTo(2);
        assertThat(saved.getAllValues()).allSatisfy(value -> {
            assertThat(value.getCaseNoFormat()).isEqualTo("26-cv-26647");
            assertThat(value.getPlaintiff()).isEmpty();
            assertThat(value.getCourt()).isEmpty();
            assertThat(value.getDateFiled()).isNull();
            assertThat(value.getPlaintiffNames()).isEmpty();
        });
        assertThat(saved.getAllValues().get(1).getCaseName()).isEqualTo("latest");
    }

    @Test
    void externalQueryJoinsPlaintiffNamesWithChineseComma() {
        TroCase value = new TroCase();
        value.setCaseNoFormat("26-cv-26647");
        value.setPlaintiff("Omega SA");
        value.setPlaintiffNames(List.of("Omega SA", "Omega中文"));
        when(mapper.selectExternal(anyList(), anyList())).thenReturn(List.of(value));

        List<TroExternalDtos.Response> result = new TroCaseService(mapper, JsonMapper.builder().build())
            .externalQuery(new TroExternalDtos.Query(" Omega中文 ", "", null));

        assertThat(result).containsExactly(new TroExternalDtos.Response("Omega SA，Omega中文", "Omega SA", "26-cv-26647"));
        verify(mapper).selectExternal(List.of(" Omega中文 ", "Omega中文", " omega中文 ", "omega中文"), List.of());
    }

    @Test
    void externalQuerySearchesAllPlaintiffVariantsAndCaseNumbersTogether() {
        when(mapper.selectExternal(anyList(), anyList())).thenReturn(List.of());

        new TroCaseService(mapper, JsonMapper.builder().build()).externalQuery(
            new TroExternalDtos.Query("  Acme Co  ", "Acme Co", List.of(" 26-cv-1 ", "26-cv-2", "26-cv-1")));

        verify(mapper).selectExternal(
            List.of("  Acme Co  ", "Acme Co", "AcmeCo", "  acme co  ", "acme co", "acmeco"),
            List.of("26-cv-1", "26-cv-2"));
    }

    @Test
    void externalSqlMatchesAnyField() throws Exception {
        String script = TroCaseMapper.class.getMethod("selectExternal", List.class, List.class)
            .getAnnotation(Select.class).value()[0];
        String sql = new XMLLanguageDriver().createSqlSource(new Configuration(), script, Map.class)
            .getBoundSql(Map.of("plaintiffValues", List.of("acme"), "caseNoFormats", List.of("26-cv-1"))).getSql();

        assertThat(sql).contains("OR LOWER(plaintiff) IN", "JSON_TABLE(COALESCE(plaintiff_names", "LOWER(names.name) IN", "OR case_no_format IN");
    }

    @Test
    void externalQueryRequiresAtLeastOneCondition() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            new TroCaseService(mapper, JsonMapper.builder().build())
                .externalQuery(new TroExternalDtos.Query("", " ", null)))
            .isInstanceOf(org.dromara.common.core.exception.ServiceException.class)
            .hasMessage("至少填写一个查询条件");
    }
}
