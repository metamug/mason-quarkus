package io.mq.quarkus.deployment;

import io.mq.quarkus.runtime.MqHttp;
import io.mq.quarkus.runtime.MqResources;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.mq.script.ScriptLoader;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ServiceProviderBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBundleBuildItem;

class MqProcessor {

    private static final String FEATURE = "mq";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    AdditionalBeanBuildItem resources() {
        return AdditionalBeanBuildItem.builder().addBeanClasses(MqResources.class, MqHttp.class).setUnremovable().build();
    }

    /** the scripts compiled ahead of time register as a ScriptLoader service; the native binary needs them registered at build time */
    @BuildStep
    ServiceProviderBuildItem compiledScripts() {
        return ServiceProviderBuildItem.allProvidersFromClassPath(ScriptLoader.class.getName());
    }

    /** plugin classes register as services; the native binary needs them registered at build time */
    @BuildStep
    ServiceProviderBuildItem plugins() {
        return ServiceProviderBuildItem.allProvidersFromClassPath(io.mq.plugin.Plugin.class.getName());
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
