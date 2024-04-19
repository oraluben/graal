/*
 * Copyright (c) 2024, 2024, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2024, 2024, Red Hat Inc. All rights reserved.
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
package com.oracle.svm.core.jfr.events;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Experimental;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Similar to the JFR event jdk.NativeMemoryUsageTotal, except that it tracks the peak usage. */
@Experimental
@Name("jdk.NativeMemoryUsageTotalPeak")
@Label("Native Memory Usage Total Peak")
@Description("Total native memory peak usage for the JVM (GraalVM Native Image only). Might not be the exact sum of the NativeMemoryUsagePeak events due to timing.")
@Category({"Java Virtual Machine", "Memory"})
@StackTrace(false)
public class NativeMemoryUsageTotalPeakEvent extends Event {
    @Label("Peak Reserved") public long peakReserved;
    @Label("Peak Committed") public long peakCommitted;
    @Label("Count At Peak") public long countAtPeak;
}