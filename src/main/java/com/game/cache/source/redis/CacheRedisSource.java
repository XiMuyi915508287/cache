package com.game.cache.source.redis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.game.cache.CacheInformation;
import com.game.cache.CacheName;
import com.game.cache.CacheType;
import com.game.cache.CacheUniqueKey;
import com.game.cache.ICacheUniqueKey;
import com.game.cache.data.DataCollection;
import com.game.cache.data.IData;
import com.game.cache.key.IKeyValueBuilder;
import com.game.cache.mapper.JsonValueConverter;
import com.game.cache.source.CacheSource;
import com.game.cache.source.ICacheDelaySource;
import com.game.cache.source.executor.ICacheExecutor;
import com.game.cache.source.interact.CacheRedisCollection;
import com.game.cache.source.interact.ICacheRedisInteract;
import com.game.db.redis.IRedisPipeline;
import jodd.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
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

    private static final SerializerFeature[] mySerializerFeatures = new SerializerFeature[] {
            SerializerFeature.WriteMapNullValue,
            SerializerFeature.WriteNullListAsEmpty,
            SerializerFeature.WriteNullStringAsEmpty,
            SerializerFeature.WriteDateUseDateFormat,
            SerializerFeature.IgnoreNonFieldGetter,
            SerializerFeature.IgnoreNonFieldGetter
    };

    private final CacheInformation EMPTY_INFO = new CacheInformation();

    private final ICacheRedisInteract cacheRedisInteract;

    public CacheRedisSource(CacheUniqueKey cacheDaoKey, IKeyValueBuilder<K> secondaryBuilder, ICacheRedisInteract cacheRedisInteract) {
        super(cacheDaoKey, secondaryBuilder);
        this.cacheRedisInteract = cacheRedisInteract;
    }

    @Override
    public CacheType getCacheType() {
        return CacheType.Redis;
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
        ICacheUniqueKey currentDaoUnique = getCacheUniqueKey();
        CacheRedisCollection redisCollection = cacheRedisInteract.removeCollection(primaryKey, currentDaoUnique);
        if (redisCollection != null){
            return readDataCollection(redisCollection.getRedisKeyValueList());
        }
        boolean redisSharedLoad = cacheRedisInteract.getAndSetSharedLoad(primaryKey, currentDaoUnique);
        List<ICacheUniqueKey> cacheDaoUniqueList = redisSharedLoad ? currentDaoUnique.sharedCacheDaoUniqueList() : Collections.singletonList(currentDaoUnique);
        List<Map.Entry<String, Object>> entryList = RedisClientUtil.getRedisClient().executeBatch(redisPipeline -> {
            for (ICacheUniqueKey cacheDaoUnique : cacheDaoUniqueList) {
                executeCommand(primaryKey, redisPipeline, cacheDaoUnique);
            }
        });
        Map<Integer, CacheRedisCollection> redisCollectionMap = new HashMap<>(cacheDaoUniqueList.size());
        int batchCount = entryList.size() / cacheDaoUniqueList.size();
        for (int i = 0; i < entryList.size(); i += batchCount) {
            ICacheUniqueKey cacheDaoUnique = cacheDaoUniqueList.get(i / batchCount);
            List<Map.Entry<String, Object>> entrySubList = entryList.subList(0, batchCount);
            int primarySharedId = cacheDaoUnique.getPrimarySharedId();
            redisCollectionMap.put(primarySharedId, new CacheRedisCollection(primarySharedId, entrySubList));
        }
        redisCollection = redisCollectionMap.remove(currentDaoUnique.getPrimarySharedId());
        cacheRedisInteract.addCollections(primaryKey, currentDaoUnique, redisCollectionMap);
        return readDataCollection(redisCollection.getRedisKeyValueList());
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
        return replaceBatch(primaryKey, values, null);
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
    public boolean replaceBatch(long primaryKey, Collection<V> values, CacheInformation information) {
        if (values.isEmpty() && (information == null || information.isEmpty())){
            return true;
        }
        Map<String, String> redisKeyValueMap = values.stream().collect(Collectors.toMap(value -> keyValueBuilder.toSecondaryKeyString(value.secondaryKey()), this::toJSONString));
        long expiredTime = 0;
        if (information != null) {
            for (Map.Entry<String, Object> entry : information.entrySet()) {
                redisKeyValueMap.put(entry.getKey(), JsonValueConverter.toJSONString(entry.getValue()));
            }
            expiredTime = information.getExpiredTime();
        }
        String keyString = getPrimaryRedisKey(primaryKey);
        if (expiredTime > 0) {
            long finalExpiredTime = expiredTime;
            RedisClientUtil.getRedisClient().executeBatch(redisPipeline -> {
                redisPipeline.hset(keyString, redisKeyValueMap);
                redisPipeline.pexpireAt(keyString, finalExpiredTime);
            });
        }
        else {
            RedisClientUtil.getRedisClient().hset(keyString, redisKeyValueMap);
        }
        return true;
    }

    /**
     * 执行对应的命令获取数据
     * @param primaryKey
     * @param redisPipeline
     * @param daoUnique
     */
    private void executeCommand(long primaryKey, IRedisPipeline redisPipeline, ICacheUniqueKey daoUnique){
        String redisKeyString = daoUnique.getRedisKeyString(primaryKey);
        redisPipeline.hgetAll(redisKeyString);
        redisPipeline.pttl(redisKeyString);
    }

    /**
     * 序列化成 DataCollection
     * @param entryList
     * @return
     */
    @SuppressWarnings("unchecked")
    private DataCollection<K, V> readDataCollection(List<Map.Entry<String, Object>> entryList){
        String redisKeyString = entryList.get(0).getKey();
        Map<String, String> redisKeyValues = (Map<String, String>)entryList.get(0).getValue();
        List<String> valueStringList = new ArrayList<>();
        Map<String, Object> informationValueMap = new HashMap<>();
        for (Map.Entry<String, String> entry : redisKeyValues.entrySet()) {
            if (CacheName.Names.contains(entry.getKey())) {
                Object object = JsonValueConverter.parse(entry.getValue());
                informationValueMap.put(entry.getKey(), object);
            }
            else {
                valueStringList.add(entry.getValue());
            }
        }
        List<V> dataList = convert2VDataValue(valueStringList);
        CacheInformation information = new CacheInformation(informationValueMap);
        return new DataCollection<>(dataList, information);
    }

    /**
     * 获取redis的键值
     * @param primaryKey
     * @return
     */
    private String getPrimaryRedisKey(long primaryKey){
        return getCacheUniqueKey().getRedisKeyString(primaryKey);
    }

    /**
     * 序列化
     * @param data
     * @return
     */
    private String toJSONString(V data){
        Map<String, Object> cacheValue = getConverter().convert2Cache(data);
        cacheValue.put(CacheName.DataIndexBit.getKeyName(), JsonValueConverter.toJSONString(data.getBitIndexBits()));
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
        return getConverter().convert2Value(cacheValue);
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
}
