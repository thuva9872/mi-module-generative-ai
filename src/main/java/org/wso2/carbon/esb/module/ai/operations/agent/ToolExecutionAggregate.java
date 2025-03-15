/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.carbon.esb.module.ai.operations.agent;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.FaultHandler;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseLog;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.mediators.eip.EIPConstants;
import org.apache.synapse.mediators.eip.aggregator.Aggregate;
import org.wso2.carbon.esb.module.ai.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An instance of this class is created to manage each aggregation group, and it holds
 * the aggregation properties and the messages collected during aggregation. This class also
 * times out itself after the timeout expires it
 */
public class ToolExecutionAggregate extends TimerTask {

    private static final Log log = LogFactory.getLog(Aggregate.class);

    /**
     * The time in millis at which this aggregation should be considered as expired
     */
    private long expiryTimeMillis = 0;
    /**
     * The number of tool executions to be collected to consider this aggregation as complete
     */
    private int toolCount = -1;
    private String correlation = null;
    /**
     * The AggregateMediator that should be invoked on completion of the aggregation
     */
    private Agent agentMediator;
    private List<MessageContext> messages = new ArrayList<>();
    private ReentrantLock lock = new ReentrantLock();
    private boolean completed = false;
    private SynapseEnvironment synEnv;
    private SharedAgentDataHolder sharedAgentDataHolder;

    /**
     * Fault handler for the aggregate mediator
     */
    private FaultHandler faultHandler;

    /**
     * Save aggregation properties and timeout
     *
     * @param synEnv                the Synapse environment
     * @param correlation           representing the correlation name of the messages in the aggregate
     * @param timeoutMillis         the timeout duration in milliseconds
     * @param toolCount             the minimum number of tool execution results to be aggregated
     * @param mediator              the mediator that should be invoked on completion of the aggregation
     * @param faultHandler          the fault handler
     * @param sharedAgentDataHolder the shared agent data holder
     */
    public ToolExecutionAggregate(SynapseEnvironment synEnv, String correlation, long timeoutMillis, int toolCount,
                                  Agent mediator, FaultHandler faultHandler,
                                  SharedAgentDataHolder sharedAgentDataHolder) {

        this.synEnv = synEnv;
        this.correlation = correlation;
        if (timeoutMillis > 0) {
            expiryTimeMillis = System.currentTimeMillis() + timeoutMillis;
        }
        if (toolCount > 0) {
            this.toolCount = toolCount;
        }
        this.faultHandler = faultHandler;
        this.agentMediator = mediator;
        this.sharedAgentDataHolder = sharedAgentDataHolder;
    }

    public ToolExecutionAggregate(SynapseEnvironment synEnv, String correlation, long timeoutMillis, int toolCount,
                                  Agent mediator, FaultHandler faultHandler) {

        this(synEnv, correlation, timeoutMillis, toolCount, mediator, faultHandler, null);
    }

    /**
     * Add a message to the interlan message list
     *
     * @param synCtx message to be added into this aggregation group
     * @return true if the message was added or false if not
     */
    public synchronized boolean addMessage(MessageContext synCtx) {

        messages.add(synCtx);
        return true;
    }

