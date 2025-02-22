/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.ml.utils;

import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.xpack.core.ml.MachineLearningField;
import org.elasticsearch.xpack.core.ml.dataframe.DataFrameAnalyticsConfig;
import org.elasticsearch.xpack.core.ml.job.config.Job;
import org.elasticsearch.xpack.ml.MachineLearning;

import java.util.Locale;
import java.util.OptionalLong;

import static org.elasticsearch.xpack.ml.MachineLearning.MACHINE_MEMORY_NODE_ATTR;
import static org.elasticsearch.xpack.ml.MachineLearning.MAX_JVM_SIZE_NODE_ATTR;
import static org.elasticsearch.xpack.ml.MachineLearning.MAX_LAZY_ML_NODES;
import static org.elasticsearch.xpack.ml.MachineLearning.MAX_MACHINE_MEMORY_PERCENT;
import static org.elasticsearch.xpack.ml.MachineLearning.MAX_ML_NODE_SIZE;
import static org.elasticsearch.xpack.ml.MachineLearning.USE_AUTO_MACHINE_MEMORY_PERCENT;

public final class NativeMemoryCalculator {

    private static final long STATIC_JVM_UPPER_THRESHOLD = ByteSizeValue.ofGb(2).getBytes();
    static final long MINIMUM_AUTOMATIC_NODE_SIZE = ByteSizeValue.ofGb(1).getBytes();
    private static final long OS_OVERHEAD = ByteSizeValue.ofMb(200L).getBytes();

    private NativeMemoryCalculator() {}

    public static OptionalLong allowedBytesForMl(DiscoveryNode node, Settings settings) {
        if (node.getRoles().contains(DiscoveryNodeRole.ML_ROLE) == false) {
            return OptionalLong.empty();
        }
        return allowedBytesForMl(
            node.getAttributes().get(MACHINE_MEMORY_NODE_ATTR),
            node.getAttributes().get(MAX_JVM_SIZE_NODE_ATTR),
            MAX_MACHINE_MEMORY_PERCENT.get(settings),
            USE_AUTO_MACHINE_MEMORY_PERCENT.get(settings)
        );
    }

    public static OptionalLong allowedBytesForMl(DiscoveryNode node, ClusterSettings settings) {
        if (node.getRoles().contains(DiscoveryNodeRole.ML_ROLE) == false) {
            return OptionalLong.empty();
        }
        return allowedBytesForMl(
            node.getAttributes().get(MACHINE_MEMORY_NODE_ATTR),
            node.getAttributes().get(MAX_JVM_SIZE_NODE_ATTR),
            settings.get(MAX_MACHINE_MEMORY_PERCENT),
            settings.get(USE_AUTO_MACHINE_MEMORY_PERCENT)
        );
    }

    public static OptionalLong allowedBytesForMl(DiscoveryNode node, int maxMemoryPercent, boolean useAutoPercent) {
        if (node.getRoles().contains(DiscoveryNodeRole.ML_ROLE) == false) {
            return OptionalLong.empty();
        }
        return allowedBytesForMl(
            node.getAttributes().get(MACHINE_MEMORY_NODE_ATTR),
            node.getAttributes().get(MAX_JVM_SIZE_NODE_ATTR),
            maxMemoryPercent,
            useAutoPercent
        );
    }

