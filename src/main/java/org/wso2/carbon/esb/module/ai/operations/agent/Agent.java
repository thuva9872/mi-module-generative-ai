/*
 *  Copyright (c) 2025, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.esb.module.ai.operations.agent;

import com.google.gson.Gson;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServiceContext;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.output.ServiceOutputParser;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.OperationContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.ContinuationState;
import org.apache.synapse.Mediator;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SequenceType;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.SynapseLog;
import org.apache.synapse.aspects.flow.statistics.collectors.RuntimeStatisticCollector;
import org.apache.synapse.continuation.ContinuationStackManager;
import org.apache.synapse.continuation.SeqContinuationState;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.FlowContinuableMediator;
import org.apache.synapse.mediators.Value;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.apache.synapse.mediators.eip.EIPConstants;
import org.apache.synapse.mediators.eip.EIPUtils;
import org.apache.synapse.mediators.eip.SharedDataHolder;
import org.apache.synapse.mediators.template.InvokeMediator;
import org.apache.synapse.mediators.template.TemplateMediator;
import org.apache.synapse.mediators.template.TemplateParam;
import org.apache.synapse.util.InlineExpressionUtil;
import org.apache.synapse.util.MessageHelper;
import org.jaxen.JaxenException;
import org.wso2.carbon.esb.module.ai.AbstractAIMediator;
import org.wso2.carbon.esb.module.ai.Constants;
import org.wso2.carbon.esb.module.ai.Errors;
import org.wso2.carbon.esb.module.ai.SynapseAIContext;
import org.wso2.carbon.esb.module.ai.llm.LLMConnectionHandler;
import org.wso2.carbon.esb.module.ai.utils.AgentUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Timer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Language model chat operation
 * Inputs:
 * - modelName: Name of the language model
 * - userId: Unique user identifier
 * - temperature: Sampling temperature
 * - maxTokens: Maximum tokens to generate
 * - topP: Top P value
 * - frequencyPenalty: Frequency penalty
 * - seed: Random seed
 * - system: System message
 * - prompt: User message
 * - knowledge: JSON array of TextSegment objects
 * - history: JSON array of ChatMessage objects
 * - maxHistory: Maximum history size
 * - connectionName: Name of the connection to the LLM
 * Outputs:
 * - Response based on the output type
 */
public class Agent extends AbstractAIMediator implements FlowContinuableMediator {

    protected Log log = LogFactory.getLog(this.getClass());
    private static final int MAX_TOOL_EXECUTIONS_PER_REQUEST = 100;
    private static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant.";
    private String agentID; // Unique identifier for the agent
    private static final Gson gson = new Gson();
    private final ServiceOutputParser serviceOutputParser = new ServiceOutputParser();
    private final Map<String, ToolExecutionAggregate> activeAggregates = Collections.synchronizedMap(new HashMap<>());
    private final Object lock = new Object();
    private final List<ToolSpecification> toolSpecifications;
    private boolean isInitialized = false;
    private final Map<String, SequenceMediator> toolInvokers; // Map of tool name to sequence mediator
    private final Map<String, Value> toolResultExpressions; // Map of tool name to result expressions
    private Map<Object, ChatMemory> chatMemories;
    private ChatMemoryProvider chatMemoryProvider;

    // Chat configurations
    private Integer maxChatHistory = 10;
    private String modelName;
    private Double temperature;
    private Integer maxTokens;
    private Double topP;
    private Double frequencyPenalty;
    private Integer seed;
    private String system;
    private String connectionName;
    private long toolExecutionTimeout = 10000;

    public Agent() {

        agentID = String.valueOf(new Random().nextLong());
        toolInvokers = new HashMap<>();
        toolResultExpressions = new HashMap<>();
        toolSpecifications = new ArrayList<>();
    }

    public interface Assistant {

        Result<String> chat(@MemoryId String memoryId, @dev.langchain4j.service.UserMessage String message);
    }

