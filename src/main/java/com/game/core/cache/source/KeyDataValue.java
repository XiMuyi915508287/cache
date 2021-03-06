package com.game.core.cache.source;

import com.game.core.cache.data.IData;

public class KeyDataValue<K, V extends IData<K>> {

    private K key;
    private CacheCommand cacheCommand;
    private V dataValue;

    private KeyDataValue(K key, CacheCommand cacheCommand, V dataValue) {
        this.key = key;
        this.cacheCommand = cacheCommand;
        this.dataValue = dataValue;
    }

    public K getKey() {
        return key;
    }

    public V getDataValue() {
        return dataValue;
    }

    public CacheCommand getCacheCommand() {
        return cacheCommand;
    }

    public void updateCommand(CacheCommand cacheCommand){
        this.cacheCommand = cacheCommand;
    }

    public boolean isUpsert(){
        return cacheCommand.equals(CacheCommand.UPSERT);
    }

    public boolean isDeleted(){
        return cacheCommand.equals(CacheCommand.DELETE);
    }

    public static <K, V extends IData<K>> KeyDataValue<K, V> createCache(K key, V dataValue){
        return new KeyDataValue<>(key, CacheCommand.UPSERT, dataValue);
    }

    public static <K, V extends IData<K>> KeyDataValue<K, V> createDelete(K key){
        return new KeyDataValue<>(key, CacheCommand.DELETE, null);
    }
}
