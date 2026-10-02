package io.mq.spike.runtime;

import io.quarkus.dev.spi.HotReplacementContext;
import io.quarkus.dev.spi.HotReplacementSetup;

/** Dev mode hook: Quarkus calls this once at dev start; we ask to be told about no-restart file changes. */
public class MqHotReplacement implements HotReplacementSetup {

    @Override
    public void setupHotDeployment(HotReplacementContext context) {
        MqRegistry.INSTANCE.init(context);
        context.consumeNoRestartChanges(changed -> MqRegistry.INSTANCE.reload(changed));
    }
}