    private void init(MessageContext mc) {

        connectionName = getProperty(mc, Constants.CONNECTION_NAME, String.class, false);
        modelName = getMediatorParameter(mc, Constants.MODEL_NAME, String.class, false);
        setResponseVariable(getMediatorParameter(
                mc, Constants.RESPONSE_VARIABLE, String.class, false));
        setOverwriteBody(getMediatorParameter(mc, Constants.OVERWRITE_BODY, Boolean.class, false));

        // Advanced configurations
        system = getMediatorParameter(mc, Constants.SYSTEM, String.class, false);
        temperature = getMediatorParameter(mc, Constants.TEMPERATURE, Double.class, true);
        maxTokens = getMediatorParameter(mc, Constants.MAX_TOKENS, Integer.class, true);
        topP = getMediatorParameter(mc, Constants.TOP_P, Double.class, true);
        frequencyPenalty = getMediatorParameter(mc, Constants.FREQUENCY_PENALTY, Double.class, true);
        seed = getMediatorParameter(mc, Constants.SEED, Integer.class, true);
        maxChatHistory = getMediatorParameter(mc, Constants.MAX_HISTORY, Integer.class, true);
        // TODO: Add toolExecutionTimeout to the ui schema
        Long toolTimeOut = getMediatorParameter(mc, Constants.TOOL_EXECUTION_TIMEOUT, Long.class, true);
        if (toolTimeOut != null) {
            toolExecutionTimeout = toolTimeOut * 1000;
        }

        chatMemories = new ConcurrentHashMap<>();
        chatMemoryProvider = memoryId -> MessageWindowChatMemory.builder().id(memoryId).maxMessages(maxChatHistory)
                .chatMemoryStore(new InMemoryChatMemoryStore()).build();

        // Generate tool specifications
        generateToolSpecifications(mc, AgentUtils.getTools(getParameter(mc, Constants.TOOLS)));
    }

    @Override
    public boolean mediate(MessageContext mc) {

        // Initialize the agent
        if (!isInitialized) {
            synchronized (lock) {
                if (!isInitialized) {
                    init(mc);
                    isInitialized = true;
                }
            }
        }

        String memoryId = getMediatorParameter(mc, Constants.USER_ID, String.class, false);
        String parsedPrompt = parsePrompt(mc);

        ChatLanguageModel model = null;
        try {
            model = LLMConnectionHandler.getChatModel(connectionName, modelName, temperature, maxTokens, topP,
                    frequencyPenalty, seed);
            if (model == null) {
                handleConnectorException(Errors.LLM_CONNECTION_ERROR, mc);
            }
        } catch (Exception e) {
            handleConnectorException(Errors.LLM_CONNECTION_ERROR, mc, e);
        }

        try {
            SynapseLog synLog = getLog(mc);

            if (synLog.isTraceOrDebugEnabled()) {
                synLog.traceOrDebug("Start : AI Agent mediator");

                if (synLog.isTraceTraceEnabled()) {
                    synLog.traceTrace("Message : " + mc.getEnvelope());
                }
            }

            // Build the AI service context
            AiServiceContext aiServiceContext = new SynapseAIContext(Assistant.class);
            ((SynapseAIContext) aiServiceContext).setToolResultVariable(toolResultExpressions);
            aiServiceContext.systemMessageProvider =
                    chatMemoryId -> system != null ? Optional.of(system) : Optional.of(DEFAULT_SYSTEM_PROMPT);
            aiServiceContext.chatModel = model;
            aiServiceContext.chatMemories = chatMemories;
            aiServiceContext.chatMemoryProvider = chatMemoryProvider;
            aiServiceContext.toolSpecifications = toolSpecifications;

            SystemMessage systemMessage = new SystemMessage(system);
            UserMessage userMessage = new UserMessage(parsedPrompt);

            aiServiceContext.chatMemory(memoryId).add(systemMessage);
            aiServiceContext.chatMemory(memoryId).add(userMessage);

            ChatRequestParameters parameters = ChatRequestParameters.builder()
                    .toolSpecifications(aiServiceContext.toolSpecifications)
//                    .responseFormat(ResponseFormat.JSON) // TODO: add response format support
                    .build();

            int executionsLeft = MAX_TOOL_EXECUTIONS_PER_REQUEST;

            SharedAgentDataHolder sharedAgentDataHolder = new SharedAgentDataHolder();

            // Clone the original MessageContext and save it to continue the next iteration of agent inference
            MessageContext orginalMessageContext = MessageHelper.cloneMessageContext(mc);
            sharedAgentDataHolder.setSynCtx(orginalMessageContext);
            sharedAgentDataHolder.setAiServiceContext(aiServiceContext);
            sharedAgentDataHolder.setExecutionsLeft(executionsLeft);
            sharedAgentDataHolder.setChatRequestParameters(parameters);
            sharedAgentDataHolder.setMemoryId(memoryId);
            mc.setProperty(Constants.AGENT_SHARED_DATA_HOLDER + "." + agentID, sharedAgentDataHolder);

            return inferenceAgentAndExecuteTools(mc, synLog);
        } catch (Exception e) {
            handleConnectorException(Errors.CHAT_COMPLETION_ERROR, mc, e);
        }
        return true;
    }

