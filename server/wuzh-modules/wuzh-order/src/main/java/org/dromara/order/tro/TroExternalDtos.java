package org.dromara.order.tro;

import java.util.List;

public final class TroExternalDtos {
    private TroExternalDtos() {}

    public record Query(String plaintiffName, String plaintiff, Object caseNoFormat) {}

    public record Response(String plaintiffNamesStr, String plaintiff, String caseNoFormat) {}

    public record Supplement(String caseNoFormat, String plaintiff, String plaintiffCn,
                             List<String> plaintiffNames) {}
}
