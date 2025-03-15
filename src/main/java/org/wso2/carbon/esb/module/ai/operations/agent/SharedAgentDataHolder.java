package org.wso2.carbon.esb.module.ai.operations.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServiceContext;
import dev.langchain4j.service.tool.ToolExecution;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.mediators.eip.SharedDataHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class SharedAgentDataHolder extends SharedDataHolder {

    protected Log log = LogFactory.getLog(this.getClass());
    private AiServiceContext aiServiceContext;
    private String memoryId;
    private List<ToolExecution> toolExecutions;
    private TokenUsage tokenUsageAccumulator;
    private AtomicInteger executionsLeft;
    private ChatRequestParameters chatRequestParameters;
    private ChatResponse finishChatResponse;
    private List<ToolExecutionRequest> currentToolExecutionRequests;
    private ReentrantLock lock = new ReentrantLock();

    public SharedAgentDataHolder() {

        this.toolExecutions = new ArrayList<>();
    }

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

    public void setExecutionsLeft(int executionsLeft) {

        this.executionsLeft = new AtomicInteger(executionsLeft);
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

        return executionsLeft.getAndDecrement();
    }

    public void addToMemory(ChatMessage message) {

        if (message instanceof AiMessage && ((AiMessage) message).hasToolExecutionRequests() &&
                ((AiMessage) message).text() == null) {
            AiMessage aiMessage = (AiMessage) message;
            AiMessage toolExecutionRequestMessage =
                    AiMessage.from("Tool Execution Request", aiMessage.toolExecutionRequests());
            aiServiceContext.chatMemory(memoryId).add(toolExecutionRequestMessage);
        } else {
            aiServiceContext.chatMemory(memoryId).add(message);
        }
    }

    public List<ToolExecutionRequest> getCurrentToolExecutionRequests() {

        return currentToolExecutionRequests;
    }

    public void setCurrentToolExecutionRequests(
            List<ToolExecutionRequest> currentToolExecutionRequests) {

        this.currentToolExecutionRequests = currentToolExecutionRequests;
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
