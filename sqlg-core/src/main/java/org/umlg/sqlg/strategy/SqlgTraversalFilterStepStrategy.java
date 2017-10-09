package org.umlg.sqlg.strategy;

import com.google.common.base.Preconditions;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.ReducingBarrierStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.umlg.sqlg.step.SqlgTraversalFilterStepBarrier;
import org.umlg.sqlg.structure.SqlgGraph;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Pieter Martin (https://github.com/pietermartin)
 * Date: 2014/08/15
 */
public class SqlgTraversalFilterStepStrategy<S> extends AbstractTraversalStrategy<TraversalStrategy.OptimizationStrategy> implements TraversalStrategy.OptimizationStrategy {

    public SqlgTraversalFilterStepStrategy() {
        super();
    }

    @Override
    public void apply(final Traversal.Admin<?, ?> traversal) {
        //Only optimize SqlgGraph. StarGraph also passes through here.
        //noinspection OptionalGetWithoutIsPresent
        if (!(traversal.getGraph().get() instanceof SqlgGraph)) {
            return;
        }
        List<TraversalFilterStep> traversalFilterSteps = TraversalHelper.getStepsOfAssignableClass(TraversalFilterStep.class, traversal);
        for (TraversalFilterStep<S> traversalFilterStep : traversalFilterSteps) {

            List<Traversal.Admin<S, ?>> filterTraversals = traversalFilterStep.getLocalChildren();
            Preconditions.checkState(filterTraversals.size() == 1);
            Traversal.Admin<S, ?> filterTraversal = filterTraversals.get(0);

            //whats this for again
            List<ReducingBarrierStep> reducingBarrierSteps = TraversalHelper.getStepsOfAssignableClass(ReducingBarrierStep.class, filterTraversal);
            if (!reducingBarrierSteps.isEmpty()) {
                continue;
            }

            SqlgTraversalFilterStepBarrier sqlgTraversalFilterStepBarrier = new SqlgTraversalFilterStepBarrier<>(
                    traversal,
                    filterTraversal
            );
            for (String label : traversalFilterStep.getLabels()) {
                sqlgTraversalFilterStepBarrier.addLabel(label);
            }
            TraversalHelper.replaceStep(
                    traversalFilterStep,
                    sqlgTraversalFilterStepBarrier,
                    traversalFilterStep.getTraversal()
            );
        }
    }

    @Override
    public Set<Class<? extends OptimizationStrategy>> applyPost() {
        return Stream.of(
                SqlgVertexStepStrategy.class
        ).collect(Collectors.toSet());
    }

    @Override
    public Set<Class<? extends OptimizationStrategy>> applyPrior() {
        return Stream.of(
                SqlgGraphStepStrategy.class
        ).collect(Collectors.toSet());
    }

}