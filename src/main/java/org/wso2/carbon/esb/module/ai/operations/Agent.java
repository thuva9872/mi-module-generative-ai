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
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
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
import dev.langchain4j.store.embedding.EmbeddingMatch;
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
import org.apache.synapse.mediators.template.ResolvedConnectorParam;
import org.apache.synapse.mediators.template.TemplateMediator;
import org.apache.synapse.mediators.template.TemplateParam;
import org.apache.synapse.mediators.template.ValueHolder;
import org.apache.synapse.util.InlineExpressionUtil;
import org.apache.synapse.util.MessageHelper;
import org.jaxen.JaxenException;
import org.wso2.carbon.esb.module.ai.AbstractAIMediator;
import org.wso2.carbon.esb.module.ai.operations.agent.SharedAgentDataHolder;
import org.wso2.carbon.esb.module.ai.operations.agent.Tool;
import org.wso2.carbon.esb.module.ai.operations.agent.ToolExecutionDataHolder;
import org.wso2.carbon.esb.module.ai.Constants;
import org.wso2.carbon.esb.module.ai.Errors;
import org.wso2.carbon.esb.module.ai.SynapseAiContext;
import org.wso2.carbon.esb.module.ai.llm.LLMConnectionHandler;
import org.wso2.carbon.esb.module.ai.operations.agent.AgentConstant;
import org.wso2.carbon.esb.module.ai.operations.agent.ToolExecutionAggregate;
import org.wso2.carbon.esb.module.ai.utils.AgentUtils;
import org.wso2.carbon.esb.module.ai.utils.Utils;