    /**
     * Has this aggregation group completed?
     *
     * @param synLog the Synapse log to use
     * @return boolean true if aggregation is complete
     */
    public synchronized boolean isComplete(SynapseLog synLog) {

        if (!completed) {

            // if any messages have been collected, check if the completion criteria is met
            if (!messages.isEmpty()) {

                // get total messages for this group, from the first message we have collected
                MessageContext mc = messages.get(0);
                Object prop = mc.getProperty(Constants.AGENT_TOOL_EXECUTION +
                        (agentMediator.getAgentID() != null ? "." + agentMediator.getAgentID() : ""));

                if (prop != null && prop instanceof String) {
                    String[] msgSequence = prop.toString().split(
                            EIPConstants.MESSAGE_SEQUENCE_DELEMITER);
                    int total = Integer.parseInt(msgSequence[1]);

                    if (synLog.isTraceOrDebugEnabled()) {
                        synLog.traceOrDebug(messages.size() +
                                " messages of " + total + " collected in current aggregation");
                    }

                    if (messages.size() >= total) {
                        synLog.traceOrDebug("Aggregation complete");
                        return true;
                    }
                }
            } else {
                synLog.traceOrDebug("No messages collected in current aggregation");
            }

            // if all the tools are executed, its complete
            if (toolCount > 0 && messages.size() >= toolCount) {//TODO: refactor this
                if (synLog.isTraceOrDebugEnabled()) {
                    synLog.traceOrDebug(
                            "Aggregation complete - the minimum : " + toolCount
                                    + " messages has been reached");
                }
                return true;
            }

            // else, has this aggregation reached its timeout?
            if (expiryTimeMillis > 0 && System.currentTimeMillis() >= expiryTimeMillis) {
                synLog.traceOrDebug("Aggregation complete - the aggregation has timed out");

                return true;
            }
        } else {
            synLog.traceOrDebug(
                    "Aggregation already completed - this message will not be processed in aggregation");
        }

        return false;
    }

    public MessageContext getLastMessage() {

        if (!messages.isEmpty()) {
            return messages.get(messages.size() - 1);
        }
        return null;
    }

    public int getToolCount() {

        return toolCount;
    }

    public void setToolCount(int toolCount) {

        this.toolCount = toolCount;
    }

    public String getCorrelation() {

        return correlation;
    }

    public void setCorrelation(String correlation) {

        this.correlation = correlation;
    }

    public synchronized List<MessageContext> getMessages() {

        return new ArrayList<MessageContext>(messages);
    }

    public void setMessages(List<MessageContext> messages) {

        this.messages = messages;
    }

    public long getExpiryTimeMillis() {

        return expiryTimeMillis;
    }

    public void setExpiryTimeMillis(long expiryTimeMillis) {

        this.expiryTimeMillis = expiryTimeMillis;
    }

    public void run() {

        while (true) {
            if (completed) {
                break;
            }
            if (getLock()) {
                try {
                    if (log.isDebugEnabled()) {
                        log.debug("Time : " + System.currentTimeMillis() + " and this aggregator " +
                                "expired at : " + expiryTimeMillis);
                    }
                    synEnv.getExecutorService().execute(new ToolExecutionTimeout(this));
                    break;
                } finally {
                    releaseLock();
                }
            }
        }
    }

    /**
     * Clear references in Aggregate Timer Task
     * <p>
     * This need to be called when aggregation is completed.
     * Task is not eligible for gc until it reach the execution time,
     * even though it is cancelled. So we need to remove references from task to other objects to
     * allow them to be garbage collected
     */
    public void clear() {

        messages = null;
    }

    private class ToolExecutionTimeout implements Runnable {

        private ToolExecutionAggregate aggregate = null;

        ToolExecutionTimeout(ToolExecutionAggregate aggregate) {

            this.aggregate = aggregate;
        }

        public void run() {

            try {
                log.warn("Tool execution timeout occurred.");
                agentMediator.completeAggregate(aggregate);
            } catch (Exception ex) {
                MessageContext messageContext = aggregate.getLastMessage();
                if (faultHandler != null && messageContext != null) {
                    faultHandler.handleFault(messageContext, ex);
                } else {
                    log.error("Synapse encountered an exception, No error handlers found or no messages were " +
                            "aggregated - [Message Dropped]\n" + ex.getMessage());
                }
            }
        }
    }

    public synchronized boolean getLock() {

        return lock.tryLock();
    }

    public synchronized void releaseLock() {

        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    public boolean isCompleted() {

        return completed;
    }

    public void setCompleted(boolean completed) {

        this.completed = completed;
    }

    public SharedAgentDataHolder getSharedAgentDataHolder() {

        return sharedAgentDataHolder;
    }
}
