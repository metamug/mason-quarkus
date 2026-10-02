package io.mq.runtime;

import java.util.ArrayList;
import java.util.List;

public class MqResource {
    public String name;
    public String version;
    public List<MqRequest> requests = new ArrayList<>();
}
