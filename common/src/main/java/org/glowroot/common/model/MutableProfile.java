/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.common.model;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.PeekingIterator;
import com.google.common.collect.Queues;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Traverser;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;

public class MutableProfile {

    private static final Logger logger = LoggerFactory.getLogger(MutableProfile.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    // TODO use primitive maps, e.g. from GS collections
    private final Map<String, Integer> packageNameIndexes = Maps.newHashMap();
    private final Map<String, Integer> classNameIndexes = Maps.newHashMap();
    private final Map<String, Integer> methodNameIndexes = Maps.newHashMap();
    private final Map<String, Integer> fileNameIndexes = Maps.newHashMap();

    private final List<String> packageNames = Lists.newArrayList();
    private final List<String> classNames = Lists.newArrayList();
    private final List<String> methodNames = Lists.newArrayList();
    private final List<String> fileNames = Lists.newArrayList();

    private final List<ProfileNode> rootNodes = Lists.newArrayList();

    // retain original sample count for in case of filtered profile
    private long unfilteredSampleCount = -1;

    // this method is not used that often (only for traces with > 20 stack trace samples) so ok
    // that it does not have most optimal implementation (converts unnecessarily to profile tree)
    public void merge(MutableProfile profile) {
        merge(profile.toProto());
    }

    public void merge(Profile profile) {
        Merger merger = new Merger(profile);
        merger.merge(profile.getNodeList(), rootNodes);
    }

    public void merge(List<StackTraceElement> stackTraceElements, Thread.State threadState) {
        PeekingIterator<StackTraceElement> i =
                Iterators.peekingIterator(Lists.reverse(stackTraceElements).iterator());
        ProfileNode lastMatchedNode = null;
        List<ProfileNode> mergeIntoNodes = rootNodes;

        boolean lookingForMatch = true;
        while (i.hasNext()) {
            StackTraceElement stackTraceElement = i.next();
            String fullClassName = stackTraceElement.getClassName();
            int index = fullClassName.lastIndexOf('.');
            String packageName;
            String className;
            if (index == -1) {
                packageName = "";
                className = fullClassName;
            } else {
                packageName = fullClassName.substring(0, index);
                className = fullClassName.substring(index + 1);
            }
            int packageNameIndex = getNameIndex(packageName, packageNameIndexes, packageNames);
            int classNameIndex = getNameIndex(className, classNameIndexes, classNames);
            int methodNameIndex =
                    getNameIndex(MoreObjects.firstNonNull(stackTraceElement.getMethodName(),
                            "<null method name>"), methodNameIndexes, methodNames);
            int fileNameIndex = getNameIndex(Strings.nullToEmpty(stackTraceElement.getFileName()),
                    fileNameIndexes, fileNames);
            int lineNumber = stackTraceElement.getLineNumber();
            Profile.LeafThreadState leafThreadState =
                    i.hasNext() ? Profile.LeafThreadState.NONE : getThreadState(threadState);

            ProfileNode node = null;
            if (lookingForMatch) {
                for (ProfileNode childNode : mergeIntoNodes) {
                    if (isMatch(childNode, packageNameIndex, classNameIndex, methodNameIndex,
                            fileNameIndex, lineNumber, leafThreadState)) {
                        node = childNode;
                        break;
                    }
                }
            }
            if (node == null) {
                lookingForMatch = false;
                node = new ProfileNode(packageNameIndex, classNameIndex, methodNameIndex,
                        fileNameIndex, lineNumber, leafThreadState);
                mergeIntoNodes.add(node);
            }
            node.sampleCount++;
            lastMatchedNode = node;
            mergeIntoNodes = lastMatchedNode.childNodes;
        }
    }

    public void filter(List<String> includes, List<String> excludes) {
        unfilteredSampleCount = getSampleCount();
        for (String include : includes) {
            for (Iterator<ProfileNode> i = rootNodes.iterator(); i.hasNext();) {
                ProfileNode rootNode = i.next();
                new ProfileFilterer(rootNode, include, false).traverse();
                if (rootNode.matched) {
                    new ProfileResetMatches(rootNode).traverse();
                } else {
                    i.remove();
                }
            }
        }
        for (String exclude : excludes) {
            for (Iterator<ProfileNode> i = rootNodes.iterator(); i.hasNext();) {
                ProfileNode rootNode = i.next();
                new ProfileFilterer(rootNode, exclude, true).traverse();
                if (rootNode.matched) {
                    i.remove();
                }
            }
        }
    }

    public void truncateBranches(int minSamples) {
        Deque<ProfileNode> toBeVisited = new ArrayDeque<ProfileNode>();
        for (ProfileNode rootNode : rootNodes) {
            toBeVisited.add(rootNode);
        }
        ProfileNode node;
        while ((node = toBeVisited.poll()) != null) {
            for (Iterator<ProfileNode> i = node.childNodes.iterator(); i.hasNext();) {
                ProfileNode childNode = i.next();
                if (childNode.sampleCount < minSamples) {
                    i.remove();
                    // TODO capture sampleCount per timerName of non-ellipsed structure
                    // and use this in UI dropdown filter of timer names
                    // (currently sampleCount per timerName of ellipsed structure is used)
                    node.ellipsedSampleCount += childNode.sampleCount;
                } else {
                    toBeVisited.add(childNode);
                }
            }
        }
    }

    public long getSampleCount() {
        long sampleCount = 0;
        for (ProfileNode rootNode : rootNodes) {
            sampleCount += rootNode.sampleCount;
        }
        return sampleCount;
    }

    public long getUnfilteredSampleCount() {
        if (unfilteredSampleCount == -1) {
            return getSampleCount();
        } else {
            return unfilteredSampleCount;
        }
    }

    public Profile toProto() {
        List<Profile.ProfileNode> nodes = Lists.newArrayList();
        for (ProfileNode rootNode : rootNodes) {
            new ProfileNodeCollector(rootNode, nodes).traverse();
        }
        return Profile.newBuilder()
                .addAllPackageName(packageNames)
                .addAllClassName(classNames)
                .addAllMethodName(methodNames)
                .addAllFileName(fileNames)
                .addAllNode(nodes)
                .build();
    }

    public String toJson() throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        writeJson(jg);
        jg.close();
        return sb.toString();
    }

