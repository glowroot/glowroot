package org.glowroot.agent.plugin.servlet;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.wire.api.model.TraceOuterClass;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
/*
Test that I had hoped would trigger the error
Caused by: java.lang.ClassCastException: class org.glowroot.agent.plugin.servlet.bclglowrootbcl.ServletMessageSupplier cannot be cast to class org.glowroot.agent.plugin.jakartaservlet.bclglowrootbcl.ServletMessageSupplier (org.glowroot.agent.plugin.servlet.bclglowrootbcl.ServletMessageSupplier and org.glowroot.agent.plugin.jakartaservlet.bclglowrootbcl.ServletMessageSupplier are in unnamed module of loader 'bootstrap')
        at org.glowroot.agent.plugin.jakartaservlet.RequestParameterAspect$GetParameterAdvice_.onReturn(RequestParameterAspect.java:48) ~[?:?]

In user code that calls getParameterNames on a jakarta.servlet.http.HttpServletRequest instance that is instantiated as a wrapper for
a  javax.servlet.http.HttpServletRequest.

This test case replicates the setup where a Jakarta servlet is invoked by a Javax servlet with adapters wrapping the request and response.

 */
@Disabled("This test doesn't fail. It should unless the corresponding change to org.glowroot.agent.plugin.jakartaservlet.ServletAspect.getServletMessageSupplier has been applied")
public class ServletPluginWithWrappedJakartaIT {
    private static Container container;

    @BeforeAll
    public static void setUp() throws Exception {
        container = Containers.create();
    }

    @AfterAll
    public static void tearDown() throws Exception {
        container.close();
    }

    @AfterEach
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void testServlet() throws Exception {
        // when
        TraceOuterClass.Trace trace = container.execute(DelegatingJavaxServlet.class, "Web");

        // then
        TraceOuterClass.Trace.Header header = trace.getHeader();
        assertThat(header.getHeadline()).isEqualTo("/testservlet?param=value");
        assertThat(header.getTransactionName()).isEqualTo("/testservlet?param=value");
        assertThat(getDetailValue(header, "Request http method")).isEqualTo("GET");
        assertThat(getDetailValueLong(header, "Response code")).isEqualTo(200);
        assertThat(header.getEntryCount()).isZero();
    }

    private static String getDetailValue(TraceOuterClass.Trace.Header header, String name) {
        for (TraceOuterClass.Trace.DetailEntry detail : header.getDetailEntryList()) {
            if (detail.getName().equals(name)) {
                return detail.getValueList().get(0).getString();
            }
        }
        return null;
    }

    private static Long getDetailValueLong(TraceOuterClass.Trace.Header header, String name) {
        for (TraceOuterClass.Trace.DetailEntry detail : header.getDetailEntryList()) {
            if (detail.getName().equals(name)) {
                return detail.getValueList().get(0).getLong();
            }
        }
        return null;
    }

    public static class JakartaServlet extends jakarta.servlet.http.HttpServlet{
        @Override
        protected void service(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) throws jakarta.servlet.ServletException, IOException {
            Map<String, String[]> params = request.getParameterMap();
            response.setStatus(200);
        }
    }

    public static class DelegatingJavaxServlet extends javax.servlet.http.HttpServlet  implements AppUnderTest {
        final jakarta.servlet.http.HttpServlet delegate;
        public DelegatingJavaxServlet(){
            delegate = new  JakartaServlet();
        }
        @Override
        public void executeApp() throws Exception {
            MockHttpServletRequest request = new MockCatalinaHttpServletRequest("GET", "/testservlet?param=value");
            MockHttpServletResponse response = new TestServlet.PatchedMockHttpServletResponse();
            before(request, response);
            service((ServletRequest) request, (ServletResponse) response);
        }

        protected void before(@SuppressWarnings("unused") javax.servlet.http.HttpServletRequest request,
                              @SuppressWarnings("unused") javax.servlet.http.HttpServletResponse response) {}

        @Override
        protected void service( javax.servlet.http.HttpServletRequest request,  javax.servlet.http.HttpServletResponse response) throws javax.servlet.ServletException, IOException {
            try {
                delegate.service(new JakartRequestAdapter(request), new JakartResponseAdapter(response));
            } catch (jakarta.servlet.ServletException e) {
                throw new ServletException(e);
            }
        }

        private class JakartRequestAdapter implements jakarta.servlet.http.HttpServletRequest {
            private final HttpServletRequest wrapped;

            public JakartRequestAdapter(HttpServletRequest request) {
                wrapped = request;
            }

            @Override
            public Object getAttribute(String name) {
                return wrapped.getAttribute(name);
            }

            @Override
            public Enumeration<String> getAttributeNames() {
                return wrapped.getAttributeNames();
            }

            @Override
            public String getCharacterEncoding() {
                return wrapped.getCharacterEncoding();
            }

            @Override
            public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
                wrapped.setCharacterEncoding(env);
            }

            @Override
            public int getContentLength() {
                return wrapped.getContentLength();
            }

            @Override
            public long getContentLengthLong() {
                return wrapped.getContentLengthLong();
            }

            @Override
            public String getContentType() {
                return wrapped.getContentType();
            }

            @Override
            public jakarta.servlet.ServletInputStream getInputStream() throws IOException {
                return new ServletInputStreamAdapter(wrapped.getInputStream());
            }

            @Override
            public String getParameter(String name) {
                return wrapped.getParameter(name);
            }

            @Override
            public Enumeration<String> getParameterNames() {
                return wrapped.getParameterNames();
            }

            @Override
            public String[] getParameterValues(String name) {
                return wrapped.getParameterValues(name);
            }

            @Override
            public Map<String, String[]> getParameterMap() {
                return wrapped.getParameterMap();
            }

            @Override
            public String getProtocol() {
                return wrapped.getProtocol();
            }

