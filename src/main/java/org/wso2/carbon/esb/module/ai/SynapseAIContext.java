package org.wso2.carbon.esb.module.ai;

import dev.langchain4j.service.AiServiceContext;
import org.apache.synapse.mediators.Value;

import java.util.HashMap;
import java.util.Map;

public class SynapseAIContext extends AiServiceContext {

    private Map<String, Value> toolResultVariable;

    public SynapseAIContext(Class<?> aiServiceClass) {

        super(aiServiceClass);
        this.toolResultVariable = new HashMap<>();
    }

    public void setToolResultVariable(Map<String, Value> toolResultVariable) {

        this.toolResultVariable = toolResultVariable;
    }

    public Value getToolResultVariable(String toolName) {

        if (!this.toolResultVariable.containsKey(toolName)) {
            return null;
        }
        return this.toolResultVariable.get(toolName);
    }
}
