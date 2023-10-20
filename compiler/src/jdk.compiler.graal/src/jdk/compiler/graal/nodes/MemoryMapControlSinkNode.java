/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
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
package jdk.compiler.graal.nodes;

import static jdk.compiler.graal.nodeinfo.InputType.Extension;

import jdk.compiler.graal.core.common.type.StampFactory;
import jdk.compiler.graal.graph.IterableNodeType;
import jdk.compiler.graal.graph.NodeClass;
import jdk.compiler.graal.nodeinfo.NodeInfo;
import jdk.compiler.graal.nodes.memory.MemoryMapNode;

/**
 * {@link ControlSinkNode}that might have a {@link MemoryMapNode} attached. This map can be used to
 * update the memory graph during inlining.
 */
@NodeInfo
public abstract class MemoryMapControlSinkNode extends ControlSinkNode implements IterableNodeType {
    public static final NodeClass<MemoryMapControlSinkNode> TYPE = NodeClass.create(MemoryMapControlSinkNode.class);

    @OptionalInput(Extension) MemoryMapNode memoryMap;

    protected MemoryMapControlSinkNode(NodeClass<? extends MemoryMapControlSinkNode> c) {
        this(c, null);
    }

    protected MemoryMapControlSinkNode(NodeClass<? extends MemoryMapControlSinkNode> c, MemoryMapNode memoryMap) {
        super(c, StampFactory.forVoid());
        this.memoryMap = memoryMap;
    }

    public void setMemoryMap(MemoryMapNode memoryMap) {
        updateUsages(this.memoryMap, memoryMap);
        this.memoryMap = memoryMap;
    }

    public MemoryMapNode getMemoryMap() {
        return memoryMap;
    }

}