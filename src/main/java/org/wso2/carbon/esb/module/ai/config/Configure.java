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

package org.wso2.carbon.esb.module.ai.config;

import org.apache.synapse.MessageContext;
import org.apache.synapse.mediators.template.ResolvedInvokeParam;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.esb.module.ai.Constants;

import java.util.List;

public class Configure extends AbstractConnector {

    private static final String SKIP_CONFIG_PROCESSING = "SKIP_CONFIG_PROCESSING";

    @Override
    public void connect(MessageContext messageContext) {

        if (messageContext.getVariable(SKIP_CONFIG_PROCESSING) != null &&
                (boolean) messageContext.getVariable(SKIP_CONFIG_PROCESSING)) {
            return;
        }
        Object connections = getParameter(messageContext, Constants.CONNECTIONS);
        if (connections instanceof ResolvedInvokeParam connectionsParam) {
            List<ResolvedInvokeParam> children = connectionsParam.getChildren();
            if (children != null) {
                for (ResolvedInvokeParam child : children) {
                    String connectionTag = child.getParamName();
                    Object connectionName = child.getInlineValue();
                    String propertyName;
                    switch (connectionTag) {
                        case "llmConfigKey":
                            propertyName = "_LLM_CONFIG_KEY";
                            break;
                        case "memoryConfigKey":
                            propertyName = "_MEMORY_CONFIG_KEY";
                            break;
                        case "vectorStoreConfigKey":
                            propertyName = "_VECTOR_STORE_CONFIG_KEY";
                            break;
                        case "embeddingConfigKey":
                            propertyName = "_EMBEDDING_CONFIG_KEY";
                            break;
                        default:
                            propertyName = null;
                    }
                    if (propertyName != null && connectionName instanceof String) {
                        messageContext.setProperty(propertyName, connectionName);
                    }
                }
            }
        } else {
            handleException("Invalid connections configuration", messageContext);
        }
        preserveOriginalPayload(messageContext);
    }

    private void preserveOriginalPayload(MessageContext messageContext) {

        messageContext.setVariable(Constants.ORIGINAL_PAYLOAD_BEFORE_INVOKE_TEMPLATE,
                messageContext.getProperty(Constants.ORIGINAL_PAYLOAD_BEFORE_INVOKE_TEMPLATE));
        messageContext.setVariable(Constants.ORIGINAL_MESSAGE_TYPE_BEFORE_INVOKE_TEMPLATE,
                messageContext.getProperty(Constants.ORIGINAL_MESSAGE_TYPE_BEFORE_INVOKE_TEMPLATE));
        messageContext.setVariable(Constants.ORIGINAL_CONTENT_TYPE_BEFORE_INVOKE_TEMPLATE,
                messageContext.getProperty(Constants.ORIGINAL_CONTENT_TYPE_BEFORE_INVOKE_TEMPLATE));
        messageContext.setVariable(Constants.ORIGINAL_NO_ENTITY_BODY_BEFORE_INVOKE_TEMPLATE,
                messageContext.getProperty(Constants.ORIGINAL_NO_ENTITY_BODY_BEFORE_INVOKE_TEMPLATE));
        messageContext.setVariable(Constants.ORIGINAL_TRANSPORT_HEADERS_BEFORE_INVOKE_TEMPLATE,
                messageContext.getProperty(Constants.ORIGINAL_TRANSPORT_HEADERS_BEFORE_INVOKE_TEMPLATE));
    }
}
