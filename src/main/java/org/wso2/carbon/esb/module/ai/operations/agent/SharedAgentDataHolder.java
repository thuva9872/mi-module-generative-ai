package org.wso2.carbon.esb.module.ai.operations.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServiceContext;
import dev.langchain4j.service.tool.ToolExecution;
import org.apache.synapse.mediators.eip.SharedDataHolder;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class SharedAgentDataHolder extends SharedDataHolder {

    private AiServiceContext aiServiceContext;
    private String memoryId;
    private List<ToolExecution> toolExecutions;
    private TokenUsage tokenUsageAccumulator;
    private int executionsLeft;
    private ChatRequestParameters chatRequestParameters;
    private ChatResponse finishChatResponse;
    private ReentrantLock lock = new ReentrantLock();

    public AiServiceContext getAiServiceContext() {

        return aiServiceContext;
    }

    public void setAiServiceContext(AiServiceContext aiServiceContext) {

        this.aiServiceContext = aiServiceContext;
    }

    public String getMemoryId() {

        return memoryId;
    }

    public void setMemoryId(String memoryId) {

        this.memoryId = memoryId;
    }

    public List<ToolExecution> getToolExecutions() {

        return toolExecutions;
    }

    public void setToolExecutions(List<ToolExecution> toolExecutions) {

        this.toolExecutions = toolExecutions;
    }

    public TokenUsage getTokenUsageAccumulator() {

        return tokenUsageAccumulator;
    }

    public void setTokenUsageAccumulator(TokenUsage tokenUsageAccumulator) {

        this.tokenUsageAccumulator = tokenUsageAccumulator;
    }

    public int getExecutionsLeft() {

        return executionsLeft;
    }

    public void setExecutionsLeft(int executionsLeft) {

        this.executionsLeft = executionsLeft;
    }

    public ChatRequestParameters getChatRequestParameters() {

        return chatRequestParameters;
    }

    public void setChatRequestParameters(ChatRequestParameters chatRequestParameters) {

        this.chatRequestParameters = chatRequestParameters;
    }

    public ChatResponse getFinishChatResponse() {

        return finishChatResponse;
    }

    public void setFinishChatResponse(ChatResponse finishChatResponse) {

        this.finishChatResponse = finishChatResponse;
    }

    public int getAndDecrementExecutionsLeft() {

        return executionsLeft--;
    }

    public void addToMemory(ChatMessage message) {

        aiServiceContext.chatMemory(memoryId)  .add(message);
    }

    public void getLock() {

        lock.lock();
    }

    public void releaseLock() {

        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
