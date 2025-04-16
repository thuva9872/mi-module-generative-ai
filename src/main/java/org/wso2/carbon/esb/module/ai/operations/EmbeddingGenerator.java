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

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.esb.module.ai.AbstractAIMediator;
import org.wso2.carbon.esb.module.ai.Constants;
import org.wso2.carbon.esb.module.ai.Errors;
import org.wso2.carbon.esb.module.ai.connections.LLMConnectionHandler;
import org.wso2.carbon.esb.module.ai.models.TextEmbedding;
import org.wso2.carbon.esb.module.ai.utils.Utils;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;


/**
 *  Embedding generation operation
 *  Inputs:
 *  - input: String or JSON array of Strings objects
 *  - model: Name of the embedding model
 *  - connectionName: Name of the connection to the LLM
 *  Outputs:
 *  - TextEmbedding object or JSON array of TextEmbedding objects
 */
public class EmbeddingGenerator extends AbstractAIMediator {

    @Override
    public void execute(MessageContext mc, String responseVariable, Boolean overwriteBody) {

        String input = getMediatorParameter(mc, Constants.INPUT, String.class, false);
        String model = getMediatorParameter(mc, Constants.MODEL, String.class, false);

        List<TextSegment> inputs = parseAndValidateInput(input);
        if (inputs == null) {
            handleConnectorException(Errors.INVALID_INPUT_FOR_EMBEDDING_GENERATION, mc);
            return;
        }

        List<TextEmbedding> textEmbeddings = new ArrayList<>();
        try {
            EmbeddingModel embeddingModel = LLMConnectionHandler.getEmbeddingModel(mc, model);
            if (embeddingModel == null) {
                handleConnectorException(Errors.EMBEDDING_MODEL_CONNECTION_ERROR, mc);
                return;
            }
            Response<List<Embedding>> embedding = embeddingModel.embedAll(inputs);
            for (int i = 0; i < inputs.size(); i++) {
                textEmbeddings.add(new TextEmbedding(inputs.get(i).text(),
                        embedding.content().get(i).vector(), inputs.get(i).metadata()));
            }
        } catch (Exception e) {
            handleConnectorException(Errors.EMBEDDING_GENERATION_ERROR, mc);
        }

        // If multiple inputs were provided, return a JSON array of TextEmbedding objects
        handleConnectorResponse(mc, responseVariable, overwriteBody, textEmbeddings, null, null);
    }

    private List<TextSegment> parseAndValidateInput(String input) {
        List<TextSegment> textSegments;
        try {
            // Try to parse input as a JSON array of TextSegment objects
            Type listType = new TypeToken<List<TextSegment>>() {}.getType();
            textSegments = Utils.fromJson(input, listType);

            // If parsing as JSON array fails, treat input as a single string
            if (textSegments == null || textSegments.isEmpty()) {
                textSegments = new ArrayList<>();
                textSegments.add(new TextSegment(input, new Metadata()));
            }

            for (TextSegment segment : textSegments) {
                if (segment.text() == null || segment.text().isEmpty()) {
                    return null;
                }
            }
        } catch (JsonSyntaxException e) {
            // If JSON parsing fails, treat input as a single string
            textSegments = new ArrayList<>();
            textSegments.add(new TextSegment(input, new Metadata()));
        }
        return textSegments;
    }
}
