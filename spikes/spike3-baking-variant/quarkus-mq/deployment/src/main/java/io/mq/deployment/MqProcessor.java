package io.mq.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import io.mq.runtime.MqModel;
import io.mq.runtime.MqRecorder;
import io.mq.runtime.MqRequest;
import io.mq.runtime.MqResource;
import io.mq.runtime.MqStep;
import io.mq.spike.parser.ResourceParser;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.vertx.http.deployment.VertxWebRouterBuildItem;

/**
 * THROWAWAY (Spike 3, question C). Build time: finds the resource XML files, runs the SHARED StAX parser and validator
 * (the same code the runtime path uses; there is no second parser here), converts the result into the recordable
 * object model and records it so that the application's startup code creates the routes. A file that does not validate
 * fails the build with the validator's messages.
 */
class MqProcessor {

    private static final String FEATURE = "mq";
    private static final String FOLDER = "mq";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /** dev mode: a changed resource file re-runs this build step, i.e. the model is rebuilt with an application restart */
    @BuildStep
    void watch(BuildProducer<HotDeploymentWatchedFileBuildItem> watched) {
        watched.produce(HotDeploymentWatchedFileBuildItem.builder()
                .setLocationPredicate(p -> p.startsWith(FOLDER + "/") && p.endsWith(".xml"))
                .setRestartNeeded(true)
                .build());
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    @Produce(ServiceStartBuildItem.class)
    void routes(MqRecorder recorder, ApplicationArchivesBuildItem archives, VertxWebRouterBuildItem router) {
        MqModel model = new MqModel();
        archives.getRootArchive().accept(tree -> {
            Path dir = tree.getPath(FOLDER);
            if (dir == null || !Files.isDirectory(dir)) {
                return;
            }
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".xml")).sorted().forEach(p -> {
                    try (InputStream in = Files.newInputStream(p)) {
                        ResourceParser.Result r = ResourceParser.parse(p.getFileName().toString().replaceAll("\\.xml$", ""), in);
                        if (!r.valid) {
                            throw new IllegalStateException("Invalid resource file " + p.getFileName() + ": " + String.join("; ", r.errors));
                        }
                        model.resources.add(convert(r.resource));
                    } catch (IOException e) {
                        throw new IllegalStateException("Cannot read " + p.getFileName() + ": " + e.getMessage(), e);
                    }
                });
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        recorder.registerRoutes(router.getHttpRouter(), model);
    }

    /** shared parser model -> recordable runtime model (plain data, no behaviour) */
    static MqResource convert(ResourceParser.Resource in) {
        MqResource out = new MqResource();
        out.name = in.name;
        out.version = in.version;
        for (ResourceParser.Request rq : in.requests) {
            MqRequest request = new MqRequest();
            request.method = rq.method;
            request.item = rq.item;
            request.status = rq.status;
            for (ResourceParser.Step st : rq.steps) {
                MqStep step = new MqStep();
                step.kind = st.kind;
                step.id = st.id;
                step.type = st.type == null ? "query" : st.type;
                step.text = st.text;
                // sql queries and text are part of the response, everything else is not in this spike
                step.output = "text".equals(st.kind) || ("sql".equals(st.kind) && "query".equals(step.type));
                request.steps.add(step);
            }
            out.requests.add(request);
        }
        return out;
    }
}
