/*
 * Copyright (c) 2012, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.graal.compiler.nodes.loop;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.Equivalence;

import jdk.graal.compiler.core.common.type.IntegerStamp;
import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.debug.DebugCloseable;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.graph.Graph.DuplicationReplacement;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.graph.NodeBitMap;
import jdk.graal.compiler.graph.Position;
import jdk.graal.compiler.graph.iterators.NodeIterable;
import jdk.graal.compiler.loop.phases.LoopTransformations;
import jdk.graal.compiler.nodeinfo.InputType;
import jdk.graal.compiler.nodes.AbstractBeginNode;
import jdk.graal.compiler.nodes.AbstractEndNode;
import jdk.graal.compiler.nodes.AbstractMergeNode;
import jdk.graal.compiler.nodes.BeginNode;
import jdk.graal.compiler.nodes.ConstantNode;
import jdk.graal.compiler.nodes.EndNode;
import jdk.graal.compiler.nodes.FixedNode;
import jdk.graal.compiler.nodes.FixedWithNextNode;
import jdk.graal.compiler.nodes.FrameState;
import jdk.graal.compiler.nodes.GraphState;
import jdk.graal.compiler.nodes.GuardNode;
import jdk.graal.compiler.nodes.GuardPhiNode;
import jdk.graal.compiler.nodes.GuardProxyNode;
import jdk.graal.compiler.nodes.IfNode;
import jdk.graal.compiler.nodes.LogicNode;
import jdk.graal.compiler.nodes.LoopBeginNode;
import jdk.graal.compiler.nodes.LoopEndNode;
import jdk.graal.compiler.nodes.LoopExitNode;
import jdk.graal.compiler.nodes.MemoryProxyNode;
import jdk.graal.compiler.nodes.MergeNode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.PhiNode;
import jdk.graal.compiler.nodes.ProxyNode;
import jdk.graal.compiler.nodes.SafepointNode;
import jdk.graal.compiler.nodes.StateSplit;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.ValuePhiNode;
import jdk.graal.compiler.nodes.ValueProxyNode;
import jdk.graal.compiler.nodes.VirtualState;
import jdk.graal.compiler.nodes.calc.AddNode;
import jdk.graal.compiler.nodes.calc.CompareNode;
import jdk.graal.compiler.nodes.calc.ConditionalNode;
import jdk.graal.compiler.nodes.calc.IntegerBelowNode;
import jdk.graal.compiler.nodes.calc.SubNode;
import jdk.graal.compiler.nodes.extended.AnchoringNode;
import jdk.graal.compiler.nodes.extended.GuardingNode;
import jdk.graal.compiler.nodes.extended.OpaqueNode;
import jdk.graal.compiler.nodes.extended.OpaqueValueNode;
import jdk.graal.compiler.nodes.extended.ValueAnchorNode;
import jdk.graal.compiler.nodes.memory.MemoryKill;
import jdk.graal.compiler.nodes.memory.MemoryPhiNode;
import jdk.graal.compiler.nodes.util.GraphUtil;
import jdk.graal.compiler.nodes.util.IntegerHelper;

public class LoopFragmentInside extends LoopFragment {

    /**
     * mergedInitializers. When an inside fragment's (loop)ends are merged to create a unique exit
     * point, some phis must be created : they phis together all the back-values of the loop-phis
     * These can then be used to update the loop-phis' forward edge value ('initializer') in the
     * peeling case. In the unrolling case they will be used as the value that replace the loop-phis
     * of the duplicated inside fragment
     */
    private EconomicMap<PhiNode, ValueNode> mergedInitializers;
    private final DuplicationReplacement dataFixBefore = new DuplicationReplacement() {

        @Override
        public Node replacement(Node oriInput) {
            if (!(oriInput instanceof ValueNode)) {
                return oriInput;
            }
            return prim((ValueNode) oriInput);
        }
    };

    private final DuplicationReplacement dataFixWithinAfter = new DuplicationReplacement() {

        @Override
        public Node replacement(Node oriInput) {
            if (!(oriInput instanceof ValueNode)) {
                return oriInput;
            }
            return primAfter((ValueNode) oriInput);
        }
    };

    public LoopFragmentInside(Loop loop) {
        super(loop);
    }

    public LoopFragmentInside(LoopFragmentInside original) {
        super(null, original);
    }

    @Override
    public LoopFragmentInside duplicate() {
        assert !isDuplicate();
        return new LoopFragmentInside(this);
    }

    @Override
    public LoopFragmentInside original() {
        return (LoopFragmentInside) super.original();
    }

    @SuppressWarnings("unused")
    public void appendInside(Loop loop) {
        GraalError.unimplemented("intentional"); // ExcludeFromJacocoGeneratedReport
    }

    @Override
    public Loop loop() {
        assert !this.isDuplicate();
        return super.loop();
    }

    /**
     * Collects all floating guards with outside anchors.
     *
     * Floating guards and loop peeling: when peeling loops while we have floating guards we have to
     * take special care about the position of a guard after peeling. A guard has 2 things that
     * define its position: the anchor and the condition. Both are relevant to execute the guard at
     * the correct point in time. The correct point is as early as possible without being to eager.
     * Peeling can now "free" guards of their conditional scheduling position. What does that mean?
     *
     * Consider the following loop
     *
     * <pre>
     * int phi = 0;
     * int sum = 0;
     * while (true) {
     *     if (phi > 0) {
     *         // Guard of the second iteration should not float before the
     *         // first
     *         // break
     *         GraalDirectives.deoptimizeAndInvalidate();
     *     }
     *     phi = (int) Math.tan(phi + 1);
     *     sum += phi;
     *     if (flag) {
     *         break;
     *     }
     * }
     * </pre>
     *
     * The position of the guard is determined by the scheduling of the inputs: that is the phi. The
     * anchor is already outside of the loop. If we peel this loop now 2 times and duplicate the
     * guard, the fact that the phi input is gone lets the guard float up above the original part of
     * the loop's previous iteration. That means we have 2 duplicates of this guard one that evals
     * the condition {@code 0>0} which evaluates to {@code false}. The other one, of iteration 1
     * evals then the condition {@code 1>0} which is true and thus unconditionally will yield a
     * deopt. That guard however can float to the beginning of the method and cause too eager
     * deopts. The fact that peeling replaces phi nodes with their inputs at the respective
     * iteration "frees" the guards of their scheduling positions. Thus, we manually inject the
     * anchor to be the end of the last iteration that was peeled. That naturally is the correct
     * position. Later optimization can specualtively move guards further.
     */
    private static NodeBitMap collectExistingGuardsWithOutsideAnchors(StructuredGraph graph, Loop loop) {
        NodeBitMap guardsWithOutsideAnchors = null;
        if (graph.getGuardsStage().allowsFloatingGuards()) {
            for (Node inside : loop.inside().nodes()) {
                if (inside instanceof GuardNode g) {
                    // the anchor is already outside the loop
                    if (!loop.whole().contains(g.getAnchor().asNode())) {
                        if (guardsWithOutsideAnchors == null) {
                            guardsWithOutsideAnchors = graph.createNodeBitMap();
                        }
                        guardsWithOutsideAnchors.mark(g);
                    }
                }
            }
        }
        return guardsWithOutsideAnchors;
    }

    private static void reconnectAnchors(Loop loop, LoopFragment fragment, StructuredGraph graph, NodeBitMap guardsWithOutsideAnchors) {
        graph.getDebug().dump(DebugContext.VERY_DETAILED_LEVEL, graph, "Before correcting floating guard anchors for loop %s", loop.loopBegin());
        AnchoringNode newAnchor = AbstractBeginNode.prevBegin(fragment.getDuplicatedNode(loop.loopBegin()));
        for (Node originalGuard : guardsWithOutsideAnchors) {
            assert originalGuard instanceof GuardNode : Assertions.errorMessage(originalGuard, guardsWithOutsideAnchors);
            GuardNode g = (GuardNode) originalGuard;
            GuardNode duplicate = (GuardNode) fragment.getDuplicatedNode(g.asNode());
            if (duplicate != null) {
                duplicate.setAnchor(newAnchor);
            }
        }
        graph.getDebug().dump(DebugContext.VERY_DETAILED_LEVEL, graph, "After correcting floating guard anchors for loop %s", loop.loopBegin());
    }

    @Override
    public void insertBefore(Loop loop) {
        StructuredGraph graph = loop.loopBegin().graph();
        NodeBitMap guardsWithOutsideAnchors = collectExistingGuardsWithOutsideAnchors(graph, loop);

        assert this.isDuplicate();
        assert this.original().loop() == loop : "Original loop " + this.original().loop() + " != " + loop;

        patchNodes(dataFixBefore);

        AbstractBeginNode end = mergeEnds();

        mergeEarlyExits();

        original().patchPeeling(this);

        AbstractBeginNode entry = getDuplicatedNode(loop.loopBegin());
        loop.entryPoint().replaceAtPredecessor(entry);
        end.setNext(loop.entryPoint());

        if (graph.getGuardsStage().allowsFloatingGuards() && guardsWithOutsideAnchors != null) {
            reconnectAnchors(loop, this, graph, guardsWithOutsideAnchors);
        }
    }

    /**
     * Duplicate the body within the loop after the current copy copy of the body, updating the
     * iteration limit to account for the duplication.
     */
    public void insertWithinAfter(Loop loop, EconomicMap<LoopBeginNode, OpaqueNode> opaqueUnrolledStrides) {
        assert isDuplicate();
        assert original().loop() == loop : original().loop() + "!=" + loop;

        patchNodes(dataFixWithinAfter);

        /*
         * Collect any new back edges values before updating them since they might reference each
         * other.
         */
        LoopBeginNode mainLoopBegin = loop.loopBegin();
        ArrayList<ValueNode> backedgeValues = new ArrayList<>();
        EconomicMap<Node, Node> new2OldPhis = EconomicMap.create();
        EconomicMap<Node, Node> originalPhi2Backedges = EconomicMap.create();
        for (PhiNode mainPhiNode : mainLoopBegin.phis()) {
            originalPhi2Backedges.put(mainPhiNode, mainPhiNode.valueAt(1));
        }
        for (PhiNode mainPhiNode : mainLoopBegin.phis()) {
            ValueNode originalNode = mainPhiNode.valueAt(1);
            ValueNode duplicatedNode = getDuplicatedNode(originalNode);
            if (duplicatedNode == null) {
                if (mainLoopBegin.isPhiAtMerge(originalNode)) {
                    duplicatedNode = ((PhiNode) (originalNode)).valueAt(1);
                } else {
                    assert originalNode.isConstant() || loop.isOutsideLoop(originalNode) : "Not duplicated node " + originalNode;
                }
            }
            if (duplicatedNode != null) {
                new2OldPhis.put(duplicatedNode, originalNode);
            }
            backedgeValues.add(duplicatedNode);
        }
        int index = 0;
        for (PhiNode mainPhiNode : mainLoopBegin.phis().snapshot()) {
            ValueNode duplicatedNode = backedgeValues.get(index++);
            if (duplicatedNode != null) {
                mainPhiNode.setValueAt(1, duplicatedNode);
            }
        }

        CompareNode condition = placeNewSegmentAndCleanup(loop, new2OldPhis, originalPhi2Backedges);

        // Remove any safepoints from the original copy leaving only the duplicated one, inverted
        // ones have their safepoint between the limit check and the backedge that have been removed
        // already.
        assert loop.whole().nodes().filter(SafepointNode.class).count() == nodes().filter(SafepointNode.class).count() ||
                        loop.counted.isInverted() : "No safepoints left in the original loop or the loop is inverted " + loop;
        for (SafepointNode safepoint : loop.whole().nodes().filter(SafepointNode.class)) {
            graph().removeFixed(safepoint);
        }

        StructuredGraph graph = mainLoopBegin.graph();
        if (opaqueUnrolledStrides != null) {
            OpaqueNode opaque = opaqueUnrolledStrides.get(loop.loopBegin());
            CountedLoopInfo counted = loop.counted();
            ValueNode counterStride = counted.getLimitCheckedIV().strideNode();
            if (opaque == null || opaque.isDeleted()) {
                ValueNode limit = counted.getLimit();
                opaque = new OpaqueValueNode(AddNode.add(counterStride, counterStride, NodeView.DEFAULT));
                ValueNode newLimit = partialUnrollOverflowCheck(opaque, limit, counted);
                GraalError.guarantee(condition.hasExactlyOneUsage(),
                                "Unrolling loop %s with condition %s, which has multiple usages. Usages other than the loop exit check would get an incorrect condition.", loop.loopBegin(), condition);
                condition.replaceFirstInput(limit, graph.addOrUniqueWithInputs(newLimit));
                opaqueUnrolledStrides.put(loop.loopBegin(), opaque);
            } else {
                assert counted.getLimitCheckedIV().isConstantStride();
                assert !LoopTransformations.strideAdditionOverflows(loop) : "Stride addition must not overflow";
                ValueNode previousValue = opaque.getValue();
                opaque.setValue(graph.addOrUniqueWithInputs(AddNode.add(counterStride, previousValue, NodeView.DEFAULT)));
                GraphUtil.tryKillUnused(previousValue);
            }
        }
        mainLoopBegin.setUnrollFactor(mainLoopBegin.getUnrollFactor() * 2);
        graph.getDebug().dump(DebugContext.DETAILED_LEVEL, graph, "LoopPartialUnroll %s", loop);

        mainLoopBegin.getDebug().dump(DebugContext.VERBOSE_LEVEL, mainLoopBegin.graph(), "After insertWithinAfter %s", mainLoopBegin);
    }

    public static ValueNode partialUnrollOverflowCheck(OpaqueNode opaque, ValueNode limit, CountedLoopInfo counted) {
        int bits = ((IntegerStamp) limit.stamp(NodeView.DEFAULT)).getBits();
        ValueNode newLimit = SubNode.create(limit, opaque, NodeView.DEFAULT);
        IntegerHelper helper = counted.getCounterIntegerHelper();
        LogicNode overflowCheck;
        ConstantNode extremum;
        if (counted.getDirection() == InductionVariable.Direction.Up) {
            // limit - counterStride could overflow negatively if limit - min <
            // counterStride
            extremum = ConstantNode.forIntegerBits(bits, helper.minValue());
            overflowCheck = IntegerBelowNode.create(SubNode.create(limit, extremum, NodeView.DEFAULT), opaque, NodeView.DEFAULT);
        } else {
            assert counted.getDirection() == InductionVariable.Direction.Down : counted.getDirection();
            // limit - counterStride could overflow if max - limit < -counterStride
            // i.e., counterStride < limit - max
            extremum = ConstantNode.forIntegerBits(bits, helper.maxValue());
            overflowCheck = IntegerBelowNode.create(opaque, SubNode.create(limit, extremum, NodeView.DEFAULT), NodeView.DEFAULT);
        }
        return ConditionalNode.create(overflowCheck, extremum, newLimit, NodeView.DEFAULT);
    }

    protected CompareNode placeNewSegmentAndCleanup(Loop loop, EconomicMap<Node, Node> new2OldPhis, @SuppressWarnings("unused") EconomicMap<Node, Node> originalPhi2Backedges) {
        CountedLoopInfo mainCounted = loop.counted();
        LoopBeginNode mainLoopBegin = loop.loopBegin();
        // Discard the segment entry and its flow, after if merging it into the loop
        StructuredGraph graph = mainLoopBegin.graph();
        IfNode loopTest = mainCounted.getLimitTest();
        IfNode newSegmentLoopTest = getDuplicatedNode(loopTest);

        graph.getDebug().dump(DebugContext.DETAILED_LEVEL, graph, "After duplicating segment");

        if (mainCounted.getBody() != loop.loopBegin()) {
            // regular loop
            Node predecessor = newSegmentLoopTest.predecessor();
            while (predecessor instanceof FixedWithNextNode fixedPredecessor) {
                for (Node usage : fixedPredecessor.usages().snapshot()) {
                    usage.replaceFirstInput(fixedPredecessor, loopTest.predecessor());
                }
                predecessor = fixedPredecessor.predecessor();
            }
            AbstractBeginNode falseSuccessor = newSegmentLoopTest.falseSuccessor();
            for (Node usage : falseSuccessor.anchored().snapshot()) {
                usage.replaceFirstInput(falseSuccessor, loopTest.falseSuccessor());
            }
            AbstractBeginNode trueSuccessor = newSegmentLoopTest.trueSuccessor();
            for (Node usage : trueSuccessor.anchored().snapshot()) {
                usage.replaceFirstInput(trueSuccessor, loopTest.trueSuccessor());
            }

            graph.getDebug().dump(DebugContext.DETAILED_LEVEL, graph, "After stitching new segment into control flow after existing one");

            assert graph.isBeforeStage(GraphState.StageFlag.VALUE_PROXY_REMOVAL) || mainLoopBegin.loopExits().count() <= 1 : "Can only merge early loop exits if graph has value proxies " +
                            mainLoopBegin;

            mergeEarlyLoopExits(graph, mainLoopBegin, mainCounted, new2OldPhis, loop);

            // remove if test
            graph.removeSplitPropagate(newSegmentLoopTest, loopTest.trueSuccessor() == mainCounted.getBody() ? trueSuccessor : falseSuccessor);

            graph.getDebug().dump(DebugContext.DETAILED_LEVEL, graph, "Before placing segment");
            if (mainCounted.getBody().next() instanceof LoopEndNode && mainCounted.getLimitTest().predecessor() == mainCounted.loop.loopBegin()) {
                /**
                 * We assume here that the body of the loop is completely empty, i.e., we assume
                 * that there is no control flow in the counted loop body. This however means that
                 * we also did not have any code between the loop header and the counted begin (we
                 * allow a few special nodes there). Else we would be killing nodes that are as well
                 * between - that potentially could be used by loop phis (which we also disallow).
                 * Thus, just be safe here and ensure we really see the pattern we are expect namely
                 * a completely empty (fixed nodes) loop body.
                 */
                GraphUtil.killCFG(getDuplicatedNode(mainLoopBegin));
            } else {
                AbstractBeginNode newSegmentBegin = getDuplicatedNode(mainLoopBegin);
                FixedNode newSegmentFirstNode = newSegmentBegin.next();
                EndNode newSegmentEnd = getBlockEnd((FixedNode) getDuplicatedNode(mainLoopBegin.loopEnds().first().predecessor()));
                FixedWithNextNode newSegmentLastNode = (FixedWithNextNode) newSegmentEnd.predecessor();
                LoopEndNode loopEndNode = mainLoopBegin.getSingleLoopEnd();
                FixedWithNextNode lastCodeNode = (FixedWithNextNode) loopEndNode.predecessor();

                newSegmentBegin.clearSuccessors();
                if (newSegmentBegin.hasAnchored()) {
                    /*
                     * LoopPartialUnrollPhase runs after guard lowering, thus we cannot see any
                     * floating guards here except multi-guard nodes (pointing to abstract begins)
                     * and other anchored nodes. We need to ensure anything anchored on the original
                     * loop begin will be anchored on the unrolled iteration. Thus we create an
                     * anchor point here ensuring nothing can flow above the original iteration.
                     */
                    if (!(lastCodeNode instanceof GuardingNode) || !(lastCodeNode instanceof AnchoringNode)) {
                        ValueAnchorNode newAnchoringPointAfterPrevIteration = graph.add(new ValueAnchorNode());
                        graph.addAfterFixed(lastCodeNode, newAnchoringPointAfterPrevIteration);
                        lastCodeNode = newAnchoringPointAfterPrevIteration;
                    }
                    newSegmentBegin.replaceAtUsages(lastCodeNode, InputType.Guard, InputType.Anchor);

                    // at this point only safepoint usages can live here
                    assert newSegmentBegin.usages().filter(x -> !(x instanceof SafepointNode)).count() == 0 : "Must only have safepoint(association) usages left for " + newSegmentBegin + " usages=" +
                                    newSegmentBegin.usages();
                    newSegmentBegin.replaceAtUsages(mainLoopBegin, InputType.Association);
                }
                lastCodeNode.replaceFirstSuccessor(loopEndNode, newSegmentFirstNode);
                newSegmentLastNode.replaceFirstSuccessor(newSegmentEnd, loopEndNode);

                newSegmentBegin.safeDelete();
                newSegmentEnd.safeDelete();
            }
            graph.getDebug().dump(DebugContext.DETAILED_LEVEL, graph, "After placing segment");
            return (CompareNode) loopTest.condition();
        } else {
            throw GraalError.shouldNotReachHere("Cannot unroll inverted loop"); // ExcludeFromJacocoGeneratedReport
        }
    }

    /**
     *
     * For counted loops we have a special nomenclature regarding loop exits, the counted loop exit
     * is the regular loop exit after all iterations finished, all other loop exits exit the loop
     * earlier, thus we call them early exits.
     *
     * Merge early, non-counted, loop exits of the loop for unrolling, this currently requires value
     * proxies to properly proxy all values along the way.
     *
     * Unrolling loops with multiple exits is special in the way the exits are handled.
     * Pre-Main-Post creation will merge them.
     */
    protected void mergeEarlyLoopExits(StructuredGraph graph, LoopBeginNode mainLoopBegin, CountedLoopInfo mainCounted, EconomicMap<Node, Node> new2OldPhis, Loop loop) {
        if (mainLoopBegin.loopExits().count() <= 1) {
            return;
        }
        assert graph.isBeforeStage(GraphState.StageFlag.VALUE_PROXY_REMOVAL) : "Unrolling with multiple exits requires proxies";
        // rewire non-counted exits with the follow nodes: merges or sinks
        for (LoopExitNode exit : mainLoopBegin.loopExits().snapshot()) {
            // regular path along we unroll
            if (exit == mainCounted.getCountedExit()) {
                continue;
            }
            FixedNode next = exit.next();
            AbstractBeginNode begin = getDuplicatedNode(exit);
            if (next instanceof EndNode) {
                mergeRegularEarlyExit(next, begin, exit, mainLoopBegin, graph, new2OldPhis, loop);
            } else {
                GraalError.shouldNotReachHere("Can only unroll loops where the early exits which merge " + next + " duplicated node is " + begin + " main loop begin is " + mainLoopBegin); // ExcludeFromJacocoGeneratedReport
            }
        }
    }

    private void mergeRegularEarlyExit(FixedNode next, AbstractBeginNode exitBranchBegin, LoopExitNode oldExit, LoopBeginNode mainLoopBegin, StructuredGraph graph,
                    EconomicMap<Node, Node> new2OldPhis, Loop loop) {
        AbstractMergeNode merge = ((EndNode) next).merge();
        assert merge instanceof MergeNode : "Can only merge loop exits on regular merges";
        assert exitBranchBegin.next() == null;
        LoopExitNode lex = graph.add(new LoopExitNode(mainLoopBegin));
        createExitStateForNewSegmentEarlyExit(graph, oldExit, lex, new2OldPhis);
        EndNode end = graph.add(new EndNode());
        exitBranchBegin.setNext(lex);
        lex.setNext(end);
        merge.addForwardEnd(end);
        for (PhiNode phi : merge.phis()) {
            ValueNode input = phi.valueAt((EndNode) next);
            ValueNode replacement;
            if (!loop.whole().contains(input)) {
                // node is produced above the loop
                replacement = input;
            } else {
                // if the node is inside this loop the input must be a proxy
                replacement = patchProxyAtPhi(phi, lex, getNodeInExitPathFromUnrolledSegment((ProxyNode) input, new2OldPhis));
            }
            phi.addInput(replacement);
        }
    }

    public static ProxyNode patchProxyAtPhi(PhiNode phi, LoopExitNode lex, ValueNode proxyInput) {
        return patchProxyAtPhi(phi, lex, proxyInput, false);
    }

    public static ProxyNode patchProxyAtPhi(PhiNode phi, LoopExitNode lex, ValueNode proxyInput, boolean unrestrictedStamp) {
        if (phi instanceof ValuePhiNode) {
            if (unrestrictedStamp) {
                /*
                 * Delay precise stamp injection to the first time #inferStamp is called on the
                 * value proxy.
                 */
                return phi.graph().addOrUnique(new ValueProxyNode(proxyInput.stamp(NodeView.DEFAULT).unrestricted(), proxyInput, lex));
            } else {
                return phi.graph().addOrUnique(new ValueProxyNode(proxyInput, lex));
            }
        } else if (phi instanceof MemoryPhiNode) {
            return phi.graph().addOrUnique(new MemoryProxyNode((MemoryKill) proxyInput, lex, ((MemoryPhiNode) phi).getLocationIdentity()));
        } else if (phi instanceof GuardPhiNode) {
            return phi.graph().addOrUnique(new GuardProxyNode((GuardingNode) proxyInput, lex));

        } else {
            throw GraalError.shouldNotReachHere("Unknown phi type " + phi); // ExcludeFromJacocoGeneratedReport
        }
    }

    private void createExitStateForNewSegmentEarlyExit(StructuredGraph graph, LoopExitNode exit, LoopExitNode lex, EconomicMap<Node, Node> new2OldPhis) {
        assert exit.stateAfter() != null;
        FrameState exitState = exit.stateAfter();
        FrameState duplicate = exitState.duplicateWithVirtualState();
        graph.getDebug().dump(DebugContext.VERY_DETAILED_LEVEL, graph, "After duplicating state %s for new exit %s", exitState, lex);
        duplicate.applyToNonVirtual(new VirtualState.NodePositionClosure<>() {
            @Override
            public void apply(Node from, Position p) {
                ValueNode to = (ValueNode) p.get(from);
                // all inputs that are proxied need replacing the other ones are implicitly not
                // produced inside this loop
                if (to instanceof ProxyNode) {
                    ProxyNode originalProxy = (ProxyNode) to;
                    if (originalProxy.proxyPoint() == exit) {
                        // create a new proxy for this value
                        ValueNode replacement = getNodeInExitPathFromUnrolledSegment(originalProxy, new2OldPhis);
                        assert replacement != null : originalProxy;
                        p.set(from, originalProxy.duplicateOn(lex, replacement));
                    }
                } else {
                    if (original().contains(to)) {
                        ValueNode replacement = getDuplicatedNode(to);
                        assert replacement != null;
                        p.set(from, replacement);
                    }
                }
            }
        });
        graph.getDebug().dump(DebugContext.VERY_DETAILED_LEVEL, graph, "After duplicating state replacing values with inputs proxied %s", duplicate);
        lex.setStateAfter(duplicate);

    }

    /**
     * Get the value of the original iteration in the unrolled segment.
     */
    private ValueNode getNodeInExitPathFromUnrolledSegment(ProxyNode proxy, EconomicMap<Node, Node> new2OldPhis) {
        ValueNode originalNode = proxy.getOriginalNode();
        ValueNode replacement = null;
        /*
         * Either the node is part of the regular duplicated nodes and is thus in the original node
         * set or its a phi for which we do not have the values of the duplicated iteration at the
         * loop ends
         */
        if (original().contains(originalNode) || proxy.proxyPoint().loopBegin().isPhiAtMerge(originalNode)) {
            ValueNode nextIterationVal = getDuplicatedNode(originalNode);
            if (nextIterationVal == null) {
                assert proxy.proxyPoint().loopBegin().isPhiAtMerge(originalNode);
                LoopBeginNode mainLoopBegin = proxy.proxyPoint().loopBegin();
                LoopEndNode endBeforeSegment = mainLoopBegin.getSingleLoopEnd();
                PhiNode loopPhi = (PhiNode) originalNode;
                ValueNode phiInputAtOriginalSegment = loopPhi.valueAt(endBeforeSegment);

                // this is already the duplicated node since the segment is already added to the
                // graph
                replacement = (ValueNode) new2OldPhis.get(phiInputAtOriginalSegment);
                if (replacement == null) {
                    /*
                     * Special case the input of the phi is not part of the loop
                     */
                    replacement = phiInputAtOriginalSegment;
                }
                assert replacement != null;
            } else {
                replacement = nextIterationVal;
            }
        } else {
            /*
             * Imprecise: We should never enter this branch, as this means there is a proxy for a
             * node that is not actually part of a loop, however, this may happen sometimes for
             * floating nodes and their, wrong, inclusion inside a loop, this question can only be
             * really solved with a schedule which we do not have.
             *
             * Thus, this node must be dominating our loop entirely.
             */
            replacement = originalNode;
        }
        return replacement;
    }

    protected static EndNode getBlockEnd(FixedNode node) {
        FixedNode curNode = node;
        while (curNode instanceof FixedWithNextNode) {
            curNode = ((FixedWithNextNode) curNode).next();
        }
        return (EndNode) curNode;
    }

    @Override
    public NodeBitMap nodes() {
        if (nodes == null) {
            LoopFragmentWhole whole = loop().whole();
            whole.nodes(); // init nodes bitmap in whole
            nodes = whole.nodes.copy();
            // remove the phis
            LoopBeginNode loopBegin = loop().loopBegin();
            for (PhiNode phi : loopBegin.phis()) {
                nodes.clear(phi);
            }
            clearStateNodes(loopBegin);
            for (LoopExitNode exit : exits()) {
                clearStateNodes(exit);
                for (ProxyNode proxy : exit.proxies()) {
                    nodes.clear(proxy);
                }
            }
        }
        return nodes;
    }

    private void clearStateNodes(StateSplit stateSplit) {
        FrameState loopState = stateSplit.stateAfter();
        if (loopState != null) {
            loopState.applyToVirtual(v -> {
                /*
                 * Frame states can reuse virtual object mappings thus n can be new
                 */
                if (v.usages().filter(n -> !nodes.isNew(n) && nodes.isMarked(n) && n != stateSplit).isEmpty()) {
                    nodes.clear(v);
                }
            });
        }

    }

    public NodeIterable<LoopExitNode> exits() {
        return loop().loopBegin().loopExits();
    }

    @Override
    @SuppressWarnings("try")
    protected DuplicationReplacement getDuplicationReplacement() {
        final LoopBeginNode loopBegin = loop().loopBegin();
        final StructuredGraph graph = graph();
        return new DuplicationReplacement() {

            private EconomicMap<Node, Node> seenNode = EconomicMap.create(Equivalence.IDENTITY);

            @Override
            public Node replacement(Node original) {
                try (DebugCloseable position = original.withNodeSourcePosition()) {
                    if (original == loopBegin) {
                        Node value = seenNode.get(original);
                        if (value != null) {
                            return value;
                        }
                        AbstractBeginNode newValue = graph.add(new BeginNode());
                        seenNode.put(original, newValue);
                        return newValue;
                    }
                    if (original instanceof LoopExitNode && ((LoopExitNode) original).loopBegin() == loopBegin) {
                        Node value = seenNode.get(original);
                        if (value != null) {
                            return value;
                        }
                        AbstractBeginNode newValue = graph.add(new BeginNode());
                        seenNode.put(original, newValue);
                        return newValue;
                    }
                    if (original instanceof LoopEndNode && ((LoopEndNode) original).loopBegin() == loopBegin) {
                        Node value = seenNode.get(original);
                        if (value != null) {
                            return value;
                        }
                        EndNode newValue = graph.add(new EndNode());
                        seenNode.put(original, newValue);
                        return newValue;
                    }
                    return original;
                }
            }
        };
    }

    @Override
    protected void beforeDuplication() {
        // Nothing to do
    }

    private EconomicMap<PhiNode, PhiNode> old2NewPhi;

    public EconomicMap<PhiNode, PhiNode> getOld2NewPhi() {
        return old2NewPhi;
    }

    private void patchPeeling(LoopFragmentInside peel) {
        LoopBeginNode loopBegin = loop().loopBegin();
        List<PhiNode> newPhis = new LinkedList<>();

        NodeBitMap usagesToPatch = nodes.copy();
        for (LoopExitNode exit : exits()) {
            markStateNodes(exit, usagesToPatch);
            for (ProxyNode proxy : exit.proxies()) {
                usagesToPatch.markAndGrow(proxy);
            }
        }
        markStateNodes(loopBegin, usagesToPatch);

        List<PhiNode> oldPhis = loopBegin.phis().snapshot();

        if (peel.old2NewPhi == null) {
            peel.old2NewPhi = EconomicMap.create();
        }

        for (PhiNode phi : oldPhis) {
            if (phi.hasNoUsages()) {
                peel.old2NewPhi.put(phi, null);
                continue;
            }
            ValueNode first;
            if (loopBegin.loopEnds().count() == 1) {
                ValueNode b = phi.valueAt(loopBegin.loopEnds().first()); // back edge value
                if (b == null) {
                    assert phi instanceof GuardPhiNode : Assertions.errorMessage(phi);
                    first = null;
                } else {
                    first = peel.prim(b); // corresponding value in the peel
                }
            } else {
                first = peel.mergedInitializers.get(phi);
            }
            // create a new phi (we don't patch the old one since some usages of the old one may
            // still be valid)
            PhiNode newPhi = phi.duplicateOn(loopBegin);
            newPhi.setNodeSourcePosition(phi.getNodeSourcePosition());
            peel.old2NewPhi.put(phi, newPhi);
            newPhi.addInput(first);
            for (LoopEndNode end : loopBegin.orderedLoopEnds()) {
                newPhi.addInput(phi.valueAt(end));
            }
            peel.putDuplicatedNode(phi, newPhi);
            newPhis.add(newPhi);
            for (Node usage : phi.usages().snapshot()) {
                // patch only usages that should use the new phi ie usages that were peeled
                if (usagesToPatch.isMarkedAndGrow(usage)) {
                    usage.replaceFirstInput(phi, newPhi);
                }
            }
        }
        // check new phis to see if they have as input some old phis, replace those inputs with the
        // new corresponding phis
        for (PhiNode phi : newPhis) {
            for (int i = 0; i < phi.valueCount(); i++) {
                ValueNode v = phi.valueAt(i);
                if (loopBegin.isPhiAtMerge(v)) {
                    PhiNode newV = peel.getDuplicatedNode((PhiNode) v);
                    if (newV != null) {
                        phi.setValueAt(i, newV);
                    }
                }
            }
        }

        boolean progress = true;
        while (progress) {
            progress = false;
            int i = 0;
            outer: while (i < oldPhis.size()) {
                PhiNode oldPhi = oldPhis.get(i);
                for (Node usage : oldPhi.usages()) {
                    if (usage instanceof PhiNode && oldPhis.contains(usage)) {
                        // Do not mark.
                    } else {
                        // Mark alive by removing from delete set.
                        oldPhis.remove(i);
                        progress = true;
                        continue outer;
                    }
                }
                i++;
            }
        }

        for (PhiNode deadPhi : oldPhis) {
            deadPhi.clearInputs();
        }

        for (PhiNode deadPhi : oldPhis) {
            if (deadPhi.isAlive()) {
                GraphUtil.killWithUnusedFloatingInputs(deadPhi);
            }
        }
    }

    private static void markStateNodes(StateSplit stateSplit, NodeBitMap marks) {
        FrameState exitState = stateSplit.stateAfter();
        if (exitState != null) {
            exitState.applyToVirtual(v -> marks.markAndGrow(v));
        }
    }

    /**
     * Gets the corresponding value in this fragment.
     *
     * @param b original value
     * @return corresponding value in the peel
     */
    @Override
    protected ValueNode prim(ValueNode b) {
        assert isDuplicate();
        LoopBeginNode loopBegin = original().loop().loopBegin();
        if (loopBegin.isPhiAtMerge(b)) {
            PhiNode phi = (PhiNode) b;
            return phi.valueAt(loopBegin.forwardEnd());
        } else if (nodesReady) {
            ValueNode v = getDuplicatedNode(b);
            if (v == null) {
                return b;
            }
            return v;
        } else {
            return b;
        }
    }

    protected ValueNode primAfter(ValueNode b) {
        assert isDuplicate();
        LoopBeginNode loopBegin = original().loop().loopBegin();
        if (loopBegin.isPhiAtMerge(b)) {
            PhiNode phi = (PhiNode) b;
            assert phi.valueCount() == 2 : Assertions.errorMessage(phi);
            return phi.valueAt(1);
        } else if (nodesReady) {
            ValueNode v = getDuplicatedNode(b);
            if (v == null) {
                return b;
            }
            return v;
        } else {
            return b;
        }
    }

    @SuppressWarnings("try")
    private AbstractBeginNode mergeEnds() {
        assert isDuplicate();
        List<EndNode> endsToMerge = new LinkedList<>();
        // map peel exits to the corresponding loop exits
        EconomicMap<AbstractEndNode, LoopEndNode> reverseEnds = EconomicMap.create(Equivalence.IDENTITY);
        LoopBeginNode loopBegin = original().loop().loopBegin();
        for (LoopEndNode le : loopBegin.loopEnds()) {
            AbstractEndNode duplicate = getDuplicatedNode(le);
            if (duplicate != null) {
                endsToMerge.add((EndNode) duplicate);
                reverseEnds.put(duplicate, le);
            }
        }
        mergedInitializers = EconomicMap.create(Equivalence.IDENTITY);
        AbstractBeginNode newExit;
        StructuredGraph graph = graph();
        if (endsToMerge.size() == 1) {
            AbstractEndNode end = endsToMerge.get(0);
            assert end.hasNoUsages();
            try (DebugCloseable position = end.withNodeSourcePosition()) {
                newExit = graph.add(new BeginNode());
                end.replaceAtPredecessor(newExit);
                end.safeDelete();
            }
        } else {
            assert endsToMerge.size() > 1 : endsToMerge;
            AbstractMergeNode newExitMerge = graph.add(new MergeNode());
            newExit = newExitMerge;
            FrameState state = loopBegin.stateAfter();
            FrameState duplicateState = null;
            if (state != null) {
                duplicateState = state.duplicateWithVirtualState();
                newExitMerge.setStateAfter(duplicateState);
                /*
                 * For complex phi cycles with floating nodes in between the phi and the state we
                 * have to replace each node that is part of the new state with the respective node
                 * in the duplicated region.
                 */
                duplicateState.applyToNonVirtual(new VirtualState.NodePositionClosure<>() {
                    @Override
                    public void apply(Node from, Position p) {
                        Node usage = p.get(from);
                        if (usage != null && !loopBegin.isPhiAtMerge(usage) && original().contains(usage)) {
                            Node duplicated = getDuplicatedNode(usage);
                            p.set(from, duplicated);
                        }
                    }
                });
            }
            for (EndNode end : endsToMerge) {
                newExitMerge.addForwardEnd(end);
            }

            for (final PhiNode phi : loopBegin.phis().snapshot()) {
                if (phi.hasNoUsages()) {
                    continue;
                }
                final PhiNode firstPhi = phi.duplicateOn(newExitMerge);
                firstPhi.setNodeSourcePosition(newExitMerge.getNodeSourcePosition());
                for (AbstractEndNode end : newExitMerge.forwardEnds()) {
                    LoopEndNode loopEnd = reverseEnds.get(end);
                    ValueNode prim = prim(phi.valueAt(loopEnd));
                    assert prim != null;
                    firstPhi.addInput(prim);
                }
                ValueNode initializer = firstPhi;
                if (duplicateState != null) {
                    // fix the merge's state after
                    duplicateState.applyToNonVirtual(new VirtualState.NodePositionClosure<>() {
                        @Override
                        public void apply(Node from, Position p) {
                            if (p.get(from) == phi) {
                                p.set(from, firstPhi);
                            }
                        }
                    });
                }
                mergedInitializers.put(phi, initializer);
            }
        }
        return newExit;
    }
}