    public void writeJson(JsonGenerator jg) throws IOException {
        jg.writeStartObject();
        jg.writeNumberField("unfilteredSampleCount", getUnfilteredSampleCount());
        jg.writeArrayFieldStart("rootNodes");
        for (ProfileNode rootNode : rootNodes) {
            new ProfileWriter(rootNode, jg).traverse();
        }
        jg.writeEndArray();
        jg.writeEndObject();
    }

    public String toFlameGraphJson() throws IOException {
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        try {
            jg.writeStartObject();
            jg.writeNumberField("totalSampleCount", getSampleCount());
            jg.writeArrayFieldStart("rootNodes");
            int height = 0;
            for (ProfileNode rootNode : rootNodes) {
                if (rootNode.sampleCount > rootNode.ellipsedSampleCount) {
                    FlameGraphWriter flameGraphWriter = new FlameGraphWriter(rootNode, jg);
                    flameGraphWriter.traverse();
                    height = Math.max(height, flameGraphWriter.height);
                }
            }
            jg.writeEndArray();
            jg.writeNumberField("height", height);
            jg.writeEndObject();
        } finally {
            jg.close();
        }
        return sb.toString();
    }

    private static int getNameIndex(String name, Map<String, Integer> nameIndexes,
            List<String> names) {
        Integer index = nameIndexes.get(name);
        if (index == null) {
            index = names.size();
            names.add(name);
            nameIndexes.put(name, index);
        }
        return index;
    }

    private static Profile.LeafThreadState getThreadState(@Nullable Thread.State state) {
        if (state == null) {
            return Profile.LeafThreadState.NONE;
        }
        switch (state) {
            case NEW:
                return Profile.LeafThreadState.NEW;
            case RUNNABLE:
                return Profile.LeafThreadState.RUNNABLE;
            case BLOCKED:
                return Profile.LeafThreadState.BLOCKED;
            case WAITING:
                return Profile.LeafThreadState.WAITING;
            case TIMED_WAITING:
                return Profile.LeafThreadState.TIMED_WAITING;
            case TERMINATED:
                return Profile.LeafThreadState.TERMINATED;
            default:
                logger.warn("unexpected thread state: {}", state);
                return Profile.LeafThreadState.NONE;
        }
    }

