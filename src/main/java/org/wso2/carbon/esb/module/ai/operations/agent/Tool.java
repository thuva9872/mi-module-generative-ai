package org.wso2.carbon.esb.module.ai.operations.agent;

import org.apache.synapse.mediators.Value;

public class Tool {

    private String name;
    private String template;
    private Value resultExpression;
    private String description;

    public Tool() {

    }

    public Tool(String name, String template, Value resultExpression, String description) {

        this.name = name;
        this.template = template;
        this.resultExpression = resultExpression;
        this.description = description;
    }

    public String getName() {

        return name;
    }

    public void setName(String name) {

        this.name = name;
    }

    public String getTemplate() {

        return template;
    }

    public void setTemplate(String template) {

        this.template = template;
    }

    public Value getResultExpression() {

        return resultExpression;
    }

    public void setResultExpression(Value resultExpression) {

        this.resultExpression = resultExpression;
    }

    public String getDescription() {

        return description;
    }

    public void setDescription(String description) {

        this.description = description;
    }
}