    private String parsePrompt(MessageContext mc) {

        String prompt = getMediatorParameter(mc, Constants.PROMPT, String.class, false);
        try {
            return InlineExpressionUtil.processInLineSynapseExpressionTemplate(mc, prompt);
        } catch (JaxenException e) {
            handleConnectorException(Errors.ERROR_PARSE_PROMPT, mc, e);
        }
        return prompt;
    }

    private boolean inferenceAgentAndExecuteTools(MessageContext mc, SynapseLog synLog) {

        boolean agentInferenceFinished = false;
        boolean toolExecutionResultAggregate = false;
        SharedAgentDataHolder sharedAgentDataHolder =
                (SharedAgentDataHolder) mc.getProperty(Constants.AGENT_SHARED_DATA_HOLDER + "." + agentID);
        String memoryId = sharedAgentDataHolder.getMemoryId();
        AiServiceContext aiServiceContext = sharedAgentDataHolder.getAiServiceContext();

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(aiServiceContext.chatMemory(memoryId).messages())
                .parameters(sharedAgentDataHolder.getChatRequestParameters())
                .build();

        if (synLog.isTraceOrDebugEnabled()) {
            synLog.traceOrDebug("Submitting chat request to the LLM model");
            if (synLog.isTraceTraceEnabled()) {
                synLog.traceTrace("Chat Request : " + chatRequest);
            }
        }

        ChatResponse chatResponse = aiServiceContext.chatModel.chat(chatRequest);

        sharedAgentDataHolder.getLock();
        sharedAgentDataHolder.setTokenUsageAccumulator(
                TokenUsage.sum(sharedAgentDataHolder.getTokenUsageAccumulator(), chatResponse.metadata().tokenUsage()));

        AiMessage aiMessage = chatResponse.aiMessage();

        if (aiServiceContext.hasChatMemory()) {
            sharedAgentDataHolder.addToMemory(aiMessage);
        }

        if (!aiMessage.hasToolExecutionRequests()) {
            agentInferenceFinished = true;
            sharedAgentDataHolder.setFinishChatResponse(chatResponse);
            sharedAgentDataHolder.releaseLock();
        } else {
            sharedAgentDataHolder.setCurrentToolExecutionRequests(aiMessage.toolExecutionRequests());
            sharedAgentDataHolder.releaseLock();
            int i = 0;
            //TODO: handle hallucinated tool execution requests
            int toolExecutionsSize = aiMessage.toolExecutionRequests().size();
            Iterator<ToolExecutionRequest> toolExecutionRequestIterator = aiMessage.toolExecutionRequests().iterator();
            while (toolExecutionRequestIterator.hasNext()) {
                ToolExecutionRequest toolExecutionRequest = toolExecutionRequestIterator.next();

                if (synLog.isTraceOrDebugEnabled()) {
                    synLog.traceOrDebug("Submitting " + (i + 1) + " of " + aiMessage.toolExecutionRequests().size() +
                            " messages for parallel tool execution");
                    if (synLog.isTraceTraceEnabled()) {
                        synLog.traceTrace("Tool Execution Request : " + toolExecutionRequest);
                    }
                }

                int executionsLeft = sharedAgentDataHolder.getAndDecrementExecutionsLeft();
                if (executionsLeft == 0) {
                    MessageContext orginalMessageContext = sharedAgentDataHolder.getSynCtx();
                    handleConnectorException(Errors.EXCEEDED_SEQUENTIAL_TOOL_EXECUTIONS, orginalMessageContext);
                }

                SequenceMediator toolMediator = toolInvokers.get(toolExecutionRequest.name());
                MessageContext clonedMessageContext =
                        getClonedMessageContextForToolExecution(mc, i + 1, toolExecutionsSize);

                // Store the current tool execution data in the cloned message context
                ToolExecutionDataHolder toolExecutionDataHolder = new ToolExecutionDataHolder();
                toolExecutionDataHolder.setToolExecutionRequest(toolExecutionRequest);
                toolExecutionDataHolder.setTotalToolExecutionCount(aiMessage.toolExecutionRequests().size());
                toolExecutionDataHolder.setCurrentToolExecutionIndex(i++);
                Value resultExpression =
                        ((SynapseAIContext) aiServiceContext).getToolResultVariable(toolExecutionRequest.name());
                toolExecutionDataHolder.setResultExpression(resultExpression);
                clonedMessageContext.setProperty(Constants.TOOL_EXECUTION_DATA_HOLDER + "." + agentID,
                        toolExecutionDataHolder);

                ContinuationStackManager.addReliantContinuationState(clonedMessageContext, 0, getMediatorPosition());
                executeTool(toolExecutionRequest, toolMediator, clonedMessageContext, toolExecutionsSize);
            }
        }
        OperationContext opCtx
                = ((Axis2MessageContext) mc).getAxis2MessageContext().getOperationContext();
        if (opCtx != null) {
            opCtx.setProperty(org.apache.axis2.Constants.RESPONSE_WRITTEN, "SKIP");
        }
        if (agentInferenceFinished) {
            return completeAgentInference(mc, sharedAgentDataHolder);
        }
        return toolExecutionResultAggregate;
    }

