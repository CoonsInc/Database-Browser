package org.databasebrowser.core.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import org.databasebrowser.core.domain.Schema.QualifiedName;

/**
 * Типы плана запроса, полученного через {@code explain}. Модель сохраняет {@code rawOutput}
 * вместе с {@code rawFormat}, чтобы неподдерживаемые адаптером узлы не терялись. Используйте
 * {@code NodeType.UNKNOWN} и {@code originalType} для узлов, которые не удалось классифицировать.
 */
public final class QueryPlans {
    private QueryPlans() {}

    /** Режим получения плана: оценка {@code ESTIMATED} или сбор фактической статистики {@code ANALYZE}. */
    public enum Mode { ESTIMATED, ANALYZE }

    /**
     * Нормализованный тип узла плана. {@code UNKNOWN} означает, что адаптер вернул неизвестный
     * тип; исходное название хранится в {@code PlanNode.originalType}.
     */
    public enum NodeType { SCAN, INDEX_SCAN, SEARCH, JOIN, SORT, AGGREGATE, FILTER, OTHER, UNKNOWN }

    /**
     * Метрики узла: оценочные строки и стоимость либо фактические строки и время.
     * {@code Optional} пусты, если адаптер не вернул соответствующее значение.
     */
    public record Metrics(Optional<BigDecimal> estimatedRows, Optional<BigDecimal> cost,
        OptionalLong actualRows, OptionalDouble actualMillis) {}
    /**
     * Узел дерева плана с дочерними узлами {@code children}. Сохраняйте {@code originalType} и
     * {@code attributes}, даже если тип сведён к {@code NodeType.OTHER} или
     * {@code NodeType.UNKNOWN}: это позволяет восстановить вывод адаптера без потери информации.
     */
    public record
        PlanNode(String id, NodeType type, String originalType, Optional<QualifiedName> table,
            Optional<QualifiedName> index, Optional<String> condition, String description,
            Metrics metrics, Map<String, String> attributes, List<PlanNode> children) {
        public PlanNode {
            attributes = Map.copyOf(attributes);
            children = List.copyOf(children);
        }
    }

    /**
     * План запроса с корнями {@code roots} и исходным выводом {@code rawOutput} в формате
     * {@code rawFormat}. {@code rawOutput} сохраняется без изменений: нормализованные узлы могут
     * быть неполными, а неизвестные узлы остаются через {@code NodeType.UNKNOWN} и
     * {@code originalType}. Дополнительные данные адаптера хранятся в {@code attributes}.
     */
    public record QueryPlan(Mode mode, List<PlanNode> roots, String rawOutput, String rawFormat,
        Map<String, String> attributes) {
        public QueryPlan {
            roots = List.copyOf(roots);
            attributes = Map.copyOf(attributes);
        }
    }
}
