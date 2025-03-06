package org.wso2.carbon.esb.module.ai;

import dev.langchain4j.service.AiServiceContext;

import java.util.HashMap;
import java.util.Map;

public class SynapseAiContext extends AiServiceContext {

    private Map<String, String> toolResultVariable;
    public SynapseAiContext(Class<?> aiServiceClass) {

        super(aiServiceClass);
        this.toolResultVariable = new HashMap<>();
    }

    public void addToolResultVariable(String toolName, String variableName) {
        this.toolResultVariable.put(toolName, variableName);
    }

    public String getToolResultVariable(String toolName) {
        if(!this.toolResultVariable.containsKey(toolName)) {
            return null;
        }
        return this.toolResultVariable.get(toolName);
    }
}
