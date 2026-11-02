package com.codebaseai.backend.config;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Chat POSTs call the LLM and the embedder, so they are limited to 20 requests per
 * minute per user and route family. Opening more conversations or projects must not
 * create more quota.
 */
class InMemoryRateLimitFilterTest {

    private static final String PROJECT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String CONVERSATION_ID = "22222222-2222-2222-2222-222222222222";
    private static final String USER_ID = "33333333-3333-3333-3333-333333333333";
    private static final int AI_LIMIT = 20;
    private static final int AUTH_LIMIT = 5;
    private static final String OTHER_USER_ID = "44444444-4444-4444-4444-444444444444";
    private static final String OTHER_CONVERSATION_ID = "55555555-5555-5555-5555-555555555555";
    private static final String THIRD_CONVERSATION_ID = "66666666-6666-6666-6666-666666666666";

    private InMemoryRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new InMemoryRateLimitFilter();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, new ArrayList<>()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private int send(String method, String path) throws Exception {
        return sendAs(USER_ID, method, path);
    }

    /** A null user id sends the request anonymously, keyed by remote address. */
    private int sendAs(String userId, String method, String path) throws Exception {
        if (userId == null) {
            SecurityContextHolder.clearContext();
        } else {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, null, new ArrayList<>()));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(method, path), response, new MockFilterChain());
        return response.getStatus();
    }

    private static String conversationPath(String conversationId) {
        return "/api/projects/" + PROJECT_ID + "/conversations/" + conversationId + "/messages";
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages",
            "/api/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages/stream",
            "/api/projects/" + PROJECT_ID + "/conversations/messages",
            "/api/projects/" + PROJECT_ID + "/conversations/messages/stream"})
    void limitsChatPostsToTheAiLimit(String path) throws Exception {
        for (int i = 0; i < AI_LIMIT; i++) {
            assertEquals(200, send("POST", path), "request " + (i + 1) + " should be allowed");
        }
        assertEquals(429, send("POST", path), "request " + (AI_LIMIT + 1) + " should be rejected");
    }

    @Test
    void chatLimitIsSharedAcrossConversationsOfOneUser() throws Exception {
        for (int i = 0; i < AI_LIMIT / 2; i++) {
            assertEquals(200, send("POST", conversationPath(CONVERSATION_ID)));
            assertEquals(200, send("POST", conversationPath(OTHER_CONVERSATION_ID)));
        }
        assertEquals(429, send("POST", conversationPath(THIRD_CONVERSATION_ID)),
                "a new conversation must not reset the user's chat quota");
    }

    @Test
    void usersHaveSeparateChatQuotas() throws Exception {
        for (int i = 0; i < AI_LIMIT; i++) {
            send("POST", conversationPath(CONVERSATION_ID));
        }
        assertEquals(429, send("POST", conversationPath(CONVERSATION_ID)));
        assertEquals(200, sendAs(OTHER_USER_ID, "POST", conversationPath(CONVERSATION_ID)));
    }

    @Test
    void anonymousAuthRequestsShareOneBucketPerAddress() throws Exception {
        for (int i = 0; i < AUTH_LIMIT; i++) {
            assertEquals(200, sendAs(null, "POST", "/api/auth/login"));
        }
        assertEquals(429, sendAs(null, "POST", "/api/auth/register"),
                "login and register share the tight auth limit");
    }

    @Test
    void doesNotLimitConversationHistoryReads() throws Exception {
        String path = "/api/projects/" + PROJECT_ID + "/conversations/" + CONVERSATION_ID + "/messages";
        for (int i = 0; i < AI_LIMIT + 5; i++) {
            assertEquals(200, send("GET", path), "history read " + (i + 1) + " should be allowed");
        }
    }
}
