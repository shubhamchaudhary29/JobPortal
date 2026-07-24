package com.example.backend.config;

import com.example.backend.security.WebSocketAuthInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    private WebSocketAuthInterceptor webSocketAuthInterceptor;

    /**
     * Configure the in-memory message broker.
     * /topic  — pub/sub (one → many, e.g. broadcast to all subscribers in a chat room)
     * /queue  — point-to-point (one → one, e.g. personal notifications)
     * /app    — prefix for @MessageMapping destinations
     * /user   — prefix for user-specific destinations (convertAndSendToUser)
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    /**
     * Register the STOMP endpoint.
     * SockJS is enabled as a fallback for browsers that don't support native WebSocket.
     */
    @Value("${app.allowed-origin:http://localhost:5173}")
        private String allowedOrigin;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigin, "http://localhost*")
                .withSockJS();
    }

    /**
     * Register the JWT auth interceptor on the inbound channel so every
     * STOMP CONNECT frame is validated before the session is accepted.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor);
    }
}
