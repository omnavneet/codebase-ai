package com.codebaseai.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Central home for the platform's tunable knobs. Defaults live here (with the
 * values the system was developed against) so the app still starts when a key
 * is absent from {@code application.properties}; every value can be overridden
 * there or through environment variables.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private AiService aiService = new AiService();
    private Upload upload = new Upload();
    private Chunking chunking = new Chunking();
    private Retrieval retrieval = new Retrieval();
    private Chat chat = new Chat();

    @Data
    public static class AiService {
        /** Base URL of the Python AI service. */
        private String url = "http://localhost:8000";
        /** Timeout for embeddings and single-shot chat completions. */
        private int timeoutSeconds = 60;
        /** Timeout for agent endpoints (several LLM round-trips). */
        private int agentTimeoutSeconds = 300;
    }

    @Data
    public static class Upload {
        private String directory = "./uploads";
        /** Total bytes allowed to be extracted per upload (zip-bomb guard). */
        private long maxTotalBytes = 250L * 1024 * 1024;
    }

    @Data
    public static class Chunking {
        /** Hard upper bound for one chunk; longer blocks are split. */
        private int maxChars = 500;
        /** Blocks smaller than this are merged with their neighbour. */
        private int minChars = 120;
        /** Lines shared by consecutive pieces of a split block. */
        private int overlapLines = 3;
    }

    @Data
    public static class Retrieval {
        /** Chunks fed to the LLM as chat context. */
        private int chatTopK = 5;
        /** Chunks returned by the semantic search endpoint. */
        private int searchTopK = 10;
    }

    @Data
    public static class Chat {
        /** Server-side lifetime of one SSE answer stream. */
        private long streamTimeoutMs = 120_000L;
    }
}