    private static boolean isMatch(ProfileNode profileNode, int packageNameIndex,
            int classNameIndex, int methodNameIndex, int fileNameIndex, int lineNumber,
            Profile.LeafThreadState leafThreadState) {
        // checking line number first since most likely to be different
        return lineNumber == profileNode.lineNumber
                && fileNameIndex == profileNode.fileNameIndex
                && leafThreadState == profileNode.leafThreadState
                && methodNameIndex == profileNode.methodNameIndex
                && classNameIndex == profileNode.classNameIndex
                && packageNameIndex == profileNode.packageNameIndex;
    }

    private static int[] makeIndexMapping(List<String> toBeMergedNames,
            Map<String, Integer> existingIndexes, List<String> existingNames) {
        int[] indexMapping = new int[toBeMergedNames.size()];
        for (int i = 0; i < toBeMergedNames.size(); i++) {
            String toBeMergedName = toBeMergedNames.get(i);
            Integer existingIndex = existingIndexes.get(toBeMergedName);
            if (existingIndex == null) {
                int newIndex = existingNames.size();
                existingNames.add(toBeMergedName);
                existingIndexes.put(toBeMergedName, newIndex);
                indexMapping[i] = newIndex;
            } else {
                indexMapping[i] = existingIndex;
            }
        }
        return indexMapping;
    }

    private class ProfileNode {

        private final int packageNameIndex;
        private final int classNameIndex;
        private final int methodNameIndex;
        private final int fileNameIndex;
        private final int lineNumber;
        private final Profile.LeafThreadState leafThreadState;

        private long sampleCount;

        private List<ProfileNode> childNodes = Lists.newArrayListWithCapacity(2);

        // these fields are only used for filtering
        private @Nullable String text;
        private @Nullable String textUpper;
        private boolean matched;
        private long ellipsedSampleCount;

        private ProfileNode(int packageNameIndex, int classNameIndex, int methodNameIndex,
                int fileNameIndex, int lineNumber, Profile.LeafThreadState leafThreadState) {
            this.packageNameIndex = packageNameIndex;
            this.classNameIndex = classNameIndex;
            this.methodNameIndex = methodNameIndex;
            this.fileNameIndex = fileNameIndex;
            this.lineNumber = lineNumber;
            this.leafThreadState = leafThreadState;
        }

        private String getText() {
            if (text == null) {
                String packageName = packageNames.get(packageNameIndex);
                String className = classNames.get(classNameIndex);
                String fullClassName;
                if (packageName.isEmpty()) {
                    fullClassName = className;
                } else {
                    fullClassName = packageName + '.' + className;
                }
                text = new StackTraceElement(fullClassName, methodNames.get(methodNameIndex),
                        fileNames.get(fileNameIndex), lineNumber).toString();
            }
            return text;
        }

        private String getTextUpper() {
            if (textUpper == null) {
                textUpper = getText().toUpperCase(Locale.ENGLISH);
            }
            return textUpper;
        }
    }

    private class Merger {

        private final int[] packageNameIndexMapping;
        private final int[] classNameIndexMapping;
        private final int[] methodNameIndexMapping;
        private final int[] fileNameIndexMapping;

        private final Deque<List<ProfileNode>> destinationStack = Queues.newArrayDeque();

        private Merger(Profile toBeMergedProfile) {
            packageNameIndexMapping = makeIndexMapping(toBeMergedProfile.getPackageNameList(),
                    packageNameIndexes, packageNames);
            classNameIndexMapping = makeIndexMapping(toBeMergedProfile.getClassNameList(),
                    classNameIndexes, classNames);
            methodNameIndexMapping = makeIndexMapping(toBeMergedProfile.getMethodNameList(),
                    methodNameIndexes, methodNames);
            fileNameIndexMapping = makeIndexMapping(toBeMergedProfile.getFileNameList(),
                    fileNameIndexes, fileNames);
        }

