package com.anthropic.claude.intellij.ui;

import com.anthropic.claude.intellij.util.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefJSQuery;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Handles Java/JS communication via JCEF message routing.
 * <p>
 * JS -> Java: Uses JBCefJSQuery to receive messages from the webview.
 * Java -> JS: Uses executeJavaScript() to call window.receiveFromJava().
 * <p>
 * Message format (both directions): JSON with "type" and "data" fields.
 */
public class WebviewBridge {

    private static final Logger LOG = Logger.getInstance(WebviewBridge.class);

    private final JBCefBrowser browser;
    private final JBCefJSQuery jsQuery;
    private BiConsumer<String, String> messageHandler;

    public WebviewBridge(JBCefBrowser browser) {
        this.browser = browser;
        this.jsQuery = JBCefJSQuery.create(browser);

        // Register handler for incoming JS messages
        jsQuery.addHandler((String request) -> {
            try {
                handleIncomingMessage(request);
            } catch (Exception e) {
                LOG.error("Error handling JS message: " + e.getMessage(), e);
            }
            return new JBCefJSQuery.Response("");
        });

        // Inject the bridge function into the page once it loads
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                if (frame.isMain()) {
                    injectBridgeFunction(cefBrowser);
                }
            }
        }, browser.getCefBrowser());
    }

    /**
     * Sets the handler that receives messages from the webview.
     * @param handler BiConsumer receiving (type, payload) where payload is a JSON string
     */
    public void setMessageHandler(BiConsumer<String, String> messageHandler) {
        this.messageHandler = messageHandler;
    }

    /**
     * Sends a message from Java to the webview JavaScript.
     * Calls window.receiveFromJava(type, data) in the browser.
     *
     * @param type    The message type string
     * @param jsonData The data as a JSON string
     */
    public void sendToWebview(String type, String jsonData) {
        if (browser.getCefBrowser() == null) {
            LOG.warn("Cannot send to webview: browser is null");
            return;
        }

        String escapedType = JsonParser.escapeJsonString(type);
        // jsonData is already a JSON string, so we embed it directly
        String script = "if (window.receiveFromJava) { window.receiveFromJava('" + escapedType + "', " + jsonData + "); }";

        try {
            browser.getCefBrowser().executeJavaScript(script, "", 0);
        } catch (Exception e) {
            LOG.error("Error executing JS in webview: " + e.getMessage(), e);
        }
    }

    /**
     * Injects the Java-callable bridge function into the webview page.
     * This creates window.__sendToJava(jsonString) which routes to our JBCefJSQuery.
     */
    private void injectBridgeFunction(CefBrowser cefBrowser) {
        String injection = jsQuery.inject("request");
        String script =
            "(function() {" +
            "  window.__sendToJava = function(request) {" +
            "    " + injection +
            "  };" +
            "  if (window.__onBridgeReady) { window.__onBridgeReady(); }" +
            "})();";

        try {
            cefBrowser.executeJavaScript(script, "", 0);
            LOG.info("Bridge function injected into webview");
        } catch (Exception e) {
            LOG.error("Failed to inject bridge function: " + e.getMessage(), e);
        }
    }

    /**
     * Handles an incoming message from the webview JavaScript.
     * Expected format: {"type": "...", "data": {...}}
     */
    private void handleIncomingMessage(String request) {
        if (request == null || request.isEmpty()) {
            return;
        }

        try {
            Map<String, Object> message = JsonParser.parseObject(request);
            String type = JsonParser.getString(message, "type");
            if (type == null) {
                LOG.warn("Received message from webview without type: " + truncate(request));
                return;
            }

            // Extract the data portion as a JSON string
            Object dataObj = message.get("data");
            String dataJson = (dataObj != null) ? JsonParser.toJson(dataObj) : "{}";

            if ("execute_slash_command".equals(type)) {
                LOG.info("Bridge execute_slash_command: raw request=" + truncate(request) + ", dataJson=" + truncate(dataJson));
            }

            if (messageHandler != null) {
                messageHandler.accept(type, dataJson);
            }
        } catch (Exception e) {
            LOG.error("Failed to parse message from webview: " + truncate(request), e);
        }
    }

    public void dispose() {
        jsQuery.dispose();
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
