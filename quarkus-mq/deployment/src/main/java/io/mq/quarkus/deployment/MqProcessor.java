package io.mq.quarkus.deployment;

import io.mq.quarkus.runtime.MqResources;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBundleBuildItem;

class MqProcessor {

    private static final String FEATURE = "mq";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem resources() {
        return AdditionalBeanBuildItem.unremovableOf(MqResources.class);
    }

    /**
     * The JDK's StAX parser reads its error messages from this bundle. Without it a native binary dies with
     * MissingResourceException on the first malformed XML file instead of reporting a validation error (Spike 2).
     * mq-core carries the same setting in META-INF/native-image for users of the library without Quarkus.
     */
    @BuildStep
    NativeImageResourceBundleBuildItem xmlMessages() {
        return new NativeImageResourceBundleBuildItem("com.sun.org.apache.xerces.internal.impl.msg.XMLMessages");
    }
}