            @Override
            public String getScheme() {
                return wrapped.getScheme();
            }

            @Override
            public String getServerName() {
                return wrapped.getServerName();
            }

            @Override
            public int getServerPort() {
                return wrapped.getServerPort();
            }

            @Override
            public BufferedReader getReader() throws IOException {
                return wrapped.getReader();
            }

            @Override
            public String getRemoteAddr() {
                return wrapped.getRemoteAddr();
            }

            @Override
            public String getRemoteHost() {
                return wrapped.getRemoteHost();
            }

            @Override
            public void setAttribute(String name, Object o) {
                wrapped.setAttribute(name, o);
            }

            @Override
            public void removeAttribute(String name) {
                wrapped.removeAttribute(name);
            }

            @Override
            public Locale getLocale() {
                return wrapped.getLocale();
            }

            @Override
            public Enumeration<Locale> getLocales() {
                return wrapped.getLocales();
            }

            @Override
            public boolean isSecure() {
                return wrapped.isSecure();
            }

            @Override
            public jakarta.servlet.RequestDispatcher getRequestDispatcher(String path) {
                return new RequestDispatcherAdapter(wrapped.getRequestDispatcher(path));
            }

            @Override
            public String getRealPath(String path) {
                return wrapped.getRealPath(path);
            }

            @Override
            public int getRemotePort() {
                return wrapped.getRemotePort();
            }

            @Override
            public String getLocalName() {
                return wrapped.getLocalName();
            }

            @Override
            public String getLocalAddr() {
                return wrapped.getLocalAddr();
            }

            @Override
            public int getLocalPort() {
                return wrapped.getLocalPort();
            }

            @Override
            public jakarta.servlet.ServletContext getServletContext() {
                return new ServletContextAdapter(wrapped.getServletContext());
            }

            @Override
            public jakarta.servlet.AsyncContext startAsync() throws IllegalStateException {
                throw new UnsupportedOperationException("Async not supported in adapter");
            }

            @Override
            public jakarta.servlet.AsyncContext startAsync(jakarta.servlet.ServletRequest servletRequest, jakarta.servlet.ServletResponse servletResponse) throws IllegalStateException {
                throw new UnsupportedOperationException("Async not supported in adapter");
            }

            @Override
            public boolean isAsyncStarted() {
                return wrapped.isAsyncStarted();
            }

            @Override
            public boolean isAsyncSupported() {
                return wrapped.isAsyncSupported();
            }

            @Override
            public jakarta.servlet.AsyncContext getAsyncContext() {
                throw new UnsupportedOperationException("Async not supported in adapter");
            }

            @Override
            public jakarta.servlet.DispatcherType getDispatcherType() {
                javax.servlet.DispatcherType javaxType = wrapped.getDispatcherType();
                return jakarta.servlet.DispatcherType.valueOf(javaxType.name());
            }

            // Additional HttpServletRequest methods
            @Override
            public String getAuthType() {
                return wrapped.getAuthType();
            }

            @Override
            public jakarta.servlet.http.Cookie[] getCookies() {
                javax.servlet.http.Cookie[] javaxCookies = wrapped.getCookies();
                if (javaxCookies == null) {
                    return null;
                }
                jakarta.servlet.http.Cookie[] jakartaCookies = new jakarta.servlet.http.Cookie[javaxCookies.length];
                for (int i = 0; i < javaxCookies.length; i++) {
                    javax.servlet.http.Cookie javaxCookie = javaxCookies[i];
                    jakarta.servlet.http.Cookie jakartaCookie = new jakarta.servlet.http.Cookie(
                        javaxCookie.getName(), javaxCookie.getValue());
                    jakartaCookie.setComment(javaxCookie.getComment());
                    jakartaCookie.setDomain(javaxCookie.getDomain());
                    jakartaCookie.setMaxAge(javaxCookie.getMaxAge());
                    jakartaCookie.setPath(javaxCookie.getPath());
                    jakartaCookie.setSecure(javaxCookie.getSecure());
                    jakartaCookie.setVersion(javaxCookie.getVersion());
                    jakartaCookie.setHttpOnly(javaxCookie.isHttpOnly());
                    jakartaCookies[i] = jakartaCookie;
                }
                return jakartaCookies;
            }

            @Override
            public long getDateHeader(String name) {
                return wrapped.getDateHeader(name);
            }

            @Override
            public String getHeader(String name) {
                return wrapped.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return wrapped.getHeaders(name);
            }

            @Override
            public Enumeration<String> getHeaderNames() {
                return wrapped.getHeaderNames();
            }

            @Override
            public int getIntHeader(String name) {
                return wrapped.getIntHeader(name);
            }

            @Override
            public String getMethod() {
                return wrapped.getMethod();
            }

            @Override
            public String getPathInfo() {
                return wrapped.getPathInfo();
            }

            @Override
            public String getPathTranslated() {
                return wrapped.getPathTranslated();
            }

            @Override
            public String getContextPath() {
                return wrapped.getContextPath();
            }

            @Override
            public String getQueryString() {
                return wrapped.getQueryString();
            }

            @Override
            public String getRemoteUser() {
                return wrapped.getRemoteUser();
            }

            @Override
            public boolean isUserInRole(String role) {
                return wrapped.isUserInRole(role);
            }

            @Override
            public java.security.Principal getUserPrincipal() {
                return wrapped.getUserPrincipal();
            }

            @Override
            public String getRequestedSessionId() {
                return wrapped.getRequestedSessionId();
            }

            @Override
            public String getRequestURI() {
                return wrapped.getRequestURI();
            }

            @Override
            public StringBuffer getRequestURL() {
                return wrapped.getRequestURL();
            }

            @Override
            public String getServletPath() {
                return wrapped.getServletPath();
            }

