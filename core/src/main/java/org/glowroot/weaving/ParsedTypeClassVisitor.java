/*
 * Copyright 2012-2014 the original author or authors.
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
package org.glowroot.weaving;

import java.lang.reflect.Modifier;
import java.security.CodeSource;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import org.glowroot.weaving.ParsedTypeCache.ParseContext;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ASM5;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class ParsedTypeClassVisitor extends ClassVisitor {

    private final ImmutableList<MixinType> mixinTypes;
    private final ImmutableList<Advice> advisors;
    @Nullable
    private final ClassLoader loader;
    private final ParsedTypeCache parsedTypeCache;
    @Nullable
    private final CodeSource codeSource;

    private ImmutableList<AdviceMatcher> adviceMatchers = ImmutableList.of();
    private ImmutableList<MixinType> matchedMixinTypes = ImmutableList.of();

    private List<ParsedType> superParsedTypes = ImmutableList.of();

    private boolean nothingInterestingHere;

    private ParsedType./*@MonotonicNonNull*/Builder parsedTypeBuilder;

    @Nullable
    private ParsedType parsedType;

    public ParsedTypeClassVisitor(ImmutableList<Advice> advisors,
            ImmutableList<MixinType> mixinTypes, @Nullable ClassLoader loader,
            ParsedTypeCache parsedTypeCache, @Nullable CodeSource codeSource) {
        super(ASM5);
        this.mixinTypes = mixinTypes;
        this.advisors = advisors;
        this.loader = loader;
        this.parsedTypeCache = parsedTypeCache;
        this.codeSource = codeSource;
    }

    @Override
    public void visit(int version, int access, String name, @Nullable String signature,
            @Nullable String superName, String/*@Nullable*/[] interfaceNamesNullable) {

        String[] interfaceNames = interfaceNamesNullable == null ? new String[0]
                : interfaceNamesNullable;
        parsedTypeBuilder = ParsedType.builder(access, TypeNames.fromInternal(name),
                TypeNames.fromInternal(superName), TypeNames.fromInternal(interfaceNames));
        String className = TypeNames.fromInternal(name);
        adviceMatchers = AdviceMatcher.getAdviceMatchers(className, advisors);
        if (Modifier.isInterface(access)) {
            ImmutableList<MixinType> matchedMixinTypes = getMatchedMixinTypes(className,
                    ImmutableList.<ParsedType>of(), ImmutableList.<ParsedType>of());
            superParsedTypes = ImmutableList.of();
            parsedTypeBuilder.addMixinTypes(matchedMixinTypes);
            nothingInterestingHere = adviceMatchers.isEmpty();
            return;
        }
        ParseContext parseContext = new ParseContext(className, codeSource);
        List<ParsedType> superHierarchy = parsedTypeCache.getTypeHierarchy(
                TypeNames.fromInternal(superName), loader, parseContext);
        List<ParsedType> newInterfaceHierarchy =
                getInterfaceHierarchy(interfaceNames, parseContext);
        // it's ok if there are duplicates in the superParsedTypes list (e.g. an interface that
        // appears twice in a type hierarchy), it's rare, dups don't cause an issue for callers, and
        // so it doesn't seem worth the (minor) performance hit to de-dup every time
        superParsedTypes = Lists.newArrayList();
        superParsedTypes.addAll(superHierarchy);
        superParsedTypes.addAll(newInterfaceHierarchy);
        matchedMixinTypes = getMatchedMixinTypes(className, superHierarchy, newInterfaceHierarchy);
        parsedTypeBuilder.addMixinTypes(matchedMixinTypes);

        boolean hasSuperAdvice = false;
        for (ParsedType parsedType : superParsedTypes) {
            if (!parsedType.getParsedMethods().isEmpty()) {
                hasSuperAdvice = true;
                break;
            }
        }
        nothingInterestingHere = !hasSuperAdvice && adviceMatchers.isEmpty();
    }

    @Override
    @Nullable
    public MethodVisitor visitMethod(int access, String name, String desc,
            @Nullable String signature, String/*@Nullable*/[] exceptions) {
        visitMethodReturningAdvisors(access, name, desc, signature, exceptions);
        return null;
    }

    @Override
    public void visitEnd() {
        visitEndReturningParsedType();
    }

    List<Advice> visitMethodReturningAdvisors(int access, String name, String desc,
            @Nullable String signature, String/*@Nullable*/[] exceptions) {
        if (nothingInterestingHere) {
            // no need to pass method on to class writer
            return ImmutableList.of();
        }
        List<Type> argTypes = Arrays.asList(Type.getArgumentTypes(desc));
        Type returnType = Type.getReturnType(desc);
        List<Advice> matchingAdvisors = getMatchingAdvisors(name, argTypes, returnType, access);
        List<String> exceptionList = exceptions == null ? ImmutableList.<String>of()
                : Arrays.asList(exceptions);
        if (!matchingAdvisors.isEmpty()) {
            checkNotNull(parsedTypeBuilder, "Call to visit() is required");
            parsedTypeBuilder.addParsedMethod(access, name, desc, signature, exceptionList,
                    matchingAdvisors);
        }
        return matchingAdvisors;
    }

    ParsedType visitEndReturningParsedType() {
        checkNotNull(parsedTypeBuilder, "Call to visit() is required");
        parsedType = parsedTypeBuilder.build();
        parsedTypeCache.add(parsedType, loader);
        return parsedType;
    }

    ImmutableList<AdviceMatcher> getAdviceMatchers() {
        return adviceMatchers;
    }

    ImmutableList<MixinType> getMatchedMixinTypes() {
        return matchedMixinTypes;
    }

    List<ParsedType> getSuperParsedTypes() {
        return superParsedTypes;
    }

    boolean isNothingInteresting() {
        return nothingInterestingHere;
    }

    @Nullable
    ParsedType getParsedType() {
        return parsedType;
    }

    private List<ParsedType> getInterfaceHierarchy(String[] interfaceNames,
            ParseContext parseContext) {
        List<ParsedType> superTypes = Lists.newArrayList();
        for (String interfaceName : interfaceNames) {
            superTypes.addAll(parsedTypeCache.getTypeHierarchy(
                    TypeNames.fromInternal(interfaceName), loader, parseContext));
        }
        return superTypes;
    }

    private ImmutableList<MixinType> getMatchedMixinTypes(String className,
            Iterable<ParsedType> superParsedTypes, List<ParsedType> newInterfaceParsedTypes) {
        Set<MixinType> matchedMixinTypes = Sets.newHashSet();
        String typeClassName = className;
        for (MixinType mixinType : mixinTypes) {
            if (MixinMatcher.isTypeMatch(mixinType, typeClassName)) {
                matchedMixinTypes.add(mixinType);
            }
        }
        for (ParsedType newInterfaceParsedType : newInterfaceParsedTypes) {
            matchedMixinTypes.addAll(newInterfaceParsedType.getMixinTypes());
        }
        // remove mixins that were already implemented in a super class
        for (ParsedType superParsedType : superParsedTypes) {
            if (!superParsedType.isInterface()) {
                matchedMixinTypes.removeAll(superParsedType.getMixinTypes());
            }
        }
        return ImmutableList.copyOf(matchedMixinTypes);
    }

    private List<Advice> getMatchingAdvisors(String methodName, List<Type> argTypes,
            Type returnType, int modifiers) {
        Set<Advice> matchingAdvisors = Sets.newHashSet();
        for (AdviceMatcher adviceMatcher : adviceMatchers) {
            if (adviceMatcher.isMethodLevelMatch(methodName, argTypes, returnType, modifiers)) {
                matchingAdvisors.add(adviceMatcher.getAdvice());
            }
        }
        // look at super types
        checkNotNull(superParsedTypes, "Call to visit() is required");
        for (ParsedType parsedType : superParsedTypes) {
            for (ParsedMethod parsedMethod : parsedType.getParsedMethods()) {
                if (parsedMethod.isOverriddenBy(methodName, argTypes)) {
                    matchingAdvisors.addAll(parsedMethod.getAdvisors());
                }
            }
        }
        // sort for consistency since the order affects metric nesting
        switch (matchingAdvisors.size()) {
            case 0:
                return ImmutableList.of();
            case 1:
                return ImmutableList.copyOf(matchingAdvisors);
            default:
                return Advice.orderingTraceMetric.immutableSortedCopy(matchingAdvisors);
        }
    }

    @Override
    @Pure
    public String toString() {
        // not including fields that are just direct copies from Weaver
        ToStringHelper toStringHelper = Objects.toStringHelper(this)
                .add("codeSource", codeSource)
                .add("adviceMatchers", adviceMatchers)
                .add("matchedMixinTypes", matchedMixinTypes)
                .add("nothingAtAllToWeave", nothingInterestingHere);
        if (parsedTypeBuilder != null) {
            toStringHelper.add("parsedType", parsedTypeBuilder.build());
        }
        return toStringHelper.toString();
    }
}
