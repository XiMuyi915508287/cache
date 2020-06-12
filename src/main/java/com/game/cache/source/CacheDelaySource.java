package com.game.cache.source;

import com.game.cache.data.DataCollection;
import com.game.cache.data.IData;
import com.game.cache.exception.CacheException;
import com.game.cache.mapper.ClassConfig;
import com.game.cache.mapper.IClassConverter;
import com.game.cache.source.executor.CacheCallable;
import com.game.cache.source.executor.CacheRunnable;
import com.game.cache.source.executor.ICacheExecutor;
import com.game.cache.source.executor.ICacheFuture;
import com.game.cache.source.executor.ICacheSource;
import com.game.common.config.Configs;
import com.game.common.lock.LockKey;
import com.mongodb.Function;
import jodd.util.ThreadUtil;
import org.apache.commons.lang3.RandomUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public abstract class CacheDelaySource<PK, K, V extends IData<K>> implements ICacheDelaySource<PK, K, V> {

    private static final Logger logger = LoggerFactory.getLogger(CacheDelaySource.class);

    public static final Map<String, Object> EMPTY = Collections.emptyMap();

    private final CacheDbSource<PK, K, V> cacheSource;
    private final Map<PK, PrimaryDelayCache<PK, K, V>> primaryCacheMap;
    private final ICacheExecutor executor;

    public CacheDelaySource(CacheDbSource<PK, K, V> cacheSource, ICacheExecutor executor) {
        this.cacheSource = cacheSource;
        this.primaryCacheMap = new ConcurrentHashMap<>();
        this.executor = executor;
        //初始化~
        CacheRunnable cacheRunnable = new CacheRunnable(getScheduleName(), this::onSchedule);
        long randomValue = RandomUtils.nextLong(1000, 2000);
        long initialDelay = randomValue * 50 / 50;    //取100ms的整数倍
        executor.scheduleAtFixedRate(cacheRunnable, initialDelay, 2000L, TimeUnit.MILLISECONDS);
    }

    @Override
    public LockKey getLockKey(PK primaryKey) {
        return cacheSource.getLockKey(primaryKey);
    }

    @Override
    public V get(PK primaryKey, K secondaryKey) {
        PrimaryDelayCache<PK, K, V> primaryCache = primaryCacheMap.get(primaryKey);
        if (primaryCache == null) {
            return cacheSource.get(primaryKey, secondaryKey);
        } else {
            return cacheSource.get(primaryKey, secondaryKey);
        }
    }

    @Override
    public List<V> getAll(PK primaryKey) {
        if (!flushOne(primaryKey)){
            throw new CacheException("%s %s", getKeyValueBuilder().toPrimaryKeyString(primaryKey), "getAll");
        }
        return cacheSource.getAll(primaryKey);
    }

    @Override
    public DataCollection<K, V> getCollection(PK primaryKey) {
        if (!flushOne(primaryKey)){
            throw new CacheException("%s %s", getKeyValueBuilder().toPrimaryKeyString(primaryKey), "getCollection");
        }
        return cacheSource.getCollection(primaryKey);
    }

    @Override
    public boolean replaceOne(PK primaryKey, V value) {
        V cloneValue = cloneValue(value);
        PrimaryDelayCache<PK, K, V> primaryCache = primaryCacheMap.computeIfAbsent(primaryKey, PrimaryDelayCache::new);
        KeyDataValue<K, V> keyDataValue = KeyDataValue.createCache(cloneValue.secondaryKey(), cloneValue);
        primaryCache.add(keyDataValue);
        return true;
    }

    @Override
    public boolean replaceBatch(PK primaryKey, Collection<V> values) {
        PrimaryDelayCache<PK, K, V> primaryCache = primaryCacheMap.computeIfAbsent(primaryKey, PrimaryDelayCache::new);
        for (V value : values) {
            primaryCache.add(KeyDataValue.createCache(value.secondaryKey(), cloneValue(value)));
        }
        return true;
    }

    @Override
    public Class<V> getAClass() {
        return cacheSource.getAClass();
    }

    @Override
    public ClassConfig getClassConfig() {
        return cacheSource.getClassConfig();
    }

    @Override
    public boolean deleteOne(PK primaryKey, K secondaryKey) {
        PrimaryDelayCache<PK, K, V> primaryCache = primaryCacheMap.computeIfAbsent(primaryKey, PrimaryDelayCache::new);
        primaryCache.deleteCacheValue(secondaryKey);
        return true;
    }

    @Override
    public boolean deleteBatch(PK primaryKey, Collection<K> secondaryKeys) {
        PrimaryDelayCache<PK, K, V> primaryCache = primaryCacheMap.computeIfAbsent(primaryKey, PrimaryDelayCache::new);
        for (K secondaryKey : secondaryKeys) {
            primaryCache.deleteCacheValue(secondaryKey);
        }
        return true;
    }

    @Override
    public V cloneValue(V value) {
        return cacheSource.cloneValue(value);
    }

    @Override
    public IClassConverter<K, V> getConverter() {
        return cacheSource.getConverter();
    }

    @Override
    public ICacheKeyValueBuilder<PK, K> getKeyValueBuilder() {
        return cacheSource.getKeyValueBuilder();
    }

    @Override
    public ICacheDelaySource<PK, K, V> createDelayUpdateSource(ICacheExecutor executor) {
        return this;
    }

    @Override
    public boolean flushAll(long currentTime) {
        String string = Configs.getInstance().getString("cache.flush.logPath");
        File parent = new File(string + "_" + currentTime);
        if (!parent.exists()){
            boolean success = parent.mkdir();
            if (!success){
                logger.error("mkdir error:{}", string);
            }
        }
        ClassConfig classConfig = getClassConfig();
        ICacheKeyValueBuilder<PK, K> keyValueBuilder = getKeyValueBuilder();
        Collection<PrimaryDelayCache<PK, K, V>> primaryDelayCaches = primaryCacheMap.values();
        for (PrimaryDelayCache<PK, K, V> delayCache : primaryDelayCaches) {
            StringBuilder builder = new StringBuilder();
            String keyString = keyValueBuilder.toPrimaryKeyString(delayCache.getPrimaryKey());
            builder.append("primaryKey:").append(keyString).append("\n");
            Collection<KeyDataValue<K, V>> dataValues = delayCache.getAll();
            for (KeyDataValue<K, V> dataValue : dataValues) {
                String secondaryKeyString = keyValueBuilder.toSecondaryKeyString(dataValue.getKey());
                builder.append("key:").append(secondaryKeyString).append("\t")
                        .append("command:").append(dataValue.getCacheCommand().name()).append("\t")
                        .append("data:").append(getConverter().convert2Cache(dataValue.getDataValue())).append("\n");
            }
            try {
                String filename = String.format("%s.cache", classConfig.getRedisKeyString(keyString));
                FileWriter writer = new FileWriter(parent.getAbsolutePath() + "/" + filename);
                writer.append(builder.toString());
                writer.flush();
                writer.close();
            }
            catch (IOException e) {
                logger.error("{}", builder.toString(), e);
            }
        }
        int tryCount = Math.max(2, Configs.getInstance().getInt("cache.flush.tryAllCount"));
        while (tryCount-- > 0 && !primaryCacheMap.isEmpty()){
            HashMap<PK, PrimaryDelayCache<PK, K, V>> primaryCacheMap = new HashMap<>(this.primaryCacheMap);
            this.primaryCacheMap.clear();
            boolean success = flushPrimaryCacheInLock(primaryCacheMap);
            if (success){
                break;
            }
            else {
                ThreadUtil.sleep(500L);
            }
        }
        for (Map.Entry<PK, PrimaryDelayCache<PK, K, V>> entry : primaryCacheMap.entrySet()) {
            String keyString = keyValueBuilder.toPrimaryKeyString(entry.getKey());
            logger.error("{} primaryKey:{} flushAll error.", getAClass().getName(), getClassConfig().tableName, keyString);
        }
        return true;
    }

    @Override
    public void flushOne(PK primaryKey, Consumer<Boolean> consumer) {
        CacheCallable<Boolean> callable = flushPrimaryCacheCallable(primaryKey, "flushOne.0", consumer);
        executor.submit(callable);
    }

    @Override
    public boolean flushOne(PK primaryKey) {
        CacheCallable<Boolean> callable = flushPrimaryCacheCallable(primaryKey, "flushOne.1", null);
        boolean isSuccess = false;
        int tryCount = Configs.getInstance().getInt("cache.flush.tryOneCount");
        long flushTimeOut = Configs.getInstance().getDuration("cache.flush.timeOut", TimeUnit.MILLISECONDS);
        Throwable throwable = null;
        for (int count = 0; count < tryCount; count++) {
            try {
                ICacheFuture<Boolean> future = executor.submit(callable);
                isSuccess = future.get(flushTimeOut, TimeUnit.MILLISECONDS);
                if (isSuccess) {
                    break;
                }
                logger.error("{} primaryKey:{} count:{} flush failure.", getAClass().getName(), getKeyValueBuilder().toPrimaryKeyString(primaryKey), count);
            }
            catch (Throwable t) {
                throwable = t;
                logger.error("{} primaryKey:{} count:{} flush error.", getAClass().getName(), getKeyValueBuilder().toPrimaryKeyString(primaryKey), count);
            }
        }
        if (!isSuccess && throwable != null){
            throw new CacheException(getKeyValueBuilder().toPrimaryKeyString(primaryKey), throwable);
        }
        return isSuccess;
    }

    private CacheCallable<Boolean> flushPrimaryCacheCallable(PK primaryKey, String message, Consumer<Boolean> consumer) {
        return new CacheCallable<>(getScheduleName(), () -> {
            PrimaryDelayCache<PK, K, V> primaryCache = primaryCacheMap.remove(primaryKey);
            if (primaryCache == null) {
                return true;
            }
            boolean isSuccess = false;
            int tryCount = Configs.getInstance().getInt("cache.flush.tryOneCount");
            long timeOut = Configs.getInstance().getDuration("cache.flush.timeOut", TimeUnit.MILLISECONDS);
            for (int count = 0; count < tryCount; count++) {
                isSuccess = flushPrimaryCacheInLock(Collections.singletonMap(primaryKey, primaryCache));
                if (isSuccess) {
                    break;
                }
                logger.error("{} primaryKey:{} count:{} {} failure.", getAClass().getName(), getKeyValueBuilder().toPrimaryKeyString(primaryKey), count, message);
                ThreadUtil.sleep(RandomUtils.nextLong(50, 200));
            }
            return isSuccess;
        }, consumer);
    }

    protected String getScheduleName() {
        return getClassConfig().tableName;
    }

    @Override
    public ICacheSource<PK, K, V> getCacheSource() {
        return cacheSource;
    }

    protected abstract Map<PK, PrimaryDelayCache<PK, K, V>> executeWritePrimaryCache(Map<PK, PrimaryDelayCache<PK, K, V>> primaryCacheMap);

    private void onSchedule() {
        if (primaryCacheMap.isEmpty()) {
            return;
        }
        long currentTime = System.currentTimeMillis();
        List<PK> removePrimaryKeyList = new ArrayList<>();
        List<Map.Entry<PK, Long>> expiredPrimaryKey = new ArrayList<>();
        for (Map.Entry<PK, PrimaryDelayCache<PK, K, V>> entry : primaryCacheMap.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            if (currentTime < entry.getValue().getExpiredTime()) {
                expiredPrimaryKey.add(new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue().getExpiredTime()));
            } else {
                removePrimaryKeyList.add(entry.getKey());
            }
        }
        int maximumCount = Configs.getInstance().getInt("cache.maximumCount");
        if (expiredPrimaryKey.size() > maximumCount) {
            expiredPrimaryKey.sort(Comparator.comparingLong(Map.Entry::getValue));
            for (int i = maximumCount; i < expiredPrimaryKey.size(); i++) {
                removePrimaryKeyList.add(expiredPrimaryKey.get(i).getKey());
            }
        }
        if (removePrimaryKeyList.isEmpty()) {
            return;
        }
        Map<PK, PrimaryDelayCache<PK, K, V>> primaryCacheMap0 = new HashMap<>();
        for (PK pk : removePrimaryKeyList) {
            PrimaryDelayCache<PK, K, V> primaryCache = primaryCacheMap.remove(pk);
            if (primaryCache == null) {
                continue;
            }
            if (primaryCache.isEmpty()) {
                continue;
            }
            primaryCacheMap0.put(pk, primaryCache);
        }
        flushPrimaryCacheInLock(primaryCacheMap0);
    }

    private boolean flushPrimaryCacheInLock(Map<PK, PrimaryDelayCache<PK, K, V>> primaryCacheMap) {
        Map<PK, PrimaryDelayCache<PK, K, V>> failurePrimaryCacheMap = executeWritePrimaryCache(primaryCacheMap);
        long duration = RandomUtils.nextLong(1000, 5000);
        for (Map.Entry<PK, PrimaryDelayCache<PK, K, V>> entry : failurePrimaryCacheMap.entrySet()) {
            PK primaryKey = entry.getValue().getPrimaryKey();
            PrimaryDelayCache<PK, K, V> newPrimaryCache = this.primaryCacheMap.computeIfAbsent(primaryKey, key -> new PrimaryDelayCache<>(key, duration));
            newPrimaryCache.rollbackAll(entry.getValue().getAll());
        }
        return failurePrimaryCacheMap.isEmpty();
    }

    /**
     * @param name
     * @param modelList
     * @param cacheList
     * @param function
     * @param <T>
     * @param <V>
     * @return 返回失败的数据
     */
    public static <T, V> List<V> handleBatch(String name, List<T> modelList, List<V> cacheList, Function<List<T>, Boolean> function){
        if (modelList.size() != cacheList.size()){
            throw new UnsupportedOperationException("");
        }
        if (modelList.isEmpty()){
            return Collections.emptyList();
        }
        List<V> failureCacheList = new ArrayList<>();
        int batchCount = Configs.getInstance().getInt("cache.flush.batchCount");
        while (modelList.size() > 0) {
            int count = modelList.size() / batchCount;
            int index = count * batchCount;
            if (modelList.size() % batchCount == 0){
                index -= batchCount;                 //刚好是整数的时候
            }
            boolean success = false;
            try {
                success = function.apply(modelList.subList(index, modelList.size()));
            }
            catch (Throwable t){
                logger.error("name:{} index:{} modelList:{}", name, index,  modelList.size(), t);
            }
            finally {
                if (success){
                }
                else {
                    failureCacheList.addAll(cacheList.subList(index, modelList.size()));
                }
            }
            modelList = index > 0 ? modelList.subList(0, index) : Collections.emptyList();
        }
        return failureCacheList;
    }
}