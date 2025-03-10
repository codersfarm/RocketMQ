/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.broker.client;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.netty.channel.Channel;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.protocol.heartbeat.ConsumeType;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.common.protocol.heartbeat.SubscriptionData;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.remoting.common.RemotingUtil;
import org.apache.rocketmq.store.stats.BrokerStatsManager;

public class ConsumerManager {
    private static final InternalLogger LOGGER = InternalLoggerFactory.getLogger(LoggerName.BROKER_LOGGER_NAME);
    private static final long CHANNEL_EXPIRED_TIMEOUT = 1000 * 120;
    private final ConcurrentMap<String, ConsumerGroupInfo> consumerTable =
        new ConcurrentHashMap<>(1024);
    private final List<ConsumerIdsChangeListener> consumerIdsChangeListenerList = new CopyOnWriteArrayList<>();
    protected final BrokerStatsManager brokerStatsManager;

    public ConsumerManager(final ConsumerIdsChangeListener consumerIdsChangeListener) {
        this.consumerIdsChangeListenerList.add(consumerIdsChangeListener);
        this.brokerStatsManager = null;
    }

    public ConsumerManager(final ConsumerIdsChangeListener consumerIdsChangeListener,
        final BrokerStatsManager brokerStatsManager) {
        this.consumerIdsChangeListenerList.add(consumerIdsChangeListener);
        this.brokerStatsManager = brokerStatsManager;
    }

    public ClientChannelInfo findChannel(final String group, final String clientId) {
        ConsumerGroupInfo consumerGroupInfo = this.consumerTable.get(group);
        if (consumerGroupInfo != null) {
            return consumerGroupInfo.findChannel(clientId);
        }
        return null;
    }

    public ClientChannelInfo findChannel(final String group, final Channel channel) {
        ConsumerGroupInfo consumerGroupInfo = this.consumerTable.get(group);
        if (consumerGroupInfo != null) {
            return consumerGroupInfo.findChannel(channel);
        }
        return null;
    }

    public SubscriptionData findSubscriptionData(final String group, final String topic) {
        ConsumerGroupInfo consumerGroupInfo = this.getConsumerGroupInfo(group);
        if (consumerGroupInfo != null) {
            return consumerGroupInfo.findSubscriptionData(topic);
        }

        return null;
    }

    public ConcurrentMap<String, ConsumerGroupInfo> getConsumerTable() {
        return this.consumerTable;
    }

    public ConsumerGroupInfo getConsumerGroupInfo(final String group) {
        return this.consumerTable.get(group);
    }

    public int findSubscriptionDataCount(final String group) {
        ConsumerGroupInfo consumerGroupInfo = this.getConsumerGroupInfo(group);
        if (consumerGroupInfo != null) {
            return consumerGroupInfo.getSubscriptionTable().size();
        }

        return 0;
    }

