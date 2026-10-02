package io.mq.runtime;

import java.util.ArrayList;
import java.util.List;

/** Object model of the resource XML files. Built at build time, recorded into the application's startup bytecode. */
public class MqModel {
    public List<MqResource> resources = new ArrayList<>();
}
