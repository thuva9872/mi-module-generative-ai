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
    LLM_CONNECTION_ERROR("701101", "AI:ERROR_CREATING_LLM_CONNECTION"),
    EMBEDDING_MODEL_CONNECTION_ERROR("701102", "AI:ERROR_CREATING_EMBEDDING_MODEL_CONNECTION"),

    // Embedding ingestion related errors
    INVALID_INPUT_FOR_EMBEDDING_INGESTION(
            "701111",
            "AI:ERROR_INVALID_INPUT_FOR_EMBEDDING_INGESTION"
    ),
    EMBEDDING_INJECTION_ERROR("701112", "AI:ERROR_EMBEDDING_INJECTION"),

    // Embedding retrieval related errors
    INVALID_INPUT_FOR_EMBEDDING_RETRIEVAL(
            "701121",
            "AI:ERROR_INVALID_INPUT_FOR_EMBEDDING_RETRIEVAL"
    ),
    EMBEDDING_RETRIEVAL_ERROR("701122", "AI:ERROR_EMBEDDING_RETRIEVAL"),

    // Vector store related errors
    PINECONE_CONNECTION_ERROR("701131", "AI:ERROR_PINECONE_CONNECTION"),
    POSTGRE_SQL_CONNECTION_ERROR("701132", "AI:ERROR_POSTGRE_SQL_CONNECTION"),
    VECTOR_STORE_CONNECTION_ERROR("701133", "AI:ERROR_VECTOR_STORE_CONNECTION"),

    // Parser related errors
    PARSE_ERROR("701141", "AI:ERROR_PARSE"),
    UNSUPPORTED_PARSER_INPUT("701142", "AI:ERROR_UNSUPPORTED_PARSER_INPUT"),
    UNSUPPORTED_PARSER_TYPE("701143", "AI:ERROR_UNSUPPORTED_PARSER_TYPE"),
    HTML_TO_TEXT_ERROR("701144", "AI:ERROR_HTML_TO_TEXT"),
    HTML_TO_MARKDOWN_ERROR("701145", "AI:ERROR_HTML_TO_MARKDOWN"),

    // Splitter related errors
    INVALID_SPLITTING_STRATEGY("701151", "AI:ERROR_INVALID_SPLITTING_STRATEGY"),
    FAILED_TO_SPLIT("701152", "AI:ERROR_FAILED_TO_SPLIT"),

    // Embedding generator related errors
    EMBEDDING_GENERATION_ERROR("701161", "AI:ERROR_EMBEDDING_GENERATION"),
    INVALID_INPUT_FOR_EMBEDDING_GENERATION(
            "701162",
            "AI:ERROR_INVALID_INPUT_FOR_EMBEDDING_GENERATION"
    ),

    // Chat related errors
    INVALID_INPUT_FOR_CHAT_KNOWLEDGE("701171", "AI:ERROR_INVALID_INPUT_FOR_CHAT_KNOWLEDGE"),
    INVALID_INPUT_FOR_CHAT_MEMORY("701172", "AI:ERROR_INVALID_INPUT_FOR_CHAT_MEMORY"),
    INVALID_OUTPUT_TYPE("701173", "AI:ERROR_INVALID_OUTPUT_TYPE"),
    CHAT_COMPLETION_ERROR("701174", "AI:ERROR_CHAT_COMPLETION"),

    // Prompt related errors
    ERROR_PARSE_PROMPT("701181", "AI:ERROR_PARSE_PROMPT"),

    // Agent related errors
    EXCEEDED_SEQUENTIAL_TOOL_EXECUTIONS("701191", "AI:ERROR_EXCEEDED_SEQUENTIAL_TOOL_EXECUTIONS"),
    INVALID_TOOL_CONFIGURATION("701192", "AI:ERROR_INVALID_TOOL_CONFIGURATION"),
    AGENT_ID_NOT_PROVIDED("701193", "AI:ERROR_AGENT_ID_NOT_PROVIDED");

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