        private void merge(List<Profile.ProfileNode> flatNodes,
                List<ProfileNode> destinationRootNodes) {
            destinationStack.push(destinationRootNodes);
            PeekingIterator<Profile.ProfileNode> i =
                    Iterators.peekingIterator(flatNodes.iterator());
            while (i.hasNext()) {
                Profile.ProfileNode flatNode = i.next();
                int destinationDepth = destinationStack.size() - 1;
                for (int j = 0; j < destinationDepth - flatNode.getDepth(); j++) {
                    // TODO optimize: faster way to pop multiple elements at once
                    destinationStack.pop();
                }
                ProfileNode destinationNode = mergeOne(flatNode, destinationStack.getFirst());
                if (i.hasNext() && i.peek().getDepth() > flatNode.getDepth()) {
                    destinationStack.push(destinationNode.childNodes);
                }
            }
        }

        private ProfileNode mergeOne(Profile.ProfileNode toBeMergedNode,
                List<ProfileNode> destinationNodes) {
            int toBeMergedPackageNameIndex =
                    packageNameIndexMapping[toBeMergedNode.getPackageNameIndex()];
            int toBeMergedClassNameIndex =
                    classNameIndexMapping[toBeMergedNode.getClassNameIndex()];
            int toBeMergedMethodNameIndex =
                    methodNameIndexMapping[toBeMergedNode.getMethodNameIndex()];
            int toBeMergedFileNameIndex = fileNameIndexMapping[toBeMergedNode.getFileNameIndex()];
            int toBeMergedLineNumber = toBeMergedNode.getLineNumber();
            Profile.LeafThreadState toBeMergedLeafThreadState = toBeMergedNode.getLeafThreadState();
            for (ProfileNode destinationNode : destinationNodes) {
                if (isMatch(destinationNode, toBeMergedPackageNameIndex, toBeMergedClassNameIndex,
                        toBeMergedMethodNameIndex, toBeMergedFileNameIndex, toBeMergedLineNumber,
                        toBeMergedLeafThreadState)) {
                    merge(toBeMergedNode, destinationNode);
                    return destinationNode;
                }
            }
            // no match found
            ProfileNode destinationNode = new ProfileNode(toBeMergedPackageNameIndex,
                    toBeMergedClassNameIndex, toBeMergedMethodNameIndex, toBeMergedFileNameIndex,
                    toBeMergedLineNumber, toBeMergedLeafThreadState);
            destinationNodes.add(destinationNode);
            merge(toBeMergedNode, destinationNode);
            return destinationNode;
        }

        private void merge(Profile.ProfileNode toBeMergedNode, ProfileNode destinationNode) {
            destinationNode.sampleCount += toBeMergedNode.getSampleCount();
        }
    }

    // using Traverser to avoid StackOverflowError caused by a recursive algorithm
    private static class ProfileNodeCollector extends Traverser<ProfileNode, RuntimeException> {

        private final List<Profile.ProfileNode> nodes;

        public ProfileNodeCollector(ProfileNode rootNode, List<Profile.ProfileNode> nodes) {
            super(rootNode);
            this.nodes = nodes;
        }

        @Override
        public List<ProfileNode> visit(ProfileNode node, int depth) {
            nodes.add(Profile.ProfileNode.newBuilder()
                    .setDepth(depth)
                    .setPackageNameIndex(node.packageNameIndex)
                    .setClassNameIndex(node.classNameIndex)
                    .setMethodNameIndex(node.methodNameIndex)
                    .setFileNameIndex(node.fileNameIndex)
                    .setLineNumber(node.lineNumber)
                    .setLeafThreadState(node.leafThreadState)
                    .setSampleCount(node.sampleCount)
                    .build());
            return node.childNodes;
        }
    }

    private static class ProfileFilterer extends Traverser<ProfileNode, RuntimeException> {

        private final String filterTextUpper;
        private final boolean exclusion;

        private ProfileFilterer(ProfileNode rootNode, String filterText, boolean exclusion) {
            super(rootNode);
            this.filterTextUpper = filterText.toUpperCase(Locale.ENGLISH);
            this.exclusion = exclusion;
        }

        @Override
        public List<ProfileNode> visit(ProfileNode node, int depth) {
            if (isMatch(node)) {
                node.matched = true;
                // no need to visit children
                return ImmutableList.of();
            }
            return node.childNodes;
        }