    @Override
    public boolean mediate(MessageContext messageContext, ContinuationState continuationState) {

        SynapseLog synLog = getLog(messageContext);
        if (synLog.isTraceOrDebugEnabled()) {
            synLog.traceOrDebug("AI Agent: Mediating from continuation state");
        }

        boolean result = false;
        boolean readyToAggregate = false;

        ToolExecutionDataHolder toolExecutionDataHolder = (ToolExecutionDataHolder) messageContext.getProperty(
                Constants.TOOL_EXECUTION_DATA_HOLDER + "." + agentID);
        if (!continuationState.hasChild()) {
            Object fromMediatorWorker = messageContext.getProperty(
                    SynapseConstants.CONTINUE_FLOW_TRIGGERED_FROM_MEDIATOR_WORKER);
            if (fromMediatorWorker != null && (Boolean) fromMediatorWorker) {
                messageContext.setProperty(SynapseConstants.CONTINUE_FLOW_TRIGGERED_FROM_MEDIATOR_WORKER, false);
            }
            readyToAggregate = true;
        } else {
            synLog.traceOrDebug(
                    "Continuation is triggered from a callback, mediating through the child continuation state");
            ToolExecutionRequest toolExecutionRequest = toolExecutionDataHolder.getToolExecutionRequest();
            SequenceMediator toolMediator = toolInvokers.get(toolExecutionRequest.name());
            FlowContinuableMediator mediator = (FlowContinuableMediator) toolMediator.getChild(0);
            result = mediator.mediate(messageContext, continuationState.getChildContState());
        }

        if (readyToAggregate) {
            return aggregateToolExecutionResult(messageContext, toolExecutionDataHolder.getTotalToolExecutionCount(),
                    synLog);
        }
        return result;
    }

