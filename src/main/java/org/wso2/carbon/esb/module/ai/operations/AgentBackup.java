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
//import dev.langchain4j.data.message.UserMessage;
//import dev.langchain4j.data.segment.TextSegment;
//import dev.langchain4j.memory.ChatMemory;
//import dev.langchain4j.model.chat.ChatLanguageModel;
//import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
//import dev.langchain4j.rag.content.Content;
//import dev.langchain4j.rag.content.retriever.ContentRetriever;
//import dev.langchain4j.service.AiServices;
//import dev.langchain4j.service.MemoryId;
//import dev.langchain4j.service.Result;
//import dev.langchain4j.service.tool.ToolExecutor;
//import dev.langchain4j.store.embedding.EmbeddingMatch;
//import org.apache.commons.logging.Log;
//import org.apache.commons.logging.LogFactory;
//import org.apache.synapse.ContinuationState;
//import org.apache.synapse.Mediator;
//import org.apache.synapse.MessageContext;
//import org.apache.synapse.continuation.ContinuationStackManager;
//import org.apache.synapse.mediators.FlowContinuableMediator;
//import org.apache.synapse.mediators.template.TemplateMediator;
//import org.apache.synapse.mediators.template.TemplateParam;
//import org.wso2.carbon.esb.module.ai.AbstractAIMediator;
//import org.wso2.carbon.esb.module.ai.Constants;
//import org.wso2.carbon.esb.module.ai.Errors;
//import org.wso2.carbon.esb.module.ai.llm.LLMConnectionHandler;
//import org.wso2.carbon.esb.module.ai.utils.Utils;
//
//import java.lang.reflect.Type;
//import java.util.ArrayList;
//import java.util.Collections;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
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
//public class AgentBackup extends AbstractAIMediator implements FlowContinuableMediator {
//
//    protected Log log = LogFactory.getLog(this.getClass());
//    private static final Gson gson = new Gson();
//
//    @Override
//    public boolean mediate(MessageContext messageContext, ContinuationState continuationState) {
//
//        log.info("Agent mediator mediate method is invoked from the continuation state");
//        return false;
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
//
//    @Override
//    public void execute(MessageContext mc) {
//        connectionName = getProperty(mc, Constants.CONNECTION_NAME, String.class, false);
//
//        String prompt = getMediatorParameter(mc, Constants.PROMPT, String.class, false);
//        modelName = getMediatorParameter(mc, Constants.MODEL_NAME, String.class, false);
//        String outputType = getMediatorParameter(mc, Constants.OUTPUT_TYPE, String.class, false);
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
//                return;
//            }
//        } catch (Exception e) {
//            handleConnectorException(Errors.LLM_CONNECTION_ERROR, mc, e);
//            return;
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
//                return;
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
//                return;
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
////            ToolSpecification tool1 = ToolSpecification.builder().name("callWeatherAPI").description("Return the weather detail").parameters(
////                    JsonObjectSchema.builder()
////                    .addStringProperty("city", "The city for which the weather forecast should be returned")
////                    .addEnumProperty("temperatureUnit", List.of("CELSIUS", "FAHRENHEIT"))
////                    .required("city") // the required properties should be specified explicitly
////                    .build()).build();
////            ToolExecutor toolExecutor1 = (toolExecutionRequest, memoryId) -> "The weather in requested city is 25 degrees Celsius";
////            ToolSpecification tool2 = ToolSpecification.builder().name("sendEmail").description("Send an email").parameters(
////                    JsonObjectSchema.builder()
////                            .addStringProperty("receiver", "The email address of the receiver")
////                            .addStringProperty("subject", "The subject of the email")
////                            .addStringProperty("body", "The body of the email")
////                            .required("receiver")
////                            .required("subject")
////                            .required("body")
////                            .build()).build();
////            ToolExecutor toolExecutor2 = (toolExecutionRequest, memoryId) -> {
////                File attachment = new File("/Users/thuvarakan/Documents/attachement.txt");
////                try {
////                    attachment.createNewFile();
////                    Files.write(attachment.toPath(), toolExecutionRequest.toString().getBytes());
////                } catch (IOException e) {
////                    throw new RuntimeException(e);
////                }
////                success = !success;
////                return success ? "Email sent successfully": "Email sending failed";
////            };
////            ContinuationStackManager.updateSeqContinuationState(mc,0);
////            ContinuationStackManager.addReliantContinuationState(mc, 1,getMediatorPosition()+1);
//            Map<ToolSpecification, ToolExecutor> tools = generateTools(mc, tool);
//            Object answer = getChatResponse(outputType, prompt, knowledgeRetriever, chatMemory, tools);
//            if (answer != null) {
//                handleConnectorResponse(mc, answer, null, null);
//            } else {
//                handleConnectorException(Errors.INVALID_OUTPUT_TYPE, mc);
//            }
//        } catch (Exception e) {
//            handleConnectorException(Errors.CHAT_COMPLETION_ERROR, mc, e);
//        }
//    }
//
//    private Map<ToolSpecification, ToolExecutor> generateTools(MessageContext mc, String tool) {
//
//        if (tool == null) {
//            return Collections.EMPTY_MAP;
//        }
//        Map<ToolSpecification, ToolExecutor> tools = new HashMap<>();
//        Mediator mediator = mc.getSequenceTemplate(tool);
//        if (mediator instanceof  TemplateMediator){
//            TemplateMediator templateMediator = (TemplateMediator) mediator;
//            String name = templateMediator.getName();
//            String description = templateMediator.getDescription();
//            List<TemplateParam> templateParams = new ArrayList<>(templateMediator.getParameters());
//            JsonObjectSchema parameterSchema = generateParameterSchema(templateParams);
//            ToolSpecification toolSpecification = ToolSpecification.builder().name(name).description(description).parameters(parameterSchema).build();
//            ToolExecutor toolExecutor = (toolExecutionRequest, memoryId) -> executeTool(toolExecutionRequest, name, mc);
//            tools.put(toolSpecification, toolExecutor);
//        }
//        return tools;
//    }
//
//    private String executeTool(ToolExecutionRequest toolExecutionRequest, String templateMediator,
//                                MessageContext mc) {
//
//        Mediator mediator = mc.getSequenceTemplate(templateMediator);
////        ContinuationStackManager.addReliantContinuationState(mc, 0, 0);
//        boolean result = mediator.mediate(mc);
//        if(result){
////            ContinuationStackManager.removeReliantContinuationState(mc);
//            return "Tool execution result: " + mc.getVariable("result").toString();
//        }
//        return "Tool execution failed";
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
