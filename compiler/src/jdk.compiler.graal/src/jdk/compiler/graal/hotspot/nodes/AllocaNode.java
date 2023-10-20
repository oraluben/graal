/*
 * Copyright (c) 2012, 2022, Oracle and/or its affiliates. All rights reserved.
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
package jdk.compiler.graal.hotspot.nodes;

import static jdk.compiler.graal.nodeinfo.NodeCycles.CYCLES_2;
import static jdk.compiler.graal.nodeinfo.NodeSize.SIZE_1;

import jdk.compiler.graal.core.common.type.StampFactory;
import jdk.compiler.graal.graph.NodeClass;
import jdk.compiler.graal.lir.VirtualStackSlot;
import jdk.compiler.graal.nodeinfo.NodeInfo;
import jdk.compiler.graal.nodes.FixedWithNextNode;
import jdk.compiler.graal.nodes.spi.LIRLowerable;
import jdk.compiler.graal.nodes.spi.NodeLIRBuilderTool;
import jdk.compiler.graal.word.Word;
import jdk.compiler.graal.word.WordTypes;

import jdk.vm.ci.meta.Value;

/**
 * Reserves a block of memory in the stack frame of a method. The block is reserved in the frame for
 * the entire execution of the associated method.
 */
@NodeInfo(cycles = CYCLES_2, size = SIZE_1)
public final class AllocaNode extends FixedWithNextNode implements LIRLowerable {

    public static final NodeClass<AllocaNode> TYPE = NodeClass.create(AllocaNode.class);

    private final int sizeInBytes;
    private final int alignmentInBytes;

    public AllocaNode(@InjectedNodeParameter WordTypes wordTypes, int sizeInBytes, int alignmentInBytes) {
        super(TYPE, StampFactory.forKind(wordTypes.getWordKind()));
        assert sizeInBytes > 0 && alignmentInBytes > 0;
        this.sizeInBytes = sizeInBytes;
        this.alignmentInBytes = alignmentInBytes;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        VirtualStackSlot array = gen.getLIRGeneratorTool().allocateStackMemory(sizeInBytes, alignmentInBytes);
        Value result = gen.getLIRGeneratorTool().emitAddress(array);
        gen.setResult(this, result);
    }

    @NodeIntrinsic
    public static native Word alloca(@ConstantNodeParameter int sizeInBytes, @ConstantNodeParameter int alignmentInBytes);
}