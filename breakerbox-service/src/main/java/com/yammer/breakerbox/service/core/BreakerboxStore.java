package com.yammer.breakerbox.service.core;

import com.google.common.base.Optional;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import com.microsoft.windowsazure.services.table.client.TableConstants;
import com.microsoft.windowsazure.services.table.client.TableQuery;
import com.yammer.azure.TableClient;
import com.yammer.azure.core.TableType;
import com.yammer.breakerbox.service.azure.DependencyEntity;
import com.yammer.breakerbox.service.azure.DependencyEntityData;
import com.yammer.breakerbox.service.azure.ServiceEntity;
import com.yammer.breakerbox.service.azure.TableId;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.TimerContext;
import com.yammer.tenacity.core.config.TenacityConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class BreakerboxStore {
    private final TableClient tableClient;
    private final Cache<ServiceId, ImmutableList<ServiceEntity>> listDependenciesCache = CacheBuilder
            .newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    private static final Timer LIST_SERVICES = Metrics.newTimer(BreakerboxStore.class, "list-services");
    private static final Timer LIST_SERVICE = Metrics.newTimer(BreakerboxStore.class, "list-service");
    private static final Timer LATEST_DEPENDENCY_CONFIG = Metrics.newTimer(BreakerboxStore.class, "latest-dependency-config");
    private static final Logger LOGGER = LoggerFactory.getLogger(BreakerboxStore.class);

    public BreakerboxStore(TableClient tableClient) {
        this.tableClient = tableClient;
    }

    public boolean storeServiceEntity(ServiceId serviceId, DependencyId dependencyId) {
        return storeServiceEntity(ServiceEntity.build(serviceId, dependencyId));
    }

    public boolean storeServiceEntity(ServiceEntity serviceEntity) {
        listDependenciesCache.invalidate(serviceEntity.getServiceId());
        return tableClient.insertOrReplace(serviceEntity);
    }

    //pass in timestamp=System.currentTimemillis if new entry
    public boolean storeDependencyEntity(DependencyId dependencyId, long timestamp, TenacityConfiguration tenacityConfiguration, String username) {
        return storeDependencyEntity(DependencyEntity.build(dependencyId, DependencyEntityData.create(timestamp, username, tenacityConfiguration)));
    }

    private boolean storeDependencyEntity(DependencyEntity dependencyEntity) {
        return tableClient.insertOrReplace(dependencyEntity);
    }

    public boolean remove(TableType tableType) {
        return tableClient.remove(tableType);
    }

    public Optional<ServiceEntity> retrieve(ServiceId serviceId, DependencyId dependencyId) {
        return tableClient.retrieve(ServiceEntity.build(serviceId, dependencyId));
    }

    public Optional<DependencyEntity> retrieve(DependencyId dependencyId, long timestamp) {
        return tableClient.retrieve(DependencyEntity.build(dependencyId, timestamp));
    }

    public ImmutableList<ServiceEntity> listServices() {
        return allServiceEntities();
    }

    public ImmutableList<ServiceEntity> listDependencies(final ServiceId serviceId) {
        try {
            return listDependenciesCache.get(serviceId, new Callable<ImmutableList<ServiceEntity>>() {
                @Override
                public ImmutableList<ServiceEntity> call() throws Exception {
                    return allServiceEntities(serviceId);
                }
            });
        } catch (ExecutionException err) {
            LOGGER.warn("Could not fetch dependencies for {}", serviceId, err);
        }
        return ImmutableList.of();
    }

    private ImmutableList<ServiceEntity> allServiceEntities(ServiceId serviceId) {
        final TimerContext timerContext = LIST_SERVICE.time();
        try {
            return tableClient.search(TableQuery
                    .from(TableId.SERVICE.toString(), ServiceEntity.class)
                    .where(TableQuery
                            .generateFilterCondition(
                                    TableConstants.PARTITION_KEY,
                                    TableQuery.QueryComparisons.EQUAL,
                                    serviceId.getId())));
        } finally {
            timerContext.stop();
        }
    }

    private ImmutableList<ServiceEntity> allServiceEntities() {
        final TimerContext timerContext = LIST_SERVICES.time();
        try {
            return tableClient.search(TableQuery
                    .from(TableId.SERVICE.toString(), ServiceEntity.class));
        } finally {
            timerContext.stop();
        }
    }

    public ImmutableList<DependencyEntity> listDependencyConfigurations(DependencyId dependencyId) {
        final TimerContext timerContext = LATEST_DEPENDENCY_CONFIG.time();
        try {
            return tableClient.search(TableQuery
                    .from(TableId.DEPENDENCY.toString(), DependencyEntity.class)
                    .where(TableQuery.generateFilterCondition(
                            TableConstants.PARTITION_KEY,
                            TableQuery.QueryComparisons.EQUAL,
                            dependencyId.getId())));
        } finally {
            timerContext.stop();
        }
    }
}
