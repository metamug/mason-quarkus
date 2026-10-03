package io.mq.core.parser;

import java.util.List;

import io.mq.core.model.Model;

/** the outcome of parsing one file: a model when there are no problems, otherwise the list of problems (never both) */
public record ParseResult(String file, Model.Resource resource, List<Problem> problems) {

    public boolean valid() {
        return problems.isEmpty();
    }
}