    public boolean doChannelCloseEvent(final String remoteAddr, final Channel channel) {
        boolean removed = false;
        Iterator<Entry<String, ConsumerGroupInfo>> it = this.consumerTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, ConsumerGroupInfo> next = it.next();
            ConsumerGroupInfo info = next.getValue();
            ClientChannelInfo clientChannelInfo = info.doChannelCloseEvent(remoteAddr, channel);
            if (clientChannelInfo != null) {
                callConsumerIdsChangeListener(ConsumerGroupEvent.CLIENT_UNREGISTER, next.getKey(), clientChannelInfo, info.getSubscribeTopics());
                if (info.getChannelInfoTable().isEmpty()) {
                    ConsumerGroupInfo remove = this.consumerTable.remove(next.getKey());
                    if (remove != null) {
                        LOGGER.info("unregister consumer ok, no any connection, and remove consumer group, {}",
                            next.getKey());
                        callConsumerIdsChangeListener(ConsumerGroupEvent.UNREGISTER, next.getKey());
                    }
                }

                callConsumerIdsChangeListener(ConsumerGroupEvent.CHANGE, next.getKey(), info.getAllChannel());
            }
        }
        return removed;
    }

    public boolean registerConsumer(final String group, final ClientChannelInfo clientChannelInfo,
        ConsumeType consumeType, MessageModel messageModel, ConsumeFromWhere consumeFromWhere,
        final Set<SubscriptionData> subList, boolean isNotifyConsumerIdsChangedEnable) {
        return registerConsumer(group, clientChannelInfo, consumeType, messageModel, consumeFromWhere, subList,
            isNotifyConsumerIdsChangedEnable, true);
    }

    public boolean registerConsumer(final String group, final ClientChannelInfo clientChannelInfo,
        ConsumeType consumeType, MessageModel messageModel, ConsumeFromWhere consumeFromWhere,
        final Set<SubscriptionData> subList, boolean isNotifyConsumerIdsChangedEnable, boolean updateSubscription) {
        long start = System.currentTimeMillis();
        ConsumerGroupInfo consumerGroupInfo = this.consumerTable.get(group);
        if (null == consumerGroupInfo) {
            callConsumerIdsChangeListener(ConsumerGroupEvent.CLIENT_REGISTER, group, clientChannelInfo,
                subList.stream().map(SubscriptionData::getTopic).collect(Collectors.toSet()));
            ConsumerGroupInfo tmp = new ConsumerGroupInfo(group, consumeType, messageModel, consumeFromWhere);
            ConsumerGroupInfo prev = this.consumerTable.putIfAbsent(group, tmp);
            consumerGroupInfo = prev != null ? prev : tmp;
        }

        boolean r1 =
            consumerGroupInfo.updateChannel(clientChannelInfo, consumeType, messageModel,
                consumeFromWhere);
        boolean r2 = false;
        if (updateSubscription) {
            r2 = consumerGroupInfo.updateSubscription(subList);
        }

        if (r1 || r2) {
            if (isNotifyConsumerIdsChangedEnable) {
                callConsumerIdsChangeListener(ConsumerGroupEvent.CHANGE, group, consumerGroupInfo.getAllChannel());
            }
        }
        if (null != this.brokerStatsManager) {
            this.brokerStatsManager.incConsumerRegisterTime((int) (System.currentTimeMillis() - start));
        }

        callConsumerIdsChangeListener(ConsumerGroupEvent.REGISTER, group, subList);

        return r1 || r2;
    }

    public void unregisterConsumer(final String group, final ClientChannelInfo clientChannelInfo,
        boolean isNotifyConsumerIdsChangedEnable) {
        ConsumerGroupInfo consumerGroupInfo = this.consumerTable.get(group);
        if (null != consumerGroupInfo) {
            boolean removed = consumerGroupInfo.unregisterChannel(clientChannelInfo);
            if (removed) {
                callConsumerIdsChangeListener(ConsumerGroupEvent.CLIENT_UNREGISTER, group, clientChannelInfo, consumerGroupInfo.getSubscribeTopics());
            }
            if (consumerGroupInfo.getChannelInfoTable().isEmpty()) {
                ConsumerGroupInfo remove = this.consumerTable.remove(group);
                if (remove != null) {
                    LOGGER.info("unregister consumer ok, no any connection, and remove consumer group, {}", group);

                    callConsumerIdsChangeListener(ConsumerGroupEvent.UNREGISTER, group);
                }
            }
            if (isNotifyConsumerIdsChangedEnable) {
                callConsumerIdsChangeListener(ConsumerGroupEvent.CHANGE, group, consumerGroupInfo.getAllChannel());
            }
        }
    }

    public void scanNotActiveChannel() {
        Iterator<Entry<String, ConsumerGroupInfo>> it = this.consumerTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, ConsumerGroupInfo> next = it.next();
            String group = next.getKey();
            ConsumerGroupInfo consumerGroupInfo = next.getValue();
            ConcurrentMap<Channel, ClientChannelInfo> channelInfoTable =
                consumerGroupInfo.getChannelInfoTable();

            Iterator<Entry<Channel, ClientChannelInfo>> itChannel = channelInfoTable.entrySet().iterator();
            while (itChannel.hasNext()) {
                Entry<Channel, ClientChannelInfo> nextChannel = itChannel.next();
                ClientChannelInfo clientChannelInfo = nextChannel.getValue();
                long diff = System.currentTimeMillis() - clientChannelInfo.getLastUpdateTimestamp();
                if (diff > CHANNEL_EXPIRED_TIMEOUT) {
                    LOGGER.warn(
                        "SCAN: remove expired channel from ConsumerManager consumerTable. channel={}, consumerGroup={}",
                        RemotingHelper.parseChannelRemoteAddr(clientChannelInfo.getChannel()), group);
                    callConsumerIdsChangeListener(ConsumerGroupEvent.CLIENT_UNREGISTER, group, clientChannelInfo, consumerGroupInfo.getSubscribeTopics());
                    RemotingUtil.closeChannel(clientChannelInfo.getChannel());
                    itChannel.remove();
                }
            }

            if (channelInfoTable.isEmpty()) {
                LOGGER.warn(
                    "SCAN: remove expired channel from ConsumerManager consumerTable, all clear, consumerGroup={}",
                    group);
                it.remove();
            }
        }
    }

    public HashSet<String> queryTopicConsumeByWho(final String topic) {
        HashSet<String> groups = new HashSet<>();
        Iterator<Entry<String, ConsumerGroupInfo>> it = this.consumerTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, ConsumerGroupInfo> entry = it.next();
            ConcurrentMap<String, SubscriptionData> subscriptionTable =
                entry.getValue().getSubscriptionTable();
            if (subscriptionTable.containsKey(topic)) {
                groups.add(entry.getKey());
            }
        }
        return groups;
    }

    public void appendConsumerIdsChangeListener(ConsumerIdsChangeListener listener) {
        consumerIdsChangeListenerList.add(listener);
    }

    protected void callConsumerIdsChangeListener(ConsumerGroupEvent event, String group, Object... args) {
        for (ConsumerIdsChangeListener listener : consumerIdsChangeListenerList) {
            try {
                listener.handle(event, group, args);
            } catch (Throwable t) {
                LOGGER.error("err when call consumerIdsChangeListener", t);
            }
        }
    }
}