        @Override
        public void revisitAfterChildren(ProfileNode node) {
            if (node.matched) {
                // if exclusion then node will be removed by parent
                // if not exclusion then keep node and all children
                return;
            }
            if (node.childNodes.isEmpty()) {
                return;
            }
            if (removeNode(node)) {
                // node will be removed by parent
                if (exclusion) {
                    node.matched = true;
                }
                return;
            }
            if (!exclusion) {
                node.matched = true;
            }
            // node is a partial match, need to filter it out
            long filteredSampleCount = 0;
            for (Iterator<ProfileNode> i = node.childNodes.iterator(); i.hasNext();) {
                ProfileNode childNode = i.next();
                if (exclusion == !childNode.matched) {
                    filteredSampleCount += childNode.sampleCount;
                } else {
                    i.remove();
                }
            }
            node.sampleCount = filteredSampleCount;
        }

        private boolean isMatch(ProfileNode node) {
            String textUpper = node.getTextUpper();
            if (textUpper.contains(filterTextUpper)) {
                return true;
            }
            Profile.LeafThreadState leafThreadState = node.leafThreadState;
            if (leafThreadState != null) {
                String leafThreadStateUpper = leafThreadState.name().toUpperCase(Locale.ENGLISH);
                if (leafThreadStateUpper.contains(filterTextUpper)) {
                    return true;
                }
            }
            return false;
        }

        private boolean removeNode(ProfileNode node) {
            if (exclusion) {
                return hasOnlyMatchedChildren(node);
            } else {
                return hasNoMatchedChildren(node);
            }
        }

        private static boolean hasOnlyMatchedChildren(ProfileNode node) {
            for (ProfileNode childNode : node.childNodes) {
                if (!childNode.matched) {
                    return false;
                }
            }
            return true;
        }

        private static boolean hasNoMatchedChildren(ProfileNode node) {
            for (ProfileNode childNode : node.childNodes) {
                if (childNode.matched) {
                    return false;
                }
            }
            return true;
        }
    }

    private static class ProfileResetMatches extends Traverser<ProfileNode, RuntimeException> {

        private ProfileResetMatches(ProfileNode rootNode) {
            super(rootNode);
        }

        @Override
        public List<ProfileNode> visit(ProfileNode node, int depth) {
            node.matched = false;
            return node.childNodes;
        }
    }

    private class ProfileWriter extends Traverser<ProfileNode, IOException> {

        private final JsonGenerator jg;

        private ProfileWriter(ProfileNode rootNode, JsonGenerator jg) {
            super(rootNode);
            this.jg = jg;
        }

        @Override
        public List<ProfileNode> visit(ProfileNode node, int depth) throws IOException {
            jg.writeStartObject();
            jg.writeStringField("stackTraceElement", node.getText());
            Profile.LeafThreadState leafThreadState = node.leafThreadState;
            if (leafThreadState != Profile.LeafThreadState.NONE) {
                jg.writeStringField("leafThreadState", leafThreadState.name());
            }
            jg.writeNumberField("sampleCount", node.sampleCount);
            long ellipsedSampleCount = node.ellipsedSampleCount;
            if (ellipsedSampleCount > 0) {
                jg.writeNumberField("ellipsedSampleCount", ellipsedSampleCount);
            }
            List<ProfileNode> childNodes = node.childNodes;
            if (!childNodes.isEmpty()) {
                jg.writeArrayFieldStart("childNodes");
            }
            return childNodes;
        }

        @Override
        public void revisitAfterChildren(ProfileNode node) throws IOException {
            if (!node.childNodes.isEmpty()) {
                jg.writeEndArray();
            }
            jg.writeEndObject();
        }
    }

    private class FlameGraphWriter extends Traverser<ProfileNode, IOException> {

        private final JsonGenerator jg;
        private int height;

        private FlameGraphWriter(ProfileNode rootNode, JsonGenerator jg) {
            super(rootNode);
            this.jg = jg;
        }

        @Override
        public List<ProfileNode> visit(ProfileNode node, int depth) throws IOException {
            height = Math.max(height, depth + 1);
            jg.writeStartObject();
            jg.writeStringField("name", node.getText());
            jg.writeNumberField("value", node.sampleCount);
            if (!node.childNodes.isEmpty()) {
                jg.writeArrayFieldStart("children");
            }
            return node.childNodes;
        }

        @Override
        public void revisitAfterChildren(ProfileNode node) throws IOException {
            if (!node.childNodes.isEmpty()) {
                jg.writeEndArray();
            }
            jg.writeEndObject();
        }
    }
}
