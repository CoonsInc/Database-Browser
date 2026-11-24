package org.databasebrowser.core.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.databasebrowser.core.domain.Schema.QualifiedName;

public final class QueryPlans {
    private QueryPlans() {}

    public enum Mode { ESTIMATED, ANALYZE }

    public enum NodeType { SCAN, INDEX_SCAN, SEARCH, JOIN, SORT, AGGREGATE, FILTER, OTHER, UNKNOWN }

    public record Metrics(Optional<BigDecimal> estimatedRows, Optional<BigDecimal> cost,
        OptionalLong actualRows, OptionalDouble actualMillis) {}
    public record
        PlanNode(String id, NodeType type, String originalType, Optional<QualifiedName> table,
            Optional<QualifiedName> index, Optional<String> condition, String description,
            Metrics metrics, Map<String, String> attributes, List<PlanNode> children) {
        public PlanNode {
            attributes = Map.copyOf(attributes);
            children = List.copyOf(children);
        }
    }

    public record QueryPlan(Mode mode, List<PlanNode> roots, String rawOutput, String rawFormat,
        Map<String, String> attributes) {
        public QueryPlan {
            roots = List.copyOf(roots);
            attributes = Map.copyOf(attributes);
        }
    }
}
