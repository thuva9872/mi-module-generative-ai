package org.wso2.carbon.esb.module.ai.operations.agent;

import dev.langchain4j.service.Result;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

public class AgentReturnType implements ParameterizedType {

    @Override
    public Type[] getActualTypeArguments() {

        return new Type[]{String.class};
    }

    @Override
    public Type getRawType() {

        return Result.class;
    }

    @Override
    public Type getOwnerType() {

        return null;
    }
}
