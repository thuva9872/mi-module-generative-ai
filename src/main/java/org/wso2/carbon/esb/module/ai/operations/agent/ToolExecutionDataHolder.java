package org.wso2.carbon.esb.module.ai.operations.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

public class ToolExecutionDataHolder {

//    private AiServiceContext aiServiceContext;
//    private String memoryId;
    private ToolExecutionRequest toolExecutionRequest;
//    private List<ToolExecution> toolExecutions;
    private TokenUsage tokenUsageAccumulator;
//    private int executionsLeft;
//    private ChatRequestParameters chatRequestParameters;
    private ChatResponse chatResponse;
    private String resultVariableKey;
    private int totalToolExecutionCount;
    private int currentToolExecutionIndex;

    public ToolExecutionRequest getToolExecutionRequest() {

        return toolExecutionRequest;
    }

    public void setToolExecutionRequest(ToolExecutionRequest toolExecutionRequest) {

        this.toolExecutionRequest = toolExecutionRequest;
    }

    public TokenUsage getTokenUsageAccumulator() {

        return tokenUsageAccumulator;
    }

    public void setTokenUsageAccumulator(TokenUsage tokenUsageAccumulator) {

        this.tokenUsageAccumulator = tokenUsageAccumulator;
    }

    public ChatResponse getChatResponse() {

        return chatResponse;
    }

    public void setChatResponse(ChatResponse chatResponse) {

        this.chatResponse = chatResponse;
    }

    public String getResultVariableKey() {

        return resultVariableKey;
    }

    public void setResultVariableKey(String resultVariableKey) {

        this.resultVariableKey = resultVariableKey;
    }

    public int getTotalToolExecutionCount() {

        return totalToolExecutionCount;
    }

    public void setTotalToolExecutionCount(int totalToolExecutionCount) {

        this.totalToolExecutionCount = totalToolExecutionCount;
    }

    public int getCurrentToolExecutionIndex() {

        return currentToolExecutionIndex;
    }

    public void setCurrentToolExecutionIndex(int currentToolExecutionIndex) {

        this.currentToolExecutionIndex = currentToolExecutionIndex;
    }
}