    private boolean aggregateToolExecutionResult(MessageContext synCtx, int toolCount, SynapseLog synLog) {

        ContinuationStackManager.removeReliantContinuationState(synCtx);
        ToolExecutionAggregate aggregate = null;

        String correlationIdName = Constants.TOOL_EXECUTION_CORRELATION + "." + agentID;

        Object correlationID = synCtx.getProperty(correlationIdName);
        String correlation = (String) correlationID;

        synLog.traceOrDebug("Aggregating agent tool execution messages started for correlation : " + correlation);

        //No need to build the message as we are going to aggregate the variables
        while (aggregate == null) {
            synchronized (lock) {
                if (activeAggregates.containsKey(correlation)) {
                    aggregate = activeAggregates.get(correlation);
                    if (aggregate != null) {
                        if (!aggregate.getLock()) {
                            aggregate = null;
                        }
                    }
                } else {
                    if (synLog.isTraceOrDebugEnabled()) {
                        synLog.traceOrDebug("Creating new Aggregator for agent tool execution - " +
                                (toolExecutionTimeout > 0 ? "expires in : "
                                        + (toolExecutionTimeout / 1000) + "secs" : "without expiry time"));
                    }
                    if (isAggregationCompleted(synCtx)) {
                        return false;
                    }

                    aggregate = new ToolExecutionAggregate(
                            synCtx.getEnvironment(),
                            correlation,
                            toolExecutionTimeout,
                            toolCount, this, synCtx.getFaultStack().peek());

                    if (toolExecutionTimeout > 0) {
                        synchronized (aggregate) {
                            if (!aggregate.isCompleted()) {
                                try {
                                    log.info("Scheduling Synapse timer for agent tool execution with correlation : " +
                                            correlation);
                                    synCtx.getConfiguration().getSynapseTimer().
                                            schedule(aggregate, toolExecutionTimeout);
                                } catch (IllegalStateException e) {
                                    log.warn("Synapse timer already cancelled. Resetting Synapse timer");
                                    synCtx.getConfiguration().setSynapseTimer(new Timer(true));
                                    synCtx.getConfiguration().getSynapseTimer().
                                            schedule(aggregate, toolExecutionTimeout);
                                }
                            }
                        }
                    }
                    aggregate.getLock();
                    activeAggregates.put(correlation, aggregate);
                }
            }
        }

        // if there is an aggregate continue on aggregation
        if (aggregate != null) {
            boolean collected = aggregate.addMessage(synCtx);
            if (synLog.isTraceOrDebugEnabled()) {
                if (collected) {
                    synLog.traceOrDebug("Collected a message during aggregation");
                    if (synLog.isTraceTraceEnabled()) {
                        synLog.traceTrace("Collected message : " + synCtx);
                    }
                }
            }
            if (aggregate.isComplete(synLog)) {
                synLog.traceOrDebug("Aggregation completed for agent tool executions");
                return completeAggregate(aggregate);
            } else {
                aggregate.releaseLock();
            }
        } else {
            synLog.traceOrDebug("Unable to find an aggregate for this message - skip");
        }
        return false;
    }

    public boolean completeAggregate(ToolExecutionAggregate aggregate) {

        boolean markedCompletedNow = false;
        boolean wasComplete = aggregate.isCompleted();
        if (wasComplete) {
            return false;
        }
        log.debug("Aggregation completed or timed out");

        // cancel the timer
        synchronized (this) {
            if (!aggregate.isCompleted()) {
                aggregate.cancel();
                aggregate.setCompleted(true);

                MessageContext lastMessage = aggregate.getLastMessage();
                if (lastMessage != null) {
                    Object aggregateTimeoutHolderObj =
                            lastMessage.getProperty(Constants.AGENT_SHARED_DATA_HOLDER + "." + agentID);

                    if (aggregateTimeoutHolderObj != null) {
                        SharedDataHolder sharedDataHolder = (SharedDataHolder) aggregateTimeoutHolderObj;
                        sharedDataHolder.markAggregationCompletion();
                    }
                } else {
                    aggregate.getSharedAgentDataHolder().markAggregationCompletion();
                }
                markedCompletedNow = true;
            }
        }

        if (!markedCompletedNow) {
            return false;
        }
        MessageContext originalMessageContext = getOriginalMessageContext(aggregate);
        if (originalMessageContext != null) {
            // Clone the message context to preserve the original message context for the next iteration
            MessageContext clonedMessageContext = getClonedMessageContext(originalMessageContext);
            if (clonedMessageContext != null) {
                collectToolExecutionResults(clonedMessageContext, aggregate);
                aggregate.clear();
                activeAggregates.remove(aggregate.getCorrelation());
                return continueAgentInference(clonedMessageContext);
            } else {
                handleException(aggregate, "Error cloning the original message context", null,
                        originalMessageContext);
                return false;
            }
        } else {
            handleException(aggregate, "Error retrieving the original message context", null,
                    aggregate.getLastMessage());
            return false;
        }
    }

