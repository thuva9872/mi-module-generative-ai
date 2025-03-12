///*
// *  Copyright (c) 2025, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
// *
// *  WSO2 LLC. licenses this file to you under the Apache License,
// *  Version 2.0 (the "License"); you may not use this file except
// *  in compliance with the License.
// *  You may obtain a copy of the License at
// *
// *    http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing,
// * software distributed under the License is distributed on an
// * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// * KIND, either express or implied.  See the License for the
// * specific language governing permissions and limitations
// * under the License.
// */
//
//package org.wso2.carbon.esb.module.ai.operations;
//
//import com.google.gson.Gson;
//import com.google.gson.JsonSyntaxException;
//import com.google.gson.reflect.TypeToken;
//import dev.langchain4j.agent.tool.ToolExecutionRequest;
//import dev.langchain4j.agent.tool.ToolSpecification;
//import dev.langchain4j.data.message.AiMessage;
//import dev.langchain4j.data.message.ChatMessage;
//import dev.langchain4j.data.message.SystemMessage;
//import dev.langchain4j.data.message.ToolExecutionResultMessage;
//import dev.langchain4j.data.message.UserMessage;
//import dev.langchain4j.data.segment.TextSegment;
//import dev.langchain4j.memory.ChatMemory;
//import dev.langchain4j.model.chat.ChatLanguageModel;
//import dev.langchain4j.model.chat.request.ChatRequest;
//import dev.langchain4j.model.chat.request.ChatRequestParameters;
//import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
//import dev.langchain4j.model.chat.response.ChatResponse;
//import dev.langchain4j.model.output.FinishReason;
//import dev.langchain4j.model.output.Response;
//import dev.langchain4j.model.output.TokenUsage;
//import dev.langchain4j.rag.content.Content;
//import dev.langchain4j.rag.content.retriever.ContentRetriever;
//import dev.langchain4j.service.AiServiceContext;
//import dev.langchain4j.service.AiServices;
//import dev.langchain4j.service.MemoryId;
//import dev.langchain4j.service.Result;
//import dev.langchain4j.service.output.ServiceOutputParser;
//import dev.langchain4j.service.tool.ToolExecution;
//import dev.langchain4j.service.tool.ToolExecutor;
//import dev.langchain4j.store.embedding.EmbeddingMatch;
//import org.apache.commons.logging.Log;
//import org.apache.commons.logging.LogFactory;
//import org.apache.synapse.ContinuationState;
//import org.apache.synapse.Mediator;
//import org.apache.synapse.MessageContext;
//import org.apache.synapse.continuation.ContinuationStackManager;
//import org.apache.synapse.mediators.FlowContinuableMediator;
//import org.apache.synapse.mediators.Value;
//import org.apache.synapse.mediators.eip.SharedDataHolder;
//import org.apache.synapse.mediators.template.InvokeMediator;
//import org.apache.synapse.mediators.template.TemplateMediator;
//import org.apache.synapse.mediators.template.TemplateParam;
//import org.apache.synapse.util.MessageHelper;
//import org.wso2.carbon.esb.module.ai.AbstractAIMediator;
//import org.wso2.carbon.esb.module.ai.operations.agent.ToolExecutionDataHolder;
//import org.wso2.carbon.esb.module.ai.Constants;
//import org.wso2.carbon.esb.module.ai.Errors;
//import org.wso2.carbon.esb.module.ai.SynapseAiContext;
//import org.wso2.carbon.esb.module.ai.llm.LLMConnectionHandler;
//import org.wso2.carbon.esb.module.ai.utils.AgentUtils;
//import org.wso2.carbon.esb.module.ai.utils.Utils;
//
//import java.lang.reflect.ParameterizedType;
//import java.lang.reflect.Type;
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.Optional;
//import java.util.Random;
//import java.util.Stack;
//import java.util.concurrent.ConcurrentHashMap;
//
///**
// * Language model chat operation
// * Inputs:
// * - modelName: Name of the language model
// * - temperature: Sampling temperature
// * - maxTokens: Maximum tokens to generate
// * - topP: Top P value
// * - frequencyPenalty: Frequency penalty
// * - seed: Random seed
// * - system: System message
// * - prompt: User message
// * - knowledge: JSON array of TextSegment objects
// * - history: JSON array of ChatMessage objects
// * - maxHistory: Maximum history size
// * - connectionName: Name of the connection to the LLM
// * Outputs:
// * - Response based on the output type
// */
//public class AgentNBBackup extends AbstractAIMediator implements FlowContinuableMediator {
//
//    protected Log log = LogFactory.getLog(this.getClass());
//    private static final Gson gson = new Gson();
//    private static final int MAX_SEQUENTIAL_TOOL_EXECUTIONS = 100;
//    private final ServiceOutputParser serviceOutputParser = new ServiceOutputParser();
//
//    @Override
//    public void execute(MessageContext messageContext) {
//        // This method is not needed as we override the mediate method
//    }
//
//    // Define the agent interfaces for different output types for the LangChain4j service
//    interface StringAgent { Result<String> chat(String userMessage); }
//    public interface Assistant {
//        Result<String> chat(@MemoryId String memoryId,@dev.langchain4j.service.UserMessage String message);
//    }
//    private static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant.";
//
//    // Chat configurations
//    private String modelName;
//    private Double temperature;
//    private Integer maxTokens;
//    private Double topP;
//    private Double frequencyPenalty;
//    private Integer seed;
//    private String system;
//    private String connectionName;
//    private ChatLanguageModel model;
//    boolean success = true;
//    private String id;
//    private Map<String, Mediator> toolInvokers;
//    private Type returnType = new ParameterizedType() {
//        @Override
//        public Type[] getActualTypeArguments() {
//
//            return new Type[]{String.class};
//        }
//
//        @Override
//        public Type getRawType() {
//
//            return Result.class;
//        }
//
//        @Override
//        public Type getOwnerType() {
//
//            return null;
//        }
//    };
//
//    public AgentNBBackup() {
//
//        id = String.valueOf(new Random().nextLong());
//        toolInvokers = new HashMap<>();
//    }
//
//    @Override
//    public boolean mediate(MessageContext mc) {
//        connectionName = getProperty(mc, Constants.CONNECTION_NAME, String.class, false);
//
////        String memoryId = getMediatorParameter(mc, Constants.MEMORY_ID, String.class, false);
//        String memoryId = "default"; //TODO: introduce memoryId parameter
//        String prompt = getMediatorParameter(mc, Constants.PROMPT, String.class, false);
//        modelName = getMediatorParameter(mc, Constants.MODEL_NAME, String.class, false);
//        String outputType = getMediatorParameter(mc, Constants.OUTPUT_TYPE, String.class, false);
//        setResponseVariable(getMediatorParameter(
//                mc, Constants.RESPONSE_VARIABLE, String.class, false));
//        setOverwriteBody(getMediatorParameter(mc, Constants.OVERWRITE_BODY, Boolean.class, false));
//
//        // Advanced configurations
//        system = getMediatorParameter(mc, Constants.SYSTEM, String.class, false);
//        temperature = getMediatorParameter(mc, Constants.TEMPERATURE, Double.class, true);
//        maxTokens = getMediatorParameter(mc, Constants.MAX_TOKENS, Integer.class, true);
//        topP = getMediatorParameter(mc, Constants.TOP_P, Double.class, true);
//        frequencyPenalty = getMediatorParameter(mc, Constants.FREQUENCY_PENALTY, Double.class, true);
//        seed = getMediatorParameter(mc, Constants.SEED, Integer.class, true);
//
//        String tool = getMediatorParameter(mc, "tools", String.class, false);
//        try {
//            model = LLMConnectionHandler.getChatModel(connectionName, modelName, temperature, maxTokens, topP, frequencyPenalty, seed);
//            if (model == null) {
//                handleConnectorException(Errors.LLM_CONNECTION_ERROR, mc);
////                return false; // TODO: throw synapse exception
//            }
//        } catch (Exception e) {
//            handleConnectorException(Errors.LLM_CONNECTION_ERROR, mc, e);
////            return false; // TODO: throw synapse exception
//        }
//
//        // Additional configurations
//        String knowledge = getMediatorParameter(mc, Constants.KNOWLEDGE, String.class, true);
//        String chatHistory = getMediatorParameter(mc, Constants.HISTORY, String.class, true);
//        Integer maxHistory = getMediatorParameter(mc, Constants.MAX_HISTORY, Integer.class, true);
//
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
//
//        ChatMemory chatMemory = null;
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
//            if(maxHistory == null) {
//                maxHistory = 10;
//            }
//            chatMemory = TemporaryChatMemory.builder().from(new ArrayList<>()).maxMessages(maxHistory).build();
//        }
//
//        try {
////========================AI Services========================
//
//            // Build the AI service context
//            AiServiceContext  aiServiceContext = new SynapseAiContext(Assistant.class);
//            Map<ToolSpecification, String> tools = generateToolSpecifications(mc, aiServiceContext, tool);
//            aiServiceContext.systemMessageProvider = chatMemoryId -> system != null ? Optional.of(system) : Optional.of(DEFAULT_SYSTEM_PROMPT);
//            aiServiceContext.chatModel = model;
//            aiServiceContext.chatMemories = new ConcurrentHashMap();
//            aiServiceContext.chatMemories.put("default", chatMemory); // TODO: Replace this with chat memory provider
////            aiServiceContext.chatMemoryProvider = chatMemoryProvider
//            aiServiceContext.toolSpecifications = new ArrayList<>(tools.keySet());
//
//            SystemMessage systemMessage = new SystemMessage(system); // TODO: check whether need to use PromptTemplate as in DefaultAiServices class
//            UserMessage userMessage = new UserMessage(prompt); // same
//
//            //TODO: check whether to support an output parser
//
//
//            aiServiceContext.chatMemory(memoryId).add(systemMessage);
//            aiServiceContext.chatMemory(memoryId).add(userMessage);
//            List<ChatMessage> messages = aiServiceContext.chatMemory(memoryId).messages();
//
//            ChatRequestParameters parameters = ChatRequestParameters.builder()
//                    .toolSpecifications(aiServiceContext.toolSpecifications)
//                    .build();
//
//            ChatRequest chatRequest = ChatRequest.builder()
//                    .messages(messages)
//                    .parameters(parameters)
//                    .build();
//
//            ChatResponse chatResponse = aiServiceContext.chatModel.chat(chatRequest);
//            TokenUsage tokenUsageAccumulator = chatResponse.metadata().tokenUsage();
//            int executionsLeft = MAX_SEQUENTIAL_TOOL_EXECUTIONS;
//
//            List<ToolExecution> toolExecutions = new ArrayList<>();
//
//            // Clone continuation states for mediation after agent completion. Here wo don't need to clone the message
//            // context as we are not going to allow overWriteBody in the tools. (If we allow overWriteBody in the future,
//            // then we need to clone the message context and store it in the shared data holder).
//            SharedDataHolder sharedDataHolder = new SharedDataHolder(); //TODO: clone the message context if overwrite body is false
////            MessageContext originalMessageContext = MessageHelper.cloneMessageContext(mc);
////            sharedDataHolder.setSynCtx(originalMessageContext);
//            Stack<ContinuationState> continuationStates = new Stack<>();
//            continuationStates.addAll(mc.getContinuationStateStack());
//            sharedDataHolder.setContinuationStateStack(continuationStates);
//            mc.setProperty("AGENT_SHARED_DATA_HOLDER."+id, sharedDataHolder);
//
//            while (true) {
//
//                if (executionsLeft-- == 0) {
//                    handleConnectorException(Errors.EXCEEDED_SEQUENTIAL_TOOL_EXECUTIONS, mc);
////                    return;
//                }
//
//                AiMessage aiMessage = chatResponse.aiMessage();
//
//                if (aiServiceContext.hasChatMemory()) {
//                    aiServiceContext.chatMemory(memoryId).add(aiMessage);
//                } else {
//                    messages = new ArrayList<>(messages);
//                    messages.add(aiMessage);
//                }
//
//                if (!aiMessage.hasToolExecutionRequests()) {
//                    break;
//                }
//
//                int i =0;
//                for (ToolExecutionRequest toolExecutionRequest : aiMessage.toolExecutionRequests()) {
//
////                    mc.setProperty("AGENT_TOOL_EXECUTION_REQUESTS_"+id, aiMessage);
//                    Mediator toolMediator = toolInvokers.get(toolExecutionRequest.name());
//                    MessageContext clonedMessageContext = MessageHelper.cloneMessageContext(mc); // TODO: do we need to clone the message context if we are going to perform sequential execution?
//                    ContinuationStackManager.addReliantContinuationState(clonedMessageContext, 0, i++);
//                    boolean result = executeTool(toolExecutionRequest, toolMediator, clonedMessageContext);
//                    if(!result) {
//                        ToolExecutionDataHolder agentDataHolder = new ToolExecutionDataHolder();
//                        agentDataHolder.setAiServiceContext(aiServiceContext);
//                        agentDataHolder.setToolExecutionRequest(aiMessage.toolExecutionRequests());
//                        agentDataHolder.setToolExecutions(toolExecutions);
//                        agentDataHolder.setTokenUsageAccumulator(tokenUsageAccumulator);
//                        agentDataHolder.setExecutionsLeft(executionsLeft);
//                        agentDataHolder.setChatRequestParameters(parameters);
//                        agentDataHolder.setMemoryId(memoryId);
//                        clonedMessageContext.setProperty("AGENT_DATA_HOLDER_"+id, agentDataHolder);
//                        return false;
//                    }
//                    ContinuationStackManager.removeReliantContinuationState(clonedMessageContext);
//
//                    String toolExecutionResult = getToolExecutionResult(aiServiceContext, toolExecutionRequest, clonedMessageContext);
//
//                    toolExecutions.add(ToolExecution.builder()
//                            .request(toolExecutionRequest)
//                            .result(toolExecutionResult)
//                            .build());
//                    ToolExecutionResultMessage toolExecutionResultMessage = ToolExecutionResultMessage.from(
//                            toolExecutionRequest,
//                            toolExecutionResult
//                                                                                                           );
//                    if (aiServiceContext.hasChatMemory()) {
//                        aiServiceContext.chatMemory(memoryId).add(toolExecutionResultMessage);
//                    } else {
//                        messages.add(toolExecutionResultMessage); // TODO: Remove this part as we are going to provide memory
//                    }
//                }
//
//                if (aiServiceContext.hasChatMemory()) {
//                    messages = aiServiceContext.chatMemory(memoryId).messages();
//                }
//
//                chatRequest = ChatRequest.builder()
//                        .messages(messages)
//                        .parameters(parameters)
//                        .build();
//
//                chatResponse = aiServiceContext.chatModel.chat(chatRequest);
//
//                tokenUsageAccumulator = TokenUsage.sum(tokenUsageAccumulator, chatResponse.metadata().tokenUsage());
//            }
//
//            FinishReason finishReason = chatResponse.metadata().finishReason();
//            Response<AiMessage>
//                    response = Response.from(chatResponse.aiMessage(), tokenUsageAccumulator, finishReason);
//
//            Object parsedResponse = serviceOutputParser.parse(response, returnType);
////            if (typeHasRawClass(returnType, Result.class)) {
//                Result<Object> parsedResult = Result.builder()
//                        .content(parsedResponse)
//                        .tokenUsage(tokenUsageAccumulator)
//                        .finishReason(finishReason)
//                        .toolExecutions(toolExecutions)
//                        .build();
////            } else {
////                return parsedResponse;
////            }
//
////            Object answer = getChatResponse(outputType, prompt, knowledgeRetriever, chatMemory, tools);
//            if (parsedResponse != null) {
//                handleConnectorResponse(mc, parsedResult, null, null);
//            } else {
//                handleConnectorException(Errors.INVALID_OUTPUT_TYPE, mc);
//            }
//        } catch (Exception e) {
//            handleConnectorException(Errors.CHAT_COMPLETION_ERROR, mc, e);
//        }
//        return true;
//    }
//
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
//
//    @Override
//    public boolean mediate(MessageContext messageContext, ContinuationState continuationState) {
//
//        try {
//            ToolExecutionDataHolder
//                    agentDataHolder = (ToolExecutionDataHolder) messageContext.getProperty("AGENT_DATA_HOLDER_" + id);
//            if (agentDataHolder != null) {
//                AiServiceContext aiServiceContext = agentDataHolder.getAiServiceContext();
//                List<ToolExecutionRequest> toolExecutionRequests = agentDataHolder.getToolExecutionRequest();
//                int lastExecutedToolIndex = continuationState.getPosition();
//                ToolExecutionRequest toolExecutionRequest =
//                        agentDataHolder.getToolExecutionRequest().get(lastExecutedToolIndex);
//
//                Mediator lastInvoker = toolInvokers.get(toolExecutionRequest.name());
//                if (continuationState.hasChild()) {
//                    FlowContinuableMediator flowContinuableMediator = (FlowContinuableMediator) lastInvoker;
//                    boolean result =
//                            flowContinuableMediator.mediate(messageContext, continuationState.getChildContState());
//                    if (result) {
//                        ContinuationStackManager.removeReliantContinuationState(messageContext);
//                    } else {
//                        return false;
//                    }
////                    return result;
//                }
//                ContinuationStackManager.removeReliantContinuationState(messageContext);
//
//                // Store the last executed tool reponse
//                String toolExecutionResult =
//                        getToolExecutionResult(aiServiceContext, toolExecutionRequest, messageContext);
//                agentDataHolder.getToolExecutions().add(ToolExecution.builder()
//                        .request(toolExecutionRequest)
//                        .result(toolExecutionResult)
//                        .build());
//                ToolExecutionResultMessage toolExecutionResultMessage = ToolExecutionResultMessage.from(
//                        toolExecutionRequest,
//                        toolExecutionResult
//                                                                                                       );
//                aiServiceContext.chatMemory(agentDataHolder.getMemoryId()).add(toolExecutionResultMessage);
//
//                // Continue the last iteration tool executions
//                if (lastExecutedToolIndex < toolExecutionRequests.size() - 1) {
//
//                    for (int i = lastExecutedToolIndex; i < toolExecutionRequests.size(); i++) {
//
//                        ToolExecutionRequest currentToolExecutionRequest = toolExecutionRequests.get(i);
////                    mc.setProperty("AGENT_TOOL_EXECUTION_REQUESTS_"+id, aiMessage);
////                        Mediator mediator1 = messageContext.getSequenceTemplate(toolExecutionRequest.name());
//                        Mediator currentInvoker = toolInvokers.get(currentToolExecutionRequest.name());
//                        MessageContext clonedMessageContext = MessageHelper.cloneMessageContext(
//                                messageContext); // TODO: do we need to clone the message context if we are going to perform sequential execution?
//                        ContinuationStackManager.addReliantContinuationState(clonedMessageContext, 0, i);
//                        boolean result = executeTool(currentToolExecutionRequest, currentInvoker, clonedMessageContext);
//                        if (!result) {
//                            agentDataHolder.setToolExecutionRequest(
//                                    toolExecutionRequests.subList(i, toolExecutionRequests.size()));
////                            agentDataHolder.setToolExecutions(agentDataHolder.getToolExecutions().subList(0, i));
//                            clonedMessageContext.setProperty("AGENT_DATA_HOLDER_" + id, agentDataHolder);
//                            return false;
//                        }
//                        ContinuationStackManager.removeReliantContinuationState(clonedMessageContext);
//
//                        String currentToolExecutionResult =
//                                getToolExecutionResult(aiServiceContext, currentToolExecutionRequest,
//                                        clonedMessageContext);
//                        agentDataHolder.getToolExecutions().add(ToolExecution.builder()
//                                .request(currentToolExecutionRequest)
//                                .result(toolExecutionResult)
//                                .build());
//                        ToolExecutionResultMessage currentToolExecutionResultMessage = ToolExecutionResultMessage.from(
//                                currentToolExecutionRequest,
//                                currentToolExecutionResult
//                                                                                                                      );
////                    if (aiServiceContext.hasChatMemory()) {
//                        aiServiceContext.chatMemory(agentDataHolder.getMemoryId())
//                                .add(currentToolExecutionResultMessage);
////                    } else {
////                        messages.add(toolExecutionResultMessage); // TODO: Remove this part as we are going to provide memory
////                    }
//                    }
//                }
//
//                ChatRequest chatRequest;
//                ChatResponse chatResponse;
//                int executionsLeft = agentDataHolder.getExecutionsLeft();
//                while (true) {
//                    List<ChatMessage> chatMessages = aiServiceContext.chatMemory(agentDataHolder.getMemoryId()).messages();
//                    chatRequest = ChatRequest.builder()
//                            .messages(chatMessages)
//                            .parameters(agentDataHolder.getChatRequestParameters())
//                            .build();
//
//                    chatResponse = aiServiceContext.chatModel.chat(chatRequest);
//
//                    agentDataHolder.setTokenUsageAccumulator(TokenUsage.sum(agentDataHolder.getTokenUsageAccumulator(), chatResponse.metadata().tokenUsage()));
//                    if (executionsLeft-- == 0) {
//                        handleConnectorException(Errors.EXCEEDED_SEQUENTIAL_TOOL_EXECUTIONS, messageContext);
////                    return;
//                    }
//
//                    AiMessage aiMessage = chatResponse.aiMessage();
//
//                    if (aiServiceContext.hasChatMemory()) {
//                        aiServiceContext.chatMemory(agentDataHolder.getMemoryId()).add(aiMessage);
//                    } else {
////                        messages = new ArrayList<>(messages);
////                        messages.add(aiMessage);
//                    }
//
//                    if (!aiMessage.hasToolExecutionRequests()) {
//                        break;
//                    }
//
//                    int i = 0;
//                    for (ToolExecutionRequest toolExecutionRequest1 : aiMessage.toolExecutionRequests()) {
//
////                    mc.setProperty("AGENT_TOOL_EXECUTION_REQUESTS_"+id, aiMessage);
//                        Mediator toolMediator = toolInvokers.get(toolExecutionRequest1.name());
//                        MessageContext clonedMessageContext = MessageHelper.cloneMessageContext(
//                                messageContext); // TODO: do we need to clone the message context if we are going to perform sequential execution?
//                        ContinuationStackManager.addReliantContinuationState(clonedMessageContext, 0, i);
//                        boolean result = executeTool(toolExecutionRequest1, toolMediator, clonedMessageContext);
//                        if (!result) {
//                            agentDataHolder.setToolExecutionRequest(aiMessage.toolExecutionRequests());
//                            agentDataHolder.setExecutionsLeft(--executionsLeft);
//                            clonedMessageContext.setProperty("AGENT_DATA_HOLDER_" + id, agentDataHolder);
//                            return false;
//                        }
//                        ContinuationStackManager.removeReliantContinuationState(clonedMessageContext);
//
//                        String toolExecutionResult1 =
//                                getToolExecutionResult(aiServiceContext, toolExecutionRequest1, clonedMessageContext);
//
//                        agentDataHolder.getToolExecutions().add(ToolExecution.builder()
//                                .request(toolExecutionRequest1)
//                                .result(toolExecutionResult1)
//                                .build());
//                        ToolExecutionResultMessage toolExecutionResultMessage1 = ToolExecutionResultMessage.from(
//                                toolExecutionRequest1,
//                                toolExecutionResult1
//                                                                                                               );
//                        if (aiServiceContext.hasChatMemory()) {
//                            aiServiceContext.chatMemory(agentDataHolder.getMemoryId()).add(toolExecutionResultMessage1);
//                        } else {
////                            messages.add(
////                                    toolExecutionResultMessage1); // TODO: Remove this part as we are going to provide memory
//                        }
//                    }
//
//                    if (aiServiceContext.hasChatMemory()) {
//                        chatMessages = aiServiceContext.chatMemory(agentDataHolder.getMemoryId()).messages();
//                    }
//                }
//
//
//                FinishReason finishReason = chatResponse.metadata().finishReason();
//                Response<AiMessage>
//                        response = Response.from(chatResponse.aiMessage(), agentDataHolder.getTokenUsageAccumulator(), finishReason);
//
//                Object parsedResponse = serviceOutputParser.parse(response, returnType);
////            if (typeHasRawClass(returnType, Result.class)) {
//                Result<Object> parsedResult = Result.builder()
//                        .content(parsedResponse)
//                        .tokenUsage(agentDataHolder.getTokenUsageAccumulator())
//                        .finishReason(finishReason)
//                        .toolExecutions(agentDataHolder.getToolExecutions())
//                        .build();
////            } else {
////                return parsedResponse;
////            }
//
////            Object answer = getChatResponse(outputType, prompt, knowledgeRetriever, chatMemory, tools);
//                if (parsedResponse != null) {
//                    handleConnectorResponse(messageContext, parsedResult, null, null);
//                } else {
//                    handleConnectorException(Errors.INVALID_OUTPUT_TYPE, messageContext);
//                }
//            }
//
//
//            ContinuationStackManager.removeReliantContinuationState(messageContext);
//            log.info("Agent mediator mediate method is invoked from the continuation state");
//            return true;
//        } catch (Exception e) {
//            handleConnectorException(Errors.CHAT_COMPLETION_ERROR, messageContext, e);
//        }
//        return false;
//    }
//
//    private Map<ToolSpecification, String> generateToolSpecifications(MessageContext mc,
//                                                                      AiServiceContext aiServiceContext, String tool) {
//
//        if (tool == null) {
//            return Collections.EMPTY_MAP;
//        }
//        Map<ToolSpecification, String> toolSpecifications = new HashMap<>();
//        Mediator mediator = mc.getSequenceTemplate(tool);
//        if (mediator instanceof  TemplateMediator){
//            TemplateMediator templateMediator = (TemplateMediator) mediator;
//            String name = templateMediator.getName();
//            String description = templateMediator.getDescription();
//            List<TemplateParam> templateParams = new ArrayList<>(templateMediator.getParameters());
//            JsonObjectSchema parameterSchema = generateParameterSchema(templateParams);
//            ToolSpecification toolSpecification = ToolSpecification.builder().name(name).description(description).parameters(parameterSchema).build();
////            ToolExecutor toolExecutor = (toolExecutionRequest, memoryId) -> {
////                boolean result = executeTool(toolExecutionRequest, name, mc);
////                return mc.getVariable("result").toString();
////            };
//            ((SynapseAiContext) aiServiceContext).addToolResultVariable(toolSpecification.name(), "result");
//            toolSpecifications.put(toolSpecification,"result"); //TODO: replace with the actual result variable
//
//            // Add invoker for tool
//            InvokeMediator invoker = new InvokeMediator();
//            invoker.setTargetTemplate(tool);
//            // TODO: add with params
//
//
//            toolInvokers.put(name, invoker);
//        }
//        return toolSpecifications;
//    }
//
//    private boolean executeTool(ToolExecutionRequest toolExecutionRequest, Mediator invoker,
//                                MessageContext mc) {
//
//        Map<String, Object> arguments = AgentUtils.argumentsAsMap(toolExecutionRequest.arguments());
//        // Set the parameter values for the template mediator
//        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
//            ((InvokeMediator) invoker).addExpressionForParamName(entry.getKey(), new Value(entry.getValue().toString()));
//        }
//        return invoker.mediate(mc);
//    }
//
//    private JsonObjectSchema generateParameterSchema(List<TemplateParam> templateParams) {
//
//        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
//        for (TemplateParam templateParam : templateParams) {
//            String paramName = templateParam.getName();
//            boolean isMandatory = templateParam.isMandatory();
////            String description = templateParam.getDescription();
//            builder.addStringProperty(paramName, paramName);
//            if (isMandatory) {
//                builder.required(paramName);
//            }
//        }
//        return builder.build();
//    }
//
//    private List<EmbeddingMatch<TextSegment>> parseAndValidateKnowledge(String knowledge) {
//        try {
//            Type listType = new TypeToken<List<EmbeddingMatch<TextSegment>>>() {}.getType();
//            List<EmbeddingMatch<TextSegment>> embeddingMatches = Utils.fromJson(knowledge, listType);
//
//            // Validate the parsed list
//            if (embeddingMatches != null) {
//                for (EmbeddingMatch<TextSegment> match : embeddingMatches) {
//                    if (match.embedding() == null || match.embedded() == null) {
//                        return null;
//                    }
//                }
//            }
//            return embeddingMatches;
//        } catch (JsonSyntaxException e) {
//            return null;
//        }
//    }
//
//    private List<ChatMessage> parseAndValidateChatHistory(String chatHistory) {
//        try {
//            Type listType = new TypeToken<List<Map<String, String>>>() {}.getType();
//            List<Map<String, String>> rawMessages = gson.fromJson(chatHistory, listType);
//
//            List<ChatMessage> chatMessages = new ArrayList<>();
//            for (Map<String, String> rawMessage : rawMessages) {
//                String role = rawMessage.get("role");
//                String content = rawMessage.get("content");
//
//                if (role == null || content == null) {
//                    return null; // Invalid format
//                }
//
//                ChatMessage chatMessage;
//                switch (role) {
//                    case "user":
//                        chatMessage = new UserMessage(content);
//                        break;
//                    case "assistant":
//                        chatMessage = new AiMessage(content);
//                        break;
//                    default:
//                        return null; // Invalid role
//                }
//                chatMessages.add(chatMessage);
//            }
//            return chatMessages;
//        } catch (JsonSyntaxException e) {
//            return null; // Invalid JSON format
//        }
//    }
//
//    private Object getChatResponse(String outputType, String prompt, ContentRetriever knowledgeRetriever, ChatMemory chatMemory, Map<ToolSpecification, ToolExecutor> tools) {
//        return getAgent(Assistant.class, knowledgeRetriever, chatMemory, tools).chat("default",prompt);
//    }
//
//    private <T> T getAgent(Class<T> agentType, ContentRetriever knowledgeRetriever, ChatMemory chatMemory, Map<ToolSpecification,ToolExecutor> tools) {
//        AiServices<T> service = AiServices
//                .builder(agentType)
//                .chatLanguageModel(model)
//                .tools(tools)
//                .systemMessageProvider(chatMemoryId -> system != null ? system : DEFAULT_SYSTEM_PROMPT);
//        if (knowledgeRetriever != null) {
//            service = service.contentRetriever(knowledgeRetriever);
//        }
//        if (chatMemory != null) {
//            service = service.chatMemory(chatMemory);
//        }
//        return service.build();
//    }
//}
