package com.game.cache.source;

public interface ICacheLoginPredicate<PK>  {

    /**
     * 抢夺登录的第一次加载
     * @param primaryKey
     */
    boolean loginSharedLoad(PK primaryKey, String cacheName);
}