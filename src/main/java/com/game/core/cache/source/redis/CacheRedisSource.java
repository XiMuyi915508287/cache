package com.game.core.cache.source.redis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.game.core.cache.CacheInformation;
import com.game.core.cache.CacheName;
import com.game.core.cache.CacheType;
import com.game.core.cache.ICacheUniqueId;
import com.game.core.cache.data.DataCollection;
import com.game.core.cache.data.DataPrivilegeUtil;
import com.game.core.cache.data.IData;
import com.game.core.cache.key.IKeyValueBuilder;
import com.game.core.cache.source.CacheSource;
import com.game.core.cache.source.ICacheDelaySource;
import com.game.core.cache.source.executor.ICacheExecutor;
import com.game.core.db.redis.IRedisPipeline;
import jodd.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/***
 * 现在redis缓存使用批量加载机制, DB也实现了批量加载:
 * A没有redis数据, B 有redis数据. 但是去加载 B 的时候没有redis数据, 把 A 数据库的数据也加载上来了。
 *
 * @param <K>
 * @param <V>
 */
public class CacheRedisSource<K, V extends IData<K>> extends CacheSource<K, V> implements ICacheRedisSource<K, V> {

    private static final Logger logger = LoggerFactory.getLogger(CacheRedisSource.class);

    private static final String ExpiredName = "ttl.ex";

    private static final SerializerFeature[] mySerializerFeatures = new SerializerFeature[] {
            SerializerFeature.WriteMapNullValue,
            SerializerFeature.WriteNullListAsEmpty,
            SerializerFeature.WriteNullStringAsEmpty,
            SerializerFeature.WriteDateUseDateFormat,
            SerializerFeature.IgnoreNonFieldGetter,
            SerializerFeature.IgnoreNonFieldGetter
    };

    private final CacheInformation EMPTY_INFO = new CacheInformation();

    public CacheRedisSource(ICacheUniqueId cacheUniqueId, IKeyValueBuilder<K> secondaryBuilder) {
        super(cacheUniqueId, secondaryBuilder);
    }

    @Override
    public V get(long primaryKey, K secondaryKey) {
        String keyString = getPrimaryRedisKey(primaryKey);
        String secondaryKeyVString = keyValueBuilder.toSecondaryKeyString(secondaryKey);
        String string = RedisClientUtil.getRedisClient().hget(keyString, secondaryKeyVString);
        return convert2VDataValue(string);
    }

    @Override
    public List<V> getAll(long primaryKey) {
        String keyString = getPrimaryRedisKey(primaryKey);
        Map<String, String> hgetAll = RedisClientUtil.getRedisClient().hgetAll(keyString);
        List<String> stringList = hgetAll.entrySet().stream().filter(entry -> !CacheName.Names.contains(entry.getKey()))
                .map( Map.Entry::getValue).collect(Collectors.toList());
        return convert2VDataValue(stringList);
    }

    @SuppressWarnings("unchecked")
    @Override
    public DataCollection<K, V> getCollection(long primaryKey) {
        List<Map.Entry<String, Object>> entryList = RedisClientUtil.getRedisClient().executeBatch(pipeline -> executeRedisCommand(primaryKey, pipeline, getCacheUniqueId()));
        RedisCollection redisCollection = readRedisCollection(entryList);
        return readDataCollection(redisCollection);
    }

    @Override
    public boolean replaceOne(long primaryKey, V value) {
        String keyString = getPrimaryRedisKey(primaryKey);
        String secondaryKeyString = keyValueBuilder.toSecondaryKeyString(value.secondaryKey());
        String jsonString = toJSONString(value);
        RedisClientUtil.getRedisClient().hset(keyString, secondaryKeyString, jsonString);
        return true;
    }

    @Override
    public boolean replaceBatch(long primaryKey, Collection<V> values) {
        String keyString = getPrimaryRedisKey(primaryKey);
        Map<String, String> redisKeyValueMap = values.stream().collect(Collectors.toMap(value -> keyValueBuilder.toSecondaryKeyString(value.secondaryKey()), this::toJSONString));
        RedisClientUtil.getRedisClient().hset(keyString, redisKeyValueMap);
        return true;
    }

    @Override
    public CacheType getCacheType() {
        return CacheType.Redis;
    }

    @Override
    public boolean deleteOne(long primaryKey, K secondaryKey) {
        String keyString = getPrimaryRedisKey(primaryKey);
        String secondaryKeyString = keyValueBuilder.toSecondaryKeyString(secondaryKey);
        RedisClientUtil.getRedisClient().hdel(keyString, secondaryKeyString);
        return true;
    }

    @Override
    public boolean deleteBatch(long primaryKey, Collection<K> secondaryKeys) {
        String keyString = getPrimaryRedisKey(primaryKey);
        String[] secondaryKeyStrings = secondaryKeys.stream().map(keyValueBuilder::toSecondaryKeyString).toArray(String[]::new);
        RedisClientUtil.getRedisClient().hdel(keyString, secondaryKeyStrings);
        return true;
    }

