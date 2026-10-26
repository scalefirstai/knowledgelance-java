package io.knowledgelake.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.knowledgelake.KlRuntime;
import io.knowledgelake.config.Settings;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Change feed: S3 Event Notifications via SQS, or a list-and-diff poll loop.
 *
 * <p>SQS messages may be raw S3 notifications or SNS-wrapped ones; both are unwrapped. Messages
 * are deleted only after the batch is committed, so a crash re-delivers rather than loses changes.
 * The nightly reconcile ({@code kl reconcile}) remains the backstop for anything events missed.
 */
public final class Events {
    private static final Logger log = LoggerFactory.getLogger(Events.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private Events() {}

    public record S3Event(String name, String key) {}

    /** (event name, URL-decoded key) pairs from one SQS message body. */
    public static List<S3Event> parse(String body) {
        List<S3Event> out = new ArrayList<>();
        try {
            JsonNode msg = JSON.readTree(body);
            if (msg.has("Message") && msg.get("Message").isTextual()) msg = JSON.readTree(msg.get("Message").asText()); // SNS
            for (JsonNode rec : msg.path("Records")) {
                String key = rec.path("s3").path("object").path("key").asText(null);
                if (key != null) out.add(new S3Event(rec.path("eventName").asText(""), URLDecoder.decode(key, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            log.warn("unparseable SQS message ignored: {}", e.toString());
        }
        return out;
    }

    public record Split(Set<String> upserted, Set<String> deleted) {}

    /** Collapse a batch to the last event per key. */
    public static Split split(List<S3Event> events, UnaryOperator<String> toStoreKey) {
        Map<String, String> last = new LinkedHashMap<>();
        for (S3Event e : events) {
            String key = toStoreKey.apply(e.key());
            if (key != null) last.put(key, e.name());
        }
        Set<String> up = new TreeSet<>(), del = new TreeSet<>();
        last.forEach((k, n) -> (n.startsWith("ObjectRemoved") ? del : up).add(k));
        return new Split(up, del);
    }

    static SqsClient sqs(Settings s) {
        SqsClientBuilder b = SqsClient.builder().httpClient(UrlConnectionHttpClient.create());
        String region = s.events.region != null ? s.events.region : s.s3.region;
        b.region(Region.of(region != null ? region : "us-east-1"));
        if (s.s3.endpointUrl != null) b.endpointOverride(URI.create(s.s3.endpointUrl));
        if (s.s3.accessKeyId != null) {
            b.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(s.s3.accessKeyId, s.s3.secretAccessKey)));
        }
        return b.build();
    }

    public static void runSqs(KlRuntime rt, double optimizeEveryS, boolean once) {
        Settings s = rt.settings;
        String queue = s.events.sqsQueueUrl;
        Indexer indexer = new Indexer(rt);
        long lastOpt = System.nanoTime();
        try (SqsClient sqs = sqs(s)) {
            while (true) {
                List<Message> messages = new ArrayList<>(sqs.receiveMessage(b -> b.queueUrl(queue)
                        .maxNumberOfMessages(10).waitTimeSeconds(20).visibilityTimeout(300)).messages());
                // drain a little more so bursts (bulk re-exports) are committed together
                while (!messages.isEmpty() && messages.size() < 100) {
                    var more = sqs.receiveMessage(b -> b.queueUrl(queue).maxNumberOfMessages(10).waitTimeSeconds(1)
                            .visibilityTimeout(300)).messages();
                    if (more.isEmpty()) break;
                    messages.addAll(more);
                }
                if (!messages.isEmpty()) {
                    List<S3Event> events = new ArrayList<>();
                    for (Message m : messages) events.addAll(parse(m.body()));
                    Split sp = split(events, rt.bundles::keyFromEvent);
                    IndexStats stats = indexer.applyKeys(sp.upserted(), sp.deleted());
                    log.info("sqs batch: {} messages -> {}", messages.size(), stats.summary());
                    for (int i = 0; i < messages.size(); i += 10) {
                        List<DeleteMessageBatchRequestEntry> entries = new ArrayList<>();
                        List<Message> slice = messages.subList(i, Math.min(messages.size(), i + 10));
                        for (int j = 0; j < slice.size(); j++) {
                            entries.add(DeleteMessageBatchRequestEntry.builder().id(String.valueOf(j)).receiptHandle(slice.get(j).receiptHandle()).build());
                        }
                        sqs.deleteMessageBatch(b -> b.queueUrl(queue).entries(entries));
                    }
                }
                if ((System.nanoTime() - lastOpt) / 1e9 > optimizeEveryS) {
                    indexer.optimize();
                    lastOpt = System.nanoTime();
                }
                if (once) return;
            }
        }
    }

    /** For local disks and S3-compatible stores without notifications: reconcile on an interval. */
    public static void runPoll(KlRuntime rt, double optimizeEveryS, boolean once) {
        Indexer indexer = new Indexer(rt);
        long lastOpt = System.nanoTime();
        while (true) {
            IndexStats stats = indexer.reconcile();
            if (stats.seen > 0) log.info("poll: {}", stats.summary());
            if ((System.nanoTime() - lastOpt) / 1e9 > optimizeEveryS) {
                indexer.optimize();
                lastOpt = System.nanoTime();
            }
            if (once) return;
            try {
                Thread.sleep((long) (rt.settings.events.pollIntervalS * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public static void run(KlRuntime rt, boolean once) {
        if (rt.settings.events.sqsQueueUrl != null && !rt.settings.events.sqsQueueUrl.isBlank()) runSqs(rt, 3600, once);
        else runPoll(rt, 3600, once);
    }
}
