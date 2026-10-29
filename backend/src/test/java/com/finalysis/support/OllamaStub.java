package com.finalysis.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A stand-in Ollama server for the tests (docs/plans/phase-9.md §4.4): the JDK's {@link HttpServer} on a random
 * loopback port, registered with {@link NoCloudAiGuard#allowStub} so the suite's host guard lets requests through to it
 * and nowhere else. It answers {@code /api/version}, {@code /api/tags} (the models it was started with),
 * {@code /api/embed} (a deterministic vector per input, of the configured length) and {@code /api/chat} from a script,
 * and records every request's method, path and body for the tests to read field by field. Anything else is a 404.
 */
public final class OllamaStub implements AutoCloseable {

    public static final String VERSION = "0.12.6";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What a request carried. */
    public record Request(String method, String path, String body) {

        public JsonNode json() {
            return JSON.readTree(body);
        }
    }

    /** One scripted answer: an HTTP status and body, sent after a delay. */
    public record Reply(int status, String body, long delayMillis) {
    }

    private final HttpServer server;
    private final List<Request> requests = new ArrayList<>();
    private final Deque<Reply> chat = new ArrayDeque<>();
    private volatile List<String> models;
    private volatile int dimensions = 768;
    private volatile Reply embedFailure;

    private OllamaStub(List<String> models) throws IOException {
        this.models = List.copyOf(models);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "ollama-stub");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/", this::handle);
        server.start();
        NoCloudAiGuard.allowStub(port());
    }

    /** A stub that reports these model names as pulled ({@code name:tag}). */
    public static OllamaStub start(String... pulled) {
        try {
            return new OllamaStub(List.of(pulled));
        } catch (IOException e) {
            throw new IllegalStateException("The Ollama stub couldn't start", e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + port());
    }

    /** Queues answers for {@code /api/chat}, in order; with none queued, a chat request is a 500. */
    public synchronized OllamaStub then(Reply... replies) {
        chat.addAll(List.of(replies));
        return this;
    }

    public OllamaStub pulled(String... names) {
        models = List.of(names);
        return this;
    }

    public OllamaStub dimensions(int length) {
        dimensions = length;
        return this;
    }

    /** Every {@code /api/embed} request is answered with this until cleared (null). */
    public OllamaStub embedFailing(Reply reply) {
        embedFailure = reply;
        return this;
    }

    public synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    public List<Request> requests(String path) {
        return requests().stream().filter(r -> r.path().equals(path)).toList();
    }

    public synchronized void reset() {
        requests.clear();
        chat.clear();
        embedFailure = null;
        dimensions = 768;
    }

    /** Stops answering but keeps the port allowed: a request is refused by the system, "Ollama unreachable". */
    public void stopServing() {
        server.stop(0);
    }

    @Override
    public void close() {
        NoCloudAiGuard.forgetStub(port());
        server.stop(0);
    }

    // ------------------------------------------------------------------ canned answers

    /** An answer with text only. */
    public static Reply text(String content) {
        return ok(message(content, null), 120, 30);
    }

    /** An answer calling these tools; each is {@code name} and its JSON arguments. */
    public static Reply toolCalls(String... nameThenArguments) {
        ArrayNode calls = JSON.createArrayNode();
        for (int i = 0; i < nameThenArguments.length; i += 2) {
            ObjectNode function = JSON.createObjectNode().put("name", nameThenArguments[i]);
            function.set("arguments", JSON.readTree(nameThenArguments[i + 1]));
            calls.add(JSON.createObjectNode().set("function", function));
        }
        return ok(message("", calls), 150, 25);
    }

    /** A raw body, as Ollama might send it, with HTTP 200. */
    public static Reply raw(String body) {
        return new Reply(200, body, 0);
    }

    public static Reply status(int status, String error) {
        return new Reply(status, JSON.createObjectNode().put("error", error).toString(), 0);
    }

    public static Reply delayed(Reply reply, long millis) {
        return new Reply(reply.status(), reply.body(), millis);
    }

    private static ObjectNode message(String content, ArrayNode toolCalls) {
        ObjectNode message = JSON.createObjectNode().put("role", "assistant").put("content", content);
        if (toolCalls != null) {
            message.set("tool_calls", toolCalls);
        }
        return message;
    }

    private static Reply ok(ObjectNode message, int promptTokens, int outputTokens) {
        ObjectNode body = JSON.createObjectNode().put("model", "stub").put("created_at", "2026-10-29T12:00:00Z")
                .put("done", true).put("done_reason", "stop").put("prompt_eval_count", promptTokens)
                .put("eval_count", outputTokens).put("total_duration", 1_000_000L);
        body.set("message", message);
        return new Reply(200, body.toString(), 0);
    }

    // ------------------------------------------------------------------ serving

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        synchronized (this) {
            requests.add(new Request(exchange.getRequestMethod(), path, body));
        }
        Reply reply = switch (path) {
            case "/api/version" -> new Reply(200, JSON.createObjectNode().put("version", VERSION).toString(), 0);
            case "/api/tags" -> new Reply(200, tags(), 0);
            case "/api/embed" -> embedFailure != null ? embedFailure : new Reply(200, embed(body), 0);
            case "/api/chat" -> nextChat();
            default -> status(404, "not found");
        };
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            // the client gave up (a timeout test): nothing to send to
        } finally {
            exchange.close();
        }
    }

    private synchronized Reply nextChat() {
        Reply next = chat.poll();
        return next != null ? next : status(500, "no scripted answer");
    }

    private String tags() {
        ArrayNode list = JSON.createArrayNode();
        models.forEach(name -> list.add(JSON.createObjectNode().put("name", name).put("model", name)));
        return JSON.createObjectNode().set("models", list).toString();
    }

    /** One vector per input, derived from its text: the same text always embeds the same. */
    private String embed(String body) {
        JsonNode request = JSON.readTree(body);
        List<String> inputs = new ArrayList<>();
        JsonNode input = request.get("input");
        if (input != null && input.isArray()) {
            input.forEach(node -> inputs.add(node.stringValue()));
        } else if (input != null) {
            inputs.add(input.stringValue());
        }
        ArrayNode embeddings = JSON.createArrayNode();
        for (String text : inputs) {
            ArrayNode vector = JSON.createArrayNode();
            int seed = text.hashCode();
            for (int i = 0; i < dimensions; i++) {
                vector.add(((seed >>> (i % 31)) & 0xFF) / 255.0 + 0.01);
            }
            embeddings.add(vector);
        }
        ObjectNode response = JSON.createObjectNode().put("model", request.path("model").stringValue(""))
                .put("prompt_eval_count", inputs.stream().collect(Collectors.summingInt(String::length)));
        response.set("embeddings", embeddings);
        return response.toString();
    }

    /** The request bodies to {@code /api/chat}, parsed. */
    public List<JsonNode> chatBodies() {
        return requests("/api/chat").stream().map(Request::json).toList();
    }

    /** Field names of a JSON object, for checking a body field by field. */
    public static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
