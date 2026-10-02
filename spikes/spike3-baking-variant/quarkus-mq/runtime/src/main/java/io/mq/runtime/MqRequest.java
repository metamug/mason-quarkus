package io.mq.runtime;

import java.util.ArrayList;
import java.util.List;

public class MqRequest {
    public String method;
    public boolean item;
    public int status;
    public List<MqStep> steps = new ArrayList<>();
}