            @Override
            public jakarta.servlet.http.HttpSession getSession(boolean create) {
                javax.servlet.http.HttpSession javaxSession = wrapped.getSession(create);
                return javaxSession != null ? new HttpSessionAdapter(javaxSession) : null;
            }

            @Override
            public jakarta.servlet.http.HttpSession getSession() {
                return getSession(true);
            }

            @Override
            public String changeSessionId() {
                return wrapped.changeSessionId();
            }

            @Override
            public boolean isRequestedSessionIdValid() {
                return wrapped.isRequestedSessionIdValid();
            }

            @Override
            public boolean isRequestedSessionIdFromCookie() {
                return wrapped.isRequestedSessionIdFromCookie();
            }

            @Override
            public boolean isRequestedSessionIdFromURL() {
                return wrapped.isRequestedSessionIdFromURL();
            }

            @Override
            @Deprecated
            public boolean isRequestedSessionIdFromUrl() {
                return wrapped.isRequestedSessionIdFromUrl();
            }

            @Override
            public boolean authenticate(jakarta.servlet.http.HttpServletResponse response) throws IOException, jakarta.servlet.ServletException {
                try {
                    return wrapped.authenticate(new JavaxHttpResponseAdapter(response));
                } catch (javax.servlet.ServletException e) {
                    throw new jakarta.servlet.ServletException(e);
                }
            }

            @Override
            public void login(String username, String password) throws jakarta.servlet.ServletException {
                try {
                    wrapped.login(username, password);
                } catch (javax.servlet.ServletException e) {
                    throw new jakarta.servlet.ServletException(e);
                }
            }

            @Override
            public void logout() throws jakarta.servlet.ServletException {
                try {
                    wrapped.logout();
                } catch (javax.servlet.ServletException e) {
                    throw new jakarta.servlet.ServletException(e);
                }
            }

            @Override
            public java.util.Collection<jakarta.servlet.http.Part> getParts() throws IOException, jakarta.servlet.ServletException {
                try {
                    java.util.Collection<javax.servlet.http.Part> javaxParts = wrapped.getParts();
                    java.util.List<jakarta.servlet.http.Part> jakartaParts = new java.util.ArrayList<>();
                    for (javax.servlet.http.Part javaxPart : javaxParts) {
                        jakartaParts.add(new PartAdapter(javaxPart));
                    }
                    return jakartaParts;
                } catch (javax.servlet.ServletException e) {
                    throw new jakarta.servlet.ServletException(e);
                }
            }

            @Override
            public jakarta.servlet.http.Part getPart(String name) throws IOException, jakarta.servlet.ServletException {
                try {
                    javax.servlet.http.Part javaxPart = wrapped.getPart(name);
                    return javaxPart != null ? new PartAdapter(javaxPart) : null;
                } catch (javax.servlet.ServletException e) {
                    throw new jakarta.servlet.ServletException(e);
                }
            }

            @Override
            public <T extends jakarta.servlet.http.HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, jakarta.servlet.ServletException {
                throw new UnsupportedOperationException("HTTP upgrade not supported in adapter");
            }
        }

        private class JakartResponseAdapter implements jakarta.servlet.http.HttpServletResponse {
            private final HttpServletResponse wrapped;

            public JakartResponseAdapter(HttpServletResponse response) {
                wrapped = response;
            }

            @Override
            public String getCharacterEncoding() {
                return wrapped.getCharacterEncoding();
            }

            @Override
            public String getContentType() {
                return wrapped.getContentType();
            }

            @Override
            public jakarta.servlet.ServletOutputStream getOutputStream() throws IOException {
                return new ServletOutputStreamAdapter(wrapped.getOutputStream());
            }

            @Override
            public PrintWriter getWriter() throws IOException {
                return wrapped.getWriter();
            }

            @Override
            public void setCharacterEncoding(String charset) {
                wrapped.setCharacterEncoding(charset);
            }

            @Override
            public void setContentLength(int len) {
                wrapped.setContentLength(len);
            }

            @Override
            public void setContentLengthLong(long len) {
                wrapped.setContentLengthLong(len);
            }

            @Override
            public void setContentType(String type) {
                wrapped.setContentType(type);
            }

            @Override
            public void setBufferSize(int size) {
                wrapped.setBufferSize(size);
            }

            @Override
            public int getBufferSize() {
                return wrapped.getBufferSize();
            }

            @Override
            public void flushBuffer() throws IOException {
                wrapped.flushBuffer();
            }

            @Override
            public void resetBuffer() {
                wrapped.resetBuffer();
            }

            @Override
            public boolean isCommitted() {
                return wrapped.isCommitted();
            }

            @Override
            public void reset() {
                wrapped.reset();
            }

            @Override
            public void setLocale(Locale loc) {
                wrapped.setLocale(loc);
            }

            @Override
            public Locale getLocale() {
                return wrapped.getLocale();
            }

            // Additional HttpServletResponse methods
            @Override
            public void addCookie(jakarta.servlet.http.Cookie cookie) {
                javax.servlet.http.Cookie javaxCookie = new javax.servlet.http.Cookie(
                    cookie.getName(), cookie.getValue());
                javaxCookie.setComment(cookie.getComment());
                javaxCookie.setDomain(cookie.getDomain());
                javaxCookie.setMaxAge(cookie.getMaxAge());
                javaxCookie.setPath(cookie.getPath());
                javaxCookie.setSecure(cookie.getSecure());
                javaxCookie.setVersion(cookie.getVersion());
                javaxCookie.setHttpOnly(cookie.isHttpOnly());
                wrapped.addCookie(javaxCookie);
            }

            @Override
            public boolean containsHeader(String name) {
                return wrapped.containsHeader(name);
            }

            @Override
            public String encodeURL(String url) {
                return wrapped.encodeURL(url);
            }

            @Override
            public String encodeRedirectURL(String url) {
                return wrapped.encodeRedirectURL(url);
            }