    private static OptionalLong allowedBytesForMl(String nodeBytes, String jvmBytes, int maxMemoryPercent, boolean useAuto) {
        assert nodeBytes != null
            : "This private method should only be called for ML nodes, and all ML nodes should have the ml.machine_memory node attribute";
        if (nodeBytes == null) {
            return OptionalLong.empty();
        }
        final long machineMemory;
        try {
            machineMemory = Long.parseLong(nodeBytes);
        } catch (NumberFormatException e) {
            assert e == null : "ml.machine_memory should parse because we set it internally: invalid value was " + nodeBytes;
            return OptionalLong.empty();
        }
        Long jvmMemory = null;
        try {
            if (Strings.isNullOrEmpty(jvmBytes) == false) {
                jvmMemory = Long.parseLong(jvmBytes);
            }
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(allowedBytesForMl(machineMemory, jvmMemory, maxMemoryPercent, useAuto));
    }

    public static long calculateApproxNecessaryNodeSize(long nativeMachineMemory, Long jvmSize, int maxMemoryPercent, boolean useAuto) {
        if (nativeMachineMemory == 0) {
            return 0;
        }
        if (useAuto) {
            // TODO utilize official ergonomic JVM size calculations when available.
            jvmSize = jvmSize == null ? dynamicallyCalculateJvmSizeFromNativeMemorySize(nativeMachineMemory) : jvmSize;
            // We haven't reached our 90% threshold, so, simply summing up the values is adequate
            if ((jvmSize + OS_OVERHEAD) / (double) nativeMachineMemory > 0.1) {
                return Math.max(nativeMachineMemory + jvmSize + OS_OVERHEAD, MINIMUM_AUTOMATIC_NODE_SIZE);
            }
            return Math.round((nativeMachineMemory / 0.9));
        }
        return (long) ((100.0 / maxMemoryPercent) * nativeMachineMemory);
    }

    public static double modelMemoryPercent(long machineMemory, Long jvmSize, int maxMemoryPercent, boolean useAuto) {
        if (useAuto) {
            jvmSize = jvmSize == null ? dynamicallyCalculateJvmSizeFromNodeSize(machineMemory) : jvmSize;
            if (machineMemory - jvmSize < OS_OVERHEAD || machineMemory == 0) {
                assert false
                    : String.format(
                        Locale.ROOT,
                        "machine memory [%d] minus jvm [%d] is less than overhead [%d]",
                        machineMemory,
                        jvmSize,
                        OS_OVERHEAD
                    );
                return maxMemoryPercent;
            }
            // This calculation is dynamic and designed to maximally take advantage of the underlying machine for machine learning
            // We only allow 200MB for the Operating system itself and take up to 90% of the underlying native memory left
            // Example calculations:
            // 1GB node -> 41%
            // 2GB node -> 66%
            // 16GB node -> 87%
            // 64GB node -> 90%
            return Math.min(90.0, ((machineMemory - jvmSize - OS_OVERHEAD) / (double) machineMemory) * 100.0D);
        }
        return maxMemoryPercent;
    }

    static long allowedBytesForMl(long machineMemory, Long jvmSize, int maxMemoryPercent, boolean useAuto) {
        // machineMemory can get set to -1 if the OS probe that determines memory fails
        if (machineMemory <= 0) {
            return 0L;
        }
        if (useAuto && jvmSize != null) {
            // It is conceivable that there is a machine smaller than 200MB.
            // If the administrator wants to use the auto configuration, the node should be larger.
            if (machineMemory - jvmSize <= OS_OVERHEAD) {
                return machineMemory / 100;
            }
            // This calculation is dynamic and designed to maximally take advantage of the underlying machine for machine learning
            // We only allow 200MB for the Operating system itself and take up to 90% of the underlying native memory left
            // Example calculations:
            // 1GB node -> 41%
            // 2GB node -> 66%
            // 16GB node -> 87%
            // 64GB node -> 90%
            double memoryProportion = Math.min(0.90, (machineMemory - jvmSize - OS_OVERHEAD) / (double) machineMemory);
            return Math.round(machineMemory * memoryProportion);
        }

        return (long) (machineMemory * (maxMemoryPercent / 100.0));
    }

    public static long allowedBytesForMl(long machineMemory, int maxMemoryPercent, boolean useAuto) {
        return allowedBytesForMl(
            machineMemory,
            useAuto ? dynamicallyCalculateJvmSizeFromNodeSize(machineMemory) : machineMemory / 2,
            maxMemoryPercent,
            useAuto
        );
    }

    // TODO replace with official ergonomic calculation
    public static long dynamicallyCalculateJvmSizeFromNodeSize(long nodeSize) {
        // While the original idea here was to predicate on 2Gb, it has been found that the knot points of
        // 2GB and 8GB cause weird issues where the JVM size will "jump the gap" from one to the other when
        // considering true tier sizes in elastic cloud.
        if (nodeSize < ByteSizeValue.ofMb(1280).getBytes()) {
            return (long) (nodeSize * 0.40);
        }
        if (nodeSize < ByteSizeValue.ofGb(8).getBytes()) {
            return (long) (nodeSize * 0.25);
        }
        return STATIC_JVM_UPPER_THRESHOLD;
    }

    public static long dynamicallyCalculateJvmSizeFromNativeMemorySize(long nativeMachineMemory) {
        // See dynamicallyCalculateJvm the following JVM calculations are arithmetic inverses of JVM calculation
        //
        // Example: For < 2GB node, the JVM is 0.4 * total_node_size. This means, the rest is 0.6 the node size.
        // So, the `nativeAndOverhead` is == 0.6 * total_node_size => total_node_size = (nativeAndOverHead / 0.6)
        // Consequently jvmSize = (nativeAndOverHead / 0.6)*0.4 = nativeAndOverHead * 2/3
        long nativeAndOverhead = nativeMachineMemory + OS_OVERHEAD;
        if (nativeAndOverhead < (ByteSizeValue.ofGb(2).getBytes() * 0.60)) {
            return Math.round((nativeAndOverhead / 0.6) * 0.4);
        }
        if (nativeAndOverhead < (ByteSizeValue.ofGb(8).getBytes() * 0.75)) {
            return Math.round((nativeAndOverhead / 0.75) * 0.25);
        }
        return STATIC_JVM_UPPER_THRESHOLD;
    }

    /**
     * Calculates the highest model memory limit that a job could be
     * given and still stand a chance of being assigned in the cluster.
     * The calculation takes into account the possibility of autoscaling,
     * i.e. if lazy nodes are available then the maximum possible node
     * size is considered as well as the sizes of nodes in the current
     * cluster.
     */
    public static ByteSizeValue calculateMaxModelMemoryLimitToFit(ClusterSettings clusterSettings, DiscoveryNodes nodes) {

        long maxMlMemory = 0;
        int numMlNodes = 0;

        for (DiscoveryNode node : nodes) {
            OptionalLong limit = allowedBytesForMl(node, clusterSettings);
            if (limit.isEmpty()) {
                continue;
            }
            maxMlMemory = Math.max(maxMlMemory, limit.getAsLong());
            ++numMlNodes;
        }

        // It is possible that there is scope for more ML nodes to be added
        // to the cluster, in which case take those into account too
        long maxMlNodeSize = clusterSettings.get(MAX_ML_NODE_SIZE).getBytes();
        int maxLazyNodes = clusterSettings.get(MAX_LAZY_ML_NODES);
        if (maxMlNodeSize > 0 && numMlNodes < maxLazyNodes) {
            maxMlMemory = Math.max(
                maxMlMemory,
                allowedBytesForMl(
                    maxMlNodeSize,
                    clusterSettings.get(MAX_MACHINE_MEMORY_PERCENT),
                    clusterSettings.get(USE_AUTO_MACHINE_MEMORY_PERCENT)
                )
            );
        }

        if (maxMlMemory == 0L) {
            // This implies there are currently no ML nodes in the cluster, and
            // no automatic mechanism for adding one, so we have no idea what
            // the effective limit would be if one were added
            return null;
        }

        maxMlMemory -= Math.max(Job.PROCESS_MEMORY_OVERHEAD.getBytes(), DataFrameAnalyticsConfig.PROCESS_MEMORY_OVERHEAD.getBytes());
        maxMlMemory -= MachineLearning.NATIVE_EXECUTABLE_CODE_OVERHEAD.getBytes();
        return ByteSizeValue.ofMb(ByteSizeUnit.BYTES.toMB(Math.max(0L, maxMlMemory)));
    }

    public static ByteSizeValue calculateTotalMlMemory(ClusterSettings clusterSettings, DiscoveryNodes nodes) {

        long totalMlMemory = 0;

        for (DiscoveryNode node : nodes) {
            OptionalLong limit = allowedBytesForMl(node, clusterSettings);
            if (limit.isEmpty()) {
                continue;
            }
            totalMlMemory += limit.getAsLong();
        }

        // Round down to a whole number of megabytes, since we generally deal with model
        // memory limits in whole megabytes
        return ByteSizeValue.ofMb(ByteSizeUnit.BYTES.toMB(totalMlMemory));
    }

    /**
     * Get the maximum value of model memory limit that a user may set in a job config.
     * If the xpack.ml.max_model_memory_limit setting is set then the value comes from that.
     * Otherwise, if xpack.ml.use_auto_machine_memory_percent is set then the maximum model
     * memory limit is considered to be the largest model memory limit that could fit into
     * the cluster (on the assumption that configured lazy nodes will be added and other
     * jobs stopped to make space).
     * @return The maximum model memory limit calculated from the current cluster settings,
     *         or {@link ByteSizeValue#ZERO} if there is no limit.
     */
    public static ByteSizeValue getMaxModelMemoryLimit(ClusterService clusterService) {
        ClusterSettings clusterSettings = clusterService.getClusterSettings();
        ByteSizeValue maxModelMemoryLimit = clusterSettings.get(MachineLearningField.MAX_MODEL_MEMORY_LIMIT);
        if (maxModelMemoryLimit != null && maxModelMemoryLimit.getBytes() > 0) {
            return maxModelMemoryLimit;
        }
        // When the ML memory percent is being set automatically and no explicit max model memory limit is set,
        // max model memory limit is considered to be the max model memory limit that will fit in the cluster
        Boolean autoMemory = clusterSettings.get(MachineLearning.USE_AUTO_MACHINE_MEMORY_PERCENT);
        if (autoMemory) {
            DiscoveryNodes nodes = clusterService.state().getNodes();
            ByteSizeValue modelMemoryLimitToFit = calculateMaxModelMemoryLimitToFit(clusterSettings, nodes);
            if (modelMemoryLimitToFit != null) {
                return modelMemoryLimitToFit;
            }
        }
        return ByteSizeValue.ZERO;
    }
}
