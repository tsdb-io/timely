package timely.client.websocket;

import java.util.List;
import java.util.Map;

import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ClientHandler extends Endpoint {

    private final static Logger log = LoggerFactory.getLogger(ClientHandler.class);

    @Override
    public void onOpen(Session session, EndpointConfig config) {
        log.info("Websocket session {} opened.", session.getId());
        session.addMessageHandler(String.class, message -> {
            log.info("Message received on Websocket session {}: {}", session.getId(), message);
        });
    }

    @Override
    public void onClose(Session session, CloseReason reason) {
        log.info("Websocket session {} closed.", session.getId());
    }

    @Override
    public void onError(Session session, Throwable error) {
        log.error("Error occurred on Websocket session" + session.getId(), error);
    }

    public void beforeRequest(Map<String,List<String>> headers) {}
}
