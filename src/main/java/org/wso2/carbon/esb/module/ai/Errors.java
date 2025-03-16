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

package org.wso2.carbon.esb.module.ai;

public enum Errors {

    // Connection related errors
    LLM_CONNECTION_ERROR("AI-LLM-0001", "Error creating LLM connection"),
    EMBEDDING_MODEL_CONNECTION_ERROR("AI-EMB-0001", "Error creating embedding model connection"),

    // Embedding ingestion related errors
    INVALID_INPUT_FOR_EMBEDDING_INGESTION(
            "AI-EMB-INGESTOR-0002",
            "Invalid input format. Expected a JSON array of TextEmbedding objects"
    ),
    EMBEDDING_INJECTION_ERROR("AI-EMB-INGESTOR-0001", "Embedding injection error occurred"),

    // Embedding retrieval related errors
    INVALID_INPUT_FOR_EMBEDDING_RETRIEVAL(
            "AI-EMB-RETRIEVER-0002",
            "Invalid input format. Expected a vector ( Array of numbers )"
    ),
    EMBEDDING_RETRIEVAL_ERROR("AI-EMB-RETRIEVER-0001", "Embedding retrieval error occurred"),

    // Vector store related errors
    PINECONE_CONNECTION_ERROR("AI-PC-0001", "Error creating Pinecone connection"),
    POSTGRE_SQL_CONNECTION_ERROR("AI-PS-0001", "Error creating Postgres connection"),

    // Parser related errors
    PARSE_ERROR("AI-PARSER-0001", "Error parsing the input"),
    UNSUPPORTED_PARSER_INPUT("AI-PARSER-0002", "Unsupported parser input"),
    UNSUPPORTED_PARSER_TYPE("AI-PARSER-0003", "Unsupported parser type"),
    HTML_TO_TEXT_ERROR("AI-PARSER-0004", "Error converting HTML to text"),
    HTML_TO_MARKDOWN_ERROR("AI-PARSER-0005", "Error converting HTML to markdown"),

    // Splitter related errors
    INVALID_SPLITTING_STRATEGY("AI-SS-0001", "Invalid splitting strategy"),
    FAILED_TO_SPLIT("AI-SS-0002", "Failed to split the input"),

    // Embedding generator related errors
    EMBEDDING_GENERATION_ERROR("AI-EG-0001", "Failed to generate embedding"),
    INVALID_INPUT_FOR_EMBEDDING_GENERATION(
            "AI-EG-0002",
            "Invalid input format. Expected a string or a JSON array of strings"
    ),

    // Chat related errors
    INVALID_INPUT_FOR_CHAT_KNOWLEDGE("AI-CH-0001", "Invalid input format. Expected a JSON array of Objects"),
    INVALID_INPUT_FOR_CHAT_MEMORY("AI-CH-0002", "Invalid chat history format. Expected a JSON array of ChatMessage objects. Use OpenAI format"),
    INVALID_OUTPUT_TYPE("AI-CH-0003", "Invalid output type selected"),
    CHAT_COMPLETION_ERROR("AI-CH-0004", "Error occurred from LLM side"),

    // Prompt related errors
    ERROR_PARSE_PROMPT("AI-PR-0001", "Error parsing the prompt"),

    // Agent related errors
    EXCEEDED_SEQUENTIAL_TOOL_EXECUTIONS("AI-AG-0001",
            "Exceeded the maximum number of sequential tool executions"),
    INVALID_TOOL_CONFIGURATION("AI-AG-0002", "Invalid tool configuration"),
    AGENT_ID_NOT_PROVIDED("AI-AG-0003", "Agent ID not provided");
    private final String code;
    private final String message;

    Errors(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
