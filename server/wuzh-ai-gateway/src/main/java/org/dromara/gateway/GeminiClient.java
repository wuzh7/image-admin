package org.dromara.gateway;

import java.util.Set;

public interface GeminiClient {
    String CASE_LOOKUP_PROMPT = "Is there a copyright holder? Look up the TRO case number; return only the array of TRO case numbers.";
    String mode();

    default Set<String> modes() {
        return Set.of(mode());
    }

    Analysis analyze(byte[] image, String contentType);

    Analysis lookupCases(byte[] image, String contentType, String previousResponse);

    default Analysis analyze(byte[] image, String contentType, String mode) {
        return analyze(image, contentType);
    }

    String model();

    record Analysis(String rawAiResponse, boolean searchUsed, int searchQueryCount, Usage usage,
                    String rawProviderResponse) {
        Analysis(String rawAiResponse, boolean searchUsed, int searchQueryCount, Usage usage) {
            this(rawAiResponse, searchUsed, searchQueryCount, usage, null);
        }
        Analysis(String rawAiResponse, boolean searchUsed, int searchQueryCount) {
            this(rawAiResponse, searchUsed, searchQueryCount, null);
        }

        Analysis withRawProviderResponse(String response) {
            return new Analysis(rawAiResponse, searchUsed, searchQueryCount, usage, response);
        }
    }

    record Usage(Integer promptTokenCount, Integer cachedContentTokenCount,
                 Integer candidatesTokenCount, Integer thoughtsTokenCount,
                 Integer toolUsePromptTokenCount, Integer totalTokenCount,
                 String rawJson) {
    }
}