    @Override
    public ICacheDelaySource<K, V> createDelayUpdateSource(ICacheExecutor executor) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean updateCacheInformation(long primaryKey, CacheInformation cacheInformation) {
        String keyString = getPrimaryRedisKey(primaryKey);
        RedisClientUtil.getRedisClient().executeBatch(redisPipeline -> {
            long expiredTime = cacheInformation.getExpiredTime();
            redisPipeline.hset(keyString, RedisCollection.ExpiredName, String.valueOf(expiredTime));
            redisPipeline.pexpireAt(keyString, cacheInformation.getExpiredTime());
        });
        return true;
    }

    @Override
    public boolean replaceBatch(long primaryKey, Collection<V> values, CacheInformation information) {
        if (values.isEmpty() && information == CacheInformation.DEFAULT){
            return true;
        }
        String keyString = getPrimaryRedisKey(primaryKey);
        Map<String, String> redisKeyValueMap = values.stream().collect(Collectors.toMap(value -> keyValueBuilder.toSecondaryKeyString(value.secondaryKey()), this::toJSONString));
        long expiredTime = information.getExpiredTime();
        if (expiredTime > 0) {
            redisKeyValueMap.put(ExpiredName, String.valueOf(expiredTime));
            RedisClientUtil.getRedisClient().executeBatch(redisPipeline -> {
                redisPipeline.hset(keyString, redisKeyValueMap);
                redisPipeline.pexpireAt(keyString, expiredTime);
            });
        }
        else {
            RedisClientUtil.getRedisClient().hset(keyString, redisKeyValueMap);
        }
        return true;
    }

    /**
     * 序列化成 DataCollection
     * @param redisCollection
     * @return
     */
    @SuppressWarnings("unchecked")
    private DataCollection<K, V> readDataCollection(RedisCollection redisCollection){
        if (redisCollection.isEmpty()){
            return null;
        }
        List<V> dataList = convert2VDataValue(redisCollection.getRedisValues());
        return new DataCollection<>(dataList, redisCollection.getCacheInformation());
    }

    /**
     * 获取redis的键值
     * @param primaryKey
     * @return
     */
    private String getPrimaryRedisKey(long primaryKey){
        return getCacheUniqueId().getRedisKeyString(primaryKey);
    }

    /**
     * 序列化
     * @param data
     * @return
     */
    private String toJSONString(V data){
        Map<String, Object> cacheValue = getConverter().convert2Cache(data);
        cacheValue.put(CacheName.DataIndexBit.getKeyName(), data.getBitIndexBits());
        return JSON.toJSONString(cacheValue, mySerializerFeatures);
    }

    /**
     * 反序列化
     * @param string
     * @return
     */
    private V convert2VDataValue(String string){
        if (StringUtil.isEmpty(string)){
            return null;
        }
        JSONObject cacheValue = JSON.parseObject(string);
        long longValue = cacheValue.getLong(CacheName.DataIndexBit.getKeyName());
        V value = getConverter().convert2Value(cacheValue);
        DataPrivilegeUtil.invokeSetBitValue(value, longValue);
        return value;
    }

    /**
     * 反序列化
     * @param strings
     * @return
     */
    private List<V> convert2VDataValue(Collection<String> strings){
        if (strings == null || strings.isEmpty()){
            return Collections.emptyList();
        }
        return strings.stream().map(this::convert2VDataValue).collect(Collectors.toList());
    }



    private static class RedisCollection {
        public static final String ExpiredName = "ttl.expired";

        private final Map<String, String> redisKeyValueMap;
        private final CacheInformation cacheInformation;

        public RedisCollection(Map<String, String> redisKeyValueMap, CacheInformation cacheInformation) {
            this.redisKeyValueMap = redisKeyValueMap;
            this.cacheInformation = cacheInformation;
        }

        public boolean isEmpty(){
            return redisKeyValueMap == null || redisKeyValueMap.isEmpty();
        }

        public Collection<String> getRedisValues(){
            return redisKeyValueMap.values();
        }

        public CacheInformation getCacheInformation() {
            return cacheInformation;
        }
    }


    /**
     * 执行对应的命令获取数据
     * @param primaryKey
     * @param redisPipeline
     * @param cacheUniqueId
     */
    public static void executeRedisCommand(long primaryKey, IRedisPipeline redisPipeline, ICacheUniqueId cacheUniqueId){
        String redisKeyString = cacheUniqueId.getRedisKeyString(primaryKey);
        redisPipeline.hgetAll(redisKeyString);
    }


    @SuppressWarnings("unchecked")
    public static RedisCollection readRedisCollection(List<Map.Entry<String, Object>> entryList){
        Map<String, String> redisKeyValueMap = (Map<String, String>)entryList.get(0).getValue();
        CacheInformation cacheInformation = new CacheInformation();
        String expiredTime = redisKeyValueMap.remove(ExpiredName);
        if (expiredTime != null){
            cacheInformation.updateCurrentTime(Long.parseLong(expiredTime));
        }
        return new RedisCollection(redisKeyValueMap, cacheInformation);
    }
}
