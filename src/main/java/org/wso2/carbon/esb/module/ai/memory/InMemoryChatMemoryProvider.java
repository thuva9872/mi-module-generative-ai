package org.wso2.carbon.esb.module.ai.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryChatMemoryProvider implements ChatMemoryProvider {

    // userId->ChatMemory
    Map<String, ChatMemory> chatMemories;
    private int maxMessages;
    public InMemoryChatMemoryProvider(int maxMessages) {

        this.maxMessages = maxMessages;
        this.chatMemories = new ConcurrentHashMap<>();
    }

    @Override
    public ChatMemory get(Object o) {
        // TODO: MessageWindowChatMemory is not thread safe. Implement a thread safe version.
        return chatMemories.computeIfAbsent((String) o, k -> MessageWindowChatMemory.withMaxMessages(maxMessages));
    }
}