            @Override
            @Deprecated
            public String encodeUrl(String url) {
                return wrapped.encodeUrl(url);
            }

            @Override
            @Deprecated
            public String encodeRedirectUrl(String url) {
                return wrapped.encodeRedirectUrl(url);
            }

            @Override
            public void sendError(int sc, String msg) throws IOException {
                wrapped.sendError(sc, msg);
            }

            @Override
            public void sendError(int sc) throws IOException {
                wrapped.sendError(sc);
            }

            @Override
            public void sendRedirect(String location) throws IOException {
                wrapped.sendRedirect(location);
            }

            @Override
            public void setDateHeader(String name, long date) {
                wrapped.setDateHeader(name, date);
            }

            @Override
            public void addDateHeader(String name, long date) {
                wrapped.addDateHeader(name, date);
            }

            @Override
            public void setHeader(String name, String value) {
                wrapped.setHeader(name, value);
            }

            @Override
            public void addHeader(String name, String value) {
                wrapped.addHeader(name, value);
            }

            @Override
            public void setIntHeader(String name, int value) {
                wrapped.setIntHeader(name, value);
            }

            @Override
            public void addIntHeader(String name, int value) {
                wrapped.addIntHeader(name, value);
            }

            @Override
            public void setStatus(int sc) {
                wrapped.setStatus(sc);
            }

            @Override
            @Deprecated
            public void setStatus(int sc, String sm) {
                wrapped.setStatus(sc, sm);
            }

            @Override
            public int getStatus() {
                return wrapped.getStatus();
            }

            @Override
            public String getHeader(String name) {
                return wrapped.getHeader(name);
            }

            @Override
            public java.util.Collection<String> getHeaders(String name) {
                return wrapped.getHeaders(name);
            }

            @Override
            public java.util.Collection<String> getHeaderNames() {
                return wrapped.getHeaderNames();
            }
        }

        private class ServletInputStreamAdapter extends jakarta.servlet.ServletInputStream {
            private final javax.servlet.ServletInputStream wrapped;

            public ServletInputStreamAdapter(javax.servlet.ServletInputStream wrapped) {
                this.wrapped = wrapped;
            }

            @Override
            public int read() throws IOException {
                return wrapped.read();
            }

            @Override
            public int read(byte[] b) throws IOException {
                return wrapped.read(b);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return wrapped.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                return wrapped.isFinished();
            }

            @Override
            public boolean isReady() {
                return wrapped.isReady();
            }

            @Override
            public void setReadListener(jakarta.servlet.ReadListener readListener) {
                // Convert jakarta ReadListener to javax ReadListener
                wrapped.setReadListener(new javax.servlet.ReadListener() {
                    @Override
                    public void onDataAvailable() throws IOException {
                        readListener.onDataAvailable();
                    }

                    @Override
                    public void onAllDataRead() throws IOException {
                        readListener.onAllDataRead();
                    }

                    @Override
                    public void onError(Throwable t) {
                        readListener.onError(t);
                    }
                });
            }
        }

        private class ServletOutputStreamAdapter extends jakarta.servlet.ServletOutputStream {
            private final javax.servlet.ServletOutputStream wrapped;

            public ServletOutputStreamAdapter(javax.servlet.ServletOutputStream wrapped) {
                this.wrapped = wrapped;
            }

            @Override
            public void write(int b) throws IOException {
                wrapped.write(b);
            }

            @Override
            public void write(byte[] b) throws IOException {
                wrapped.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                wrapped.write(b, off, len);
            }

            @Override
            public boolean isReady() {
                return wrapped.isReady();
            }

            @Override
            public void setWriteListener(jakarta.servlet.WriteListener writeListener) {
                // Convert jakarta WriteListener to javax WriteListener
                wrapped.setWriteListener(new javax.servlet.WriteListener() {
                    @Override
                    public void onWritePossible() throws IOException {
                        writeListener.onWritePossible();
                    }

                    @Override
                    public void onError(Throwable t) {
                        writeListener.onError(t);
                    }
                });
            }

            @Override
            public void flush() throws IOException {
                wrapped.flush();
            }

            @Override
            public void close() throws IOException {
                wrapped.close();
            }
        }

        private class RequestDispatcherAdapter implements jakarta.servlet.RequestDispatcher {
            private final javax.servlet.RequestDispatcher wrapped;

            public RequestDispatcherAdapter(javax.servlet.RequestDispatcher wrapped) {
                this.wrapped = wrapped;
            }

            @Override
            public void forward(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws jakarta.servlet.ServletException, IOException {
                try {
                    wrapped.forward(new JavaxRequestAdapter(request), new JavaxResponseAdapter(response));
                } catch (javax.servlet.ServletException e) {
                    throw new jakarta.servlet.ServletException(e);
                }
            }

            @Override
            public void include(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response)
                    throws jakarta.servlet.ServletException, IOException {
                try {
                    wrapped.include(new JavaxRequestAdapter(request), new JavaxResponseAdapter(response));
                } catch (javax.servlet.ServletException e) {
                    throw new jakarta.servlet.ServletException(e);
                }
            }
        }

        private class ServletContextAdapter implements jakarta.servlet.ServletContext {
            private final javax.servlet.ServletContext wrapped;

            public ServletContextAdapter(javax.servlet.ServletContext wrapped) {
                this.wrapped = wrapped;
            }

            @Override
            public String getContextPath() {
                return wrapped.getContextPath();
            }

            @Override
            public jakarta.servlet.ServletContext getContext(String uripath) {
                javax.servlet.ServletContext context = wrapped.getContext(uripath);
                return context != null ? new ServletContextAdapter(context) : null;
            }

            @Override
            public int getMajorVersion() {
                return wrapped.getMajorVersion();
            }

