/*
 * Copyright (c) 2021, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.strings.test;

import static com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;

@TruffleLanguage.Registration(name = TStringTestDummyLanguage.NAME, id = TStringTestDummyLanguage.ID, characterMimeTypes = TStringTestDummyLanguage.MIME_TYPE, version = "0.1")
@SuppressWarnings("truffle-inlining")
public class TStringTestDummyLanguage extends TruffleLanguage<TStringTestDummyLanguage.DummyLanguageContext> {

    public static final String NAME = "TRUFFLE_STRING_TEST_DUMMY_LANG";
    public static final String ID = "tStringTestDummyLang";
    public static final String MIME_TYPE = "application/tstringdummytest";

    @Override
    protected CallTarget parse(ParsingRequest parsingRequest) {
        return null;
    }

    @Override
    protected DummyLanguageContext createContext(Env env) {
        return new DummyLanguageContext(env);
    }

    @Override
    protected boolean patchContext(DummyLanguageContext context, Env newEnv) {
        context.patchContext(newEnv);
        return true;
    }

    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return true;
    }

    public static final class DummyLanguageContext {

        private static final ContextReference<DummyLanguageContext> REFERENCE = ContextReference.create(TStringTestDummyLanguage.class);

        @CompilationFinal private Env env;

        DummyLanguageContext(Env env) {
            this.env = env;
        }

        void patchContext(Env patchedEnv) {
            this.env = patchedEnv;
        }

        public Env getEnv() {
            return env;
        }

        public static DummyLanguageContext get(Node node) {
            return REFERENCE.get(node);
        }
    }
}
