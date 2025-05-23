/*
 * Copyright (c) 2019, 2022, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.hotspot.replacements;

import static jdk.graal.compiler.hotspot.GraalHotSpotVMConfig.INJECTED_VMCONFIG;

import jdk.graal.compiler.debug.Assertions;
import jdk.graal.compiler.hotspot.meta.HotSpotProviders;
import jdk.graal.compiler.nodes.gc.SerialArrayRangeWriteBarrierNode;
import jdk.graal.compiler.nodes.gc.SerialWriteBarrierNode;
import jdk.graal.compiler.nodes.spi.LoweringTool;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.replacements.ReplacementsUtil;
import jdk.graal.compiler.replacements.SnippetCounter.Group;
import jdk.graal.compiler.replacements.SnippetTemplate.AbstractTemplates;
import jdk.graal.compiler.replacements.SnippetTemplate.SnippetInfo;
import jdk.graal.compiler.replacements.gc.SerialWriteBarrierSnippets;
import jdk.graal.compiler.word.Word;

public class HotSpotSerialWriteBarrierSnippets extends SerialWriteBarrierSnippets {

    public HotSpotSerialWriteBarrierSnippets() {
    }

    @Override
    public Word cardTableAddress() {
        return Word.unsigned(HotSpotReplacementsUtil.cardTableStart(INJECTED_VMCONFIG));
    }

    @Override
    public int cardTableShift() {
        return HotSpotReplacementsUtil.cardTableShift(INJECTED_VMCONFIG);
    }

    @Override
    public boolean verifyBarrier() {
        return ReplacementsUtil.REPLACEMENTS_ASSERTIONS_ENABLED || HotSpotReplacementsUtil.verifyBeforeOrAfterGC(INJECTED_VMCONFIG);
    }

    @Override
    protected byte dirtyCardValue() {
        return HotSpotReplacementsUtil.dirtyCardValue(INJECTED_VMCONFIG);
    }

    public static class Templates extends AbstractTemplates {
        private final SnippetInfo serialImpreciseWriteBarrier;
        private final SnippetInfo serialPreciseWriteBarrier;
        private final SnippetInfo serialArrayRangeWriteBarrier;

        private final SerialWriteBarrierLowerer lowerer;

        @SuppressWarnings("this-escape")
        public Templates(OptionValues options, Group.Factory factory, HotSpotProviders providers) {
            super(options, providers);
            this.lowerer = new SerialWriteBarrierLowerer(factory);

            HotSpotSerialWriteBarrierSnippets receiver = new HotSpotSerialWriteBarrierSnippets();

            if (Assertions.assertionsEnabled()) {
                serialImpreciseWriteBarrier = snippet(providers,
                                SerialWriteBarrierSnippets.class,
                                "serialImpreciseWriteBarrier",
                                null,
                                receiver,
                                GC_CARD_LOCATION,
                                getClassComponentTypeLocation(providers.getMetaAccess()));
            } else {
                serialImpreciseWriteBarrier = snippet(providers,
                                SerialWriteBarrierSnippets.class,
                                "serialImpreciseWriteBarrier",
                                null,
                                receiver,
                                GC_CARD_LOCATION);
            }

            serialPreciseWriteBarrier = snippet(providers,
                            SerialWriteBarrierSnippets.class,
                            "serialPreciseWriteBarrier",
                            null,
                            receiver,
                            GC_CARD_LOCATION);
            serialArrayRangeWriteBarrier = snippet(providers,
                            SerialWriteBarrierSnippets.class,
                            "serialArrayRangeWriteBarrier",
                            null,
                            receiver,
                            GC_CARD_LOCATION);
        }

        public void lower(SerialWriteBarrierNode barrier, LoweringTool tool) {
            lowerer.lower(this, serialPreciseWriteBarrier, serialImpreciseWriteBarrier, barrier, tool);
        }

        public void lower(SerialArrayRangeWriteBarrierNode barrier, LoweringTool tool) {
            lowerer.lower(this, serialArrayRangeWriteBarrier, barrier, tool);
        }
    }
}
