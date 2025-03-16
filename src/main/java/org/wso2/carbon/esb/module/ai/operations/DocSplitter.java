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

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentBySentenceSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.esb.module.ai.AbstractAIMediator;
import org.wso2.carbon.esb.module.ai.Constants;
import org.wso2.carbon.esb.module.ai.Errors;

import java.util.List;
import java.util.Objects;

/**
 * Document splitting operation
 * Inputs:
 * - input: String
 * - strategy: Splitting strategy (Recursive, ByParagraph, BySentence)
 * - maxSegmentSize: Maximum segment size
 * - maxOverlapSize: Maximum overlap size
 * Outputs:
 * - List of TextSegment objects
 */
public class DocSplitter extends AbstractAIMediator {

    @Override
    public void execute(MessageContext mc) {

        String input = getMediatorParameter(mc, Constants.INPUT, String.class, false);
        String strategy = getMediatorParameter(mc, Constants.STRATEGY, String.class, false);
        Integer maxSegmentSize = getMediatorParameter(mc, Constants.MAX_SEGMENT_SIZE, Integer.class, true);
        Integer maxOverlapSize = getMediatorParameter(mc, Constants.MAX_OVERLAP_SIZE, Integer.class, true);

        String responseVariable = getMediatorParameter(mc, Constants.RESPONSE_VARIABLE, String.class, false);
        Boolean overwriteBody = getMediatorParameter(mc, Constants.OVERWRITE_BODY, Boolean.class, false);

        maxSegmentSize = (maxSegmentSize == null) ? 1000 : maxSegmentSize;
        maxOverlapSize = (maxOverlapSize == null) ? 200 : maxOverlapSize;

        DocumentSplitter splitter = null;
        switch (strategy) {
            case Constants.RECURSIVE:
                splitter = DocumentSplitters.recursive(maxSegmentSize, maxOverlapSize, new OpenAiTokenizer());
                break;
            case Constants.BY_PARAGRAPH:
                splitter = new DocumentByParagraphSplitter(maxSegmentSize, maxOverlapSize, new OpenAiTokenizer());
                break;
            case Constants.BY_SENTENCE:
                splitter = new DocumentBySentenceSplitter(maxSegmentSize, maxOverlapSize, new OpenAiTokenizer());
                break;
            default:
                handleConnectorException(Errors.INVALID_SPLITTING_STRATEGY, mc);
        }

        List<TextSegment> segments = null;
        try {
            segments = Objects.requireNonNull(splitter).split(new Document(input));
        } catch (Exception e) {
            handleConnectorException(Errors.FAILED_TO_SPLIT, mc, e);
        }

        if (segments == null) {
            handleConnectorException(Errors.FAILED_TO_SPLIT, mc);
        }

        handleConnectorResponse(mc, segments, null, null, responseVariable, overwriteBody);
    }
}
