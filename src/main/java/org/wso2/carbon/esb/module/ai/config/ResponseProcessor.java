package org.wso2.carbon.esb.module.ai.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.synapse.MessageContext;
import org.wso2.carbon.esb.module.ai.AbstractAIMediator;
import org.wso2.carbon.esb.module.ai.Constants;

import java.util.HashMap;
import java.util.Map;

public class ResponseProcessor extends AbstractAIMediator {

    @Override
    public void execute(MessageContext messageContext) {

        Object responseVariable = getParameter(
                messageContext, Constants.RESPONSE_VARIABLE);
        if (!(responseVariable instanceof String)) {
            handleException("Invalid value for responseVariable", messageContext);
        }

        Object response = messageContext.getVariable(responseVariable.toString());
        if (response == null || !(response instanceof Map<?, ?>)) {
            handleException("Response not found", messageContext);
        }

        Object payload = null;
        Map<String, Object> headers = new HashMap<>();
        Map<String, Object> attributes = new HashMap<>();
        Map<?, ?> responseMap = (Map<?, ?>) response;
        if (responseMap.containsKey(Constants.PAYLOAD)) {
            payload = responseMap.get(Constants.PAYLOAD);
        }
        if (responseMap.containsKey(Constants.HEADERS) && responseMap.get(Constants.HEADERS) instanceof JsonObject) {
            JsonObject headerJson = (JsonObject) responseMap.get(Constants.HEADERS);
            for (Map.Entry<String, JsonElement> entry : headerJson.entrySet()) {
                headers.put(entry.getKey(), entry.getValue());
            }
        }
        if (responseMap.containsKey(Constants.ATTRIBUTES) &&
                responseMap.get(Constants.ATTRIBUTES) instanceof JsonObject) {
            JsonObject attributesJson = (JsonObject) responseMap.get(Constants.ATTRIBUTES);
            for (Map.Entry<String, JsonElement> entry : attributesJson.entrySet()) {
                attributes.put(entry.getKey(), entry.getValue());
            }
        }
        handleConnectorResponse(messageContext, payload, headers, attributes);

        // Restore the original payload so that the InvokeMediator can handle the response
        restoreOriginalPayload(messageContext);
    }

    private void restoreOriginalPayload(MessageContext messageContext) {

        messageContext.setProperty(Constants.ORIGINAL_PAYLOAD_BEFORE_INVOKE_TEMPLATE,
                messageContext.getVariable(Constants.ORIGINAL_PAYLOAD_BEFORE_INVOKE_TEMPLATE));
        messageContext.setProperty(Constants.ORIGINAL_MESSAGE_TYPE_BEFORE_INVOKE_TEMPLATE,
                messageContext.getVariable(Constants.ORIGINAL_MESSAGE_TYPE_BEFORE_INVOKE_TEMPLATE));
        messageContext.setProperty(Constants.ORIGINAL_CONTENT_TYPE_BEFORE_INVOKE_TEMPLATE,
                messageContext.getVariable(Constants.ORIGINAL_CONTENT_TYPE_BEFORE_INVOKE_TEMPLATE));
        messageContext.setProperty(Constants.ORIGINAL_TRANSPORT_HEADERS_BEFORE_INVOKE_TEMPLATE,
                messageContext.getVariable(Constants.ORIGINAL_TRANSPORT_HEADERS_BEFORE_INVOKE_TEMPLATE));
        messageContext.setProperty(Constants.ORIGINAL_NO_ENTITY_BODY_BEFORE_INVOKE_TEMPLATE,
                messageContext.getVariable(Constants.ORIGINAL_NO_ENTITY_BODY_BEFORE_INVOKE_TEMPLATE));
    }
}
