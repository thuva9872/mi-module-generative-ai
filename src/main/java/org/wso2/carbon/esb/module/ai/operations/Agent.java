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

package org.wso2.carbon.esb.module.ai.operations;

import com.google.gson.Gson;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
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
import org.apache.synapse.mediators.template.TemplateMediator;
import org.apache.synapse.mediators.template.TemplateParam;
import org.apache.synapse.util.InlineExpressionUtil;
import org.apache.synapse.util.MessageHelper;
import org.apache.synapse.util.xpath.SynapseExpression;
import org.jaxen.JaxenException;
import org.wso2.carbon.esb.module.ai.AbstractAIMediator;
import org.wso2.carbon.esb.module.ai.Constants;
import org.wso2.carbon.esb.module.ai.Errors;
import org.wso2.carbon.esb.module.ai.SynapseAiContext;
import org.wso2.carbon.esb.module.ai.llm.LLMConnectionHandler;
import org.wso2.carbon.esb.module.ai.operations.agent.AgentConstant;
import org.wso2.carbon.esb.module.ai.operations.agent.SharedAgentDataHolder;
import org.wso2.carbon.esb.module.ai.operations.agent.Tool;
import org.wso2.carbon.esb.module.ai.operations.agent.ToolExecutionAggregate;
import org.wso2.carbon.esb.module.ai.operations.agent.ToolExecutionDataHolder;
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
    private boolean isInitialized =false;
    private final Map<String, SequenceMediator> toolInvokers; // Map of tool name to sequence mediator
    private final Map<String, Value> toolResultExpressions; // Map of tool name to result expressions
    private ChatMemoryProvider chatMemoryProvider;
    private Map<Object, ChatMemory> chatMemories;

    // Chat configurations
    private String memoryId;
    private Integer maxChatHistory = 10;
    private String modelName;
    private Double temperature;
    private Integer maxTokens;
    private Double topP;
    private Double frequencyPenalty;
    private Integer seed;
    private String system;
    private String connectionName;
    boolean success = true;
    private long toolExecutionTimeout = 10000; // use the global timeout in synapse configuration


    public Agent() {

        agentID = String.valueOf(new Random().nextLong());

        // Concurrent hashmap is not used as the content will inserted only once and accessed multiple times
        toolInvokers = new HashMap<>();
        toolResultExpressions = new HashMap<>();
        toolSpecifications = new ArrayList<>();
    }

    public interface Assistant {
        Result<String> chat(@MemoryId String memoryId,@dev.langchain4j.service.UserMessage String message);
    }
    @Override
    public void execute(MessageContext messageContext) {
        // This method is not needed as we override the mediate method
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
        chatMemories = new ConcurrentHashMap<>();
        chatMemoryProvider = memoryId -> MessageWindowChatMemory.builder().id(memoryId).maxMessages(maxChatHistory).chatMemoryStore(new InMemoryChatMemoryStore()).build();
        // Generate tool specifications
        generateToolSpecifications(mc, getTools(mc));
    }

    private List<Tool> getTools(MessageContext mc) {

        List<Tool> tools = new ArrayList<>();
        Object toolsObject = getParameter(mc, "tools");
        if (toolsObject instanceof Map<?,?>) {
            Map<String, Object> toolsParams = (Map<String,Object>) toolsObject;
            List<Map> toolList = (List<Map>) toolsParams.get("children");
            Iterator<Map> iterator = toolList.iterator();
            while (iterator.hasNext()) {
                Map toolParam = iterator.next();
                Map<String, Object> toolAttributes = (Map<String, Object>) toolParam.get("attributes");
                String name = (String) toolAttributes.get("name"); // TODO: add null check
                String template = (String) toolAttributes.get("template");
                String resultExpression = (String) toolAttributes.get("resultExpression");
                SynapseExpression resultSynapseExpression = new ValueFactory().createSynapseExpression(resultExpression);
                Value resultExpressionValue = new Value(resultSynapseExpression);
                String description = (String) toolAttributes.get("description");
                Tool tool = new Tool(name, template, resultExpressionValue, description);
                tools.add(tool);
            }
        }
        return tools;
    }

    @Override
    public boolean mediate(MessageContext mc) {

        // Initialize the agent
        if(!isInitialized) {
            synchronized (lock) {
                if(!isInitialized) {
                    init(mc);
                    isInitialized = true;
                }
            }
        }

        String memoryId = getMediatorParameter(mc, Constants.USER_ID, String.class, false);
        String parsedPrompt = parsePrompt(mc);

        ChatLanguageModel model = null;
        try {
            model = LLMConnectionHandler.getChatModel(connectionName, modelName, temperature, maxTokens, topP, frequencyPenalty, seed);
            if (model == null) {
                handleConnectorException(Errors.LLM_CONNECTION_ERROR, mc);
            }
        } catch (Exception e) {
            handleConnectorException(Errors.LLM_CONNECTION_ERROR, mc, e);
        }

        try {
            SynapseLog synLog = getLog(mc);

            // Build the AI service context
            AiServiceContext  aiServiceContext = new SynapseAiContext(Assistant.class);
            ((SynapseAiContext) aiServiceContext).setToolResultVariable(toolResultExpressions);
            aiServiceContext.systemMessageProvider = chatMemoryId -> system != null ? Optional.of(system) : Optional.of(DEFAULT_SYSTEM_PROMPT);
            aiServiceContext.chatModel = model;
            aiServiceContext.chatMemories = chatMemories;
//            aiServiceContext.chatMemories.put(memoryId, TemporaryChatMemory.builder().from(new ArrayList<>()).maxMessages(maxChatHistory).build());
            aiServiceContext.chatMemoryProvider = chatMemoryProvider;
            aiServiceContext.toolSpecifications = toolSpecifications;

            SystemMessage systemMessage = new SystemMessage(system);
            UserMessage userMessage = new UserMessage(parsedPrompt);

            //TODO: check whether to support an output parser


            aiServiceContext.chatMemory(memoryId).add(systemMessage);
            aiServiceContext.chatMemory(memoryId).add(userMessage);

            ChatRequestParameters parameters = ChatRequestParameters.builder()
                    .toolSpecifications(aiServiceContext.toolSpecifications)
//                    .responseFormat(ResponseFormat.JSON) // TODO: modify it to support json schema if output parser is used
                    .build();

            int executionsLeft = MAX_TOOL_EXECUTIONS_PER_REQUEST;

            List<ToolExecution> toolExecutions = new ArrayList<>();

            SharedAgentDataHolder sharedAgentDataHolder = new SharedAgentDataHolder();

            // Always clone the original MessageContext and save it to continue the iterative agent inference
            MessageContext orginalMessageContext = MessageHelper.cloneMessageContext(mc);
            sharedAgentDataHolder.setSynCtx(orginalMessageContext);
            sharedAgentDataHolder.setAiServiceContext(aiServiceContext);
            sharedAgentDataHolder.setToolExecutions(toolExecutions);
            sharedAgentDataHolder.setExecutionsLeft(executionsLeft);
            sharedAgentDataHolder.setChatRequestParameters(parameters);
            sharedAgentDataHolder.setMemoryId(memoryId);

            mc.setProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." + agentID, sharedAgentDataHolder);

            boolean result = executeInferenceAndToolsLoop(mc, synLog);
            return result;
        } catch (Exception e) {
            handleConnectorException(Errors.CHAT_COMPLETION_ERROR, mc, e);
        }
        return true; // TODO: check whether to return true or false
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

    @Override
    public boolean mediate(MessageContext messageContext, ContinuationState continuationState) {

        log.info("Reaching the continuation state:" + continuationState);
        SynapseLog synLog = getLog(messageContext);
        boolean result = false;
        boolean readyToAggregate = false;
        ToolExecutionDataHolder toolExecutionDataHolder = (ToolExecutionDataHolder) messageContext.getProperty(AgentConstant.TOOL_EXECUTION_DATA_HOLDER + "." +
                agentID);
        if(!continuationState.hasChild()) {
            Object val = messageContext.getProperty(SynapseConstants.CONTINUE_MEDIATION_FROM_CONTINUATION_STATE);
            if (val != null && (Boolean) val) {
                messageContext.setProperty(SynapseConstants.CONTINUE_MEDIATION_FROM_CONTINUATION_STATE, false);
            }
            readyToAggregate = true;
        } else {
            ToolExecutionRequest toolExecutionRequest = toolExecutionDataHolder.getToolExecutionRequest();
            SequenceMediator toolMediator = toolInvokers.get(toolExecutionRequest.name());
            FlowContinuableMediator mediator =
                    (FlowContinuableMediator) toolMediator.getChild(0);
            log.info("Continution passed to the tool mediator:" + mediator);
            result = mediator.mediate(messageContext, continuationState.getChildContState());
        }
        if(readyToAggregate) {
            return aggregateToolExecutionResult(messageContext,toolExecutionDataHolder.getTotalToolExecutionCount() , synLog);
        }
        return result;

    }

    private boolean executeInferenceAndToolsLoop(MessageContext mc, SynapseLog synLog) {
        //TODO: make it thread safe
        boolean agentInferenceFinished = false;
        boolean toolExecutionResultAggregate = false;
        SharedAgentDataHolder sharedAgentDataHolder =
                (SharedAgentDataHolder) mc.getProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." + agentID);
        String memoryId = sharedAgentDataHolder.getMemoryId();
        AiServiceContext aiServiceContext = sharedAgentDataHolder.getAiServiceContext();

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(aiServiceContext.chatMemory(memoryId).messages())
                .parameters(sharedAgentDataHolder.getChatRequestParameters())
                .build();

        ChatResponse chatResponse = aiServiceContext.chatModel.chat(chatRequest);

        sharedAgentDataHolder.getLock();
        sharedAgentDataHolder.setTokenUsageAccumulator(
                TokenUsage.sum(sharedAgentDataHolder.getTokenUsageAccumulator(), chatResponse.metadata().tokenUsage()));

        AiMessage aiMessage = chatResponse.aiMessage();

        if (aiServiceContext.hasChatMemory()) {
            log.info("Got response form AI: "+ aiMessage.toString());
            sharedAgentDataHolder.addToMemory(aiMessage);
        }

        if (!aiMessage.hasToolExecutionRequests()) {
            agentInferenceFinished = true;
            sharedAgentDataHolder.setFinishChatResponse(chatResponse);
            sharedAgentDataHolder.releaseLock();
        } else {
            sharedAgentDataHolder.setCurrentToolExecutionRequests(aiMessage.toolExecutionRequests());
            int i = 0;
            //TODO: handle hallucinated tool execution requests
            Iterator<ToolExecutionRequest> toolExecutionRequestIterator = aiMessage.toolExecutionRequests().iterator();
            while (toolExecutionRequestIterator.hasNext()){

                ToolExecutionRequest toolExecutionRequest = toolExecutionRequestIterator.next();
                int executionsLeft = sharedAgentDataHolder.getAndDecrementExecutionsLeft();
                sharedAgentDataHolder.releaseLock(); // TODO: Do we need this???
                if (executionsLeft == 0) {
                    MessageContext orginalMessageContext = sharedAgentDataHolder.getSynCtx();
                    handleConnectorException(Errors.EXCEEDED_SEQUENTIAL_TOOL_EXECUTIONS, orginalMessageContext);
                }

                SequenceMediator toolMediator = toolInvokers.get(toolExecutionRequest.name());
                MessageContext clonedMessageContext =
                        getClonedMessageContextForToolExecution(mc, i+1, aiMessage.toolExecutionRequests().size());

                // Store the current tool execution data in the cloned message context
                ToolExecutionDataHolder toolExecutionDataHolder = new ToolExecutionDataHolder();
//                toolExecutionDataHolder.setToolExecutionRequests(aiMessage.toolExecutionRequests());
                toolExecutionDataHolder.setToolExecutionRequest(toolExecutionRequest);
                toolExecutionDataHolder.setTotalToolExecutionCount(aiMessage.toolExecutionRequests().size());
                toolExecutionDataHolder.setCurrentToolExecutionIndex(i++);
                Value resultExpression =
                        ((SynapseAiContext) aiServiceContext).getToolResultVariable(toolExecutionRequest.name());
                toolExecutionDataHolder.setResultExpression(resultExpression);
                clonedMessageContext.setProperty(AgentConstant.TOOL_EXECUTION_DATA_HOLDER + "." + agentID, toolExecutionDataHolder);

                // As this is a class mediator, we can't get the actual mediator position. So, hardcode the position
                // in the agent sequence template.
                ContinuationStackManager.addReliantContinuationState(clonedMessageContext, 0, 1);

                log.info("Executing tool: " + toolExecutionRequest);
                executeTool(toolExecutionRequest, toolMediator, clonedMessageContext);
            }
        }
        OperationContext opCtx
                = ((Axis2MessageContext) mc).getAxis2MessageContext().getOperationContext();
        if (opCtx != null) {
            opCtx.setProperty(org.apache.axis2.Constants.RESPONSE_WRITTEN, "SKIP");
        }
        log.info("Agent inference finished:" + agentInferenceFinished);
        if (agentInferenceFinished) {
            log.info("Agent inference finished:" + sharedAgentDataHolder.getFinishChatResponse());
            return completeAgentInference(mc, sharedAgentDataHolder);
        }
        return toolExecutionResultAggregate;
    }

    private boolean completeAgentInference(MessageContext mc, SharedAgentDataHolder sharedAgentDataHolder) {

        MessageContext originalMessageContext = getOriginalMessageContext(sharedAgentDataHolder);
        ChatResponse finishChatResponse = sharedAgentDataHolder.getFinishChatResponse();
        Result<Object> parsedResponse = parseFinalResponse(originalMessageContext, finishChatResponse, sharedAgentDataHolder);
        if (parsedResponse != null) {
            handleConnectorResponse(originalMessageContext, parsedResponse, null, null);
        } else {
            handleConnectorException(Errors.INVALID_OUTPUT_TYPE, originalMessageContext);
        }

        ContinuationStackManager.updateSeqContinuationState(originalMessageContext, 1);

        getLog(originalMessageContext).traceOrDebug("End : Agent mediator");
        boolean result;
        do {
            SeqContinuationState seqContinuationState =
                    (SeqContinuationState) ContinuationStackManager.peakContinuationStateStack(originalMessageContext);
            if (seqContinuationState != null) {
                SequenceMediator sequenceMediator = ContinuationStackManager.retrieveSequence(originalMessageContext, seqContinuationState);
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

    private boolean aggregateToolExecutionResult(MessageContext synCtx, int toolCount, SynapseLog synLog) {

        ContinuationStackManager.removeReliantContinuationState(synCtx);
        ToolExecutionAggregate aggregate = null;

        String correlationIdName = AgentConstant.TOOL_EXECUTION_CORRELATION + "." + agentID;

        Object correlationID = synCtx.getProperty(correlationIdName);
        String correlation = (String) correlationID;
        log.info("Aggregating tool execution messages started for correlation : " + correlation);
        log.info("Aggregation for:" + synCtx.getProperty(AgentConstant.AGENT_TOOL_EXECUTION + "." + agentID));

        synLog.traceOrDebug("Aggregating tool execution messages started for correlation : " + correlation);

        //No need to build the message as we are going to aggregate the variables
        while (aggregate == null) {
            synchronized (lock) {
                log.info("Aggregating lock acquired for correlation : " + correlation);
                if (activeAggregates.containsKey(correlation)) {
                    log.info("Found an active aggregate for correlation : " + correlation);
                    aggregate = activeAggregates.get(correlation);
                    if (aggregate != null) {
                        if (!aggregate.getLock()) {
                            log.info("Failed to acquire lock for correlation : " + correlation);
                            aggregate = null;
                        }
                    }
                } else {
                    log.info("Creating new Aggregator for agent tool execution with correlation : " + correlation);
                    if (synLog.isTraceOrDebugEnabled()) {
                        synLog.traceOrDebug("Creating new Aggregator for agent tool execution - " +
                                (toolExecutionTimeout > 0 ? "expires in : "
                                        + (toolExecutionTimeout / 1000) + "secs" :
                                        "without expiry time"));
                    }
                    if (isAggregationCompleted(synCtx)) {
                        log.info("Aggregation already completed for correlation : " + correlation);
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
                                    log.info("Scheduling Synapse timer for agent tool execution with correlation : " + correlation);
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
                    log.info("Acquired aggregation lock for correlation : " + correlation);
                    activeAggregates.put(correlation, aggregate);
                }
            }
        }
        // if there is an aggregate continue on aggregation
        if (aggregate != null) {
            log.info("Added message to the aggregator for correlation : " + correlation);
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
                log.info("Aggregation completed for correlation : " + correlation);
                synLog.traceOrDebug("Aggregation completed");
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
                            lastMessage.getProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." + agentID);

                    if (aggregateTimeoutHolderObj != null) {
                        SharedDataHolder sharedDataHolder = (SharedDataHolder) aggregateTimeoutHolderObj;
                        sharedDataHolder.markAggregationCompletion();
                    }
                }
                markedCompletedNow = true;
            }
        }

        if (!markedCompletedNow) {
            return false;
        }
        MessageContext originalMessageContext = getOriginalMessageContext(aggregate);
        if(originalMessageContext!=null){
            MessageContext clonedMessageContext = getClonedMessageContext(originalMessageContext);
            if (clonedMessageContext != null) {
                setAggregatedMessageAsVariable(clonedMessageContext, aggregate);
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

        SharedAgentDataHolder sharedAgentDataHolder = (SharedAgentDataHolder) originalMessageContext.getProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." +
                agentID);
        if(sharedAgentDataHolder!=null){
            sharedAgentDataHolder.resetAggregationCompletion();
            return executeInferenceAndToolsLoop(originalMessageContext, getLog(originalMessageContext));
        }
        return false;
    }

    private void setAggregatedMessageAsVariable(MessageContext originalMessageContext, ToolExecutionAggregate aggregate) {

        log.debug("Merging aggregated Tool executions responses to the original message context");
        if(aggregate.getToolCount() != aggregate.getMessages().size()){
            log.warn("The agent tool executions are not finished for the correlation : " + aggregate.getCorrelation());
//            handleException(aggregate, "Tool executions are not completed. Cannot continue further inference", null, aggregate.getLastMessage());
        }
        SharedAgentDataHolder sharedAgentDataHolder = extractToolExecutionResult(aggregate);
        originalMessageContext.setProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." + agentID,sharedAgentDataHolder);
    }

    private SharedAgentDataHolder extractToolExecutionResult(ToolExecutionAggregate aggregate) {
        //TODO: Check NPE
        SharedAgentDataHolder sharedAgentDataHolder = (SharedAgentDataHolder) aggregate.getLastMessage().getProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." +
                agentID);
        List<ToolExecutionRequest> toolExecutionRequestList = new ArrayList<>(sharedAgentDataHolder.getCurrentToolExecutionRequests());
        for (MessageContext synCtx : aggregate.getMessages()) {
            ToolExecutionDataHolder toolExecutionDataHolder = (ToolExecutionDataHolder) synCtx.getProperty(AgentConstant.TOOL_EXECUTION_DATA_HOLDER + "." +
                    agentID);
            Value resultVariable = toolExecutionDataHolder.getResultExpression();
            if (resultVariable!=null) {
                Object result = resultVariable.evaluateValue(synCtx);
                if(result!=null){
                    sharedAgentDataHolder.getToolExecutions().add(ToolExecution.builder()
                            .request(toolExecutionDataHolder.getToolExecutionRequest())
                            .result(result.toString())
                            .build());
                    ToolExecutionResultMessage toolExecutionResultMessage = ToolExecutionResultMessage.from(
                            toolExecutionDataHolder.getToolExecutionRequest(),
                            result.toString()
                                                                                                           );
                    sharedAgentDataHolder.addToMemory(toolExecutionResultMessage); // TODO: Thread safe
                    toolExecutionRequestList.remove(toolExecutionDataHolder.getToolExecutionRequest());
                }
            } else {
                handleException(aggregate, "Error retrieving the tool execution result", null, synCtx);
            }
        }
        if(!toolExecutionRequestList.isEmpty()) {
            for(ToolExecutionRequest toolExecutionRequest : toolExecutionRequestList) {
                log.warn("Tool execution failed or timed out. Marking it as failed. Tool: "+ toolExecutionRequest.name());
                sharedAgentDataHolder.getToolExecutions().add(ToolExecution.builder()
                        .request(toolExecutionRequest)
                        .result(Constants.TOOL_EXECUTION_FAILED)
                        .build());
                ToolExecutionResultMessage toolExecutionResultMessage = ToolExecutionResultMessage.from(
                        toolExecutionRequest,
                        Constants.TOOL_EXECUTION_FAILED
                                                                                                     );
                sharedAgentDataHolder.addToMemory(toolExecutionResultMessage); // TODO: Thread safe
            }
        }
        return sharedAgentDataHolder;
    }

    private void handleException(ToolExecutionAggregate aggregate, String msg, Exception exception, MessageContext msgContext) {

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
            Object aggregateHolderObj = lastMessage.getProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." + agentID);
            return getOriginalMessageContext((SharedAgentDataHolder) aggregateHolderObj);
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

        Object aggregateTimeoutHolderObj = synCtx.getProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." + agentID);

        if (aggregateTimeoutHolderObj != null) {
            SharedDataHolder sharedDataHolder = (SharedDataHolder) aggregateTimeoutHolderObj;
            if (sharedDataHolder.isAggregationCompleted()) {
                log.debug("Received a response for already completed Aggregate");
                return true;
            }
        }
        return false;
    }

    private MessageContext getClonedMessageContextForToolExecution(MessageContext mc, int toolId, int totalToolExecutions) {
        MessageContext newCtx = null;
        try {
            newCtx = MessageHelper.cloneMessageContext(mc);

            // Set isServerSide property in the cloned message context
            ((Axis2MessageContext) newCtx).getAxis2MessageContext().setServerSide(
                    ((Axis2MessageContext) mc).getAxis2MessageContext().isServerSide());

            // Set the continue mediation property in the cloned message context to continue from MediatorWorker
            newCtx.setProperty(SynapseConstants.CONTINUE_MEDIATION_FROM_CONTINUATION_STATE, true);
            newCtx.setProperty(AgentConstant.TOOL_EXECUTION_CORRELATION + "." + agentID, mc.getMessageID());
            newCtx.setProperty(AgentConstant.AGENT_TOOL_EXECUTION + "." + agentID, toolId + EIPConstants.MESSAGE_SEQUENCE_DELEMITER + totalToolExecutions);
        } catch (AxisFault axisFault) {
            handleException("Error cloning the message context", axisFault, mc);
        }
        return newCtx;
    }

    private MessageContext getClonedMessageContext(MessageContext mc) {
        MessageContext newCtx = null;
        try {
            newCtx = MessageHelper.cloneMessageContext(mc);
            newCtx.setProperty(AgentConstant.TOOL_EXECUTION_CORRELATION + "." + agentID, mc.getMessageID());
        } catch (AxisFault axisFault) {
            handleException("Error cloning the message context", axisFault, mc);
        }
        return newCtx;
    }

    private void generateToolSpecifications(MessageContext mc, List<Tool> tools) {

        if (tools == null) {
            return;
        }
        if(tools != null && !tools.isEmpty()) {
            for (Tool tool : tools) {
                String toolTemplate = tool.getTemplate();
                Mediator mediator = mc.getSequenceTemplate(toolTemplate);
                if (mediator instanceof TemplateMediator) {
                    TemplateMediator templateMediator = (TemplateMediator) mediator;
                    String name = tool.getName();
                    String description = templateMediator.getDescription();
                    List<TemplateParam> templateParams = new ArrayList<>(templateMediator.getParameters());
                    JsonObjectSchema parameterSchema = generateParameterSchema(templateParams);
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
                                MessageContext mc) {

        Map<String, Object> arguments = AgentUtils.argumentsAsMap(toolExecutionRequest.arguments());
        Iterator<Map.Entry<String, Object>> iterator = arguments.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Object> entry = iterator.next();
            EIPUtils.createSynapseEIPTemplateProperty(mc, toolExecutionRequest.name(), entry.getKey(), entry.getValue());
        }
        if (log.isDebugEnabled()) {
            log.debug("Asynchronously mediating using the in-lined anonymous sequence");
        }
        mc.getEnvironment().injectAsync(mc, invoker);
        return false; //Async invocation
    }

    private JsonObjectSchema generateParameterSchema(List<TemplateParam> templateParams) {

        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        for (TemplateParam templateParam : templateParams) {
            String paramName = templateParam.getName();
            boolean isMandatory = templateParam.isMandatory();
            String parameterDescription = templateParam.getDescription();
            builder.addStringProperty(paramName, parameterDescription != null ? parameterDescription : paramName);
            if (isMandatory) {
                builder.required(paramName);
            }
        }
        return builder.build();
    }

    private Result<Object> parseFinalResponse(MessageContext mc, ChatResponse chatResponse, SharedAgentDataHolder agentDataHolder) {

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
}