            @Override
            public int getMinorVersion() {
                return wrapped.getMinorVersion();
            }

            @Override
            public int getEffectiveMajorVersion() {
                return wrapped.getEffectiveMajorVersion();
            }

            @Override
            public int getEffectiveMinorVersion() {
                return wrapped.getEffectiveMinorVersion();
            }

            @Override
            public String getMimeType(String file) {
                return wrapped.getMimeType(file);
            }

            @Override
            public java.util.Set<String> getResourcePaths(String path) {
                return wrapped.getResourcePaths(path);
            }

            @Override
            public java.net.URL getResource(String path) throws java.net.MalformedURLException {
                return wrapped.getResource(path);
            }

            @Override
            public java.io.InputStream getResourceAsStream(String path) {
                return wrapped.getResourceAsStream(path);
            }

            @Override
            public jakarta.servlet.RequestDispatcher getRequestDispatcher(String path) {
                javax.servlet.RequestDispatcher dispatcher = wrapped.getRequestDispatcher(path);
                return dispatcher != null ? new RequestDispatcherAdapter(dispatcher) : null;
            }

            @Override
            public jakarta.servlet.RequestDispatcher getNamedDispatcher(String name) {
                javax.servlet.RequestDispatcher dispatcher = wrapped.getNamedDispatcher(name);
                return dispatcher != null ? new RequestDispatcherAdapter(dispatcher) : null;
            }

            @Override
            @Deprecated
            public jakarta.servlet.Servlet getServlet(String name) throws jakarta.servlet.ServletException {
                throw new UnsupportedOperationException("getServlet is deprecated and not supported in adapter");
            }

            @Override
            @Deprecated
            public java.util.Enumeration<jakarta.servlet.Servlet> getServlets() {
                throw new UnsupportedOperationException("getServlets is deprecated and not supported in adapter");
            }

            @Override
            @Deprecated
            public java.util.Enumeration<String> getServletNames() {
                throw new UnsupportedOperationException("getServletNames is deprecated and not supported in adapter");
            }

            @Override
            public void log(String msg) {
                wrapped.log(msg);
            }

            @Override
            public void log(Exception exception, String msg) {
                wrapped.log(exception, msg);
            }

            @Deprecated
            public void log(jakarta.servlet.Servlet servlet, String message) {
                throw new UnsupportedOperationException("log(Servlet, String) is deprecated and not supported in adapter");
            }

            @Override
            public void log(String message, Throwable throwable) {
                wrapped.log(message, throwable);
            }

            @Override
            public String getRealPath(String path) {
                return wrapped.getRealPath(path);
            }

            @Override
            public String getServerInfo() {
                return wrapped.getServerInfo();
            }

            @Override
            public String getInitParameter(String name) {
                return wrapped.getInitParameter(name);
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return wrapped.getInitParameterNames();
            }

            @Override
            public boolean setInitParameter(String name, String value) {
                return wrapped.setInitParameter(name, value);
            }

            @Override
            public Object getAttribute(String name) {
                return wrapped.getAttribute(name);
            }

            @Override
            public Enumeration<String> getAttributeNames() {
                return wrapped.getAttributeNames();
            }

            @Override
            public void setAttribute(String name, Object object) {
                wrapped.setAttribute(name, object);
            }

            @Override
            public void removeAttribute(String name) {
                wrapped.removeAttribute(name);
            }

            @Override
            public String getServletContextName() {
                return wrapped.getServletContextName();
            }

            @Override
            public jakarta.servlet.ServletRegistration.Dynamic addServlet(String servletName, String className) {
                throw new UnsupportedOperationException("Dynamic servlet registration not supported in adapter");
            }

            @Override
            public jakarta.servlet.ServletRegistration.Dynamic addServlet(String servletName, jakarta.servlet.Servlet servlet) {
                throw new UnsupportedOperationException("Dynamic servlet registration not supported in adapter");
            }

            @Override
            public jakarta.servlet.ServletRegistration.Dynamic addServlet(String servletName, Class<? extends jakarta.servlet.Servlet> servletClass) {
                throw new UnsupportedOperationException("Dynamic servlet registration not supported in adapter");
            }

            @Override
            public jakarta.servlet.ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
                throw new UnsupportedOperationException("Dynamic JSP registration not supported in adapter");
            }

            @Override
            public <T extends jakarta.servlet.Servlet> T createServlet(Class<T> clazz) {
                throw new UnsupportedOperationException("Servlet creation not supported in adapter");
            }

            @Override
            public jakarta.servlet.ServletRegistration getServletRegistration(String servletName) {
                throw new UnsupportedOperationException("Servlet registration lookup not supported in adapter");
            }

            @Override
            public Map<String, ? extends jakarta.servlet.ServletRegistration> getServletRegistrations() {
                throw new UnsupportedOperationException("Servlet registration lookup not supported in adapter");
            }

            @Override
            public jakarta.servlet.FilterRegistration.Dynamic addFilter(String filterName, String className) {
                throw new UnsupportedOperationException("Dynamic filter registration not supported in adapter");
            }

            @Override
            public jakarta.servlet.FilterRegistration.Dynamic addFilter(String filterName, jakarta.servlet.Filter filter) {
                throw new UnsupportedOperationException("Dynamic filter registration not supported in adapter");
            }

            @Override
            public jakarta.servlet.FilterRegistration.Dynamic addFilter(String filterName, Class<? extends jakarta.servlet.Filter> filterClass) {
                throw new UnsupportedOperationException("Dynamic filter registration not supported in adapter");
            }

            @Override
            public <T extends jakarta.servlet.Filter> T createFilter(Class<T> clazz) {
                throw new UnsupportedOperationException("Filter creation not supported in adapter");
            }

            @Override
            public jakarta.servlet.FilterRegistration getFilterRegistration(String filterName) {
                throw new UnsupportedOperationException("Filter registration lookup not supported in adapter");
            }

