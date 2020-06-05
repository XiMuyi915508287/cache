package com.game.common.config;

import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigList;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Configs implements IConfig {

    private static Configs applicationConfig;
    static {
        String property = System.getProperty("application.conf.path", "application.conf");
        com.typesafe.config.Config config = ConfigFactory.load(property);
        applicationConfig = new Configs(config);
    }

    public static Configs getInstance() {
        return applicationConfig;
    }

    private final com.typesafe.config.Config config;

    private Configs(com.typesafe.config.Config config) {
        this.config = config;
    }

    @Override
    public boolean hasPath(String path) {
        return config.hasPath(path);
    }

    @Override
    public IConfig getConfig(String path){
        return new Configs(Objects.requireNonNull(config.getConfig(path)));
    }

    @Override
    public int getInt(String path){
        return config.getInt(path);
    }

    @Override
    public long getLong(String path){
        return config.getLong(path);
    }

    @Override
    public String getString(String path) {
        return config.getString(path);
    }

    @Override
    public boolean getBoolean(String path) {
        return config.getBoolean(path);
    }

    @Override
    public List<IConfig> getConfigList(String path){
        List<? extends com.typesafe.config.Config> configList = config.getConfigList(path);
        return configList.stream().map(Configs::new).collect(Collectors.toList());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String path){
        ConfigList configList = config.getList(path);
        return (List<T>)configList.unwrapped();
    }

    @Override
    public long getDuration(String path, TimeUnit timeUnit) {
        return config.getDuration(path, timeUnit);
    }
}
