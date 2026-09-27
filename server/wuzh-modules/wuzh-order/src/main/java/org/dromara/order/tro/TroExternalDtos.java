package org.dromara.order.tro;

public final class TroExternalDtos {
    private TroExternalDtos() {}

    public record Query(String plaintiffName, String plaintiff, Object caseNoFormat) {}

    public record Response(String plaintiffNamesStr, String plaintiff, String caseNoFormat) {}
}
