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
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.FilterParser;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.esb.module.ai.AbstractAIMediator;
import org.wso2.carbon.esb.module.ai.Constants;
import org.wso2.carbon.esb.module.ai.Errors;
import org.wso2.carbon.esb.module.ai.exceptions.VectorStoreException;
import org.wso2.carbon.esb.module.ai.utils.StringFilterParser;
import org.wso2.carbon.esb.module.ai.stores.VectorStore;
import org.wso2.carbon.esb.module.ai.connections.VectorStoreConnectionHandler;
import org.wso2.carbon.esb.module.ai.utils.Utils;

import java.util.List;

/**
 * Embedding store retrieval operation
 * Inputs:
 * - connectionName: Name of the connection to the vector store
 * - input: TextEmbedding object
 * - maxResults: Maximum number of results to return
 * - minScore: Minimum score for the results
 * - filter: Filter string
 * Outputs:
 * - List of EmbeddingMatch objects
 */
public class EmbeddingStoreRetriever extends AbstractAIMediator {

    // TODO: Implement filter parsing
    FilterParser filterParser = new StringFilterParser();

    @Override
    public void execute(MessageContext mc, String responseVariable, Boolean overwriteBody) {
        String connectionName = getProperty(mc, Constants.CONNECTION_NAME, String.class, false);
        String input = getMediatorParameter(mc, Constants.INPUT, String.class, false);
        Integer maxResults = getMediatorParameter(mc, Constants.MAX_RESULTS, Integer.class, false);
        Double minScore = getMediatorParameter(mc, Constants.MIN_SCORE, Double.class, false);
        // TODO: Implement filter parsing
        String filterString = getMediatorParameter(mc, Constants.FILTER, String.class, true);

        Embedding embedding = parseAndValidateInput(input);
        if (embedding == null) {
            handleConnectorException(Errors.INVALID_INPUT_FOR_EMBEDDING_RETRIEVAL, mc);
            return;
        }

        // TODO: Implement filter parsing
        Filter filter = null;

        List<EmbeddingMatch<TextSegment>> matches = List.of();
        try {
            VectorStore vectorStore = VectorStoreConnectionHandler.getVectorStore(connectionName, mc);
            if (vectorStore == null) {
                handleConnectorException(Errors.VECTOR_STORE_CONNECTION_ERROR, mc);
                return;
            }
            matches = vectorStore.search(embedding, maxResults, minScore, filter);
        } catch (VectorStoreException e) {
            handleConnectorException(e.getError(), mc, e);
        } catch (Exception e) {
            handleConnectorException(Errors.EMBEDDING_RETRIEVAL_ERROR, mc, e);
        }
        handleConnectorResponse(mc, responseVariable, overwriteBody,
                matches, null, null);
    }

    private Embedding parseAndValidateInput(String input) {
        try {
            float[] embeddingArray = Utils.fromJson(input, float[].class);
            if (embeddingArray == null || embeddingArray.length == 0) {
                return null;
            }
            return new Embedding(embeddingArray);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }
}
