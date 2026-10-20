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
    private Mail mail = new Mail();
    private Verification verification = new Verification();
    private Storage storage = new Storage();
    private S3 s3 = new S3();
    private Indexing indexing = new Indexing();

    /**
     * Public URL of the SPA (what a user types in the browser), used to build links
     * that are clicked from an email. Never the API base URL: the SPA route calls
     * the API.
     */
    private String publicUrl = "http://localhost:5173";

    @Data
    public static class AiService {
        /** Base URL of the Python AI service. */
        private String url = "http://localhost:8000";
        /** Shared secret for backend-to-AI-service authentication. */
        private String internalToken = "";
        /** Timeout for embeddings and single-shot chat completions. */
        private int timeoutSeconds = 60;
        /** Timeout for agent endpoints (several LLM round-trips). */
        private int agentTimeoutSeconds = 300;
        /**
         * Server-side lifetime of one streaming agent SSE run. Must exceed the
         * AI service's own run deadline (AGENT_RUN_TIMEOUT_SECONDS, default 60s)
         * plus headroom to stream the final events back.
         */
        private long agentStreamTimeoutMs = 200_000L;
        /**
         * Max bytes WebClient buffers per response from the AI service. The
         * framework default (256 KB) is far too small for a batch of embeddings
         * (each vector is hundreds of floats), which otherwise fails with
         * DataBufferLimitException. Sized generously; only a safety valve.
         */
        private int maxInMemoryBytes = 16 * 1024 * 1024;
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
        /**
         * Per-chunk character cap for chat context (approx tokens = chars/4),
         * so one huge chunk cannot blow the model's context window.
         */
        private int chatContextMaxChars = 2000;
    }

    @Data
    public static class Chat {
        /** Server-side lifetime of one SSE answer stream. */
        private long streamTimeoutMs = 120_000L;
        /**
         * How many of the most recent messages are sent to the LLM as history
         * (no summarisation). Bounds the prompt on long conversations.
         */
        private int historyMaxMessages = 8;
        /**
         * Approximate character budget for the chat prompt (tokens ~ chars/4).
         * When the assembled prompt exceeds 70% of this budget,
         * summarization is triggered.
         */
        private int chatContextBudgetChars = 60000;
        /**
         * Maximum number of messages older than the recent window and not covered
         * by the summary that can accumulate before summarization is triggered.
         * Defaults to about 6 messages.
         */
        private int chatHistorySummaryThreshold = 6;
    }

    @Data
    public static class Mail {
        /** From address on outgoing mail; providers such as SES need a verified sender. */
        private String from = "no-reply@codebase-ai.local";
    }

    @Data
    public static class Verification {
        /** How long a verification link stays valid. */
        private int tokenTtlHours = 24;
        /** Minimum seconds between two verification emails for the same address. */
        private int resendCooldownSeconds = 60;
    }

    @Data
    public static class Storage {
        /**
         * Where a project's source tree and generated artefacts live:
         * {@code local} (filesystem under {@link Upload#getDirectory()}) or
         * {@code s3}. Local is the default so a fresh clone and the compose
         * stack need no cloud configuration.
         */
        private String provider = "local";
    }

    @Data
    public static class Indexing {
        /** Maximum number of retry attempts for a failed indexing job. */
        private int maxAttempts = 3;
        /** How many seconds a processing job can be stuck before being recovered (stale lease). */
        private int staleJobSeconds = 300;
    }

    @Data
    public static class S3 {
        /** Bucket holding every project's source tree and generated artefacts. */
        private String bucket = "";
        /** Region; when blank the AWS default region provider chain is used. */
        private String region = "";
        /** Key prefix inside the bucket, so it can host other data too. */
        private String prefix = "projects";
    }
}
