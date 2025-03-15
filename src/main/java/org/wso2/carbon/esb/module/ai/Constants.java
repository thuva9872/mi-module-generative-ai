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

public class Constants {
    // Content types
    public final static String JSON_CONTENT_TYPE = "application/json";

    // Vector store connection properties
    public static final String CONNECTION_TYPE = "connectionType";
    public static final String CONNECTION_NAME = "connectionName";
    public static final String PERSISTENCE = "persistence";
    public static final String URL = "url";
    public static final String COLLECTION = "collection";
    public static final String API_KEY = "apiKey";
    public static final String CLOUD = "cloud";
    public static final String REGION = "region";
    public static final String INDEX = "index";
    public static final String NAMESPACE = "namespace";
    public static final String DIMENSION = "dimension";
    public static final String HOST = "host";
    public static final String PORT = "port";
    public static final String DATABASE = "database";
    public static final String USER = "user";
    public static final String PASSWORD = "password";
    public static final String TABLE = "table";
    public static final String ENABLE = "Enable";
    public static final String MI_VECTOR_STORE = "MI_VECTOR_STORE";
    public static final String CHROMA_DB = "CHROMA_DB";
    public static final String PINECONE = "PINECONE";
    public static final String POSTGRE_SQL = "POSTGRE_SQL";

    // LLM connection properties
    public static final String DEPLOYMENT_NAME = "deploymentName";
    public static final String ENDPOINT = "endpoint";
    public static final String BASE_URL = "baseUrl";
    public static final String OPEN_AI = "OPEN_AI";
    public static final String MISTRAL_AI = "MISTRAL_AI";
    public static final String ANTHROPIC = "ANTHROPIC";
    public static final String AZURE_OPEN_AI = "AZURE_OPEN_AI";
    public static final String DEEPSEEK = "DEEPSEEK";

    // Parser Constants
    public static final String MD_TO_TEXT = "markdown-to-text";
    public static final String HTML_TO_TEXT = "html-to-text";
    public static final String PDF_TO_TEXT = "pdf-to-text";
    public static final String DOC_TO_TEXT = "doc-to-text";
    public static final String DOCX_TO_TEXT = "docx-to-text";
    public static final String PPT_TO_TEXT = "ppt-to-text";
    public static final String PPTX_TO_TEXT = "pptx-to-text";
    public static final String XLS_TO_TEXT = "xls-to-text";
    public static final String XLSX_TO_TEXT = "xlsx-to-text";

    public static final String INPUT = "input";
    public static final String TYPE = "type";

    // Output Constants
    public static final String RESPONSE_VARIABLE = "responseVariable";
    public static final String OVERWRITE_BODY = "overwriteBody";

    // Splitter Constants
    public static final String STRATEGY = "strategy";
    public static final String MAX_SEGMENT_SIZE = "maxSegmentSize";
    public static final String MAX_OVERLAP_SIZE = "maxOverlapSize";

    // Splitting strategies
    public static final String RECURSIVE = "Recursive";
    public static final String BY_PARAGRAPH = "ByParagraph";
    public static final String BY_SENTENCE = "BySentence";

    // Embedding generation constants
    public static final String MODEL = "model";

    // Embedding store retrieval constants
    public static final String MAX_RESULTS = "maxResults";
    public static final String MIN_SCORE = "minScore";
    public static final String FILTER = "filter";

    // LLM Chat Constants
    public static final String PROMPT = "prompt";
    public static final String MODEL_NAME = "modelName";
    public static final String OUTPUT_TYPE = "outputType";
    public static final String SYSTEM = "system";
    public static final String TEMPERATURE = "temperature";
    public static final String MAX_TOKENS = "maxTokens";
    public static final String TOP_P = "topP";
    public static final String FREQUENCY_PENALTY = "frequencyPenalty";
    public static final String SEED = "seed";
    public static final String KNOWLEDGE = "knowledge";
    public static final String HISTORY = "history";
    public static final String MAX_HISTORY = "maxHistory";

    // Agent Constants
    public static final String TOOL_EXECUTION_CORRELATION = "toolExecutionCorrelation";
    public static final String AGENT_TOOL_EXECUTION = "agentToolExecution";
    public static final String AGENT_SHARED_DATA_HOLDER = "AGENT_SHARED_DATA_HOLDER";
    public static final String TOOL_EXECUTION_DATA_HOLDER = "toolExecutionDataHolder";
    public static final String MEMORY_ID = "memoryId";
    public static final String USER_ID = "userID";
    public static final String TOOL_EXECUTION_FAILED = "Tool execution failed";
    public static final String CONNECTIONS = "connections";
    public static final String TOOLS = "tools";
    public static final String NAME = "name";
    public static final String TEMPLATE = "template";
    public static final String RESULT_EXPRESSION = "resultExpression";
    public static final String DESCRIPTION = "description";

    // Preserve message context properties
    public static final String ORIGINAL_PAYLOAD_BEFORE_INVOKE_TEMPLATE = "ORIGINAL_PAYLOAD_BEFORE_INVOKE_TEMPLATE";
    public static final String ORIGINAL_MESSAGE_TYPE_BEFORE_INVOKE_TEMPLATE =
            "_ORIGINAL_MESSAGE_TYPE_BEFORE_INVOKE_TEMPLATE";
    public static final String ORIGINAL_CONTENT_TYPE_BEFORE_INVOKE_TEMPLATE =
            "_ORIGINAL_CONTENT_TYPE_BEFORE_INVOKE_TEMPLATE";
    public static final String ORIGINAL_NO_ENTITY_BODY_BEFORE_INVOKE_TEMPLATE =
            "_ORIGINAL_NO_ENTITY_BODY_BEFORE_INVOKE_TEMPLATE";
    public static final String ORIGINAL_TRANSPORT_HEADERS_BEFORE_INVOKE_TEMPLATE =
            "_ORIGINAL_TRANSPORT_HEADERS_BEFORE_INVOKE_TEMPLATE";

    // Response processor constants
    public static final String PAYLOAD = "payload";
    public static final String HEADERS = "headers";
    public static final String ATTRIBUTES = "attributes";
    public static final String TOOL_EXECUTION_TIMEOUT = "toolExecutionTimeout";
}
