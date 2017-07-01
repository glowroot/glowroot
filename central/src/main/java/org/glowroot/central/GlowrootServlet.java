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
package org.glowroot.central;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.annotation.Nullable;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.io.CharStreams;
import io.netty.buffer.ByteBuf;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.ui.ChunkSource;
import org.glowroot.ui.ChunkSource.ChunkCopier;
import org.glowroot.ui.CommonHandler;
import org.glowroot.ui.CommonHandler.CommonRequest;
import org.glowroot.ui.CommonHandler.CommonResponse;

import static com.google.common.base.Preconditions.checkNotNull;

@WebServlet("/*")
@SuppressWarnings("serial")
public class GlowrootServlet extends HttpServlet {

    private volatile @MonotonicNonNull CentralModule centralModule;
    private volatile @MonotonicNonNull CommonHandler commonHandler;

    @Override
    public void init(ServletConfig config) throws ServletException {
        try {
            File centralDir = getCentralDir();
            File propFile = new File(centralDir, "glowroot-central.properties");
            if (!propFile.exists()) {
                Files.copy(config.getServletContext().getResourceAsStream(
                        "/META-INF/glowroot-central.properties"), propFile.toPath());
            }
            centralModule = CentralModule.createForServletContainer(centralDir);
            commonHandler = centralModule.getCommonHandler();
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    @Override
    public void destroy() {
        if (centralModule != null) {
            centralModule.shutdown();
        }
    }

    @Override
    public void service(ServletRequest req, ServletResponse res)
            throws ServletException, IOException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        CommonResponse commonResponse;
        try {
            commonResponse = checkNotNull(commonHandler).handle(new ServletReq(request));
        } catch (Exception e) {
            throw new ServletException(e);
        }
        response.setStatus(commonResponse.getStatus().code());
        for (Entry<String, String> entry : commonResponse.getHeaders()) {
            response.addHeader(entry.getKey(), entry.getValue());
        }
        Object content = commonResponse.getContent();
        if (content instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) content;
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            response.getOutputStream().write(bytes);
            response.flushBuffer();
        } else if (content instanceof ChunkSource) {
            ChunkSource chunkSource = (ChunkSource) content;
            String zipFileName = commonResponse.getZipFileName();
            if (zipFileName == null) {
                PrintWriter out = res.getWriter();
                ChunkCopier copier = chunkSource.getCopier(out);
                while (copier.copyNext()) {
                }
                out.flush();
            } else {
                ServletOutputStream out = res.getOutputStream();
                ZipOutputStream zipOut = new ZipOutputStream(out);
                zipOut.putNextEntry(new ZipEntry(zipFileName + ".html"));
                OutputStreamWriter zipWriter = new OutputStreamWriter(zipOut, Charsets.UTF_8);
                ChunkCopier copier = chunkSource.getCopier(zipWriter);
                while (copier.copyNext()) {
                }
                zipWriter.close();
                out.flush();
            }
        } else {
            throw new IllegalStateException("Unexpected content: " + content.getClass().getName());
        }
    }

    private static File getCentralDir() throws IOException {
        String centralDirPath = System.getProperty("glowroot.central.dir");
        if (Strings.isNullOrEmpty(centralDirPath)) {
            return getDefaultCentralDir();
        }
        File centralDir = new File(centralDirPath);
        centralDir.mkdirs();
        if (!centralDir.isDirectory()) {
            // not using logger since the central dir is needed to set up the logger
            return getDefaultCentralDir();
        }
        return centralDir;
    }

    private static File getDefaultCentralDir() throws IOException {
        File centralDir = new File("glowroot-central");
        if (!centralDir.exists()) {
            // upgrade from 0.9.11 to 0.9.12 if needed
            File oldCentralDir = new File("glowroot");
            if (oldCentralDir.exists() && !oldCentralDir.renameTo(centralDir)) {
                throw new IOException("Unable to rename glowroot central directory from '"
                        + oldCentralDir.getAbsolutePath() + "' to '" + centralDir.getAbsolutePath()
                        + "'");
            }
        }
        centralDir.mkdirs();
        if (!centralDir.isDirectory()) {
            throw new IOException("Could not create directory: " + centralDir.getAbsolutePath());
        }
        return centralDir;
    }

    private static class ServletReq implements CommonRequest {

        private final HttpServletRequest request;

        private ServletReq(HttpServletRequest request) {
            this.request = request;
        }

        @Override
        public String getMethod() {
            return request.getMethod();
        }

        // includes context path
        @Override
        public String getUri() {
            return request.getRequestURI();
        }

        @Override
        public String getContextPath() {
            return request.getContextPath();
        }

        // does not include context path
        @Override
        public String getPath() {
            return request.getPathInfo();
        }

        @Override
        public @Nullable String getHeader(CharSequence name) {
            return request.getHeader(name.toString());
        }

        @Override
        public Map<String, List<String>> getParameters() {
            Map<String, List<String>> parameters = Maps.newHashMap();
            for (Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
                parameters.put(entry.getKey(), Arrays.asList(entry.getValue()));
            }
            return parameters;
        }

        @Override
        public List<String> getParameters(String name) {
            String[] values = request.getParameterValues(name);
            if (values == null) {
                return ImmutableList.of();
            } else {
                return Arrays.asList(values);
            }
        }

        @Override
        public String getContent() throws IOException {
            return CharStreams.toString(request.getReader());
        }
    }
}
