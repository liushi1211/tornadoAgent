package com.tornado.boot.memory;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.Serializer;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.alibaba.cloud.ai.graph.serializer.check_point.CheckPointSerializer;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;

import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static java.lang.String.format;

public class TtlAwareRedisSaver extends RedisSaver {



    private static final String CHECKPOINT_PREFIX = "graph:checkpoint:content:";
    private static final String THREAD_META_PREFIX = "graph:thread:meta:";
    private static final String THREAD_REVERSE_PREFIX = "graph:thread:reverse:";
    private static final String LOCK_PREFIX = "graph:checkpoint:lock:";
    // Thread meta hash field names
    private static final String FIELD_THREAD_ID = "thread_id";
    private static final String FIELD_IS_RELEASED = "is_released";
    private static final String FIELD_THREAD_NAME = "thread_name";


    private RedissonClient redisson;
    private final Serializer<Checkpoint> checkpointSerializer;
    private Duration ttl;
    /**
     * Protected constructor for RedisSaver.
     * Use {@link #builder()} to create instances.
     *
     * @param redisson        the redisson
     * @param stateSerializer the state serializer
     */
    protected TtlAwareRedisSaver(RedissonClient redisson, StateSerializer stateSerializer,Duration ttl) {
        super(redisson, stateSerializer);
        this.redisson = redisson;
        this.checkpointSerializer =  new CheckPointSerializer(stateSerializer);
        this.ttl = ttl;

    }

    @Override
    public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
        Optional<String> threadNameOpt = config.threadId();
        if (!threadNameOpt.isPresent()) {
            throw new IllegalArgumentException("threadId isn't allow null");
        }

        String threadName = threadNameOpt.get();
        RLock lock = redisson.getLock(LOCK_PREFIX + threadName);
        boolean tryLock = false;
        try {
            // 3 seconds timeout for write operations (put) - longer timeout for concurrent scenarios
            tryLock = lock.tryLock(3, TimeUnit.SECONDS);
            if (!tryLock) {
                throw new RuntimeException("Failed to acquire lock for thread: " + threadName);
            }

            // Get or create thread_id
            String threadId = getOrCreateThreadId(threadName);

            // Use thread_id as key for checkpoint storage
            RBucket<String> bucket = redisson.getBucket(CHECKPOINT_PREFIX + threadId);
            String content = bucket.get();
            LinkedList<Checkpoint> checkpoints = deserializeCheckpoints(content);

            if (config.checkPointId().isPresent()) {
                // Replace Checkpoint
                String checkPointId = config.checkPointId().get();
                int index = IntStream.range(0, checkpoints.size())
                        .filter(i -> checkpoints.get(i).getId().equals(checkPointId))
                        .findFirst()
                        .orElseThrow(() -> new NoSuchElementException(
                                format("Checkpoint with id %s not found!", checkPointId)));
                checkpoints.set(index, checkpoint);
            }
            else {
                // Add Checkpoint
                checkpoints.push(checkpoint);
            }
            bucket.set(serializeCheckpoints(checkpoints));
            bucket.expireIfNotSet(ttl);
            return RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build();

        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to serialize/deserialize checkpoints", e);
        }
        finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String getOrCreateThreadId(String threadName) {
        String metaKey = THREAD_META_PREFIX + threadName;
        RMap<String, String> meta = redisson.getMap(metaKey);

        // Check if an active thread exists
        String threadId = meta.get(FIELD_THREAD_ID);
        String isReleased = meta.get(FIELD_IS_RELEASED);

        if (threadId != null && !"true".equals(isReleased)) {
            // Active thread exists, return its thread_id
            return threadId;
        }

        // No active thread exists or thread is released, create a new thread_id
        String newThreadId = UUID.randomUUID().toString();
        meta.put(FIELD_THREAD_ID, newThreadId);
        meta.put(FIELD_IS_RELEASED, "false");
        meta.expireIfNotSet(ttl);

        // Set reverse mapping
        String reverseKey = THREAD_REVERSE_PREFIX + newThreadId;
        RMap<String, String> reverse = redisson.getMap(reverseKey);
        reverse.put(FIELD_THREAD_NAME, threadName);
        reverse.put(FIELD_IS_RELEASED, "false");
        reverse.expireIfNotSet(ttl);
        return newThreadId;
    }

