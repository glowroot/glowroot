/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.common.repo.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.security.CodeSource;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class Compilations {

    private static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("\\bpublic\\s+class\\s+([^\\s{]+)[\\s{]");
    private static final Pattern PACKAGE_NAME_PATTERN =
            Pattern.compile("^\\s*\\bpackage\\s+([^;]+)\\s*;");

    private Compilations() {}

    public static Class<?> compile(String source) throws Exception {
        JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnosticCollector =
                new DiagnosticCollector<JavaFileObject>();

        IsolatedClassLoader isolatedClassLoader = new IsolatedClassLoader();

        StandardJavaFileManager standardFileManager = javaCompiler
                .getStandardFileManager(diagnosticCollector, Locale.ENGLISH, Charsets.UTF_8);
        standardFileManager.setLocation(StandardLocation.CLASS_PATH, getCompilationClassPath());
        JavaFileManager fileManager =
                new IsolatedJavaFileManager(standardFileManager, isolatedClassLoader);
        try {
            List<JavaFileObject> compilationUnits = Lists.newArrayList();

            String className = getPublicClassName(source);
            int index = className.lastIndexOf('.');
            String simpleName;
            if (index == -1) {
                simpleName = className;
            } else {
                simpleName = className.substring(index + 1);
            }
            compilationUnits.add(new SourceJavaFileObject(simpleName, source));

            JavaCompiler.CompilationTask task =
                    javaCompiler.getTask(null, fileManager, diagnosticCollector, null, null,
                            compilationUnits);
            task.call();

            List<Diagnostic<? extends JavaFileObject>> diagnostics =
                    diagnosticCollector.getDiagnostics();
            if (!diagnostics.isEmpty()) {
                List<String> compilationErrors = Lists.newArrayList();
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                    compilationErrors.add(diagnostic.toString());
                }
                throw new CompilationException(compilationErrors);
            }
            if (className.isEmpty()) {
                throw new CompilationException(ImmutableList.of("Class must be public"));
            }
            return isolatedClassLoader.loadClass(className);
        } finally {
            fileManager.close();
        }
    }

    private static List<File> getCompilationClassPath() throws Exception {
        // selenium-api, selenium-support and guava are needed for compilation
        // cannot use default system classpath when running in a servlet container
        return ImmutableList.of(getJarFile("org.openqa.selenium.WebDriver"),
                getJarFile("org.openqa.selenium.support.ui.ExpectedConditions"),
                getJarFile("com.google.common.base.Function"));
    }

    private static File getJarFile(String className) throws Exception {
        CodeSource codeSource = Class.forName(className).getProtectionDomain().getCodeSource();
        if (codeSource == null) {
            throw new IllegalStateException("Code source is null for class: " + className);
        }
        URL location = codeSource.getLocation();
        return new File(location.toURI());
    }

    @VisibleForTesting
    static String getPublicClassName(String source) {
        Matcher matcher = CLASS_NAME_PATTERN.matcher(source);
        if (matcher.find()) {
            return getPackagePrefix(source) + matcher.group(1);
        } else {
            return "";
        }
    }

    private static String getPackagePrefix(String source) {
        Matcher matcher = PACKAGE_NAME_PATTERN.matcher(source);
        if (matcher.find()) {
            return matcher.group(1) + ".";
        } else {
            return "";
        }
    }

    @SuppressWarnings("serial")
    public static class CompilationException extends Exception {

        private final List<String> compilationErrors;

        private CompilationException(List<String> compilationErrors) {
            this.compilationErrors = compilationErrors;
        }

        public List<String> getCompilationErrors() {
            return compilationErrors;
        }
    }

    private static class IsolatedJavaFileManager
            extends ForwardingJavaFileManager<JavaFileManager> {

        private final IsolatedClassLoader loader;

        private IsolatedJavaFileManager(JavaFileManager fileManager, IsolatedClassLoader loader) {
            super(fileManager);
            this.loader = loader;
        }

        @Override
        public ClassLoader getClassLoader(Location location) {
            return loader;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location,
                String className, JavaFileObject.Kind kind, FileObject sibling) throws IOException {
            CompiledJavaFileObject javaFileObject = new CompiledJavaFileObject();
            loader.compiledJavaFileObjects.put(className, javaFileObject);
            return javaFileObject;
        }
    }

    private static class IsolatedClassLoader extends ClassLoader {

        private final Map<String, CompiledJavaFileObject> compiledJavaFileObjects =
                Maps.newHashMap();

        private IsolatedClassLoader() {
            super(IsolatedClassLoader.class.getClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            CompiledJavaFileObject compiledJavaFileObject = compiledJavaFileObjects.get(name);
            if (compiledJavaFileObject == null) {
                return super.findClass(name);
            }
            byte[] byteCode = compiledJavaFileObject.baos.toByteArray();
            return defineClass(name, byteCode, 0, byteCode.length);
        }
    }

    private static class SourceJavaFileObject extends SimpleJavaFileObject {

        private final String source;

        private SourceJavaFileObject(String simpleClassName, String source) {
            super(URI.create(simpleClassName + JavaFileObject.Kind.SOURCE.extension),
                    JavaFileObject.Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static class CompiledJavaFileObject extends SimpleJavaFileObject {

        private final ByteArrayOutputStream baos = new ByteArrayOutputStream();

        protected CompiledJavaFileObject() {
            super(URI.create(""), Kind.CLASS);
        }

        @Override
        public OutputStream openOutputStream() throws IOException {
            return baos;
        }
    }
}
