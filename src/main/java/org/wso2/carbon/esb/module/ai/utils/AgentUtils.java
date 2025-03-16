package org.wso2.carbon.esb.module.ai.utils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.apache.synapse.config.xml.ValueFactory;
import org.apache.synapse.mediators.Value;
import org.apache.synapse.mediators.template.ResolvedInvokeParam;
import org.apache.synapse.mediators.template.TemplateParam;
import org.apache.synapse.util.xpath.SynapseExpression;
import org.wso2.carbon.esb.module.ai.Constants;
import org.wso2.carbon.esb.module.ai.operations.agent.Tool;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AgentUtils {

    private static final Gson GSON = new Gson();
    /**
     * Utility {@link TypeToken} describing {@code Map<String, Object>}.
     */
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();
    private static final Pattern TRAILING_COMMA_PATTERN = Pattern.compile(",(\\s*[}\\]])");

    public static JsonObjectSchema generateParameterSchema(List<TemplateParam> templateParams) {

        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        for (TemplateParam templateParam : templateParams) {
            String paramName = templateParam.getName();
            boolean isMandatory = templateParam.isMandatory();
            String parameterDescription = templateParam.getDescription();
            builder.addStringProperty(paramName, parameterDescription != null ? parameterDescription : paramName);
            if (isMandatory) {
                builder.required(paramName);
            }
        }
        return builder.build();
    }

    public static Map<String, Object> argumentsAsMap(String arguments) {

        return GSON.fromJson(removeTrailingComma(arguments), MAP_TYPE);
    }

    /**
     * Removes trailing commas before closing braces or brackets in JSON strings.
     *
     * @param json the JSON string
     * @return the corrected JSON string
     */
    static String removeTrailingComma(String json) {

        if (json == null || json.isEmpty()) {
            return json;
        }
        Matcher matcher = TRAILING_COMMA_PATTERN.matcher(json);
        return matcher.replaceAll("$1");
    }
}
