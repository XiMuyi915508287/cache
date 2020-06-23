package com.game.cache;

import com.game.cache.mapper.IClassAnnotation;

import java.util.List;
import java.util.Map;

public interface ICacheUniqueId extends IClassConfig, IClassAnnotation {

	String getSourceUniqueId();

	String getRedisKeyString(long primaryKey);

	List<ICacheUniqueId> sharedCacheDaoUniqueList();

	List<Map.Entry<String, Object>> createPrimaryUniqueKeys(long primaryKey);
}
