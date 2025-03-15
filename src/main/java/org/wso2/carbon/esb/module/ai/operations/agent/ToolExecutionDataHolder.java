package org.wso2.carbon.esb.module.ai.operations.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.apache.synapse.mediators.Value;

import java.util.List;

public class ToolExecutionDataHolder {

    private List<ToolExecutionRequest> toolExecutionRequests; // This is stored for reference.
    private ToolExecutionRequest toolExecutionRequest; // This is the current tool execution request.
    private TokenUsage tokenUsageAccumulator;
    private ChatResponse chatResponse;
    private Value resultExpression;
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

    public Value getResultExpression() {

        return resultExpression;
    }

    public void setResultExpression(Value resultExpression) {

        this.resultExpression = resultExpression;
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

    public List<ToolExecutionRequest> getToolExecutionRequests() {

        return toolExecutionRequests;
    }

    public void setToolExecutionRequests(List<ToolExecutionRequest> toolExecutionRequests) {

        this.toolExecutionRequests = toolExecutionRequests;
    }
}