            @Override
            public Map<String, ? extends jakarta.servlet.FilterRegistration> getFilterRegistrations() {
                throw new UnsupportedOperationException("Filter registration lookup not supported in adapter");
            }

            @Override
            public jakarta.servlet.SessionCookieConfig getSessionCookieConfig() {
                throw new UnsupportedOperationException("Session cookie config not supported in adapter");
            }

            @Override
            public void setSessionTrackingModes(java.util.Set<jakarta.servlet.SessionTrackingMode> sessionTrackingModes) {
                throw new UnsupportedOperationException("Session tracking modes not supported in adapter");
            }

            @Override
            public java.util.Set<jakarta.servlet.SessionTrackingMode> getDefaultSessionTrackingModes() {
                throw new UnsupportedOperationException("Session tracking modes not supported in adapter");
            }

            @Override
            public java.util.Set<jakarta.servlet.SessionTrackingMode> getEffectiveSessionTrackingModes() {
                throw new UnsupportedOperationException("Session tracking modes not supported in adapter");
            }

            @Override
            public void addListener(String className) {
                throw new UnsupportedOperationException("Dynamic listener registration not supported in adapter");
            }

            @Override
            public <T extends java.util.EventListener> void addListener(T t) {
                throw new UnsupportedOperationException("Dynamic listener registration not supported in adapter");
            }

            @Override
            public void addListener(Class<? extends java.util.EventListener> listenerClass) {
                throw new UnsupportedOperationException("Dynamic listener registration not supported in adapter");
            }

            @Override
            public <T extends java.util.EventListener> T createListener(Class<T> clazz) {
                throw new UnsupportedOperationException("Listener creation not supported in adapter");
            }

            @Override
            public jakarta.servlet.descriptor.JspConfigDescriptor getJspConfigDescriptor() {
                throw new UnsupportedOperationException("JSP config descriptor not supported in adapter");
            }

            @Override
            public ClassLoader getClassLoader() {
                return wrapped.getClassLoader();
            }

            @Override
            public void declareRoles(String... roleNames) {
                wrapped.declareRoles(roleNames);
            }

            @Override
            public String getVirtualServerName() {
                return wrapped.getVirtualServerName();
            }

            @Override
            public int getSessionTimeout() {
                // These methods don't exist in javax.servlet.ServletContext, so return defaults
                return 30; // Default session timeout in minutes
            }

            @Override
            public void setSessionTimeout(int sessionTimeout) {
                // This method doesn't exist in javax.servlet.ServletContext, so do nothing
            }

            @Override
            public String getRequestCharacterEncoding() {
                // This method doesn't exist in javax.servlet.ServletContext, so return null
                return null;
            }

            @Override
            public void setRequestCharacterEncoding(String encoding) {
                // This method doesn't exist in javax.servlet.ServletContext, so do nothing
            }

            @Override
            public String getResponseCharacterEncoding() {
                // This method doesn't exist in javax.servlet.ServletContext, so return null
                return null;
            }

            @Override
            public void setResponseCharacterEncoding(String encoding) {
                // This method doesn't exist in javax.servlet.ServletContext, so do nothing
            }
        }

        private class JavaxRequestAdapter implements javax.servlet.ServletRequest {
            private final jakarta.servlet.ServletRequest wrapped;

            public JavaxRequestAdapter(jakarta.servlet.ServletRequest wrapped) {
                this.wrapped = wrapped;
            }

            @Override
            public Object getAttribute(String name) {
                return wrapped.getAttribute(name);
            }

            @Override
            public Enumeration<String> getAttributeNames() {
                return wrapped.getAttributeNames();
            }

            @Override
            public String getCharacterEncoding() {
                return wrapped.getCharacterEncoding();
            }

            @Override
            public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
                wrapped.setCharacterEncoding(env);
            }

            @Override
            public int getContentLength() {
                return wrapped.getContentLength();
            }

            @Override
            public long getContentLengthLong() {
                return wrapped.getContentLengthLong();
            }

            @Override
            public String getContentType() {
                return wrapped.getContentType();
            }

            @Override
            public javax.servlet.ServletInputStream getInputStream() throws IOException {
                throw new UnsupportedOperationException("Input stream conversion not supported");
            }

            @Override
            public String getParameter(String name) {
                return wrapped.getParameter(name);
            }

            @Override
            public Enumeration<String> getParameterNames() {
                return wrapped.getParameterNames();
            }

            @Override
            public String[] getParameterValues(String name) {
                return wrapped.getParameterValues(name);
            }

            @Override
            public Map<String, String[]> getParameterMap() {
                return wrapped.getParameterMap();
            }

            @Override
            public String getProtocol() {
                return wrapped.getProtocol();
            }

            @Override
            public String getScheme() {
                return wrapped.getScheme();
            }

            @Override
            public String getServerName() {
                return wrapped.getServerName();
            }

            @Override
            public int getServerPort() {
                return wrapped.getServerPort();
            }

            @Override
            public BufferedReader getReader() throws IOException {
                return wrapped.getReader();
            }

            @Override
            public String getRemoteAddr() {
                return wrapped.getRemoteAddr();
            }

            @Override
            public String getRemoteHost() {
                return wrapped.getRemoteHost();
            }

            @Override
            public void setAttribute(String name, Object o) {
                wrapped.setAttribute(name, o);
            }

            @Override
            public void removeAttribute(String name) {
                wrapped.removeAttribute(name);
            }

            @Override
            public Locale getLocale() {
                return wrapped.getLocale();
            }

            @Override
            public Enumeration<Locale> getLocales() {
                return wrapped.getLocales();
            }

            @Override
            public boolean isSecure() {
                return wrapped.isSecure();
            }

