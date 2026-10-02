package io.mq.spike.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;

class MqSpikeProcessor {

    private static final String FEATURE = "mq-spike";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /** every mq/*.xml in a resource root: changes must not restart the application */
    @BuildStep
    HotDeploymentWatchedFileBuildItem watchResources() {
        return HotDeploymentWatchedFileBuildItem.builder()
                .setLocationPredicate(p -> p.startsWith("mq/") && p.endsWith(".xml"))
                .setRestartNeeded(false)
                .build();
    }
}
