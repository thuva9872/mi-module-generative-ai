package org.wso2.carbon.esb.module.ai.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryChatMemoryProvider implements ChatMemoryProvider {

    // userId->ChatMemory
    Map<Object, ChatMemory> chatMemories;
    private int maxMessages;

    public InMemoryChatMemoryProvider(int maxMessages) {

        this.maxMessages = maxMessages;
        this.chatMemories = new ConcurrentHashMap<>();
    }

    @Override
    public ChatMemory get(Object memoryId) {
        // TODO: MessageWindowChatMemory is not thread safe. Implement a thread safe version.
        return chatMemories.computeIfAbsent(memoryId,
                k -> MessageWindowChatMemory.builder().id(memoryId).maxMessages(maxMessages)
                        .chatMemoryStore(new InMemoryChatMemoryStore()).build());
    }

    public Map<Object, ChatMemory> getChatMemories() {

        return chatMemories;
    }
}
