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

import org.apache.synapse.ManagedLifecycle;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.SynapseEnvironment;
import org.wso2.carbon.connector.core.AbstractConnector;
import org.wso2.carbon.esb.module.ai.Constants;
import org.wso2.carbon.esb.module.ai.llm.LLMConnectionHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Config extends AbstractConnector {

    @Override
    public void connect(MessageContext messageContext) {

        Object connections = getParameter(messageContext, Constants.CONNECTIONS);
        if (connections instanceof Map<?,?>) {
            Map<String, Object> connectionsMap = (Map<String, Object>) connections;
            Object children = connectionsMap.get("children");
            if(children instanceof List<?>) {
                List<Object> childrenList = (List) children;
                for(Object child : childrenList) {
                    if (child instanceof Map<?, ?>) {
                        Map<String, Object> childMap = (Map<String, Object>) child;
                        Object connectionTypeObj = childMap.get("name");
                        Object connectionNameObj = childMap.get("inlineValue");
                        if(connectionTypeObj instanceof String){
                            String connectionType = (String) connectionTypeObj;
                            String propertyName;
                            switch (connectionType) {
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
                            if(propertyName != null && connectionNameObj instanceof String) {
                                messageContext.setProperty(propertyName, connectionNameObj);
                            }
                        }
                    }
                }
            }
        } else {
            handleException("Invalid connections configuration", messageContext);
        }
    }
}
