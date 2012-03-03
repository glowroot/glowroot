/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.plugin.servlet;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.informantproject.api.PluginServices;
import org.informantproject.api.SpanDetail;
import org.informantproject.shaded.aspectj.lang.ProceedingJoinPoint;
import org.informantproject.shaded.aspectj.lang.annotation.AfterReturning;
import org.informantproject.shaded.aspectj.lang.annotation.Around;
import org.informantproject.shaded.aspectj.lang.annotation.Aspect;
import org.informantproject.shaded.aspectj.lang.annotation.Before;
import org.informantproject.shaded.aspectj.lang.annotation.Pointcut;
import org.informantproject.shaded.aspectj.lang.annotation.SuppressAjWarnings;

/**
 * Defines pointcuts and captures data on
 * {@link javax.servlet.http.HttpServlet#service(javax.servlet.ServletRequest, javax.servlet.ServletResponse)}
 * and
 * {@link javax.servlet.Filter#doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse, javax.servlet.FilterChain)}
 * calls.
 * 
 * By default only calls to the top-most Filter and to the top-most Servlet are captured.
 * "isWarnOnSpanOutsideTrace" core configuration property can be used to enable capturing of nested
 * Filters and nested Servlets as well.
 * 
 * This plugin is careful not to rely on request or session objects being thread-safe.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// TODO add support for async servlets (servlet 3.0)
@Aspect
@SuppressAjWarnings("adviceDidNotMatch")
public class ServletAspect {

    private static final String TOP_LEVEL_SERVLET_SUMMARY_KEY = "http request";

    private static final ThreadLocal<ServletSpanDetail> topLevelServletSpanDetail =
            new ThreadLocal<ServletSpanDetail>();
    // TODO allow this to be mocked out for unit testing
    private static final PluginServices pluginServices = PluginServices.get();

    @Pointcut("if()")
    public static boolean isPluginEnabled() {
        return pluginServices.isEnabled();
    }

    @Pointcut("if()")
    public static boolean inTrace() {
        return pluginServices.getRootSpanDetail() != null;
    }

    @Pointcut("execution(void javax.servlet.Filter.doFilter(javax.servlet.ServletRequest,"
            + " javax.servlet.ServletResponse, javax.servlet.FilterChain))")
    void filterPointcut() {}

    @Pointcut("execution(void javax.servlet.Servlet.service(javax.servlet.ServletRequest,"
            + " javax.servlet.ServletResponse)) || execution(void javax.servlet.http.HttpServlet"
            + ".do*(javax.servlet.http.HttpServletRequest,"
            + " javax.servlet.http.HttpServletResponse))")
    void servletPointcut() {}

    @Pointcut("(filterPointcut() || servletPointcut()) && !cflowbelow(filterPointcut())"
            + " && !cflowbelow(servletPointcut())")
    void topLevelPointcut() {}

    @Around("isPluginEnabled() && topLevelPointcut() && args(realRequest, ..)")
    public void aroundTopLevelPointcut(ProceedingJoinPoint joinPoint, Object realRequest)
            throws Throwable {

        HttpServletRequest request = HttpServletRequest.from(realRequest);
        // capture more expensive data (request parameter map and session info) for the top
        // level filter/servlet pointcut
        // request parameter map is collected in afterReturningRequestGetParameterPointcut()
        // session info is collected here if the request already has a session
        ServletSpanDetail spanDetail;
        // passing "false" so it won't create a session if the request doesn't already have one
        HttpSession session = request.getSession(false);
        if (session == null) {
            spanDetail = new ServletSpanDetail(request.getMethod(), request.getRequestURI(),
                    null, null, null);
        } else {
            String username = getSessionAttributeTextValue(session,
                    ServletPluginPropertyUtils.getUsernameSessionAttributePath());
            spanDetail = new ServletSpanDetail(request.getMethod(), request.getRequestURI(),
                    username, session.getId(), getSessionAttributes(session));
        }
        topLevelServletSpanDetail.set(spanDetail);
        try {
            pluginServices.executeRootSpan(spanDetail, joinPoint,
                    TOP_LEVEL_SERVLET_SUMMARY_KEY);
        } finally {
            topLevelServletSpanDetail.set(null);
        }
    }

    /*
     * ================== Http Servlet Request Parameters ==================
     */

    @Pointcut("call(* javax.servlet.ServletRequest.getParameter*(..))")
    void requestGetParameterPointcut() {}

    @AfterReturning("isPluginEnabled() && inTrace() && requestGetParameterPointcut()"
            + " && !cflowbelow(requestGetParameterPointcut()) && target(realRequest)"
            + " && !within(org.informantproject.plugin.servlet.ServletAspect)")
    public void afterReturningRequestGetParameterPointcut(Object realRequest) {
        HttpServletRequest request = HttpServletRequest.from(realRequest);
        // only now is it safe to get parameters (if parameters are retrieved before this, it could
        // prevent a servlet from choosing to read the underlying stream instead of using the
        // getParameter* methods) see SRV.3.1.1 "When Parameters Are Available"
        ServletSpanDetail spanDetail = topLevelServletSpanDetail.get();
        if (spanDetail != null && !spanDetail.isRequestParameterMapCaptured()) {
            // this request is being traced and the request parameter map hasn't been captured yet
            spanDetail.captureRequestParameterMap(request.getParameterMap());
        }
    }

    /*
     * ================== Http Session Attributes ==================
     */

    @Pointcut("call(javax.servlet.http.HttpSession"
            + " javax.servlet.http.HttpServletRequest.getSession(..))")
    void requestGetSessionPointcut() {}

    @AfterReturning(pointcut = "isPluginEnabled() && inTrace() && requestGetSessionPointcut()"
            + " && !cflowbelow(requestGetSessionPointcut())", returning = "realSession")
    public void afterReturningRequestGetSession(Object realSession) {
        HttpSession session = HttpSession.from(realSession);
        // either getSession(), getSession(true) or getSession(false) has triggered this pointcut
        // after calls to the first two, a new session may have been created
        // (the third one could be ignored but is harmless)
        ServletSpanDetail spanDetail = topLevelServletSpanDetail.get();
        if (spanDetail != null && session != null && session.isNew()) {
            spanDetail.setSessionIdUpdatedValue(session.getId());
        }
    }
    @Pointcut("call(void javax.servlet.http.HttpSession.invalidate())")
    void sessionInvalidatePointcut() {}

    @Before("isPluginEnabled() && inTrace() && sessionInvalidatePointcut()"
            + " && !cflowbelow(sessionInvalidatePointcut()) && target(realSession)")
    public void beforeSessionInvalidatePointcut(Object realSession) {
        HttpSession session = HttpSession.from(realSession);
        ServletSpanDetail spanDetail = getRootServletSpanDetail(session);
        if (spanDetail != null) {
            spanDetail.setSessionIdUpdatedValue("");
        }
    }

    // TODO support deprecated HttpSession.putValue()

    @Pointcut("call(void javax.servlet.http.HttpSession.setAttribute(String, Object))")
    void sessionSetAttributePointcut() {}

    @AfterReturning("isPluginEnabled() && inTrace() && sessionSetAttributePointcut()"
            + " && !cflowbelow(sessionSetAttributePointcut()) && target(realSession)"
            + " && args(name, value)")
    public void afterReturningSessionSetAttributePointcut(Object realSession, String name,
            Object value) {

        HttpSession session = HttpSession.from(realSession);
        // both name and value are non-null per HttpSession.setAttribute() specification
        ServletSpanDetail spanDetail = getRootServletSpanDetail(session);
        if (spanDetail != null) {
            // check for username attribute
            // TODO handle possible nested path here
            if (name.equals(ServletPluginPropertyUtils.getUsernameSessionAttributePath())) {
                // value should be a String, but toString() is still called just to be safe
                spanDetail.setUsername(value.toString());
            }
            // update session attribute in ServletSpanDetail if necessary
            Set<String> sessionAttributePaths = ServletPluginPropertyUtils
                    .getSessionAttributePaths();
            if (isSingleWildcard(sessionAttributePaths) || sessionAttributePaths.contains(name)) {
                spanDetail.putSessionAttributeChangedValue(name, value.toString());
            }
        }
    }

    @Pointcut("call(void javax.servlet.http.HttpSession.removeAttribute(String))")
    void sessionRemoveAttributePointcut() {}

    @AfterReturning("isPluginEnabled() && inTrace() && sessionRemoveAttributePointcut()"
            + " && !cflowbelow(sessionRemoveAttributePointcut()) && target(realSession)"
            + " && args(name)")
    public void afterReturningSessionRemoveAttributePointcut(Object realSession, String name) {
        HttpSession session = HttpSession.from(realSession);
        // update session attribute in ServletSpanDetail if necessary
        Set<String> sessionAttributePaths = ServletPluginPropertyUtils.getSessionAttributePaths();
        if (isSingleWildcard(sessionAttributePaths) || sessionAttributePaths.contains(name)) {
            ServletSpanDetail spanDetail = getRootServletSpanDetail(session);
            if (spanDetail != null) {
                spanDetail.putSessionAttributeChangedValue(name, "");
            }
        }
    }

    private static ServletSpanDetail getRootServletSpanDetail(HttpSession session) {
        SpanDetail rootSpanDetail = pluginServices.getRootSpanDetail();
        if (!(rootSpanDetail instanceof ServletSpanDetail)) {
            return null;
        }
        ServletSpanDetail rootServletSpanDetail = (ServletSpanDetail) rootSpanDetail;
        String sessionId;
        if (rootServletSpanDetail.getSessionIdUpdatedValue() != null) {
            sessionId = rootServletSpanDetail.getSessionIdUpdatedValue();
        } else {
            sessionId = rootServletSpanDetail.getSessionIdInitialValue();
        }
        if (!session.getId().equals(sessionId)) {
            // the target session for this pointcut is not the same as the SpanDetail
            return null;
        }
        return rootServletSpanDetail;
    }

    private static Map<String, String> getSessionAttributes(HttpSession session) {
        Set<String> sessionAttributePaths = ServletPluginPropertyUtils.getSessionAttributePaths();
        if (sessionAttributePaths == null || sessionAttributePaths.isEmpty()) {
            return null;
        }
        if (isSingleWildcard(sessionAttributePaths)) {
            // special single value of "*" means dump all http session attributes
            Map<String, String> sessionAttributeMap = new HashMap<String, String>();
            for (Enumeration<?> e = session.getAttributeNames(); e.hasMoreElements();) {
                String attributeName = (String) e.nextElement();
                Object attributeValue = session.getAttribute(attributeName);
                sessionAttributeMap.put(attributeName, attributeValue.toString());
            }
            return sessionAttributeMap;
        } else {
            Map<String, String> sessionAttributeMap = new HashMap<String, String>(
                    sessionAttributePaths.size());
            // dump only http session attributes in list
            for (String attributePath : sessionAttributePaths) {
                String attributeValue = getSessionAttributeTextValue(session, attributePath);
                sessionAttributeMap.put(attributePath, attributeValue);
            }
            return sessionAttributeMap;
        }
    }

    private static String getSessionAttributeTextValue(HttpSession session, String attributePath) {
        Object value = session.getAttribute(attributePath);
        if (value == null) {
            return null;
        } else {
            return value.toString();
        }
    }

    private static boolean isSingleWildcard(Set<String> sessionAttributePaths) {
        return sessionAttributePaths.size() == 1
                && sessionAttributePaths.iterator().next().equals("*");
    }
}
