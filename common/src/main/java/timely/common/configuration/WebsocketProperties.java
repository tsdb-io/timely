package timely.common.configuration;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

@RefreshScope
@ConfigurationProperties(prefix = "timely.websocket")
public class WebsocketProperties {
    @Autowired
    TimelyProperties timelyProperties;
    @NotBlank
    private String ip;
    @NotNull
    private Integer basePort;
    private int timeout = 60;
    private int subscriptionLag = 120;
    private int scannerBatchSize = 5000;
    private int flushIntervalSeconds = 30;
    private int scannerReadAhead = 1;
    private int subscriptionBatchSize = 1000;

    private int getPortOffset() {
        return (timelyProperties.getInstance() - 1) * timelyProperties.getPortIncrement();
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return basePort + getPortOffset();
    }

    public Integer getBasePort() {
        return basePort;
    }

    public void setBasePort(int basePort) {
        this.basePort = basePort;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public int getSubscriptionLag() {
        return subscriptionLag;
    }

    public void setSubscriptionLag(int subscriptionLag) {
        this.subscriptionLag = subscriptionLag;
    }

    public int getScannerBatchSize() {
        return this.scannerBatchSize;
    }

    public void setScannerBatchSize(int batchSize) {
        this.scannerBatchSize = batchSize;
    }

    public int getFlushIntervalSeconds() {
        return this.flushIntervalSeconds;
    }

    public void setFlushIntervalSeconds(int flushInterval) {
        this.flushIntervalSeconds = flushInterval;
    }

    public int getScannerReadAhead() {
        return this.scannerReadAhead;
    }

    public void setScannerReadAhead(int readAhead) {
        this.scannerReadAhead = readAhead;
    }

    public int getSubscriptionBatchSize() {
        return this.subscriptionBatchSize;
    }

    public void setSubscriptionBatchSize(int batchSize) {
        this.subscriptionBatchSize = batchSize;
    }
}
