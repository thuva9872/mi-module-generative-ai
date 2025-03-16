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
import org.apache.synapse.config.xml.ValueFactory;
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
import org.apache.synapse.mediators.template.ResolvedInvokeParam;
import org.apache.synapse.mediators.template.TemplateMediator;
import org.apache.synapse.mediators.template.TemplateParam;
import org.apache.synapse.util.InlineExpressionUtil;
import org.apache.synapse.util.MessageHelper;
import org.apache.synapse.util.xpath.SynapseExpression;
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
    private final ServiceOutputParser serviceOutputParser = new ServiceOutputParser();

    // Currently we are assigning a id for each agent in the operation configuration. This is used to create a map for
    // each agent to store the tool execution related data. This is a temporary solution.
    // This was done to reduce redundant processing of same configurations for each request.
    // TODO: Replace this with a better approach if possible

    // agentId -> (correlationId -> ToolExecutionAggregate)
    private final Map<String, Map<String, ToolExecutionAggregate>> activeAggregates =
            Collections.synchronizedMap(new HashMap<>());

    // agentId -> lock
    private final Map<String, Object> lockMap = Collections.synchronizedMap(new HashMap<>());

    // agentId -> List of tool specifications
    private final Map<String, List<ToolSpecification>> toolSpecifications = new HashMap<>();

    // agentId -> isInitialized
    private final Map<String, Boolean> isInitialized = new HashMap<>();

    // agentId -> (toolName -> toolInvoker)
    private final Map<String, Map<String, SequenceMediator>> toolInvokers = new HashMap<>();

    // agentId -> (toolName -> resultExpression)
    private final Map<String, Map<String, Value>> toolResultExpressions = new HashMap<>();

    // agentId -> (memoryId -> ChatMemory)
    private final Map<String, Map<Object, ChatMemory>> chatMemories = new HashMap<>();

    // agentId -> ChatMemoryProvider
    private final Map<String, ChatMemoryProvider> chatMemoryProvider = new HashMap<>();

    private long toolExecutionTimeout = 10000;

    public Object getLock(String agentId) {

        return lockMap.computeIfAbsent(agentId, key -> new Object());
    }

    public interface Assistant {

        Result<String> chat(@MemoryId String memoryId, @dev.langchain4j.service.UserMessage String message);
    }

    private void init(MessageContext mc, String agentID, int maxChatHistory) {

        chatMemories.put(agentID, new ConcurrentHashMap<>());
        chatMemoryProvider.put(agentID,
                memoryId -> MessageWindowChatMemory.builder().id(memoryId).maxMessages(maxChatHistory)
                        .chatMemoryStore(new InMemoryChatMemoryStore()).build());

        // Generate tool specifications
        toolSpecifications.put(agentID, new ArrayList<>());
        toolInvokers.put(agentID, new HashMap<>());
        toolResultExpressions.put(agentID, new HashMap<>());
        generateToolSpecifications(agentID, mc, getTools(mc));
    }

    public List<Tool> getTools(MessageContext mc) {

        Object toolsObject = getParameter(mc, Constants.TOOLS);
        List<Tool> tools = new ArrayList<>();
        if (toolsObject instanceof ResolvedInvokeParam toolsParams) {
            List<ResolvedInvokeParam> toolList = toolsParams.getChildren();
            for (ResolvedInvokeParam toolParam : toolList) {
                Map<String, Object> toolAttributes = toolParam.getAttributes();
                String name = (String) toolAttributes.get(Constants.NAME);
                String template = (String) toolAttributes.get(Constants.TEMPLATE);
                String resultExpression = (String) toolAttributes.get(Constants.RESULT_EXPRESSION);
                if (StringUtils.isBlank(name) || StringUtils.isBlank(template) ||
                        StringUtils.isBlank(resultExpression)) {
                    handleConnectorException(Errors.INVALID_TOOL_CONFIGURATION, mc);
                }
                name = name.replaceAll("\\s", "_");
                SynapseExpression resultSynapseExpression =
                        new ValueFactory().createSynapseExpression(resultExpression);
                Value resultExpressionValue = new Value(resultSynapseExpression);
                String description = (String) toolAttributes.get(Constants.DESCRIPTION);
                Tool tool = new Tool(name, template, resultExpressionValue, description);
                tools.add(tool);
            }
        }
        return tools;
    }

    @Override
    public boolean mediate(MessageContext mc) {

        String agentID = getMediatorParameter(mc, Constants.AGENT_ID, String.class, false);
        if (StringUtils.isEmpty(agentID)) {
            handleConnectorException(Errors.AGENT_ID_NOT_PROVIDED, mc);
        }
        String connectionName = getProperty(mc, Constants.CONNECTION_NAME, String.class, false);
        String modelName = getMediatorParameter(mc, Constants.MODEL_NAME, String.class, false);
        String responseVariable = getMediatorParameter(
                mc, Constants.RESPONSE_VARIABLE, String.class, false);
        Boolean overWriteBody = getMediatorParameter(mc, Constants.OVERWRITE_BODY, Boolean.class, false);

        // Advanced configurations
        String system = getMediatorParameter(mc, Constants.SYSTEM, String.class, false);
        Double temperature = getMediatorParameter(mc, Constants.TEMPERATURE, Double.class, true);
        Integer maxTokens = getMediatorParameter(mc, Constants.MAX_TOKENS, Integer.class, true);
        Double topP = getMediatorParameter(mc, Constants.TOP_P, Double.class, true);
        Double frequencyPenalty = getMediatorParameter(mc, Constants.FREQUENCY_PENALTY, Double.class, true);
        Integer seed = getMediatorParameter(mc, Constants.SEED, Integer.class, true);
        Integer maxChatHistory = getMediatorParameter(mc, Constants.MAX_HISTORY, Integer.class, true);
        if (maxChatHistory == null) {
            maxChatHistory = 10;
        }
        // TODO: Add toolExecutionTimeout to the ui schema
//        Long toolExecutionTimeout = getMediatorParameter(mc, Constants.TOOL_EXECUTION_TIMEOUT, Long.class, true);
//        if (toolExecutionTimeout != null) {
//            toolExecutionTimeout = toolExecutionTimeout * 1000;
//        } else {
//            toolExecutionTimeout = 10000L;
//        }

        // Initialize the agent
        if (isInitialized.get(agentID) == null || !isInitialized.get(agentID)) {
            synchronized (getLock(agentID)) {
                if (isInitialized.get(agentID) == null || !isInitialized.get(agentID)) {
                    init(mc, agentID, maxChatHistory);
                    isInitialized.put(agentID, true);
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
            ((SynapseAIContext) aiServiceContext).setToolResultVariable(toolResultExpressions.get(agentID));
            aiServiceContext.systemMessageProvider =
                    chatMemoryId -> system != null ? Optional.of(system) : Optional.of(DEFAULT_SYSTEM_PROMPT);
            aiServiceContext.chatModel = model;
            aiServiceContext.chatMemories = chatMemories.get(agentID);
            aiServiceContext.chatMemoryProvider = chatMemoryProvider.get(agentID);
            aiServiceContext.toolSpecifications = toolSpecifications.get(agentID);

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
            sharedAgentDataHolder.setAgentId(agentID);
            sharedAgentDataHolder.setOverwriteBody(overWriteBody);
            sharedAgentDataHolder.setResponseVariable(responseVariable);
            // Clone the original MessageContext and save it to continue the next iteration of agent inference
            MessageContext orginalMessageContext = MessageHelper.cloneMessageContext(mc);
            sharedAgentDataHolder.setSynCtx(orginalMessageContext);
            sharedAgentDataHolder.setAiServiceContext(aiServiceContext);
            sharedAgentDataHolder.setExecutionsLeft(executionsLeft);
            sharedAgentDataHolder.setChatRequestParameters(parameters);
            sharedAgentDataHolder.setMemoryId(memoryId);
            mc.setProperty(Constants.AGENT_SHARED_DATA_HOLDER + "." + agentID, sharedAgentDataHolder);

            return inferenceAgentAndExecuteTools(mc, synLog, agentID);
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

    private boolean inferenceAgentAndExecuteTools(MessageContext mc, SynapseLog synLog, String agentID) {

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

                SequenceMediator toolMediator = toolInvokers.get(agentID).get(toolExecutionRequest.name());
                MessageContext clonedMessageContext =
                        getClonedMessageContextForToolExecution(mc, agentID, i + 1, toolExecutionsSize);

                // Store the current tool execution data in the cloned message context
                ToolExecutionDataHolder toolExecutionDataHolder = new ToolExecutionDataHolder();
                toolExecutionDataHolder.setAgentID(agentID);
                toolExecutionDataHolder.setToolExecutionRequest(toolExecutionRequest);
                toolExecutionDataHolder.setTotalToolExecutionCount(aiMessage.toolExecutionRequests().size());
                toolExecutionDataHolder.setCurrentToolExecutionIndex(i++);
                Value resultExpression =
                        ((SynapseAIContext) aiServiceContext).getToolResultVariable(toolExecutionRequest.name());
                toolExecutionDataHolder.setResultExpression(resultExpression);
                clonedMessageContext.setProperty(Constants.TOOL_EXECUTION_DATA_HOLDER + "." + agentID,
                        toolExecutionDataHolder);

                ContinuationStackManager.addReliantContinuationState(clonedMessageContext, 0, getMediatorPosition());
                executeTool(agentID, toolExecutionRequest, toolMediator, clonedMessageContext, toolExecutionsSize);
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

        String agentID = messageContext.getProperty(Constants.AGENT_ID).toString();
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
            SequenceMediator toolMediator =
                    toolInvokers.get(toolExecutionDataHolder.getAgentID()).get(toolExecutionRequest.name());
            FlowContinuableMediator mediator = (FlowContinuableMediator) toolMediator.getChild(0);
            result = mediator.mediate(messageContext, continuationState.getChildContState());
        }

        if (readyToAggregate) {
            return aggregateToolExecutionResult(toolExecutionDataHolder.getAgentID(), messageContext,
                    toolExecutionDataHolder.getTotalToolExecutionCount(), synLog);
        }
        return result;
    }

    private boolean aggregateToolExecutionResult(String agentID, MessageContext synCtx, int toolCount,
                                                 SynapseLog synLog) {

        ContinuationStackManager.removeReliantContinuationState(synCtx);
        ToolExecutionAggregate aggregate = null;

        String correlationIdName = Constants.TOOL_EXECUTION_CORRELATION + "." + agentID;

        Object correlationID = synCtx.getProperty(correlationIdName);
        String correlation = (String) correlationID;

        synLog.traceOrDebug("Aggregating agent tool execution messages started for correlation : " + correlation);

        //No need to build the message as we are going to aggregate the variables
        while (aggregate == null) {
            synchronized (getLock(agentID)) {
                if (activeAggregates.containsKey(agentID) && activeAggregates.get(agentID).containsKey(correlation)) {
                    aggregate = activeAggregates.get(agentID).get(correlation);
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
                    if (isAggregationCompleted(agentID, synCtx)) {
                        return false;
                    }

                    aggregate = new ToolExecutionAggregate(
                            synCtx.getEnvironment(),
                            correlation,
                            toolExecutionTimeout,
                            toolCount, this, synCtx.getFaultStack().peek());
                    aggregate.setAgentID(agentID);
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
                    Map<String, ToolExecutionAggregate> agentAggregates = new HashMap<>();
                    agentAggregates.put(correlation, aggregate);
                    activeAggregates.put(agentID, agentAggregates);
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
                            lastMessage.getProperty(Constants.AGENT_SHARED_DATA_HOLDER + "." + aggregate.getAgentID());

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
            MessageContext clonedMessageContext =
                    getClonedMessageContext(originalMessageContext, aggregate.getAgentID());
            if (clonedMessageContext != null) {
                collectToolExecutionResults(clonedMessageContext, aggregate);
                aggregate.clear();
                activeAggregates.get(aggregate.getAgentID()).remove(aggregate.getCorrelation());
                return continueAgentInference(aggregate.getAgentID(), clonedMessageContext);
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

    private boolean continueAgentInference(String agentID, MessageContext originalMessageContext) {

        SharedAgentDataHolder sharedAgentDataHolder = (SharedAgentDataHolder) originalMessageContext.getProperty(
                Constants.AGENT_SHARED_DATA_HOLDER + "." + agentID);
        if (sharedAgentDataHolder != null) {
            sharedAgentDataHolder.resetAggregationCompletion();
            return inferenceAgentAndExecuteTools(originalMessageContext, getLog(originalMessageContext),
                    sharedAgentDataHolder.getAgentId());
        }
        return false;
    }

    private void collectToolExecutionResults(MessageContext originalMessageContext, ToolExecutionAggregate aggregate) {

        log.debug("Collecting aggregated tool executions responses to the original message context");
        if (aggregate.getToolCount() != aggregate.getMessages().size()) {
            log.warn("The agent tool executions are not finished for the correlation : "
                    + aggregate.getCorrelation());
        }
        SharedAgentDataHolder sharedAgentDataHolder = extractToolExecutionResult(aggregate.getAgentID(), aggregate);
        originalMessageContext.setProperty(Constants.AGENT_SHARED_DATA_HOLDER + "." + aggregate.getAgentID(),
                sharedAgentDataHolder);
    }

    private SharedAgentDataHolder extractToolExecutionResult(String agentID, ToolExecutionAggregate aggregate) {

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
            handleConnectorResponse(originalMessageContext, sharedAgentDataHolder.getResponseVariable(),
                    sharedAgentDataHolder.isOverwriteBody(), parsedResponse, null, null);
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
        activeAggregates.get(aggregate.getAgentID()).remove(aggregate.getCorrelation());
        if (exception != null) {
            super.handleException(msg, exception, msgContext);
        } else {
            super.handleException(msg, msgContext);
        }
    }

    private MessageContext getOriginalMessageContext(ToolExecutionAggregate aggregate) {

        MessageContext lastMessage = aggregate.getLastMessage();
        if (lastMessage != null) {
            Object aggregateHolderObj =
                    lastMessage.getProperty(Constants.AGENT_SHARED_DATA_HOLDER + "." + aggregate.getAgentID());
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

    private boolean isAggregationCompleted(String agentID, MessageContext synCtx) {

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

    private MessageContext getClonedMessageContextForToolExecution(MessageContext mc, String agentID, int toolId,
                                                                   int totalToolExecutions) {

        MessageContext newCtx = getClonedMessageContext(mc, agentID);

        // Set isServerSide property in the cloned message context
        ((Axis2MessageContext) newCtx).getAxis2MessageContext().setServerSide(
                ((Axis2MessageContext) mc).getAxis2MessageContext().isServerSide());

        // Set the continue mediation property in the cloned message context to continue from MediatorWorker
        newCtx.setProperty(Constants.AGENT_ID, agentID);
        newCtx.setProperty(SynapseConstants.SCATTER_MESSAGES, true);
        newCtx.setProperty(Constants.AGENT_TOOL_EXECUTION + "." + agentID,
                toolId + EIPConstants.MESSAGE_SEQUENCE_DELEMITER + totalToolExecutions);
        return newCtx;
    }

    private MessageContext getClonedMessageContext(MessageContext mc, String agentID) {

        MessageContext newCtx = null;
        try {
            newCtx = MessageHelper.cloneMessageContext(mc);
            newCtx.setProperty(Constants.TOOL_EXECUTION_CORRELATION + "." + agentID, mc.getMessageID());
        } catch (AxisFault axisFault) {
            handleException("Error cloning the message context", axisFault, mc);
        }
        return newCtx;
    }

    private void generateToolSpecifications(String agentID, MessageContext mc, List<Tool> tools) {

        if (tools == null) {
            return;
        }
        if (tools != null && !tools.isEmpty()) {
            for (Tool tool : tools) {
                String toolTemplate = tool.getTemplate();
                Mediator mediator = mc.getSequenceTemplate(toolTemplate);
                if (mediator instanceof TemplateMediator) {
                    TemplateMediator templateMediator = (TemplateMediator) mediator;
                    String template = tool.getTemplate();
                    String description = templateMediator.getDescription();
                    List<TemplateParam> templateParams = new ArrayList<>(templateMediator.getParameters());
                    JsonObjectSchema parameterSchema = AgentUtils.generateParameterSchema(templateParams);
                    ToolSpecification toolSpecification =
                            ToolSpecification.builder().name(template).description(description)
                                    .parameters(parameterSchema)
                                    .build();
                    toolResultExpressions.get(agentID).put(template, tool.getResultExpression());
                    toolSpecifications.get(agentID).add(toolSpecification);

                    // Add invoker for tool
                    SequenceMediator toolInvoker = new SequenceMediator();
                    toolInvoker.setSequenceType(SequenceType.ANON);
                    InvokeMediator invoker = new InvokeMediator();
                    invoker.setTargetTemplate(toolTemplate);

                    toolInvoker.addChild(invoker);
                    toolInvokers.get(agentID).put(template, toolInvoker);
                }
            }
        }
    }

    private boolean executeTool(String agentID, ToolExecutionRequest toolExecutionRequest, SequenceMediator invoker,
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

        String correlationIdName = Constants.TOOL_EXECUTION_CORRELATION + "." + agentID;
        Object correlationID = mc.getProperty(correlationIdName);
        String correlation = (String) correlationID;
        if (!activeAggregates.containsKey(agentID) || !activeAggregates.get(agentID).containsKey(correlation)) {
            startToolExecutionTimeout(agentID, mc, correlation, toolCount);
        }
        mc.getEnvironment().injectAsync(mc, invoker);

        return false; //Async invocation
    }

    private void startToolExecutionTimeout(String agentID, MessageContext mc, String correlation, int toolCount) {

        SharedAgentDataHolder sharedAgentDataHolder = (SharedAgentDataHolder) mc.getProperty(
                Constants.AGENT_SHARED_DATA_HOLDER + "." + agentID);
        ToolExecutionAggregate aggregate = new ToolExecutionAggregate(
                mc.getEnvironment(),
                correlation,
                toolExecutionTimeout,
                toolCount, this, mc.getFaultStack().peek(), sharedAgentDataHolder);
        aggregate.setAgentID(agentID);
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
        if (activeAggregates.containsKey(agentID)) {
            activeAggregates.get(agentID).put(correlation, aggregate);
        } else {
            Map<String, ToolExecutionAggregate> aggregateMap = new HashMap<>();
            aggregateMap.put(correlation, aggregate);
            activeAggregates.put(agentID, aggregateMap);
        }
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

    @Override
    public void execute(MessageContext messageContext, String responseVariable, Boolean overwriteBody) {
        // This method is not needed as we override the mediate method
    }
}
