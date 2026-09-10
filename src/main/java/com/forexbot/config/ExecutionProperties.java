package com.forexbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the MT5 execution bridge gateway.
 * The bridge URL and trading defaults are supplied via environment
 * variables so the same build can target different prop-firm terminals.
 */
@ConfigurationProperties(prefix = "execution")
public class ExecutionProperties {

    /** Master switch; when false orders are logged but never dispatched. */
    private boolean enabled = true;

    /** Base URL of the local Python MT5 bridge (e.g. http://localhost:8090). */
    private String bridgeUrl = "http://localhost:8090";

    /** Shared secret sent as a bearer token to the bridge. */
    private String apiKey = "";

    /** Default order volume in lots when a decision omits one. */
    private double defaultVolumeLots = 0.01;

    /** Hard stop-loss distance in pips applied to every market order. */
    private int stopLossPips = 20;

    /** Optional take-profit distance in pips (0 disables it). */
    private int takeProfitPips = 40;

    /** Maximum tolerated slippage in points before the broker rejects. */
    private int maxSlippagePoints = 20;

    /** Magic number identifying orders originated by this bot. */
    private long magicNumber = 555000;

    /** Request timeout in seconds for a single dispatch. */
    private int timeoutSeconds = 15;

    /**
     * Transport used to reach the MT5 bridge:
     * <ul>
     *   <li>{@code auto} - use the WebSocket tunnel when a bridge is connected,
     *       otherwise fall back to outbound REST (best for cloud + local dev).</li>
     *   <li>{@code ws} - always multiplex over the bridge WebSocket.</li>
     *   <li>{@code rest} - always use outbound REST to {@link #bridgeUrl}.</li>
     * </ul>
     */
    private String transport = "auto";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBridgeUrl() {
        return bridgeUrl;
    }

    public void setBridgeUrl(String bridgeUrl) {
        this.bridgeUrl = bridgeUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public double getDefaultVolumeLots() {
        return defaultVolumeLots;
    }

    public void setDefaultVolumeLots(double defaultVolumeLots) {
        this.defaultVolumeLots = defaultVolumeLots;
    }

    public int getStopLossPips() {
        return stopLossPips;
    }

    public void setStopLossPips(int stopLossPips) {
        this.stopLossPips = stopLossPips;
    }

    public int getTakeProfitPips() {
        return takeProfitPips;
    }

    public void setTakeProfitPips(int takeProfitPips) {
        this.takeProfitPips = takeProfitPips;
    }

    public int getMaxSlippagePoints() {
        return maxSlippagePoints;
    }

    public void setMaxSlippagePoints(int maxSlippagePoints) {
        this.maxSlippagePoints = maxSlippagePoints;
    }

    public long getMagicNumber() {
        return magicNumber;
    }

    public void setMagicNumber(long magicNumber) {
        this.magicNumber = magicNumber;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }
}