            @Override
            public javax.servlet.RequestDispatcher getRequestDispatcher(String path) {
                throw new UnsupportedOperationException("Request dispatcher conversion not supported");
            }

            @Override
            public String getRealPath(String path) {
                return wrapped.getRealPath(path);
            }

            @Override
            public int getRemotePort() {
                return wrapped.getRemotePort();
            }

            @Override
            public String getLocalName() {
                return wrapped.getLocalName();
            }

            @Override
            public String getLocalAddr() {
                return wrapped.getLocalAddr();
            }

            @Override
            public int getLocalPort() {
                return wrapped.getLocalPort();
            }

            @Override
            public javax.servlet.ServletContext getServletContext() {
                throw new UnsupportedOperationException("Servlet context conversion not supported");
            }

            @Override
            public javax.servlet.AsyncContext startAsync() throws IllegalStateException {
                throw new UnsupportedOperationException("Async not supported in adapter");
            }

            @Override
            public javax.servlet.AsyncContext startAsync(javax.servlet.ServletRequest servletRequest, javax.servlet.ServletResponse servletResponse) throws IllegalStateException {
                throw new UnsupportedOperationException("Async not supported in adapter");
            }

            @Override
            public boolean isAsyncStarted() {
                return wrapped.isAsyncStarted();
            }

            @Override
            public boolean isAsyncSupported() {
                return wrapped.isAsyncSupported();
            }

            @Override
            public javax.servlet.AsyncContext getAsyncContext() {
                throw new UnsupportedOperationException("Async not supported in adapter");
            }

