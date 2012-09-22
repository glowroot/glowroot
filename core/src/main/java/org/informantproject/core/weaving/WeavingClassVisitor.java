/**
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.informantproject.core.weaving;

import java.security.CodeSource;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.informantproject.api.weaving.Mixin;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class WeavingClassVisitor extends ClassVisitor implements Opcodes {

    private static final Logger logger = LoggerFactory.getLogger(WeavingClassVisitor.class);

    private static final Type adviceFlowType = Type.getType(AdviceFlowThreadLocal.class);

    private final List<Mixin> mixins;
    private final List<Advice> advisors;
    @Nullable
    private final ClassLoader loader;
    private final ParsedTypeCache parsedTypeCache;
    @Nullable
    private final CodeSource codeSource;
    private final List<AdviceMatcher> adviceMatchers = Lists.newArrayList();
    private final List<Mixin> matchedMixins = Lists.newArrayList();
    @Nullable
    private Type type;

    private final Map<Advice, Integer> adviceFlowThreadLocalNums = Maps.newHashMap();
    private boolean writtenAdviceFlowThreadLocals = false;

    private int innerMethodCounter;

    private boolean nothingAtAllToWeave = false;
    @Nullable
    private ParsedType.Builder parsedType;

    public WeavingClassVisitor(List<Mixin> mixins, List<Advice> advisors,
            @Nullable ClassLoader loader, ParsedTypeCache parsedTypeCache,
            @Nullable CodeSource codeSource, ClassVisitor cv) {

        super(ASM4, cv);
        this.mixins = mixins;
        this.advisors = advisors;
        this.loader = loader;
        this.parsedTypeCache = parsedTypeCache;
        this.codeSource = codeSource;
    }

    @Override
    public void visit(int version, int access, String name, @Nullable String signature,
            @Nullable String superName, String[] interfaceNames) {

        if (logger.isTraceEnabled()) {
            logger.trace("visit(): access={}, name={}, signature={}, superName={},"
                    + " interfaceNames={}", new Object[] { access, name, signature, superName,
                    Joiner.on(", ").join(interfaceNames) });
        }
        parsedType = ParsedType.builder(name, superName, ImmutableList.copyOf(interfaceNames));
        if ((access & ACC_INTERFACE) != 0) {
            // interfaces never get woven
            nothingAtAllToWeave = true;
            return;
        }
        type = Type.getObjectType(name);
        List<ParsedType> superTypes = Lists.newArrayList();
        superTypes.addAll(parsedTypeCache.getTypeHierarchy(superName, loader));
        for (String interfaceName : interfaceNames) {
            superTypes.addAll(parsedTypeCache.getTypeHierarchy(interfaceName, loader));
        }
        for (Iterator<ParsedType> i = superTypes.iterator(); i.hasNext();) {
            ParsedType superType = i.next();
            if (superType.isMissing()) {
                i.remove();
                logger.warn("type not found '{}' while recursing super types of '{}'{}",
                        new Object[] { superType.getClassName(), type.getClassName(),
                                codeSource == null ? "" : "(" + codeSource.getLocation() + ")" });
            }
        }

        for (Advice advice : advisors) {
            AdviceMatcher adviceMatcher = new AdviceMatcher(advice, type, superTypes);
            if (adviceMatcher.isClassLevelMatch()) {
                adviceMatchers.add(adviceMatcher);
            }
        }
        for (Mixin mixin : mixins) {
            MixinMatcher mixinMatcher = new MixinMatcher(mixin, type, superTypes);
            if (mixinMatcher.isMatch()) {
                matchedMixins.add(mixin);
            }
        }
        if (adviceMatchers.isEmpty() && matchedMixins.isEmpty()) {
            nothingAtAllToWeave = true;
            return;
        }
        logger.debug("visit(): adviceMatchers={}", adviceMatchers);
        for (int i = 0; i < adviceMatchers.size(); i++) {
            adviceFlowThreadLocalNums.put(adviceMatchers.get(i).getAdvice(), i);
        }
        if (matchedMixins.isEmpty()) {
            super.visit(version, access, name, signature, superName, interfaceNames);
        } else {
            // add mixin types
            List<String> interfacesIncludingMixins = Lists.newArrayList(interfaceNames);
            for (Mixin mixin : mixins) {
                interfacesIncludingMixins.add(Type.getInternalName(mixin.mixin()));
            }
            super.visit(version, access, name, signature, superName,
                    Iterables.toArray(interfacesIncludingMixins, String.class));
        }
    }

    @Override
    @Nullable
    public MethodVisitor visitMethod(int access, String name, String desc,
            @Nullable String signature, @Nullable String[] exceptions) {

        if (logger.isTraceEnabled()) {
            String exceptionsText;
            if (exceptions == null) {
                exceptionsText = "";
            } else {
                exceptionsText = Joiner.on(", ").join(exceptions);
            }
            logger.trace("visitMethod(): access={}, name={}, desc={}, signature={}, exceptions={}",
                    new Object[] { access, name, desc, signature, exceptionsText });
        }
        ParsedMethod parsedMethod = ParsedMethod.from(name, Type.getArgumentTypes(desc), access);
        parsedType.addMethod(parsedMethod);
        if (nothingAtAllToWeave) {
            // no need to pass method on to class writer
            return null;
        }
        if ((access & ACC_ABSTRACT) != 0) {
            // abstract methods never get woven
            return cv.visitMethod(access, name, desc, signature, exceptions);
        }
        MethodVisitor mv = null;
        if (name.equals("<clinit>")) {
            writeThreadLocalFields();
            mv = cv.visitMethod(access, name, desc, signature, exceptions);
            mv = new InitThreadLocals(mv, access, name, desc);
            // there can only be at most one clinit
            writtenAdviceFlowThreadLocals = true;
        } else if (name.equals("<init>") && !matchedMixins.isEmpty()) {
            mv = cv.visitMethod(access, name, desc, signature, exceptions);
            mv = new InitMixins(mv, access, name, desc, matchedMixins);
        }
        if ((access & ACC_SYNTHETIC) != 0) {
            // skip synthetic methods
            if (mv == null) {
                return cv.visitMethod(access, name, desc, signature, exceptions);
            } else {
                return mv;
            }
        }
        List<Advice> matchingAdvisors = Lists.newArrayList();
        for (AdviceMatcher adviceMatcher : adviceMatchers) {
            if (adviceMatcher.isMethodLevelMatch(access, parsedMethod)) {
                matchingAdvisors.add(adviceMatcher.getAdvice());
            }
        }
        if (matchingAdvisors.isEmpty()) {
            if (mv == null) {
                return cv.visitMethod(access, name, desc, signature, exceptions);
            } else {
                return mv;
            }
        } else {
            logger.debug("visitMethod(): weaving method '{}'", name);
            String currMethodName = name;
            String nextMethodName = null;
            int currAccess = access;
            for (Advice advice : matchingAdvisors) {
                String metricName = advice.getPointcut().metricName();
                if (metricName.length() != 0) {
                    nextMethodName = name + "$informant$metric$" + metricName.replace(' ', '$')
                            + '$' + innerMethodCounter++;
                    if (mv == null) {
                        MethodVisitor mv2 = cv.visitMethod(currAccess, currMethodName, desc,
                                signature, exceptions);
                        GeneratorAdapter mg = new GeneratorAdapter(mv2, currAccess, nextMethodName,
                                desc);
                        if ((access & ACC_STATIC) == 0) {
                            mg.loadThis();
                            mg.loadArgs();
                            mg.invokeVirtual(type, new Method(nextMethodName, desc));
                        } else {
                            mg.loadArgs();
                            mg.invokeStatic(type, new Method(nextMethodName, desc));
                        }
                        mg.returnValue();
                        mg.endMethod();
                        currMethodName = nextMethodName;
                        currAccess = Opcodes.ACC_PRIVATE + Opcodes.ACC_FINAL;
                        currAccess += (access & ACC_STATIC);
                    } else {
                        // already too late, a method with the same name was already created above
                        logger.error("");
                    }
                }
            }
            if (mv == null) {
                mv = cv.visitMethod(currAccess, currMethodName, desc, signature, exceptions);
            }
            return new WeavingMethodVisitor(mv, currAccess, currMethodName, desc, type,
                    matchingAdvisors, adviceFlowThreadLocalNums);
        }
    }

    @Override
    public void visitEnd() {
        parsedTypeCache.add(parsedType.build(), loader);
        if (nothingAtAllToWeave) {
            return;
        }
        if (!writtenAdviceFlowThreadLocals) {
            writeThreadLocalFields();
            MethodVisitor mv = super.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            writeThreadLocalInitialization(mv);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        for (int i = 0; i < matchedMixins.size(); i++) {
            // add mixin field
            Type mixinType = Type.getType(matchedMixins.get(i).mixin());
            visitField(ACC_PRIVATE | ACC_FINAL, "informant$mixin$" + i, mixinType.getDescriptor(),
                    null, null);
            // add mixin methods
            for (java.lang.reflect.Method method : matchedMixins.get(i).mixin().getMethods()) {
                Type[] exceptions = new Type[method.getExceptionTypes().length];
                for (int j = 0; j < method.getExceptionTypes().length; j++) {
                    exceptions[j] = Type.getType(method.getExceptionTypes()[j]);
                }
                // null is passed for signature since generics are not supported at this time
                // TODO support methods with generics
                // TODO make it clear that generics are not supported at the class/interface level
                Method m = Method.getMethod(method);
                GeneratorAdapter mg = new GeneratorAdapter(ACC_PUBLIC, m, null, exceptions, cv);
                mg.loadThis();
                mg.getField(type, "informant$mixin$" + i, mixinType);
                mg.loadArgs();
                mg.invokeInterface(mixinType, m);
                mg.returnValue();
                mg.endMethod();
            }
        }
    }

    boolean isNothingAtAllToWeave() {
        return nothingAtAllToWeave;
    }

    private void writeThreadLocalFields() {
        for (int i = 0; i < adviceMatchers.size(); i++) {
            if (!adviceMatchers.get(i).getAdvice().getPointcut().captureNested()) {
                super.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "informant$adviceFlow$" + i,
                        adviceFlowType.getDescriptor(), null, null);
            }
        }
    }

    private void writeThreadLocalInitialization(MethodVisitor mv) {
        for (int i = 0; i < adviceMatchers.size(); i++) {
            if (!adviceMatchers.get(i).getAdvice().getPointcut().captureNested()) {
                // cannot use visitLdcInsn(Type) since .class constants are not supported in classes
                // that were compiled to jdk 1.4
                mv.visitLdcInsn(adviceMatchers.get(i).getAdvice().getAdviceType().getClassName());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName",
                        "(Ljava/lang/String;)Ljava/lang/Class;");
                String adviceFlowInternalName = adviceFlowType.getInternalName();
                mv.visitMethodInsn(INVOKESTATIC, adviceFlowInternalName, "getSharedInstance",
                        "(Ljava/lang/Class;)L" + adviceFlowInternalName + ";");
                mv.visitFieldInsn(PUTSTATIC, type.getInternalName(), "informant$adviceFlow$" + i,
                        adviceFlowType.getDescriptor());
            }
        }
    }

    private class InitMixins extends AdviceAdapter {

        private final List<Mixin> matchedMixins;

        protected InitMixins(MethodVisitor mv, int access, String name, String desc,
                List<Mixin> matchedMixins) {

            super(ASM4, mv, access, name, desc);
            this.matchedMixins = matchedMixins;
        }

        @Override
        protected void onMethodExit(int opcode) {
            for (int i = 0; i < matchedMixins.size(); i++) {
                Type mixinImplType = Type.getType(matchedMixins.get(i).mixinImpl());
                loadThis();
                newInstance(mixinImplType);
                dup();
                invokeConstructor(mixinImplType, new Method("<init>", "()V"));
                putField(type, "informant$mixin$" + i, Type.getType(matchedMixins.get(i).mixin()));
            }
        }
    }

    private class InitThreadLocals extends AdviceAdapter {

        protected InitThreadLocals(MethodVisitor mv, int access, String name, String desc) {
            super(ASM4, mv, access, name, desc);
        }

        @Override
        protected void onMethodEnter() {
            writeThreadLocalInitialization(this);
        }
    }
}
