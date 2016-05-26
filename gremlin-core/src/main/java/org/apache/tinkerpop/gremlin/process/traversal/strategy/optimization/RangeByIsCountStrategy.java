/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization;

import org.apache.tinkerpop.gremlin.process.traversal.Compare;
import org.apache.tinkerpop.gremlin.process.traversal.Contains;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.IsStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.NotStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.RangeGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.CountGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.sideEffect.SideEffectStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.ConnectiveP;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * This strategy optimizes any occurrence of {@link CountGlobalStep} followed by an {@link IsStep}. The idea is to limit
 * the number of incoming elements in a way that it's enough for the {@link IsStep} to decide whether it evaluates
 * {@code true} or {@code false}. If the traversal already contains a user supplied limit, the strategy won't
 * modify it.
 *
 * @author Daniel Kuppitz (http://gremlin.guru)
 * @example <pre>
 * __.outE().count().is(0)      // is replaced by __.not(outE())
 * __.outE().count().is(lt(3))  // is replaced by __.outE().limit(3).count().is(lt(3))
 * __.outE().count().is(gt(3))  // is replaced by __.outE().limit(4).count().is(gt(3))
 * </pre>
 */
public final class RangeByIsCountStrategy extends AbstractTraversalStrategy<TraversalStrategy.OptimizationStrategy> implements TraversalStrategy.OptimizationStrategy {

    private static final Map<BiPredicate, Long> RANGE_PREDICATES = new HashMap<BiPredicate, Long>() {{
        put(Contains.within, 1L);
        put(Contains.without, 0L);
    }};
    private static final Set<Compare> INCREASED_OFFSET_SCALAR_PREDICATES =
            EnumSet.of(Compare.eq, Compare.neq, Compare.lte, Compare.gt);

    private static final RangeByIsCountStrategy INSTANCE = new RangeByIsCountStrategy();

    private RangeByIsCountStrategy() {
    }

    @Override
    public void apply(final Traversal.Admin<?, ?> traversal) {
        final TraversalParent parent = traversal.getParent();
        int size = traversal.getSteps().size();
        Step prev = null;
        for (int i = 0; i < size; i++) {
            final Step curr = traversal.getSteps().get(i);
            if (curr instanceof CountGlobalStep && i < size - 1) {
                final Step next = traversal.getSteps().get(i + 1);
                if (next instanceof IsStep && !(prev instanceof RangeGlobalStep)) { // if a RangeStep was provided, assume that the user knows what he's doing
                    final IsStep isStep = (IsStep) next;
                    final P isStepPredicate = isStep.getPredicate();
                    Long highRange = null;
                    boolean useNotStep = false;
                    for (P p : isStepPredicate instanceof ConnectiveP ? ((ConnectiveP<?>) isStepPredicate).getPredicates() : Collections.singletonList(isStepPredicate)) {
                        final Object value = p.getValue();
                        final BiPredicate predicate = p.getBiPredicate();
                        if (value instanceof Number) {
                            final long highRangeOffset = INCREASED_OFFSET_SCALAR_PREDICATES.contains(predicate) ? 1L : 0L;
                            final Long highRangeCandidate = ((Number) value).longValue() + highRangeOffset;
                            final boolean update = highRange == null || highRangeCandidate > highRange;
                            if (update) {
                                final boolean isNested = !(parent instanceof EmptyStep);
                                highRange = highRangeCandidate;
                                useNotStep = curr.getLabels().isEmpty() && next.getLabels().isEmpty()
                                        && (!isNested || parent instanceof SideEffectStep)
                                        && next.getNextStep() instanceof EmptyStep
                                        && ((highRange <= 1L && predicate.equals(Compare.lt))
                                        || (highRange == 1L && (predicate.equals(Compare.eq) || predicate.equals(Compare.lte))));
                            }
                        } else {
                            final Long highRangeOffset = RANGE_PREDICATES.get(predicate);
                            if (value instanceof Collection && highRangeOffset != null) {
                                final Object high = Collections.max((Collection) value);
                                if (high instanceof Number) {
                                    final Long highRangeCandidate = ((Number) high).longValue() + highRangeOffset;
                                    final boolean update = highRange == null || highRangeCandidate > highRange;
                                    if (update) highRange = highRangeCandidate;
                                }
                            }
                        }
                    }
                    if (highRange != null) {
                        if (useNotStep) {
                            traversal.asAdmin().removeStep(next); // IsStep
                            traversal.asAdmin().removeStep(curr); // CountStep
                            size -= 2;
                            if (prev != null) {
                                final Traversal.Admin inner = __.start().asAdmin();
                                TraversalHelper.insertAfterStep(prev, inner.getStartStep(), inner);
                                TraversalHelper.replaceStep(prev, new NotStep<>(traversal, inner), traversal);
                            } else {
                                traversal.asAdmin().addStep(new NotStep<>(traversal, __.identity()));
                            }
                        } else {
                            TraversalHelper.insertBeforeStep(new RangeGlobalStep<>(traversal, 0L, highRange), curr, traversal);
                        }
                        i++;
                    }
                }
            }
            prev = curr;
        }
    }

    public static RangeByIsCountStrategy instance() {
        return INSTANCE;
    }
}
