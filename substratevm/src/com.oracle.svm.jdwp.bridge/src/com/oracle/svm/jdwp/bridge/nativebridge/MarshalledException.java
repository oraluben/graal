/*
 * Copyright (c) 2024, 2024, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.jdwp.bridge.nativebridge;

/**
 * An exception representing an exception thrown over the isolate boundary.
 */
@SuppressWarnings("serial")
public final class MarshalledException extends RuntimeException {

    private final String foreignExceptionClassName;

    /**
     * Creates a {@link MarshalledException} for foreign exception of the
     * {@code foreignExceptionClassName} type with the {@code foreignExceptionMessage} message.
     *
     * @param foreignExceptionClassName the foreign exception class name
     * @param foreignExceptionMessage the foreign exception message
     * @param stackTrace the merged stack trace.
     */
    public MarshalledException(String foreignExceptionClassName, String foreignExceptionMessage, StackTraceElement[] stackTrace) {
        super(foreignExceptionMessage);
        this.foreignExceptionClassName = foreignExceptionClassName;
        setStackTrace(stackTrace);
    }

    /**
     * Returns the foreign exception class name.
     */
    public String getForeignExceptionClassName() {
        return foreignExceptionClassName;
    }

    @Override
    @SuppressWarnings("sync-override")
    public Throwable fillInStackTrace() {
        return this;
    }

    @Override
    public String toString() {
        String message = getLocalizedMessage();
        return (message != null) ? (foreignExceptionClassName + ": " + message) : foreignExceptionClassName;
    }
}
