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
import org.apache.synapse.MessageContext;
import org.wso2.carbon.esb.module.ai.AbstractAIMediator;
import org.wso2.carbon.esb.module.ai.Constants;
import org.wso2.carbon.esb.module.ai.Errors;
import org.wso2.carbon.esb.module.ai.exceptions.VectorStoreException;
import org.wso2.carbon.esb.module.ai.stores.VectorStore;
import org.wso2.carbon.esb.module.ai.stores.VectorStoreConnectionHandler;
import org.wso2.carbon.esb.module.ai.models.TextEmbedding;
import org.wso2.carbon.esb.module.ai.utils.Utils;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Embedding ingestion operation
 * Inputs:
 * - input: JSON array of TextEmbedding objects
 * - connectionName: Name of the connection to the vector store
 * Outputs:
 * - SUCCESS: true or false
 */
public class EmbeddingIngestor extends AbstractAIMediator {

    @Override
    public void execute(MessageContext mc, String responseVariable, Boolean overwriteBody) {
        String connectionName = getProperty(mc, Constants.CONNECTION_NAME, String.class, false);
        String embeddings = getMediatorParameter(mc, Constants.INPUT, String.class, false);

        List<TextEmbedding> textEmbeddings = parseAndValidateInput(embeddings);
        if (textEmbeddings == null) {
            handleConnectorException(Errors.INVALID_INPUT_FOR_EMBEDDING_INGESTION, mc);
            return;
        }

        try {
            VectorStore vectorStore = VectorStoreConnectionHandler.getVectorStore(connectionName, mc);
            if (vectorStore == null) {
                handleConnectorException(Errors.VECTOR_STORE_CONNECTION_ERROR, mc);
                return;
            }
            vectorStore.add(textEmbeddings);
            handleConnectorResponse(mc, responseVariable, overwriteBody, Map.of("success", true),
                    null, Map.of("SUCCESS", "true"));
            return;
        } catch (VectorStoreException e) {
            handleConnectorException(e.getError(), mc, e);
        } catch (Exception e) {
            handleConnectorException(Errors.EMBEDDING_INJECTION_ERROR, mc, e);
        }
        handleConnectorResponse(mc, responseVariable, overwriteBody, Map.of("success", false), null,
                Map.of("SUCCESS", "false"));
    }

    private List<TextEmbedding> parseAndValidateInput(String input) {
        try {
            Type listType = new TypeToken<List<TextEmbedding>>() {}.getType();
            List<TextEmbedding> textEmbeddings = Utils.fromJson(input, listType);
            return validateTextEmbeddings(textEmbeddings);
        } catch (JsonSyntaxException e) {
            try {
                TextEmbedding singleEmbedding = Utils.fromJson(input, TextEmbedding.class);
                List<TextEmbedding> textEmbeddings = new ArrayList<>();
                textEmbeddings.add(singleEmbedding);
                return validateTextEmbeddings(textEmbeddings);
            } catch (JsonSyntaxException ex) {
                return null;
            }
        }
    }

    private List<TextEmbedding> validateTextEmbeddings(List<TextEmbedding> textEmbeddings) {
        if (textEmbeddings != null) {
            for (TextEmbedding embedding : textEmbeddings) {
                if (embedding.getText() == null || embedding.getText().isEmpty() ||
                        embedding.getEmbedding() == null || embedding.getEmbedding().length == 0) {
                    return null;
                }
            }
        }
        return textEmbeddings;
    }
}