import java.lang.reflect.Type;
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
    private static final Gson gson = new Gson();
    private static final int MAX_SEQUENTIAL_TOOL_EXECUTIONS = 100;
    private final ServiceOutputParser serviceOutputParser = new ServiceOutputParser();
    private final Map<String, ToolExecutionAggregate> activeAggregates = Collections.synchronizedMap(new HashMap<>());
    private final Object lock = new Object();
    private static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant.";

    // Chat configurations
    private String modelName;
    private Double temperature;
    private Integer maxTokens;
    private Double topP;
    private Double frequencyPenalty;
    private Integer seed;
    private String system;
    private String connectionName;
    private ChatLanguageModel model;
    boolean success = true;
    private String id;
    private long toolExecutionTimeout = 100000;
    private final Map<String, SequenceMediator> toolInvokers;
    private final Map<String, Value> toolResultVariables;
    private boolean isInitialized =false;

    public Agent() {

        id = String.valueOf(new Random().nextLong());
        toolInvokers = new HashMap<>();
        toolResultVariables = new HashMap<>();
    }

    public interface Assistant {
        Result<String> chat(@MemoryId String memoryId,@dev.langchain4j.service.UserMessage String message);
    }
    @Override
    public void execute(MessageContext messageContext) {
        // This method is not needed as we override the mediate method
    }


    @Override
    public boolean mediate(MessageContext mc) {
        connectionName = getProperty(mc, Constants.CONNECTION_NAME, String.class, false);

//        String memoryId = getMediatorParameter(mc, Constants.MEMORY_ID, String.class, false);
        String memoryId = "default"; //TODO: introduce memoryId parameter
        String prompt = getMediatorParameter(mc, Constants.PROMPT, String.class, false);
        String parsedPrompt = null;
        try {
            parsedPrompt = InlineExpressionUtil.processInLineSynapseExpressionTemplate(mc, prompt);
        } catch (JaxenException e) {
            parsedPrompt = prompt;
        }
        modelName = getMediatorParameter(mc, Constants.MODEL_NAME, String.class, false);
//        String outputType = getMediatorParameter(mc, Constants.OUTPUT_TYPE, String.class, false);
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

        List<Tool> tools = getTools(mc);
        Map<ToolSpecification, String> toolSpecifications = generateToolSpecifications(mc, tools);

        try {
            model = LLMConnectionHandler.getChatModel(connectionName, modelName, temperature, maxTokens, topP, frequencyPenalty, seed);
            if (model == null) {
                handleConnectorException(Errors.LLM_CONNECTION_ERROR, mc);
//                return false; // TODO: throw synapse exception
            }
        } catch (Exception e) {
            handleConnectorException(Errors.LLM_CONNECTION_ERROR, mc, e);
//            return false; // TODO: throw synapse exception
        }

        // Additional configurations
//        String knowledge = getMediatorParameter(mc, Constants.KNOWLEDGE, String.class, true);
//        String chatHistory = getMediatorParameter(mc, Constants.HISTORY, String.class, true);
        Integer maxHistory = getMediatorParameter(mc, Constants.MAX_HISTORY, Integer.class, true);

//        ContentRetriever knowledgeRetriever = null;
//        if (knowledge != null) {
//            List<EmbeddingMatch<TextSegment>> parsedKnowledge = parseAndValidateKnowledge(knowledge);
//            if (parsedKnowledge == null) {
//                handleConnectorException(Errors.INVALID_INPUT_FOR_CHAT_KNOWLEDGE, mc);
////                return false; // TODO: throw synapse exception
//            }
//
//            // Extract text segments from the parsed knowledge and convert to content
//            List<Content> knowledgeTexts = parsedKnowledge.stream()
//                    .map(match -> new Content(match.embedded()))
//                    .toList();
//            knowledgeRetriever = query -> knowledgeTexts;
//        }

        ChatMemory chatMemory = null;
//        if (chatHistory != null) {
//            List<ChatMessage> chatMessages = parseAndValidateChatHistory(chatHistory);
//            if (chatMessages == null) {
//                handleConnectorException(Errors.INVALID_INPUT_FOR_CHAT_MEMORY, mc);
////                return;
//            }
//            if (maxHistory == null) {
//                maxHistory = chatMessages.size();
//            }
//            chatMemory = TemporaryChatMemory.builder()
//                    .from(chatMessages)
//                    .maxMessages(maxHistory)
//                    .build();
//        } else {
            if(maxHistory == null) {
                maxHistory = 10;
            }
            chatMemory = TemporaryChatMemory.builder().from(new ArrayList<>()).maxMessages(maxHistory).build();
//        }

        try {
            SynapseLog synLog = getLog(mc);
            // Build the AI service context
            AiServiceContext  aiServiceContext = new SynapseAiContext(Assistant.class);
//            Map<ToolSpecification, String> toolSpecifications = generateToolSpecifications(mc, aiServiceContext, tools);
            ((SynapseAiContext) aiServiceContext).setToolResultVariable(toolResultVariables);
            aiServiceContext.systemMessageProvider = chatMemoryId -> system != null ? Optional.of(system) : Optional.of(DEFAULT_SYSTEM_PROMPT);
            aiServiceContext.chatModel = model;
            aiServiceContext.chatMemories = new ConcurrentHashMap();
            aiServiceContext.chatMemories.put("default", chatMemory); // TODO: Replace this with chat memory provider
//            aiServiceContext.chatMemoryProvider = chatMemoryProvider
            aiServiceContext.toolSpecifications = new ArrayList<>(toolSpecifications.keySet());

            SystemMessage systemMessage = new SystemMessage(system); // TODO: check whether need to use PromptTemplate as in DefaultAiServices class
            UserMessage userMessage = new UserMessage(parsedPrompt); // same

            //TODO: check whether to support an output parser


            aiServiceContext.chatMemory(memoryId).add(systemMessage);
            aiServiceContext.chatMemory(memoryId).add(userMessage);
            List<ChatMessage> messages = aiServiceContext.chatMemory(memoryId).messages();

            ChatRequestParameters parameters = ChatRequestParameters.builder()
                    .toolSpecifications(aiServiceContext.toolSpecifications)
//                    .responseFormat(ResponseFormat.JSON) // TODO: modify it to support json schema if output parser is used
                    .build();

            int executionsLeft = MAX_SEQUENTIAL_TOOL_EXECUTIONS;

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

            mc.setProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." + id, sharedAgentDataHolder);

            boolean result = executeInferenceAndToolsLoop(mc, synLog);
            return result;
        } catch (Exception e) {
            handleConnectorException(Errors.CHAT_COMPLETION_ERROR, mc, e);
        }
        return true; // TODO: check whether to return true or false
    }

    private List<Tool> getTools(MessageContext mc) {

        List<Tool> tools = new ArrayList<>();
        Object toolsObject = getParameter(mc, "tools");
        if (toolsObject instanceof ResolvedConnectorParam) {
            ResolvedConnectorParam toolsParams = (ResolvedConnectorParam) toolsObject;
            Iterator<ResolvedConnectorParam> iterator = toolsParams.getChildParams().iterator();
            while (iterator.hasNext()) {
                ResolvedConnectorParam toolParam = iterator.next();
                Map<String, ValueHolder> toolAttributes = toolParam.getAttributeMappings();
                String name = (String) toolAttributes.get("name").getResolvedValue(); // TODO: add null check
                String template = (String) toolAttributes.get("template").getResolvedValue();
                Value resultExpression = toolAttributes.get("resultExpression").getExpression();
                String description = (String) toolAttributes.get("description").getResolvedValue();
                Tool tool = new Tool(name, template, resultExpression, description);
                tools.add(tool);
            }
        }
        return tools;
    }

    @Override
    public boolean mediate(MessageContext messageContext, ContinuationState continuationState) {

        log.info("Reaching the continuation state:" + continuationState);
        SynapseLog synLog = getLog(messageContext);
        boolean result = false;
        boolean readyToAggregate = false;
        ToolExecutionDataHolder toolExecutionDataHolder = (ToolExecutionDataHolder) messageContext.getProperty(AgentConstant.TOOL_EXECUTION_DATA_HOLDER + "." + id);
        if(!continuationState.hasChild()) {
            readyToAggregate = true;
        } else {
            ToolExecutionRequest toolExecutionRequest = toolExecutionDataHolder.getToolExecutionRequest();
            SequenceMediator toolMediator = toolInvokers.get(toolExecutionRequest.name());
            FlowContinuableMediator mediator =
                    (FlowContinuableMediator) toolMediator.getChild(0);
            log.info("Continution passed to the tool mediator:" + mediator);
            result = mediator.mediate(messageContext, continuationState.getChildContState());
//            result = ((FlowContinuableMediator) toolMediator).mediate(messageContext, continuationState.getChildContState());
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
                (SharedAgentDataHolder) mc.getProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." + id);
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

//        while (true) {

//        if (sharedAgentDataHolder.getAndDecrementExecutionsLeft() == 0) {
//            handleConnectorException(Errors.EXCEEDED_SEQUENTIAL_TOOL_EXECUTIONS, mc);
////                    return;
//        }

        AiMessage aiMessage = chatResponse.aiMessage();

        if (aiServiceContext.hasChatMemory()) {
            aiServiceContext.chatMemory(memoryId).add(aiMessage);
        }
//            else {
//                messages = new ArrayList<>(messages);
//                messages.add(aiMessage);
//            }

        if (!aiMessage.hasToolExecutionRequests()) {
            agentInferenceFinished = true;
            sharedAgentDataHolder.setFinishChatResponse(chatResponse);
        } else {
            int i = 0;
            //TODO: handle hallucinated tool execution requests
            for (ToolExecutionRequest toolExecutionRequest : aiMessage.toolExecutionRequests()) { // TODO: replace with iterator
                int executionsLeft = sharedAgentDataHolder.getAndDecrementExecutionsLeft();
                sharedAgentDataHolder.releaseLock();
                if (executionsLeft == 0) {
                    MessageContext orginalMessageContext = sharedAgentDataHolder.getSynCtx();
                    handleConnectorException(Errors.EXCEEDED_SEQUENTIAL_TOOL_EXECUTIONS, orginalMessageContext);
//                    return;
                }

                //


                SequenceMediator toolMediator = toolInvokers.get(toolExecutionRequest.name());
                MessageContext clonedMessageContext =
                        getClonedMessageContextForToolExecution(mc, i+1, aiMessage.toolExecutionRequests().size());

                // Store the current tool execution data in the cloned message context
                ToolExecutionDataHolder toolExecutionDataHolder = new ToolExecutionDataHolder();
                toolExecutionDataHolder.setToolExecutionRequest(toolExecutionRequest);
                toolExecutionDataHolder.setTotalToolExecutionCount(aiMessage.toolExecutionRequests().size());
                toolExecutionDataHolder.setCurrentToolExecutionIndex(i);
                Value resultExpression =
                        ((SynapseAiContext) aiServiceContext).getToolResultVariable(toolExecutionRequest.name());
                toolExecutionDataHolder.setResultExpression(resultExpression);
                clonedMessageContext.setProperty(AgentConstant.TOOL_EXECUTION_DATA_HOLDER + "." + id, toolExecutionDataHolder);

                ContinuationStackManager.addReliantContinuationState(clonedMessageContext, i++, 0);
                //TODO: use injectAsync to improve performance
                log.info("Executing tool: " + toolExecutionRequest);
                boolean result = executeTool(toolExecutionRequest, toolMediator, clonedMessageContext);
//                if (false && result) {
//                    toolExecutionResultAggregate =
//                            aggregateToolExecutionResult(clonedMessageContext, aiMessage.toolExecutionRequests().size(),
//                                    synLog);
//                }
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

        ContinuationStackManager.updateSeqContinuationState(originalMessageContext, getMediatorPosition());
//        originalMessageContext.setProperty(StatisticsConstants.CONTINUE_STATISTICS_FLOW, true);

//        if (RuntimeStatisticCollector.isStatisticsEnabled()) {
//            CloseEventCollector.closeEntryEvent(originalMessageContext, getMediatorName(), ComponentType.MEDIATOR,
//                    statisticReportingIndex, isContentAltering());
//        }

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

        String correlationIdName = AgentConstant.TOOL_EXECUTION_CORRELATION + "." + id;

        Object correlationID = synCtx.getProperty(correlationIdName);
        String correlation = (String) correlationID;
        log.info("Aggregating tool execution messages started for correlation : " + correlation);
        log.info("Aggregation for:" + synCtx.getProperty(AgentConstant.AGENT_TOOL_EXECUTION + "." + id));

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
                            lastMessage.getProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." + id);

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

        SharedAgentDataHolder sharedAgentDataHolder = (SharedAgentDataHolder) originalMessageContext.getProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." + id);
        if(sharedAgentDataHolder!=null){
            sharedAgentDataHolder.resetAggregationCompletion();
            return executeInferenceAndToolsLoop(originalMessageContext, getLog(originalMessageContext));
        }
        return false;
    }

    private void setAggregatedMessageAsVariable(MessageContext originalMessageContext, ToolExecutionAggregate aggregate) {

        log.debug("Merging aggregated Tool executions responses to the original message context");
        if(aggregate.getToolCount() != aggregate.getMessages().size()){
            //TODO: set the tool execution as failed and pass it to the LLM. How to get the tool request id?
            handleException(aggregate, "Tool executions are not completed. Cannot continue further inference", null, aggregate.getLastMessage());
        }
        SharedAgentDataHolder sharedAgentDataHolder = extractToolExecutionResult(aggregate);
//        originalMessageContext.setVariable(AgentConstant.TOOL_EXECUTION_RESULT_VARIABLE + "." + id, variable);
        originalMessageContext.setProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." + id,sharedAgentDataHolder);
//        StatisticDataCollectionHelper.collectAggregatedParents(aggregate.getMessages(), originalMessageContext);
    }

    private SharedAgentDataHolder extractToolExecutionResult(ToolExecutionAggregate aggregate) {
        //TODO: Check NPE
        SharedAgentDataHolder sharedAgentDataHolder = (SharedAgentDataHolder) aggregate.getLastMessage().getProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." + id);
        for (MessageContext synCtx : aggregate.getMessages()) {
            ToolExecutionDataHolder toolExecutionDataHolder = (ToolExecutionDataHolder) synCtx.getProperty(AgentConstant.TOOL_EXECUTION_DATA_HOLDER + "." + id);
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
                }
            } else {
                handleException(aggregate, "Error retrieving the tool execution result", null, synCtx);
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
            Object aggregateHolderObj = lastMessage.getProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." + id);
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

        Object aggregateTimeoutHolderObj = synCtx.getProperty(AgentConstant.AGENT_SHARED_DATA_HOLDER + "." + id);

        if (aggregateTimeoutHolderObj != null) {
            SharedDataHolder sharedDataHolder = (SharedDataHolder) aggregateTimeoutHolderObj;
            if (sharedDataHolder.isAggregationCompleted()) {
                log.debug("Received a response for already completed Aggregate");
                return true;
            }
        }
        return false;
    }

    private MessageContext getClonedMessageContextForToolExecution(MessageContext mc, int tool, int totalToolExecutions) {
        MessageContext newCtx = null;
        try {
            newCtx = MessageHelper.cloneMessageContext(mc);
            // Set isServerSide property in the cloned message context
            ((Axis2MessageContext) newCtx).getAxis2MessageContext().setServerSide(
                    ((Axis2MessageContext) mc).getAxis2MessageContext().isServerSide());
            // Set the SCATTER_MESSAGES property to the cloned message context which will be used by the MediatorWorker
            // to continue the mediation from the continuation state
            newCtx.setProperty(AgentConstant.TOOL_EXECUTION_CORRELATION + "." + id, mc.getMessageID());
            newCtx.setProperty(AgentConstant.AGENT_TOOL_EXECUTION, true); //TODO: set the correct one here and remove it from execute tool method
//            newCtx.setProperty("AGENT_AGGREGATE_CORRELATION" + "." + id, mc.getMessageID());
            newCtx.setProperty(AgentConstant.AGENT_TOOL_EXECUTION + "." + id, tool + EIPConstants.MESSAGE_SEQUENCE_DELEMITER + totalToolExecutions);
        } catch (AxisFault axisFault) {
            handleException("Error cloning the message context", axisFault, mc);
        }
        return newCtx;
    }

    private MessageContext getClonedMessageContext(MessageContext mc) {
        MessageContext newCtx = null;
        try {
            newCtx = MessageHelper.cloneMessageContext(mc);
            newCtx.setProperty(AgentConstant.TOOL_EXECUTION_CORRELATION + "." + id, mc.getMessageID());
        } catch (AxisFault axisFault) {
            handleException("Error cloning the message context", axisFault, mc);
        }
        return newCtx;
    }

//    private String getToolExecutionResult(AiServiceContext aiServiceContext, ToolExecutionRequest toolExecutionRequest,
//                                          MessageContext mc) {
//
//        String resultKey = ((SynapseAiContext) aiServiceContext).getToolResultVariable(toolExecutionRequest.name());
//        if (resultKey != null) {
//            Object toolResult = mc.getVariable(resultKey);
//            if (toolResult != null) {
//                return toolResult.toString();
//            } else {
//                return "Tool execution failed";
//            }
//        }
//        return "Invalid tool configuration";
//    }



    private Map<ToolSpecification, String> generateToolSpecifications(MessageContext mc, List<Tool> tools) {

        if (tools == null) {
            return Collections.EMPTY_MAP;
        }
        Type listType = new TypeToken<List<Map<String, String>>>() {}.getType();
//        List<Map<String, String>> parsedTools = Utils.fromJson(tools, listType);

        Map<ToolSpecification, String> toolSpecifications = new HashMap<>();
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
                    toolResultVariables.put(tool.getName(), tool.getResultExpression());
//                    ((SynapseAiContext) aiServiceContext).addToolResultVariable(toolSpecification.name(), "result");
                    toolSpecifications.put(toolSpecification, "result"); //TODO: replace with the actual result variable

                    // Add invoker for tool
                    SequenceMediator toolInvoker = new SequenceMediator();
                    toolInvoker.setSequenceType(SequenceType.ANON);
                    InvokeMediator invoker = new InvokeMediator();
                    invoker.setTargetTemplate(toolTemplate);
//                    for(TemplateParam templateParam : templateParams) {
//                        invoker.addExpressionForParamName(templateParam.getName(), new ValueFactory().createSynapseExpression()));
//                    }
                    //TODO: set the fault handler for this invoker
                    //TODO: check whether need to init the sequence mediator
                    toolInvoker.addChild(invoker);
                    toolInvokers.put(name, toolInvoker);
                }
            }
        }
        return toolSpecifications;
    }

    private boolean executeTool(ToolExecutionRequest toolExecutionRequest, SequenceMediator invoker,
                                MessageContext mc) {

        //TODO: remove this as the invoker is shared between multiple requests. Use message context to achieve the same.
        Map<String, Object> arguments = AgentUtils.argumentsAsMap(toolExecutionRequest.arguments());
        // Set the parameter values for the template mediator
//        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
//            ((InvokeMediator) invoker).addExpressionForParamName(entry.getKey(), new Value(entry.getValue().toString()));
//        }
        Iterator<Map.Entry<String, Object>> iterator = arguments.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Object> entry = iterator.next();
            EIPUtils.createSynapseEIPTemplateProperty(mc, toolExecutionRequest.name(), entry.getKey(), entry.getValue());
        }
        if (log.isDebugEnabled()) {
            log.debug("Asynchronously mediating using the in-lined anonymous sequence");
        }
        mc.setProperty(SynapseConstants.IS_AGENT_TOOL_EXECUTION, true);
        mc.getEnvironment().injectAsync(mc, invoker);
        return false; //Async invocation
    }

    private JsonObjectSchema generateParameterSchema(List<TemplateParam> templateParams) {

        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        for (TemplateParam templateParam : templateParams) {
            String paramName = templateParam.getName();
            boolean isMandatory = templateParam.isMandatory();
//            String description = templateParam.getDescription();
            builder.addStringProperty(paramName, paramName);
            if (isMandatory) {
                builder.required(paramName);
            }
        }
        return builder.build();
    }

    private List<EmbeddingMatch<TextSegment>> parseAndValidateKnowledge(String knowledge) {
        try {
            Type listType = new TypeToken<List<EmbeddingMatch<TextSegment>>>() {}.getType();
            List<EmbeddingMatch<TextSegment>> embeddingMatches = Utils.fromJson(knowledge, listType);

            // Validate the parsed list
            if (embeddingMatches != null) {
                for (EmbeddingMatch<TextSegment> match : embeddingMatches) {
                    if (match.embedding() == null || match.embedded() == null) {
                        return null;
                    }
                }
            }
            return embeddingMatches;
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    private List<ChatMessage> parseAndValidateChatHistory(String chatHistory) {
        try {
            Type listType = new TypeToken<List<Map<String, String>>>() {}.getType();
            List<Map<String, String>> rawMessages = gson.fromJson(chatHistory, listType);

            List<ChatMessage> chatMessages = new ArrayList<>();
            for (Map<String, String> rawMessage : rawMessages) {
                String role = rawMessage.get("role");
                String content = rawMessage.get("content");

                if (role == null || content == null) {
                    return null; // Invalid format
                }

                ChatMessage chatMessage;
                switch (role) {
                    case "user":
                        chatMessage = new UserMessage(content);
                        break;
                    case "assistant":
                        chatMessage = new AiMessage(content);
                        break;
                    default:
                        return null; // Invalid role
                }
                chatMessages.add(chatMessage);
            }
            return chatMessages;
        } catch (JsonSyntaxException e) {
            return null; // Invalid JSON format
        }
    }

    private Result<Object> parseFinalResponse(MessageContext mc, ChatResponse chatResponse, SharedAgentDataHolder agentDataHolder) {

        TokenUsage tokenUsageAccumulator = agentDataHolder.getTokenUsageAccumulator();
        FinishReason finishReason = chatResponse.metadata().finishReason();
        Response<AiMessage>
                response = Response.from(chatResponse.aiMessage(), tokenUsageAccumulator, finishReason);

        Object parsedResponse = serviceOutputParser.parse(response, String.class);
        Result<Object> parsedResult = Result.builder()
                .content(parsedResponse)
                .tokenUsage(tokenUsageAccumulator)
                .finishReason(finishReason)
                .toolExecutions(agentDataHolder.getToolExecutions())
                .build();
        return parsedResult;
    }

    public String getId() {

        return id;
    }
}
