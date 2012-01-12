/**
 * Copyright 2011 the original author or authors.
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

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

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
 * {@link HttpServlet#service(ServletRequest, ServletResponse)} and
 * {@link Filter#doFilter(ServletRequest, ServletResponse, FilterChain)} calls.
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
    private static final String ROOT_SPAN_DETAIL_ATTRIBUTE_NAME =
            "org.informantproject.plugin.servlet.RootSpanDetail";

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

    @Pointcut("filterPointcut() && !cflowbelow(filterPointcut())")
    void topLevelFilterPointcut() {}

    @Pointcut("servletPointcut() && !cflowbelow(servletPointcut())")
    void topLevelServletPointcut() {}

    @Pointcut("filterPointcut() && cflowbelow(filterPointcut())")
    void nestedFilterPointcut() {}

    @Pointcut("servletPointcut() && cflowbelow(servletPointcut())")
    void nestedServletPointcut() {}

    // conveniently the same code can handle both Servlet.service() and Filter.doFilter()
    @Around("isPluginEnabled() && (topLevelServletPointcut() || topLevelFilterPointcut())"
            + " && target(target) && args(request, ..)")
    public void aroundTopLevelServletPointcut(ProceedingJoinPoint joinPoint, Object target,
            HttpServletRequest request) throws Throwable {

        // capture more expensive data (request parameter map and session info)
        // for top level servlet pointcuts
        ServletSpanDetail spanDetail;
        // passing "false" so it won't create a session
        // if the request doesn't already have one
        HttpSession session = request.getSession(false);
        if (session == null) {
            spanDetail = new ServletSpanDetail(target.getClass(), request.getMethod(),
                    request.getRequestURI());
        } else {
            String username = getSessionAttributeTextValue(session,
                    ServletPluginPropertyUtils.getUsernameSessionAttributePath());
            spanDetail = new ServletSpanDetail(target.getClass(), request.getMethod(),
                    request.getRequestURI(), username, session.getId(),
                    getSessionAttributes(session));
        }
        topLevelServletSpanDetail.set(spanDetail);
        try {
            pluginServices.executeRootSpan(spanDetail, joinPoint, TOP_LEVEL_SERVLET_SUMMARY_KEY);
        } finally {
            topLevelServletSpanDetail.set(null);
        }
    }

    @Around("isPluginEnabled() && inTrace() && (nestedServletPointcut() || nestedFilterPointcut())"
            + " && target(target) && args(request, ..)")
    public void aroundNestedServletPointcut(ProceedingJoinPoint joinPoint, Object target,
            HttpServletRequest request) throws Throwable {

        if (ServletPluginPropertyUtils.isCaptureNestedExecutions()) {
            ServletSpanDetail spanDetail = new ServletSpanDetail(target.getClass(),
                    request.getMethod(), request.getRequestURI());
            // passing null spanSummaryKey so it won't record aggregate timing data
            // for nested servlets and filters
            pluginServices.executeSpan(spanDetail, joinPoint, null);
        } else {
            joinPoint.proceed();
        }
    }

    /*
     * ================== Http Servlet Request Parameters ==================
     */

    @Pointcut("call(* javax.servlet.ServletRequest.getParameter*(..))")
    void requestGetParameterPointcut() {}

    @AfterReturning("isPluginEnabled() && inTrace() && requestGetParameterPointcut()"
            + " && !cflowbelow(requestGetParameterPointcut()) && target(request)"
            + " && !within(org.informantproject.plugin.servlet.ServletAspect)")
    public void afterReturningRequestGetParameterPointcut(HttpServletRequest request) {
        // only now is it safe to get parameters (if parameters are retrieved before this, it could
        // prevent a servlet from choosing to read the underlying stream instead of using the
        // getParameter* methods) see SRV.3.1.1 "When Parameters Are Available"
        ServletSpanDetail spanDetail = getRootServletSpanDetail(request);
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
            + " && !cflowbelow(requestGetSessionPointcut()) && target(request)",
            returning = "session")
    public void afterReturningRequestGetSession(HttpServletRequest request, HttpSession session) {
        ServletSpanDetail spanDetail = getRootServletSpanDetail(request);
        if (spanDetail != null && session != null && session.isNew()) {
            spanDetail.setSessionIdUpdatedValue(session.getId());
        }
    }

    @Pointcut("call(void javax.servlet.http.HttpSession.invalidate())")
    void sessionInvalidatePointcut() {}

    @Before("isPluginEnabled() && inTrace() && sessionInvalidatePointcut()"
            + " && !cflowbelow(sessionInvalidatePointcut()) && target(session)")
    public void beforeSessionInvalidatePointcut(HttpSession session) {
        ServletSpanDetail spanDetail = getRootServletSpanDetail(session);
        if (spanDetail != null) {
            spanDetail.setSessionIdUpdatedValue("");
        }
    }

    // TODO support deprecated HttpSession.putValue()

    @Pointcut("call(void javax.servlet.http.HttpSession.setAttribute(String, Object))")
    void sessionSetAttributePointcut() {}

    @AfterReturning("isPluginEnabled() && inTrace() && sessionSetAttributePointcut()"
            + " && !cflowbelow(sessionSetAttributePointcut()) && target(session)"
            + " && args(name, value)")
    public void afterReturningSessionSetAttributePointcut(HttpSession session, String name,
            Object value) {

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
            + " && !cflowbelow(sessionRemoveAttributePointcut()) && target(session) && args(name)")
    public void afterReturningSessionRemoveAttributePointcut(HttpSession session, String name) {
        // update session attribute in ServletSpanDetail if necessary
        Set<String> sessionAttributePaths = ServletPluginPropertyUtils.getSessionAttributePaths();
        if (isSingleWildcard(sessionAttributePaths) || sessionAttributePaths.contains(name)) {
            ServletSpanDetail spanDetail = getRootServletSpanDetail(session);
            if (spanDetail != null) {
                spanDetail.putSessionAttributeChangedValue(name, "");
            }
        }
    }

    private static ServletSpanDetail getRootServletSpanDetail(HttpServletRequest request) {
        return (ServletSpanDetail) request.getAttribute(ROOT_SPAN_DETAIL_ATTRIBUTE_NAME);
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
