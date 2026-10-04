package com.tailoredbrands.otd.erpmq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code erp.jms.*} - bound from the {@code MQ_*} / {@code JMS_*} environment variables in application.yml.
 */
@ConfigurationProperties(prefix = "erp.jms")
public class ErpJmsProperties {

    /** {@code ibmmq} (default) or {@code artemis}. */
    private String provider = "ibmmq";

    /** Queue to consume ({@code MQ_QUEUE}); on the IBM MQ dev image use DEV.QUEUE.1 unless MQSC defines more. */
    private String queue = "ERP.ORDERS.IN";

    /** Listener concurrency, e.g. "1-3". */
    private String concurrency = "1-3";

    /** Max messages kept in memory for GET /received. */
    private int keepLast = 500;

    private final IbmMq ibmmq = new IbmMq();
    private final Artemis artemis = new Artemis();

    public static class IbmMq {
        private String host = "localhost";
        private int port = 1414;
        private String queueManager = "QM1";
        private String channel = "DEV.APP.SVRCONN";
        private String user = "app";
        private String password = "";
        private String appName = "tb-erp-consumer";

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getQueueManager() {
            return queueManager;
        }

        public void setQueueManager(String queueManager) {
            this.queueManager = queueManager;
        }

        public String getChannel() {
            return channel;
        }

        public void setChannel(String channel) {
            this.channel = channel;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getAppName() {
            return appName;
        }

        public void setAppName(String appName) {
            this.appName = appName;
        }
    }

    public static class Artemis {
        private String url = "tcp://localhost:61616";
        private String user = "";
        private String password = "";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public String getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(String concurrency) {
        this.concurrency = concurrency;
    }

    public int getKeepLast() {
        return keepLast;
    }

    public void setKeepLast(int keepLast) {
        this.keepLast = keepLast;
    }

    public IbmMq getIbmmq() {
        return ibmmq;
    }

    public Artemis getArtemis() {
        return artemis;
    }
}
