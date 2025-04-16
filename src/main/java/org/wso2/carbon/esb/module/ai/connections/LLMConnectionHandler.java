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

package org.wso2.carbon.esb.module.ai.connections;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.azure.AzureOpenAiChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.mistralai.MistralAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.esb.module.ai.Constants;

import java.util.HashMap;
import java.util.Objects;

public class LLMConnectionHandler {

    public static ChatLanguageModel getChatModel(MessageContext messageContext,
                                                 String modelName, Double temperature, Integer maxTokens,
                                                 Double topP, Double frequencyPenalty, Integer seed) {

        // TODO: Need to pool the models as it is resource intensive to create a new model for each request
        ChatLanguageModel chatModel = null;
        ConnectionParams connectionParams = getConnectionParams(messageContext);

        switch (Objects.requireNonNull(connectionParams).getConnectionType()) {
            case Constants.OPEN_AI:
            case Constants.DEEPSEEK:
                // Null values of LLM params will be handled by LangChain4j
                 chatModel =  OpenAiChatModel.builder()
                         .baseUrl(connectionParams.getConnectionProperty(Constants.BASE_URL))
                         .modelName(modelName)
                         .temperature(temperature)
                         .maxTokens(maxTokens)
                         .topP(topP)
                         .frequencyPenalty(frequencyPenalty)
                         .seed(seed)
                         .apiKey(connectionParams.getConnectionProperty(Constants.API_KEY))
                         .build();
                 break;
            case Constants.AZURE_OPEN_AI:
                // Null values of LLM params will be handled by LangChain4j
                chatModel =  AzureOpenAiChatModel.builder()
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .topP(topP)
                        .frequencyPenalty(frequencyPenalty)
                        .seed(Long.valueOf(seed))
                        .apiKey(connectionParams.getConnectionProperty(Constants.API_KEY))
                        .deploymentName(connectionParams.getConnectionProperty(Constants.DEPLOYMENT_NAME))
                        .endpoint(connectionParams.getConnectionProperty(Constants.ENDPOINT))
                        .build();
                break;
            case Constants.ANTHROPIC:
                chatModel = AnthropicChatModel.builder()
                        .modelName(modelName)
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .topP(topP)
                        .apiKey(connectionParams.getConnectionProperty(Constants.API_KEY))
                        .build();
                break;
            case Constants.MISTRAL_AI:
                chatModel = MistralAiChatModel.builder()
                        .modelName(modelName)
                        .temperature(temperature)
                        .maxTokens(maxTokens)
                        .topP(topP)
                        .apiKey(connectionParams.getConnectionProperty(Constants.API_KEY))
                        .build();
                break;
            default:
                break;
        }
        return chatModel;
    }

    public static EmbeddingModel getEmbeddingModel(MessageContext messageContext, String modelName) {
        EmbeddingModel embeddingModel = null;
        ConnectionParams connectionParams = getConnectionParams(messageContext);

        switch (Objects.requireNonNull(connectionParams).getConnectionType()) {
            case "OPEN_AI":
                // Null values of LLM params will be handled by LangChain4j
                embeddingModel = OpenAiEmbeddingModel.builder()
                        .apiKey(connectionParams.getConnectionProperty(Constants.API_KEY))
                        .modelName(modelName)
                        .build();
            default:
                break;
        }
        return embeddingModel;
    }

    public static String getProperty(MessageContext messageContext, String key) {
        return messageContext.getProperty(key) != null ? messageContext.getProperty(key).toString() : null;
    }

    public static ConnectionParams getConnectionParams(MessageContext messageContext) {
        String connectionType = getProperty(messageContext, Constants.CONNECTION_TYPE);
        String connectionName = getProperty(messageContext, Constants.CONNECTION_NAME);

        HashMap<String, String> connectionProperties = new HashMap<>();
        connectionProperties.put(Constants.API_KEY, getProperty(messageContext, Constants.API_KEY));
        connectionProperties.put(Constants.DEPLOYMENT_NAME, getProperty(messageContext, Constants.DEPLOYMENT_NAME));
        connectionProperties.put(Constants.ENDPOINT, getProperty(messageContext, Constants.ENDPOINT));
        connectionProperties.put(Constants.BASE_URL, getProperty(messageContext, Constants.BASE_URL));

        // Clear the apiKey property for security reasons
        messageContext.setProperty(Constants.API_KEY, null);

        return new ConnectionParams(connectionName, connectionType, connectionProperties);
    }
}
