package org.dromara.order.tro;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.order.mapper.TroCaseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TroCaseService {
    private final TroCaseMapper mapper;
    private final JsonMapper objectMapper;

    public PageResult<TroCase> list(String caseNoFormat, String caseName, String plaintiff, PageQuery pageQuery) {
        Page<TroCase> page = mapper.selectPage(pageQuery.build(), new QueryWrapper<TroCase>()
            .like(hasText(caseNoFormat), "case_no_format", caseNoFormat)
            .like(hasText(caseName), "case_name", caseName)
            .like(hasText(plaintiff), "plaintiff", plaintiff)
            .orderByDesc("date_filed", "id"));
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    public List<TroExternalDtos.Response> externalQuery(TroExternalDtos.Query query) {
        if (query == null) throw new ServiceException("请求体不能为空");
        LinkedHashSet<String> plaintiffValues = new LinkedHashSet<>(searchVariants(query.plaintiffName()));
        plaintiffValues.addAll(searchVariants(query.plaintiff()));
        List<String> caseNoFormats = caseNoFormats(query.caseNoFormat());
        if (plaintiffValues.isEmpty() && caseNoFormats.isEmpty()) {
            throw new ServiceException("至少填写一个查询条件");
        }
        return mapper.selectExternal(List.copyOf(plaintiffValues), caseNoFormats).stream()
            .map(value -> new TroExternalDtos.Response(
                value.getPlaintiffNames() == null ? "" : value.getPlaintiffNames().stream()
                    .filter(name -> name != null && !name.isBlank())
                    .collect(Collectors.joining("，")),
                value.getPlaintiff(), value.getCaseNoFormat()))
            .toList();
    }

    private List<String> searchVariants(String value) {
        if (value == null || value.isBlank()) return List.of();
        String trimmed = value.trim();
        String withoutSpaces = value.replaceAll("\\s+", "");
        String lower = value.toLowerCase(Locale.ROOT);
        String lowerTrimmed = trimmed.toLowerCase(Locale.ROOT);
        String lowerWithoutSpaces = withoutSpaces.toLowerCase(Locale.ROOT);
        return new LinkedHashSet<>(List.of(value, trimmed, withoutSpaces, lower, lowerTrimmed, lowerWithoutSpaces))
            .stream().filter(item -> !item.isEmpty()).toList();
    }

    private List<String> caseNoFormats(Object value) {
        if (value == null) return List.of();
        List<?> values = value instanceof String ? List.of(value) : value instanceof List<?> list ? list : null;
        if (values == null || values.stream().anyMatch(item -> !(item instanceof String))) {
            throw new ServiceException("caseNoFormat必须是字符串或字符串数组");
        }
        return values.stream().map(item -> ((String) item).trim()).filter(item -> !item.isEmpty()).distinct().toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public int importFile(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > 20 * 1024 * 1024) {
            throw new ServiceException("请选择不超过20MB的JSON文件");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(file.getInputStream());
        } catch (IOException e) {
            throw new ServiceException("JSON文件解析失败");
        }
        if (root == null || (!root.isObject() && !root.isArray())) {
            throw new ServiceException("JSON根节点必须是对象或对象数组");
        }
        List<JsonNode> items = new ArrayList<>();
        if (root.isArray()) root.forEach(items::add);
        else items.add(root);
        for (JsonNode item : items) {
            if (!item.isObject()) throw new ServiceException("JSON数组内只能包含对象");
            mapper.upsert(toCase(item));
        }
        return items.size();
    }

    private TroCase toCase(JsonNode item) {
        TroCase value = new TroCase();
        value.setCaseNoFormat(text(item, "caseNoFormat", 32).trim());
        value.setCaseName(text(item, "caseName", 512));
        value.setCourt(text(item, "court", 128));
        value.setJudge(text(item, "judge", 128));
        value.setDescription(text(item, "description", 255));
        value.setNatureOfSuit(text(item, "natureOfSuit", 128));
        value.setCause(text(item, "cause", 255));
        String date = text(item, "dateFiled", 10);
        try {
            value.setDateFiled(date.isEmpty() ? null : LocalDate.parse(date));
        } catch (DateTimeParseException e) {
            throw new ServiceException("dateFiled必须是yyyy-MM-dd格式");
        }
        value.setPlaintiff(text(item, "plaintiff", 255).trim());
        value.setPlaintiffCn(text(item, "plaintiffCn", 255));
        List<String> names = new ArrayList<>();
        JsonNode sourceNames = item.path("plaintiffNames");
        if (sourceNames.isArray()) sourceNames.forEach(name -> {
            if (name.isTextual()) names.add(name.asText());
        });
        value.setPlaintiffNames(names);
        value.setLawyer(text(item, "lawyer", 128));
        value.setLawFirms(text(item, "lawFirms", 128));
        return value;
    }

    private String text(JsonNode item, String field, int maxLength) {
        JsonNode node = item.path(field);
        String value = node.isTextual() ? node.asText() : "";
        if (value.length() > maxLength) throw new ServiceException(field + "超过最大长度" + maxLength);
        return value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
