package io.mq.leverage;

import org.eclipse.microprofile.openapi.OASFactory;
import org.eclipse.microprofile.openapi.OASModelReader;
import org.eclipse.microprofile.openapi.models.OpenAPI;

/** An OpenAPI document built in code, from a model: what MQ would do from the XML. */
public class MqOpenApi implements OASModelReader {

    @Override
    public OpenAPI buildModel() {
        return OASFactory.createOpenAPI().openapi("3.0.3")
                .info(OASFactory.createInfo().title("generated from the model").version("1"))
                .paths(OASFactory.createPaths()
                        .addPathItem("/db/{name}", OASFactory.createPathItem().GET(OASFactory.createOperation().operationId("rows").summary("count rows of a datasource"))));
    }
}
