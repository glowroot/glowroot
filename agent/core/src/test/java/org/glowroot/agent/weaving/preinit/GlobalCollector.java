/*
 * Copyright 2012-2017 the original author or authors.
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
package org.glowroot.agent.weaving.preinit;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GlobalCollector {

    private static final Logger logger = LoggerFactory.getLogger(GlobalCollector.class);

    // caches
    private final Set<ReferencedMethod> referencedMethods = Sets.newHashSet();
    private final Map<String, Optional<ClassCollector>> classCollectors = Maps.newHashMap();

    private final Set<ReferencedMethod> overrides = Sets.newTreeSet();

    private String indent = "";

    public void processMethodFailIfNotFound(ReferencedMethod rootMethod) throws IOException {
        processMethod(rootMethod, true);
    }

    public void processMethod(ReferencedMethod rootMethod) throws IOException {
        String prevIndent = indent;
        indent = indent + "  ";
        processMethod(rootMethod, false);
        indent = prevIndent;
    }

    public void registerClass(String internalName) throws IOException {
        addClass(internalName);
    }

    public void processOverrides() throws IOException {
        while (true) {
            for (String internalName : classCollectors.keySet()) {
                addOverrideReferencedMethods(internalName);
                addOverrideBootstrapMethods(internalName);
            }
            if (overrides.isEmpty()) {
                return;
            }
            for (ReferencedMethod override : overrides) {
                logger.debug("{} (processing overrides)", override);
                processMethod(override);
            }
            overrides.clear();
        }
    }

    public List<String> usedInternalNames() {
        List<String> internalNames = Lists.newArrayList();
        for (String internalName : Ordering.natural().sortedCopy(classCollectors.keySet())) {
            if (!InternalNames.inBootstrapClassLoader(internalName)
                    && !internalName.startsWith("org/slf4j/")
                    && !internalName.startsWith("org/glowroot/agent/shaded/slf4j/")
                    && InternalNames.exists(internalName)) {
                internalNames.add(internalName.replace('/', '.'));
            }
        }
        return internalNames;
    }

    private void processMethod(ReferencedMethod rootMethod, boolean expected) throws IOException {
        if (referencedMethods.contains(rootMethod)) {
            return;
        }
        String owner = rootMethod.getOwner();
        if (owner.startsWith("[")) {
            // method on an Array, e.g. new String[] {}.clone()
            return;
        }
        logger.debug("{}{}", indent, rootMethod);
        // add the containing class and its super classes if not already added
        Optional<ClassCollector> optional = classCollectors.get(owner);
        if (optional == null) {
            optional = addClass(owner);
        }
        if (!optional.isPresent()) {
            // couldn't find class
            if (expected) {
                throw new IOException("Could not find class: " + owner);
            } else {
                return;
            }
        }
        referencedMethods.add(rootMethod);
        if (InternalNames.inBootstrapClassLoader(owner)) {
            return;
        }
        if (owner.startsWith("org/slf4j/")
                || owner.startsWith("org/glowroot/agent/shaded/slf4j/")) {
            return;
        }
        if (rootMethod.getOwner().startsWith("org/glowroot/transaction/model/Transaction")
                && rootMethod.getName().equals("toString")
                && rootMethod.getDesc().equals("()Ljava/lang/String;")) {
            // special case since Transaction.toString() would otherwise pull in many other classes
            // but it only exists for debugging so no need to worry about these classes
            return;
        }
        ClassCollector classCollector = optional.get();
        String methodId = rootMethod.getName() + ":" + rootMethod.getDesc();
        MethodCollector methodCollector = classCollector.getMethodCollector(methodId);
        if (expected && methodCollector == null) {
            throw new IOException("Could not find method: " + rootMethod);
        }
        if (methodCollector == null && !rootMethod.getName().equals("<clinit>")
                && classCollector.getSuperInternalNames() != null) {
            // can't find method in class, so go up to super class
            processMethod(
                    ReferencedMethod.create(classCollector.getSuperInternalNames(), methodId));
        }
        // methodCollector can be null, e.g. unimplemented interface method in an abstract class
        if (methodCollector != null) {
            processMethod(methodCollector);
        }
    }

    private void processMethod(MethodCollector methodCollector) throws IOException {
        // add classes referenced from inside the method
        for (String referencedInternalName : methodCollector.getReferencedInternalNames()) {
            addClass(referencedInternalName);
        }
        // recurse into other methods called from inside the method
        for (ReferencedMethod referencedMethod : methodCollector.getReferencedMethods()) {
            processMethod(referencedMethod);
        }
    }

    private Optional<ClassCollector> addClass(String internalName) throws IOException {
        Optional<ClassCollector> optional = classCollectors.get(internalName);
        if (optional != null) {
            return optional;
        }
        List<String> allSuperInternalNames = Lists.newArrayList();
        ClassCollector classCollector = createClassCollector(internalName);
        if (classCollector == null) {
            optional = Optional.absent();
            classCollectors.put(internalName, optional);
            return optional;
        }
        // don't return or recurse without classCollector being fully built
        classCollectors.put(internalName, Optional.of(classCollector));
        if (classCollector.getSuperInternalNames() != null) {
            // it's a major problem if super class is not present, ok to call Optional.get()
            ClassCollector superClassCollector =
                    addClass(classCollector.getSuperInternalNames()).get();
            allSuperInternalNames.addAll(superClassCollector.getAllSuperInternalNames());
            allSuperInternalNames.add(classCollector.getSuperInternalNames());
        }
        for (String interfaceInternalName : classCollector.getInterfaceInternalNames()) {
            Optional<ClassCollector> loopOptional = addClass(interfaceInternalName);
            if (loopOptional.isPresent()) {
                allSuperInternalNames.addAll(loopOptional.get().getAllSuperInternalNames());
                allSuperInternalNames.add(interfaceInternalName);
            } else {
                logger.debug("could not find class: {}", interfaceInternalName);
                classCollector.setAllSuperInternalNames(allSuperInternalNames);
                return Optional.absent();
            }
        }
        classCollector.setAllSuperInternalNames(allSuperInternalNames);
        // add static initializer (if it exists)
        processMethod(ReferencedMethod.create(internalName, "<clinit>", "()V"));
        return Optional.of(classCollector);
    }

    private @Nullable ClassCollector createClassCollector(String internalName) {
        if (ClassLoader.getSystemResource(internalName + ".class") == null) {
            // no need to log error for H2 optional geometry support
            if (!internalName.startsWith("com/vividsolutions/jts/")) {
                logger.error("could not find class: {}", internalName);
            }
            return null;
        }
        ClassCollector classCollector = new ClassCollector();
        try {
            ClassReader cr = new ClassReader(internalName);
            MyRemappingClassAdapter visitor = new MyRemappingClassAdapter(classCollector);
            cr.accept(visitor, 0);
            return classCollector;
        } catch (IOException e) {
            logger.error("error parsing class: {}", internalName);
            return null;
        }
    }

    private void addOverrideReferencedMethods(String internalName) {
        Optional<ClassCollector> optional = classCollectors.get(internalName);
        if (!optional.isPresent()) {
            return;
        }
        ClassCollector classCollector = optional.get();
        for (String methodId : classCollector.getMethodIds()) {
            if (methodId.startsWith("<clinit>:") || methodId.startsWith("<init>:")) {
                continue;
            }
            for (String superInternalName : classCollector.getAllSuperInternalNames()) {
                if (referencedMethods
                        .contains(ReferencedMethod.create(superInternalName, methodId))) {
                    addOverrideMethod(internalName, methodId);
                    // break inner loop
                    break;
                }
            }
        }
    }

    private void addOverrideBootstrapMethods(String internalName) {
        if (InternalNames.inBootstrapClassLoader(internalName)) {
            return;
        }
        Optional<ClassCollector> optional = classCollectors.get(internalName);
        if (!optional.isPresent()) {
            return;
        }
        ClassCollector classCollector = optional.get();
        // add overridden bootstrap methods in class, e.g. hashCode(), toString()
        for (String methodId : classCollector.getMethodIds()) {
            if (methodId.startsWith("<clinit>:") || methodId.startsWith("<init>:")) {
                continue;
            }
            for (String superInternalName : classCollector.getAllSuperInternalNames()) {
                if (InternalNames.inBootstrapClassLoader(superInternalName)) {
                    ClassCollector superClassCollector =
                            classCollectors.get(superInternalName).get();
                    if (superClassCollector.getMethodCollector(methodId) != null) {
                        addOverrideMethod(internalName, methodId);
                    }
                }
            }
        }
    }

    private void addOverrideMethod(String internalName, String methodId) {
        ReferencedMethod referencedMethod = ReferencedMethod.create(internalName, methodId);
        if (!referencedMethods.contains(referencedMethod)) {
            overrides.add(referencedMethod);
        }
    }
}
