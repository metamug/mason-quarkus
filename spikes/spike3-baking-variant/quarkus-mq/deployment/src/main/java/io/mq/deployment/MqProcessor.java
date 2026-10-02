package io.mq.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import io.mq.runtime.MqModel;
import io.mq.runtime.MqRecorder;
import io.mq.runtime.MqRequest;
import io.mq.runtime.MqResource;
import io.mq.runtime.MqStep;
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
 * Build time: finds the resource XML files, parses them (StAX; any parser would do because this never reaches the
 * runtime) into the object model and records that model so that the application's startup code creates the routes.
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
                        model.resources.add(parse(p.getFileName().toString().replaceAll("\\.xml$", ""), in));
                    } catch (IOException | XMLStreamException e) {
                        throw new IllegalStateException("Invalid resource file " + p.getFileName() + ": " + e.getMessage(), e);
                    }
                });
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
        recorder.registerRoutes(router.getHttpRouter(), model);
    }

    static MqResource parse(String name, InputStream in) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XMLStreamReader r = f.createXMLStreamReader(in);
        MqResource resource = new MqResource();
        resource.name = name;
        MqRequest request = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            switch (r.getLocalName()) {
                case "Resource":
                    resource.version = attr(r, "v", "1.0");
                    break;
                case "Request":
                    request = new MqRequest();
                    request.method = attr(r, "method", null);
                    if (request.method == null) {
                        throw new XMLStreamException("Request without a method");
                    }
                    request.item = Boolean.parseBoolean(attr(r, "item", "false"));
                    request.status = Integer.parseInt(attr(r, "status", "0"));
                    resource.requests.add(request);
                    break;
                case "Sql": {
                    MqStep s = new MqStep();
                    s.kind = "sql";
                    s.id = attr(r, "id", null);
                    s.type = attr(r, "type", "query");
                    s.output = Boolean.parseBoolean(attr(r, "output", "query".equals(s.type) ? "true" : "false"));
                    s.text = r.getElementText().trim().replaceAll("\\s+", " ");
                    request.steps.add(s);
                    break;
                }
                case "Text": {
                    MqStep s = new MqStep();
                    s.kind = "text";
                    s.id = attr(r, "id", null);
                    s.output = true;
                    s.text = r.getElementText().trim();
                    request.steps.add(s);
                    break;
                }
                case "Desc":
                    break;
                default:
                    throw new XMLStreamException("Unsupported element <" + r.getLocalName() + "> in " + name + ".xml");
            }
        }
        return resource;
    }

    private static String attr(XMLStreamReader r, String name, String dflt) {
        String v = r.getAttributeValue(null, name);
        return v == null ? dflt : v;
    }
}