    private LinkedList<Checkpoint> deserializeCheckpoints(String content) throws IOException, ClassNotFoundException {
        if (content == null || content.isEmpty()) {
            return new LinkedList<>();
        }
        byte[] bytes = Base64.getDecoder().decode(content);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            int size = ois.readInt();
            LinkedList<Checkpoint> checkpoints = new LinkedList<>();
            for (int i = 0; i < size; i++) {
                checkpoints.add(checkpointSerializer.read(ois));
            }
            return checkpoints;
        }
    }

    private String serializeCheckpoints(List<Checkpoint> checkpoints) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeInt(checkpoints.size());
            for (Checkpoint checkpoint : checkpoints) {
                checkpointSerializer.write(checkpoint, oos);
            }
            oos.flush();
            byte[] bytes = baos.toByteArray();
            return Base64.getEncoder().encodeToString(bytes);
        }
    }

    @Override
    public Tag release(RunnableConfig config) throws Exception {
        Optional<String> threadNameOpt = config.threadId();
        if (!threadNameOpt.isPresent()) {
            throw new IllegalArgumentException("threadId is not allow null");
        }

        String threadName = threadNameOpt.get();
        RLock lock = redisson.getLock(LOCK_PREFIX + threadName);
        boolean tryLock = false;
        try {
            // 3 seconds timeout for write operations (release) - longer timeout for concurrent scenarios
            tryLock = lock.tryLock(3, TimeUnit.SECONDS);
            if (!tryLock) {
                throw new RuntimeException("Failed to acquire lock for thread: " + threadName);
            }

            String metaKey = THREAD_META_PREFIX + threadName;
            RMap<String, String> meta = redisson.getMap(metaKey);

            String threadId = meta.get(FIELD_THREAD_ID);
            if (threadId == null) {
                throw new IllegalStateException("Thread not found: " + threadName);
            }
            meta.deleteAsync();
            // Update reverse mapping
            String reverseKey = THREAD_REVERSE_PREFIX + threadId;
            RMap<String, String> reverse = redisson.getMap(reverseKey);
            if (reverse != null) {
                reverse.deleteAsync();
            }
            // Get checkpoints for Tag (using thread_id)
            String contentKey = CHECKPOINT_PREFIX + threadId;
            RBucket<String> bucket = redisson.getBucket(contentKey);
            String content = bucket.get();
            Collection<Checkpoint> checkpoints = deserializeCheckpoints(content);
            bucket.deleteAsync();
            return new Tag(threadName, checkpoints);

        }
        catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize checkpoints", e);
        }
        finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }
    public static class Builder extends RedisSaver.Builder {
        private RedissonClient redisson;
        private StateSerializer stateSerializer;
        private Duration ttl;

        /**
         * Sets the Redisson client.
         *
         * @param redisson the Redisson client
         * @return this builder
         */
        public TtlAwareRedisSaver.Builder redisson(RedissonClient redisson) {
            this.redisson = redisson;
            return this;
        }

        /**
         * Sets the state serializer.
         *
         * @param stateSerializer the state serializer
         * @return this builder
         */
        public TtlAwareRedisSaver.Builder stateSerializer(StateSerializer stateSerializer) {
            this.stateSerializer = stateSerializer;
            return this;
        }

        public TtlAwareRedisSaver.Builder ttl(Duration ttl) {
            this.ttl = ttl;
            return this;
        }

        /**
         * Builds a new RedisSaver instance.
         * @return a new RedisSaver instance
         * @throws IllegalArgumentException if redisson or stateSerializer is null
         */
        public RedisSaver build() {
            if (redisson == null) {
                throw new IllegalArgumentException("redisson cannot be null");
            }
            if (stateSerializer == null) {
                this.stateSerializer = StateGraph.DEFAULT_JACKSON_SERIALIZER;
            }
            return new TtlAwareRedisSaver(redisson, stateSerializer, ttl);
        }
    }

}