    private boolean continueAgentInference(MessageContext originalMessageContext) {

        SharedAgentDataHolder sharedAgentDataHolder = (SharedAgentDataHolder) originalMessageContext.getProperty(
                Constants.AGENT_SHARED_DATA_HOLDER + "." + agentID);
        if (sharedAgentDataHolder != null) {
            sharedAgentDataHolder.resetAggregationCompletion();
            return inferenceAgentAndExecuteTools(originalMessageContext, getLog(originalMessageContext));
        }
        return false;
    }

    private void collectToolExecutionResults(MessageContext originalMessageContext, ToolExecutionAggregate aggregate) {

        log.debug("Collecting aggregated tool executions responses to the original message context");
        if (aggregate.getToolCount() != aggregate.getMessages().size()) {
            log.warn("The agent tool executions are not finished for the correlation : "
                    + aggregate.getCorrelation());
        }
        SharedAgentDataHolder sharedAgentDataHolder = extractToolExecutionResult(aggregate);
        originalMessageContext.setProperty(Constants.AGENT_SHARED_DATA_HOLDER + "." + agentID,
                sharedAgentDataHolder);
    }

    private SharedAgentDataHolder extractToolExecutionResult(ToolExecutionAggregate aggregate) {
        //TODO: Check NPE
        SharedAgentDataHolder sharedAgentDataHolder;
        if (aggregate.getLastMessage() != null) {
            sharedAgentDataHolder = (SharedAgentDataHolder) aggregate.getLastMessage().getProperty(
                    Constants.AGENT_SHARED_DATA_HOLDER + "." + agentID);
        } else {
            sharedAgentDataHolder = aggregate.getSharedAgentDataHolder();
        }
        List<ToolExecutionRequest> toolExecutionRequestList = new ArrayList<>(
                sharedAgentDataHolder.getCurrentToolExecutionRequests());
        for (MessageContext synCtx : aggregate.getMessages()) {
            ToolExecutionDataHolder toolExecutionDataHolder = (ToolExecutionDataHolder) synCtx.getProperty(
                    Constants.TOOL_EXECUTION_DATA_HOLDER + "." + agentID);
            Value resultVariable = toolExecutionDataHolder.getResultExpression();
            if (resultVariable != null) {
                String result = resultVariable.evaluateValue(synCtx);
                if (StringUtils.isNoneBlank(result)) {
                    addToolExecutionResultToDataHolder(sharedAgentDataHolder, toolExecutionDataHolder
                            .getToolExecutionRequest(), result);
                    toolExecutionRequestList.remove(toolExecutionDataHolder.getToolExecutionRequest());
                }
            }
        }
        if (!toolExecutionRequestList.isEmpty()) {
            for (ToolExecutionRequest toolExecutionRequest : toolExecutionRequestList) {
                log.warn("Tool execution failed or timed out. Marking it as failed. Tool: "
                        + toolExecutionRequest.name());
                addToolExecutionResultToDataHolder(sharedAgentDataHolder, toolExecutionRequest,
                        Constants.TOOL_EXECUTION_FAILED);
            }
        }
        return sharedAgentDataHolder;
    }

    private void addToolExecutionResultToDataHolder(SharedAgentDataHolder sharedAgentDataHolder,
                                                    ToolExecutionRequest executionRequest, String result) {

        sharedAgentDataHolder.getToolExecutions().add(ToolExecution.builder()
                .request(executionRequest)
                .result(result.toString())
                .build());
        ToolExecutionResultMessage toolExecutionResultMessage = ToolExecutionResultMessage.from(
                executionRequest,
                result.toString());
        sharedAgentDataHolder.addToMemory(toolExecutionResultMessage);
    }

