package org.dromara.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "relay")
public class RelayStageProperties {
    private String prompt1File;
    private String prompt2File;
    private List<Stage> stages = new ArrayList<>();

    public String getPrompt1File() {
        return prompt1File;
    }

    public void setPrompt1File(String prompt1File) {
        this.prompt1File = prompt1File;
    }

    public String getPrompt2File() {
        return prompt2File;
    }

    public void setPrompt2File(String prompt2File) {
        this.prompt2File = prompt2File;
    }

    public List<Stage> getStages() {
        return stages;
    }

    public void setStages(List<Stage> stages) {
        this.stages = stages == null ? new ArrayList<>() : new ArrayList<>(stages);
    }

    public static class Stage {
        private String model;
        private String protocol = "GEMINI";

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

    }
}
