package org.umlg.sqlg.strategy;

import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.*;
import org.apache.tinkerpop.gremlin.process.traversal.step.HasContainerHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.PathStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.TreeStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.IdentityStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.TreeSideEffectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.HasContainer;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.AndP;
import org.apache.tinkerpop.gremlin.process.traversal.util.OrP;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;
import org.apache.tinkerpop.gremlin.structure.T;
import org.umlg.sqlg.predicate.Text;
import org.umlg.sqlg.sql.parse.ReplacedStep;

import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Created by pieter on 2015/07/19.
 */
public abstract class BaseSqlgStrategy extends AbstractTraversalStrategy<TraversalStrategy.OptimizationStrategy> implements TraversalStrategy.OptimizationStrategy {

    private static final List<BiPredicate> SUPPORTED_BI_PREDICATE = Arrays.asList(
            Compare.eq, Compare.neq, Compare.gt, Compare.gte, Compare.lt, Compare.lte);

    protected boolean mayNotBeOptimized(List<Step> steps, int index) {
        List<Step> toCome = steps.subList(index, steps.size());
        return toCome.stream().anyMatch(s ->
                s.getClass().equals(PathStep.class) ||
                        s.getClass().equals(TreeStep.class) ||
                        s.getClass().equals(TreeSideEffectStep.class) ||
                        s.getClass().equals(Order.class));
    }

    protected void collectHasSteps(ListIterator<Step> iterator, Traversal.Admin<?, ?> traversal, ReplacedStep<?, ?> replacedStep) {
        //Collect the hasSteps
        while (iterator.hasNext()) {
            Step<?, ?> currentStep = iterator.next();
            if (currentStep instanceof HasContainerHolder &&
                    (isSingleBiPredicate(((HasContainerHolder) currentStep).getHasContainers()) ||
                            isBetween(((HasContainerHolder) currentStep).getHasContainers()) ||
                            isInside(((HasContainerHolder) currentStep).getHasContainers()) ||
                            isOutside(((HasContainerHolder) currentStep).getHasContainers()) ||
                            isWithinOut(((HasContainerHolder) currentStep).getHasContainers()) ||
                            isTextContains(((HasContainerHolder) currentStep).getHasContainers()))) {
                if (!currentStep.getLabels().isEmpty()) {
                    final IdentityStep identityStep = new IdentityStep<>(traversal);
                    currentStep.getLabels().forEach(replacedStep::addLabel);
                    TraversalHelper.insertAfterStep(identityStep, currentStep, traversal);
                }
                iterator.remove();
                traversal.removeStep(currentStep);
                replacedStep.getHasContainers().addAll(((HasContainerHolder) currentStep).getHasContainers());
            } else if (currentStep instanceof IdentityStep) {
                // do nothing
            } else {
                iterator.previous();
                break;
            }
        }
    }

    private boolean isSingleBiPredicate(List<HasContainer> hasContainers) {
        if (hasContainers.size() == 1) {
            return SUPPORTED_BI_PREDICATE.contains(hasContainers.get(0).getBiPredicate());
        } else {
            return false;
        }
    }

    private boolean isBetween(List<HasContainer> hasContainers) {
        if (hasContainers.size() == 2) {
            HasContainer hasContainer1 = hasContainers.get(0);
            HasContainer hasContainer2 = hasContainers.get(1);
            return hasContainer1.getBiPredicate().equals(Compare.gte) && hasContainer2.getBiPredicate().equals(Compare.lt);
        } else {
            return false;
        }
    }

    private boolean isInside(List<HasContainer> hasContainers) {
        if (hasContainers.size() == 2) {
            HasContainer hasContainer1 = hasContainers.get(0);
            HasContainer hasContainer2 = hasContainers.get(1);
            return hasContainer1.getBiPredicate().equals(Compare.gt) && hasContainer2.getBiPredicate().equals(Compare.lt);
        } else {
            return false;
        }
    }

    private <V> boolean isOutside(List<HasContainer> hasContainers) {
        if (hasContainers.size() == 1 && hasContainers.get(0).getPredicate() instanceof OrP) {
            OrP<V> orP = (OrP) hasContainers.get(0).getPredicate();
            if (orP.getPredicates().size() == 2) {
                P<V> predicate1 = orP.getPredicates().get(0);
                P<V> predicate2 = orP.getPredicates().get(1);
                return predicate1.getBiPredicate().equals(Compare.lt) && predicate2.getBiPredicate().equals(Compare.gt);
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    private boolean isWithinOut(List<HasContainer> hasContainers) {
        return (hasContainers.size() == 1 && !hasContainers.get(0).getKey().equals(T.label.getAccessor()) &&
                !hasContainers.get(0).getKey().equals(T.id.getAccessor()) &&
                (hasContainers.get(0).getBiPredicate() == Contains.without || hasContainers.get(0).getBiPredicate() == Contains.within));
    }

    private boolean isTextContains(List<HasContainer> hasContainers) {
        return (hasContainers.size() == 1 && !hasContainers.get(0).getKey().equals(T.label.getAccessor()) &&
                !hasContainers.get(0).getKey().equals(T.id.getAccessor()) &&
                (hasContainers.get(0).getBiPredicate() == Text.contains ||
                        hasContainers.get(0).getBiPredicate() == Text.ncontains ||
                        hasContainers.get(0).getBiPredicate() == Text.containsCIS ||
                        hasContainers.get(0).getBiPredicate() == Text.ncontainsCIS ||
                        hasContainers.get(0).getBiPredicate() == Text.startsWith ||
                        hasContainers.get(0).getBiPredicate() == Text.nstartsWith ||
                        hasContainers.get(0).getBiPredicate() == Text.endsWith ||
                        hasContainers.get(0).getBiPredicate() == Text.nendsWith
                ));
    }

}