    private boolean completeAgentInference(MessageContext mc, SharedAgentDataHolder sharedAgentDataHolder) {

        MessageContext originalMessageContext = getOriginalMessageContext(sharedAgentDataHolder);
        ChatResponse finishChatResponse = sharedAgentDataHolder.getFinishChatResponse();
        Result<Object> parsedResponse =
                parseFinalResponse(originalMessageContext, finishChatResponse, sharedAgentDataHolder);
        if (parsedResponse != null) {
            handleConnectorResponse(originalMessageContext, parsedResponse, null, null);
        } else {
            handleConnectorException(Errors.INVALID_OUTPUT_TYPE, originalMessageContext);
        }

        ContinuationStackManager.updateSeqContinuationState(originalMessageContext, getMediatorPosition());

        getLog(originalMessageContext).traceOrDebug("End : Agent mediator");

        boolean result;
        do {
            SeqContinuationState seqContinuationState =
                    (SeqContinuationState) ContinuationStackManager.peakContinuationStateStack(originalMessageContext);
            if (seqContinuationState != null) {
                SequenceMediator sequenceMediator =
                        ContinuationStackManager.retrieveSequence(originalMessageContext, seqContinuationState);
                result = sequenceMediator.mediate(originalMessageContext, seqContinuationState);
                if (RuntimeStatisticCollector.isStatisticsEnabled()) {
                    sequenceMediator.reportCloseStatistics(originalMessageContext, null);
                }
            } else {
                break;
            }
        } while (result && !originalMessageContext.getContinuationStateStack().isEmpty());

        return false;
    }

    private void handleException(ToolExecutionAggregate aggregate, String msg, Exception exception,
                                 MessageContext msgContext) {

        aggregate.clear();
        activeAggregates.remove(aggregate.getCorrelation());
        if (exception != null) {
            super.handleException(msg, exception, msgContext);
        } else {
            super.handleException(msg, msgContext);
        }
    }

    private MessageContext getOriginalMessageContext(ToolExecutionAggregate aggregate) {

        MessageContext lastMessage = aggregate.getLastMessage();
        if (lastMessage != null) {
            Object aggregateHolderObj = lastMessage.getProperty(Constants.AGENT_SHARED_DATA_HOLDER + "." + agentID);
            return getOriginalMessageContext((SharedAgentDataHolder) aggregateHolderObj);
        } else if (aggregate.getSharedAgentDataHolder() != null) {
            return getOriginalMessageContext(aggregate.getSharedAgentDataHolder());
        }
        return null;
    }

    private MessageContext getOriginalMessageContext(SharedAgentDataHolder sharedAgentDataHolder) {

        if (sharedAgentDataHolder != null) {
            return sharedAgentDataHolder.getSynCtx();
        }
        return null;
    }

    private boolean isAggregationCompleted(MessageContext synCtx) {

        Object aggregateTimeoutHolderObj = synCtx.getProperty(Constants.AGENT_SHARED_DATA_HOLDER + "." + agentID);

        if (aggregateTimeoutHolderObj != null) {
            SharedDataHolder sharedDataHolder = (SharedDataHolder) aggregateTimeoutHolderObj;
            if (sharedDataHolder.isAggregationCompleted()) {
                log.debug("Received a response for already completed Aggregate");
                return true;
            }
        }
        return false;
    }

    private MessageContext getClonedMessageContextForToolExecution(MessageContext mc, int toolId,
                                                                   int totalToolExecutions) {

        MessageContext newCtx = getClonedMessageContext(mc);

        // Set isServerSide property in the cloned message context
        ((Axis2MessageContext) newCtx).getAxis2MessageContext().setServerSide(
                ((Axis2MessageContext) mc).getAxis2MessageContext().isServerSide());

        // Set the continue mediation property in the cloned message context to continue from MediatorWorker
        newCtx.setProperty(SynapseConstants.SCATTER_MESSAGES, true);
        newCtx.setProperty(Constants.AGENT_TOOL_EXECUTION + "." + agentID,
                toolId + EIPConstants.MESSAGE_SEQUENCE_DELEMITER + totalToolExecutions);
        return newCtx;
    }

    private MessageContext getClonedMessageContext(MessageContext mc) {

        MessageContext newCtx = null;
        try {
            newCtx = MessageHelper.cloneMessageContext(mc);
            newCtx.setProperty(Constants.TOOL_EXECUTION_CORRELATION + "." + agentID, mc.getMessageID());
        } catch (AxisFault axisFault) {
            handleException("Error cloning the message context", axisFault, mc);
        }
        return newCtx;
    }

