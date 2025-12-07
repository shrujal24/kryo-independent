package com.esotericsoftware.kryo.benchmarks;

import com.esotericsoftware.kryo.util.CuckooObjectMap;
import com.esotericsoftware.kryo.util.IdentityMap;
import com.esotericsoftware.kryo.util.ObjectMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;
import net.bytebuddy.ByteBuddy;

public class MapBenchmark {

    @Benchmark
    public void read(ReadBenchmarkState state, Blackhole blackhole) {
        state.read(blackhole);
    }

    @Benchmark
    public void miss(MissBenchmarkState state, Blackhole blackhole) {
        state.miss(blackhole);
    }

    @Benchmark
    public void write(WriteBenchmarkState state, Blackhole blackhole) {
        state.write(blackhole);
    }

    @Benchmark
    public void writeRead(WriteBenchmarkState state, Blackhole blackhole) {
        state.readWrite(blackhole);
    }

    @State(Scope.Thread)
    public static class AbstractBenchmarkState {

        @Param({"OBJECT", "IDENTITY", "CUCKOO", "HASH"})
        public MapType MAP_TYPE;

        @Param({"integers", "strings", "classes"})
        public DataSource DATA_SOURCE;

        @Param({"100", "500", "1000", "2500", "5000", "10000"})
        public int NUM_CLASSES;

        @Param({"51"})
        public int INITIAL_CAPACITY;

        @Param({"0.7", "0.75", "0.8"})
        public float LOAD_FACTOR;

        @Param({"8192"})
        public int MAX_CAPACITY;

        MapAdapter<Object, Integer> map;
        List<Object> data;
    }

    @State(Scope.Thread)
    public static class ReadBenchmarkState extends AbstractBenchmarkState {
        final Random random = new Random(123L);

        @Setup(Level.Trial)
        public void setup() {
            map = createMap(MAP_TYPE, INITIAL_CAPACITY, LOAD_FACTOR, MAX_CAPACITY);
            data = DATA_SOURCE.buildData(random, NUM_CLASSES);
            data.forEach(c -> map.put(c, 1));
            Collections.shuffle(data);
        }

        public void read(Blackhole blackhole) {
            data.stream()
                .limit(NUM_CLASSES)
                .map(map::get)
                .forEach(blackhole::consume);
        }
    }

    @State(Scope.Thread)
    public static class MissBenchmarkState extends AbstractBenchmarkState {
        final Random random = new Random(123L);
        private List<Object> moreData;

        @Setup(Level.Trial)
        public void setup() {
            map = createMap(MAP_TYPE, INITIAL_CAPACITY, LOAD_FACTOR, MAX_CAPACITY);
            data = DATA_SOURCE.buildData(random, NUM_CLASSES);
            data.forEach(c -> map.put(c, 1));
            moreData = DATA_SOURCE.buildData(random, NUM_CLASSES);
        }

        public void miss(Blackhole blackhole) {
            moreData.stream()
                .map(map::get)
                .forEach(blackhole::consume);
        }
    }

    @State(Scope.Thread)
    public static class WriteBenchmarkState extends AbstractBenchmarkState {
        final Random random = new Random(123L);

        @Setup(Level.Trial)
        public void setup() {
            map = createMap(MAP_TYPE, INITIAL_CAPACITY, LOAD_FACTOR, MAX_CAPACITY);
            data = DATA_SOURCE.buildData(random, NUM_CLASSES);
            Collections.shuffle(data);
        }

        public void write(Blackhole blackhole) {
            data.stream()
                .map(c -> map.put(c, 1))
                .forEach(blackhole::consume);
        }

        public void readWrite(Blackhole blackhole) {
            data.forEach(c -> map.put(c, 1));
            Collections.shuffle(data);
            data.stream()
                .limit(NUM_CLASSES)
                .map(map::get)
                .forEach(blackhole::consume);
            map.clear();
        }
    }

    public enum MapType {
        OBJECT,
        IDENTITY,
        CUCKOO,
        HASH
    }

    public enum DataSource {
        integers {
            Object getData(Random random) {
                return random.nextInt();
            }
        },
        strings {
            Object getData(Random random) {
                int leftLimit = 97;
                int rightLimit = 122;
                int low = 10;
                int high = 100;
                int length = random.nextInt(high - low) + low;
                return random.ints(leftLimit, rightLimit + 1)
                    .limit(length)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString();
            }
        },
        classes {
            Object getData(Random random) {
                return new ByteBuddy()
                    .subclass(Object.class)
                    .make()
                    .load(MapBenchmark.class.getClassLoader())
                    .getLoaded();
            }
        };

        abstract Object getData(Random random);

        public List<Object> buildData(Random random, int numClasses) {
            return IntStream.rangeClosed(0, numClasses)
                .mapToObj(i -> getData(random))
                .collect(Collectors.toList());
        }
    }

    private static MapAdapter<Object, Integer> createMap(MapType mapType, int initialCapacity, float loadFactor, int maxCapacity) {
        switch (mapType) {
            case CUCKOO:
                return new CuckooMapAdapter<>(new CuckooObjectMap<>(initialCapacity, loadFactor), maxCapacity);
            case OBJECT:
                return new ObjectMapAdapter<>(new ObjectMap<>(initialCapacity, loadFactor), maxCapacity);
            case IDENTITY:
                return new ObjectMapAdapter<>(new IdentityMap<>(initialCapacity, loadFactor), maxCapacity);
            case HASH:
                return new HashMapAdapter<>(new HashMap<>(initialCapacity, loadFactor));
            default:
                throw new IllegalStateException("Unexpected value: " + mapType);
        }
    }

    interface MapAdapter<K, V> {
        V get(K key);
        V put(K key, V value);
        void clear();
    }

    static class ObjectMapAdapter<K> implements MapAdapter<K, Integer> {
        private final ObjectMap<K, Integer> delegate;
        private final int maxCapacity;

        public ObjectMapAdapter(ObjectMap<K, Integer> delegate, int maxCapacity) {
            this.delegate = delegate;
            this.maxCapacity = maxCapacity;
        }

        @Override
        public Integer get(K key) {
            return delegate.get(key, -1);
        }

        @Override
        public Integer put(K key, Integer value) {
            delegate.put(key, value);
            return null;
        }

        @Override
        public void clear() {
            delegate.clear(maxCapacity);
        }
    }

    static class CuckooMapAdapter<K> implements MapAdapter<K, Integer> {
        private final CuckooObjectMap<K, Integer> delegate;
        private final int maxCapacity;

        public CuckooMapAdapter(CuckooObjectMap<K, Integer> delegate, int maxCapacity) {
            this.delegate = delegate;
            this.maxCapacity = maxCapacity;
        }

        @Override
        public Integer get(K key) {
            return delegate.get(key, -1);
        }

        @Override
        public Integer put(K key, Integer value) {
            delegate.put(key, value);
            return null;
        }

        @Override
        public void clear() {
            delegate.clear(maxCapacity);
        }
    }

    private static class HashMapAdapter<K> implements MapAdapter<K, Integer> {
        private final HashMap<K, Integer> delegate;

        public HashMapAdapter(HashMap<K, Integer> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Integer get(K key) {
            return delegate.get(key);
        }

        @Override
        public Integer put(K key, Integer value) {
            return delegate.put(key, value);
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }
}