            @Override
            public javax.servlet.DispatcherType getDispatcherType() {
                jakarta.servlet.DispatcherType jakartaType = wrapped.getDispatcherType();
                return javax.servlet.DispatcherType.valueOf(jakartaType.name());
            }
        }

        private class JavaxResponseAdapter implements javax.servlet.ServletResponse {
            private final jakarta.servlet.ServletResponse wrapped;

            public JavaxResponseAdapter(jakarta.servlet.ServletResponse wrapped) {
                this.wrapped = wrapped;
            }

            @Override
            public String getCharacterEncoding() {
                return wrapped.getCharacterEncoding();
            }

            @Override
            public String getContentType() {
                return wrapped.getContentType();
            }

            @Override
            public javax.servlet.ServletOutputStream getOutputStream() throws IOException {
                throw new UnsupportedOperationException("Output stream conversion not supported");
            }

            @Override
            public PrintWriter getWriter() throws IOException {
                return wrapped.getWriter();
            }

            @Override
            public void setCharacterEncoding(String charset) {
                wrapped.setCharacterEncoding(charset);
            }

            @Override
            public void setContentLength(int len) {
                wrapped.setContentLength(len);
            }

            @Override
            public void setContentLengthLong(long len) {
                wrapped.setContentLengthLong(len);
            }

            @Override
            public void setContentType(String type) {
                wrapped.setContentType(type);
            }

            @Override
            public void setBufferSize(int size) {
                wrapped.setBufferSize(size);
            }

            @Override
            public int getBufferSize() {
                return wrapped.getBufferSize();
            }

            @Override
            public void flushBuffer() throws IOException {
                wrapped.flushBuffer();
            }

            @Override
            public void resetBuffer() {
                wrapped.resetBuffer();
            }

            @Override
            public boolean isCommitted() {
                return wrapped.isCommitted();
            }

            @Override
            public void reset() {
                wrapped.reset();
            }

            @Override
            public void setLocale(Locale loc) {
                wrapped.setLocale(loc);
            }

            @Override
            public Locale getLocale() {
                return wrapped.getLocale();
            }
        }

        private class HttpSessionAdapter implements jakarta.servlet.http.HttpSession {
            private final javax.servlet.http.HttpSession wrapped;

            public HttpSessionAdapter(javax.servlet.http.HttpSession wrapped) {
                this.wrapped = wrapped;
            }

            @Override
            public long getCreationTime() {
                return wrapped.getCreationTime();
            }

            @Override
            public String getId() {
                return wrapped.getId();
            }

            @Override
            public long getLastAccessedTime() {
                return wrapped.getLastAccessedTime();
            }

            @Override
            public jakarta.servlet.ServletContext getServletContext() {
                return new ServletContextAdapter(wrapped.getServletContext());
            }

            @Override
            public void setMaxInactiveInterval(int interval) {
                wrapped.setMaxInactiveInterval(interval);
            }

            @Override
            public int getMaxInactiveInterval() {
                return wrapped.getMaxInactiveInterval();
            }

            @Override
            @Deprecated
            public jakarta.servlet.http.HttpSessionContext getSessionContext() {
                throw new UnsupportedOperationException("getSessionContext is deprecated and not supported in adapter");
            }

            @Override
            public Object getAttribute(String name) {
                return wrapped.getAttribute(name);
            }

            @Override
            @Deprecated
            public Object getValue(String name) {
                return wrapped.getValue(name);
            }

            @Override
            public Enumeration<String> getAttributeNames() {
                return wrapped.getAttributeNames();
            }

            @Override
            @Deprecated
            public String[] getValueNames() {
                return wrapped.getValueNames();
            }

            @Override
            public void setAttribute(String name, Object value) {
                wrapped.setAttribute(name, value);
            }

            @Override
            @Deprecated
            public void putValue(String name, Object value) {
                wrapped.putValue(name, value);
            }

            @Override
            public void removeAttribute(String name) {
                wrapped.removeAttribute(name);
            }

            @Override
            @Deprecated
            public void removeValue(String name) {
                wrapped.removeValue(name);
            }

            @Override
            public void invalidate() {
                wrapped.invalidate();
            }

            @Override
            public boolean isNew() {
                return wrapped.isNew();
            }
        }

        private class PartAdapter implements jakarta.servlet.http.Part {
            private final javax.servlet.http.Part wrapped;

            public PartAdapter(javax.servlet.http.Part wrapped) {
                this.wrapped = wrapped;
            }

            @Override
            public java.io.InputStream getInputStream() throws IOException {
                return wrapped.getInputStream();
            }

            @Override
            public String getContentType() {
                return wrapped.getContentType();
            }

            @Override
            public String getName() {
                return wrapped.getName();
            }

            @Override
            public String getSubmittedFileName() {
                return wrapped.getSubmittedFileName();
            }

            @Override
            public long getSize() {
                return wrapped.getSize();
            }

            @Override
            public void write(String fileName) throws IOException {
                wrapped.write(fileName);
            }

            @Override
            public void delete() throws IOException {
                wrapped.delete();
            }

            @Override
            public String getHeader(String name) {
                return wrapped.getHeader(name);
            }

            @Override
            public java.util.Collection<String> getHeaders(String name) {
                return wrapped.getHeaders(name);
            }

            @Override
            public java.util.Collection<String> getHeaderNames() {
                return wrapped.getHeaderNames();
            }
        }

        private class JavaxHttpResponseAdapter implements javax.servlet.http.HttpServletResponse {
            private final jakarta.servlet.http.HttpServletResponse wrapped;

            public JavaxHttpResponseAdapter(jakarta.servlet.http.HttpServletResponse wrapped) {
                this.wrapped = wrapped;
            }

            @Override
            public void addCookie(javax.servlet.http.Cookie cookie) {
                jakarta.servlet.http.Cookie jakartaCookie = new jakarta.servlet.http.Cookie(
                    cookie.getName(), cookie.getValue());
                jakartaCookie.setComment(cookie.getComment());
                jakartaCookie.setDomain(cookie.getDomain());
                jakartaCookie.setMaxAge(cookie.getMaxAge());
                jakartaCookie.setPath(cookie.getPath());
                jakartaCookie.setSecure(cookie.getSecure());
                jakartaCookie.setVersion(cookie.getVersion());
                jakartaCookie.setHttpOnly(cookie.isHttpOnly());
                wrapped.addCookie(jakartaCookie);
            }

            @Override
            public boolean containsHeader(String name) {
                return wrapped.containsHeader(name);
            }

            @Override
            public String encodeURL(String url) {
                return wrapped.encodeURL(url);
            }

            @Override
            public String encodeRedirectURL(String url) {
                return wrapped.encodeRedirectURL(url);
            }

            @Override
            @Deprecated
            public String encodeUrl(String url) {
                return wrapped.encodeUrl(url);
            }

            @Override
            @Deprecated
            public String encodeRedirectUrl(String url) {
                return wrapped.encodeRedirectUrl(url);
            }

            @Override
            public void sendError(int sc, String msg) throws IOException {
                wrapped.sendError(sc, msg);
            }

            @Override
            public void sendError(int sc) throws IOException {
                wrapped.sendError(sc);
            }

            @Override
            public void sendRedirect(String location) throws IOException {
                wrapped.sendRedirect(location);
            }

            @Override
            public void setDateHeader(String name, long date) {
                wrapped.setDateHeader(name, date);
            }

            @Override
            public void addDateHeader(String name, long date) {
                wrapped.addDateHeader(name, date);
            }

            @Override
            public void setHeader(String name, String value) {
                wrapped.setHeader(name, value);
            }

            @Override
            public void addHeader(String name, String value) {
                wrapped.addHeader(name, value);
            }

            @Override
            public void setIntHeader(String name, int value) {
                wrapped.setIntHeader(name, value);
            }

            @Override
            public void addIntHeader(String name, int value) {
                wrapped.addIntHeader(name, value);
            }

            @Override
            public void setStatus(int sc) {
                wrapped.setStatus(sc);
            }

            @Override
            @Deprecated
            public void setStatus(int sc, String sm) {
                wrapped.setStatus(sc, sm);
            }

            @Override
            public int getStatus() {
                return wrapped.getStatus();
            }

            @Override
            public String getHeader(String name) {
                return wrapped.getHeader(name);
            }

            @Override
            public java.util.Collection<String> getHeaders(String name) {
                return wrapped.getHeaders(name);
            }

            @Override
            public java.util.Collection<String> getHeaderNames() {
                return wrapped.getHeaderNames();
            }

            @Override
            public String getCharacterEncoding() {
                return wrapped.getCharacterEncoding();
            }

            @Override
            public String getContentType() {
                return wrapped.getContentType();
            }

            @Override
            public javax.servlet.ServletOutputStream getOutputStream() throws IOException {
                throw new UnsupportedOperationException("Output stream conversion not supported");
            }

            @Override
            public PrintWriter getWriter() throws IOException {
                return wrapped.getWriter();
            }

            @Override
            public void setCharacterEncoding(String charset) {
                wrapped.setCharacterEncoding(charset);
            }

            @Override
            public void setContentLength(int len) {
                wrapped.setContentLength(len);
            }

            @Override
            public void setContentLengthLong(long len) {
                wrapped.setContentLengthLong(len);
            }

            @Override
            public void setContentType(String type) {
                wrapped.setContentType(type);
            }

            @Override
            public void setBufferSize(int size) {
                wrapped.setBufferSize(size);
            }

            @Override
            public int getBufferSize() {
                return wrapped.getBufferSize();
            }

            @Override
            public void flushBuffer() throws IOException {
                wrapped.flushBuffer();
            }

            @Override
            public void resetBuffer() {
                wrapped.resetBuffer();
            }

            @Override
            public boolean isCommitted() {
                return wrapped.isCommitted();
            }

            @Override
            public void reset() {
                wrapped.reset();
            }

            @Override
            public void setLocale(Locale loc) {
                wrapped.setLocale(loc);
            }

            @Override
            public Locale getLocale() {
                return wrapped.getLocale();
            }
        }
    }


}