    private void generateToolSpecifications(MessageContext mc, List<Tool> tools) {

        if (tools == null) {
            return;
        }
        if (tools != null && !tools.isEmpty()) {
            for (Tool tool : tools) {
                String toolTemplate = tool.getTemplate();
                Mediator mediator = mc.getSequenceTemplate(toolTemplate);
                if (mediator instanceof TemplateMediator) {
                    TemplateMediator templateMediator = (TemplateMediator) mediator;
                    String name = tool.getName();
                    String description = templateMediator.getDescription();
                    List<TemplateParam> templateParams = new ArrayList<>(templateMediator.getParameters());
                    JsonObjectSchema parameterSchema = AgentUtils.generateParameterSchema(templateParams);
                    ToolSpecification toolSpecification =
                            ToolSpecification.builder().name(name).description(description).parameters(parameterSchema)
                                    .build();
                    toolResultExpressions.put(tool.getName(), tool.getResultExpression());
                    toolSpecifications.add(toolSpecification);

                    // Add invoker for tool
                    SequenceMediator toolInvoker = new SequenceMediator();
                    toolInvoker.setSequenceType(SequenceType.ANON);
                    InvokeMediator invoker = new InvokeMediator();
                    invoker.setTargetTemplate(toolTemplate);

                    toolInvoker.addChild(invoker);
                    toolInvokers.put(name, toolInvoker);
                }
            }
        }
    }

    private boolean executeTool(ToolExecutionRequest toolExecutionRequest, SequenceMediator invoker,
                                MessageContext mc, int toolCount) {

        Map<String, Object> arguments = AgentUtils.argumentsAsMap(toolExecutionRequest.arguments());
        Iterator<Map.Entry<String, Object>> iterator = arguments.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Object> entry = iterator.next();
            EIPUtils.createSynapseEIPTemplateProperty(mc, toolExecutionRequest.name(), entry.getKey(),
                    entry.getValue());
        }
        if (log.isDebugEnabled()) {
            log.debug("Asynchronously mediating using the tool invoker anonymous sequence");
        }
        startToolExecutionTimeout(mc, toolCount);
        mc.getEnvironment().injectAsync(mc, invoker);

        return false; //Async invocation
    }

    private void startToolExecutionTimeout(MessageContext mc, int toolCount) {

        String correlationIdName = Constants.TOOL_EXECUTION_CORRELATION + "." + agentID;
        Object correlationID = mc.getProperty(correlationIdName);
        String correlation = (String) correlationID;

        SharedAgentDataHolder sharedAgentDataHolder = (SharedAgentDataHolder) mc.getProperty(
                Constants.AGENT_SHARED_DATA_HOLDER + "." + agentID);
        ToolExecutionAggregate aggregate = new ToolExecutionAggregate(
                mc.getEnvironment(),
                correlation,
                toolExecutionTimeout,
                toolCount, this, mc.getFaultStack().peek(), sharedAgentDataHolder);

        try {
            log.info("Scheduling Synapse timer for agent tool execution with correlation : " +
                    correlation);
            mc.getConfiguration().getSynapseTimer().
                    schedule(aggregate, toolExecutionTimeout);
        } catch (IllegalStateException e) {
            log.warn("Synapse timer already cancelled. Resetting Synapse timer");
            mc.getConfiguration().setSynapseTimer(new Timer(true));
            mc.getConfiguration().getSynapseTimer().
                    schedule(aggregate, toolExecutionTimeout);
        }
        activeAggregates.put(correlation, aggregate);
    }

    private Result<Object> parseFinalResponse(MessageContext mc, ChatResponse chatResponse,
                                              SharedAgentDataHolder agentDataHolder) {

        TokenUsage tokenUsageAccumulator = agentDataHolder.getTokenUsageAccumulator();
        FinishReason finishReason = chatResponse.metadata().finishReason();
        Response<AiMessage>
                response = Response.from(chatResponse.aiMessage(), tokenUsageAccumulator, finishReason);

        // TODO: Support different output types such as int, boolean, Json(as schema), etc.
        Object parsedResponse = serviceOutputParser.parse(response, String.class);
        Result<Object> parsedResult = Result.builder()
                .content(parsedResponse)
                .tokenUsage(tokenUsageAccumulator)
                .finishReason(finishReason)
                .toolExecutions(agentDataHolder.getToolExecutions())
                .build();
        return parsedResult;
    }

    public String getAgentID() {

        return agentID;
    }

    @Override
    public void execute(MessageContext messageContext) {
        // This method is not needed as we override the mediate method
    }
}
