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

import org.apache.synapse.MessageContext;
import org.wso2.carbon.esb.module.ai.Constants;
import org.wso2.carbon.esb.module.ai.Errors;
import org.wso2.carbon.esb.module.ai.exceptions.VectorStoreException;
import org.wso2.carbon.esb.module.ai.stores.*;
import org.wso2.micro.integrator.registry.MicroIntegratorRegistry;

import java.util.HashMap;

public class VectorStoreConnectionHandler {

    public static VectorStore getVectorStore(String connectionName, MessageContext mc) throws VectorStoreException {

        VectorStore vectorStore = null;
        ConnectionParams connectionParams = getConnectionParams(mc);

        switch (connectionParams.getConnectionType()) {
            case Constants.MI_VECTOR_STORE:
                Boolean persistence = connectionParams.getConnectionProperty(Constants.PERSISTENCE).equals(Constants.ENABLE);
                MicroIntegratorRegistry microIntegratorRegistry = (MicroIntegratorRegistry) mc.getConfiguration().getRegistry();
                vectorStore = new MIVectorStore(connectionName, persistence, microIntegratorRegistry);
                break;

            case Constants.CHROMA_DB:
                vectorStore = new ChromaDB(
                        connectionParams.getConnectionProperty(Constants.URL),
                        connectionParams.getConnectionProperty(Constants.COLLECTION)
                );
                break;

            case Constants.PINECONE:
                try {
                    vectorStore = new Pinecone(
                            connectionParams.getConnectionProperty(Constants.API_KEY),
                            connectionParams.getConnectionProperty(Constants.NAMESPACE),
                            connectionParams.getConnectionProperty(Constants.CLOUD),
                            connectionParams.getConnectionProperty(Constants.REGION),
                            connectionParams.getConnectionProperty(Constants.INDEX),
                            Integer.parseInt(connectionParams.getConnectionProperty(Constants.DIMENSION)));
                } catch (Exception e) {
                    throw new VectorStoreException(Errors.PINECONE_CONNECTION_ERROR, e);
                }
                break;

            case Constants.POSTGRE_SQL:
                try {
                    boolean status = PGVector.testConnection(
                            connectionParams.getConnectionProperty(Constants.HOST),
                            Integer.parseInt(connectionParams.getConnectionProperty(Constants.PORT)),
                            connectionParams.getConnectionProperty(Constants.DATABASE),
                            connectionParams.getConnectionProperty(Constants.USER),
                            connectionParams.getConnectionProperty(Constants.PASSWORD));
                    if (!status) {
                        throw new VectorStoreException(Errors.POSTGRE_SQL_CONNECTION_ERROR);
                    }

                    vectorStore = new PGVector(
                            connectionParams.getConnectionProperty(Constants.HOST),
                            connectionParams.getConnectionProperty(Constants.PORT),
                            connectionParams.getConnectionProperty(Constants.DATABASE),
                            connectionParams.getConnectionProperty(Constants.USER),
                            connectionParams.getConnectionProperty(Constants.PASSWORD),
                            connectionParams.getConnectionProperty(Constants.TABLE),
                            Integer.parseInt(connectionParams.getConnectionProperty(Constants.DIMENSION)));
                } catch (Exception e) {
                    throw new VectorStoreException(Errors.POSTGRE_SQL_CONNECTION_ERROR, e);
                }
                break;

            default:
                break;
        }
        return vectorStore;
    }

    public static String getProperty(MessageContext messageContext, String key) {
        return messageContext.getProperty(key) != null ? messageContext.getProperty(key).toString() : null;
    }

    public static ConnectionParams getConnectionParams(MessageContext messageContext) {
        String connectionType = getProperty(messageContext, Constants.CONNECTION_TYPE);
        String connectionName = getProperty(messageContext, Constants.CONNECTION_NAME);

        HashMap<String, String> connectionProperties = new HashMap<>();
        connectionProperties.put(Constants.PERSISTENCE, getProperty(messageContext, Constants.PERSISTENCE));
        connectionProperties.put(Constants.URL, getProperty(messageContext, Constants.URL));
        connectionProperties.put(Constants.COLLECTION, getProperty(messageContext, Constants.COLLECTION));

        connectionProperties.put(Constants.API_KEY, getProperty(messageContext, Constants.API_KEY));
        connectionProperties.put(Constants.CLOUD, getProperty(messageContext, Constants.CLOUD));
        connectionProperties.put(Constants.REGION, getProperty(messageContext, Constants.REGION));
        connectionProperties.put(Constants.INDEX, getProperty(messageContext, Constants.INDEX));
        connectionProperties.put(Constants.NAMESPACE, getProperty(messageContext, Constants.NAMESPACE));
        connectionProperties.put(Constants.DIMENSION, getProperty(messageContext, Constants.DIMENSION));

        connectionProperties.put(Constants.HOST, getProperty(messageContext, Constants.HOST));
        connectionProperties.put(Constants.PORT, getProperty(messageContext, Constants.PORT));
        connectionProperties.put(Constants.DATABASE, getProperty(messageContext, Constants.DATABASE));
        connectionProperties.put(Constants.USER, getProperty(messageContext, Constants.USER));
        connectionProperties.put(Constants.PASSWORD, getProperty(messageContext, Constants.PASSWORD));
        connectionProperties.put(Constants.TABLE, getProperty(messageContext, Constants.TABLE));

        // Clear the apiKey property for security reasons
        messageContext.setProperty(Constants.API_KEY, null);

        return new ConnectionParams(connectionName, connectionType, connectionProperties);
    }
}
