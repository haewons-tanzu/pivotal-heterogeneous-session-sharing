package org.apache.geode.modules.session.filter;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.Principal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestWrapper;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import javax.servlet.http.HttpSession;
import org.apache.geode.modules.session.internal.filter.GemfireHttpSession;
import org.apache.geode.modules.session.internal.filter.SessionManager;
import org.apache.geode.modules.session.internal.filter.attributes.DeltaQueuedSessionAttributes;
import org.apache.geode.modules.session.internal.filter.attributes.DeltaSessionAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SessionCachingFilter
  implements Filter
{
  private static final Logger LOG = LoggerFactory.getLogger(SessionCachingFilter.class.getName());

  private FilterConfig filterConfig = null;

  private static SessionManager manager = null;

  private static AtomicInteger started = new AtomicInteger(
      Integer.getInteger("gemfire.override.session.manager.count", 1).intValue());


  private static int percentInactiveTimeTriggerRebuild = Integer.getInteger("gemfire.session.inactive.trigger.rebuild", 80).intValue();

  private static CountDownLatch startingLatch = new CountDownLatch(1);

  public static class RequestWrapper
    extends HttpServletRequestWrapper
  {
    private static final String URL_SESSION_IDENTIFIER = ";jsessionid=";


    private SessionCachingFilter.ResponseWrapper response;


    private boolean sessionFromCookie = false;


    private boolean sessionFromURL = false;

    private String requestedSessionId = null;

    private GemfireHttpSession session = null;

    private SessionManager manager;

    private HttpServletRequest outerRequest = null;

    private final ServletContext context;

    private HttpServletRequest originalRequest;

    public RequestWrapper(SessionManager manager, HttpServletRequest request, SessionCachingFilter.ResponseWrapper response, ServletContext context) {
      super(request);
      this.response = response;
      this.manager = manager;
      this.originalRequest = request;
      this.context = context;

      Cookie[] cookies = request.getCookies();
      if (cookies != null) {
        for (Cookie cookie : cookies) {
            if (cookie.getName().equalsIgnoreCase("GF-" + manager.getSessionCookieName())) {
                this.requestedSessionId = cookie.getValue();
                this.sessionFromCookie = true;

                LOG.debug("Cookie contains sessionId: {}", this.requestedSessionId);

                continue;
            }

          if (cookie.getName().equalsIgnoreCase(manager.getSessionCookieName())) {
                  if (cookie.getValue().endsWith("-GF")) {
                  this.requestedSessionId = cookie.getValue();
                  this.sessionFromCookie = true;

                  LOG.debug("Cookie contains sessionId: {}", this.requestedSessionId);
                  } else if (cookie.getValue().contains("-GF.")) {
                  int endIndex = cookie.getValue().lastIndexOf("-GF.") + 3;

                  this.requestedSessionId = cookie.getValue().substring(0, endIndex);
                  this.sessionFromCookie = true;

                  LOG.debug("Cookie contains sessionId: {}", this.requestedSessionId);
                  }

          }
        }
      }

      if (this.requestedSessionId == null) {
        this.requestedSessionId = extractSessionId();
        LOG.debug("Extracted sessionId from URL {}", this.requestedSessionId);
        if (this.requestedSessionId != null) {
          this.sessionFromURL = true;
        }
      }
    }

    public HttpSession getSession() { return getSession(true); }

    public HttpSession getSession(boolean create) {
      if (this.session != null && this.session.isValid()) {
        this.session.setIsNew(false);
        this.session.updateAccessTime();
        return this.session;
      }

      if (this.requestedSessionId != null) {
        this.session = (GemfireHttpSession)this.manager.getSession(this.requestedSessionId);
        if (this.session != null) {
          this.session.setIsNew(false);

          if (this.session.getServletContext() == null) {
            this.session.setServletContext(this.context);
          }
        }
      }

      if (this.session == null || !this.session.isValid()) {
        if (create) {
          HttpSession native_session = this.originalRequest.getSession();
          try {
            this.session = (GemfireHttpSession)this.manager.wrapSession(this.context, native_session
                .getMaxInactiveInterval());
            this.session.setIsNew(true);
            this.manager.putSession(this.session);
          } finally {
            native_session.invalidate();
          }

        } else {

          return null;
        }
      }

      if (this.session != null) {
        addSessionCookie(this.response);
        this.session.updateAccessTime();
      }

      return this.session;
    }


    private void addSessionCookie(HttpServletResponse response) {
      if (response.isCommitted()) {
        return;
      }

      Cookie cookie = new Cookie(this.manager.getSessionCookieName(), this.session.getId());
      cookie.setPath("".equals(getContextPath()) ? "/" : getContextPath());
      response.addCookie(cookie);

      cookie = new Cookie("GF-" + this.manager.getSessionCookieName(), this.session.getId());
      cookie.setPath("".equals(getContextPath()) ? "/" : getContextPath());
      response.addCookie(cookie);
    }

    public boolean isRequestedSessionIdFromCookie() { return this.sessionFromCookie; }
    public boolean isRequestedSessionIdFromURL() { return this.sessionFromURL; }

    public String getRequestedSessionId() {
      if (this.requestedSessionId != null) {
        return this.requestedSessionId;
      }
      return super.getRequestedSessionId();
    }

    public Principal getUserPrincipal() {
      if (this.outerRequest != null) {
        return this.outerRequest.getUserPrincipal();
      }
      return super.getUserPrincipal();
    }

    public String getRemoteUser() {
      if (this.outerRequest != null) {
        return this.outerRequest.getRemoteUser();
      }
      return super.getRemoteUser();
    }

    public boolean isUserInRole(String role) {
      if (this.outerRequest != null) {
        return this.outerRequest.isUserInRole(role);
      }
      return super.isUserInRole(role);
    }

    void setOuterWrapper(HttpServletRequest outer) { this.outerRequest = outer; }

    private String extractSessionId() {
      int prefix = getRequestURL().indexOf(";jsessionid=");
      if (prefix != -1) {
        int start = prefix + ";jsessionid=".length();
        int suffix = getRequestURL().indexOf("?", start);
        if (suffix < 0) {
          suffix = getRequestURL().indexOf("#", start);
        }
        if (suffix <= prefix) {
          return getRequestURL().substring(start);
        }
        return getRequestURL().substring(start, suffix);
      }
      return null;
    }
  }

  class ResponseWrapper
    extends HttpServletResponseWrapper
  {
    HttpServletResponse originalResponse;

    public ResponseWrapper(HttpServletResponse response) throws IOException {
      super(response);
      this.originalResponse = response;
    }

    public HttpServletResponse getOriginalResponse() { return this.originalResponse; }

    public void setHeader(String name, String value) { super.setHeader(name, value); }

    public void setIntHeader(String name, int value) { super.setIntHeader(name, value); }
  }

  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
    HttpServletRequest httpReq = (HttpServletRequest)request;
    HttpServletResponse httpResp = (HttpServletResponse)response;

    if (alreadyWrapped(httpReq)) {
      LOG.debug("Handling already-wrapped request");
      chain.doFilter(request, response);

      return;
    }

    ResponseWrapper wrappedResponse = new ResponseWrapper(httpResp);

    RequestWrapper wrappedRequest = new RequestWrapper(manager, httpReq, wrappedResponse, this.filterConfig.getServletContext());

    Throwable problem = null;

    try {
      chain.doFilter(wrappedRequest, wrappedResponse);
    } catch (Throwable t) {

      problem = t;
      LOG.error("Exception processing filter chain", t);
    }

    GemfireHttpSession session = (GemfireHttpSession)wrappedRequest.getSession(false);

    if (problem != null) {
      if (problem instanceof ServletException) {
        throw (ServletException)problem;
      }
      if (problem instanceof IOException) {
        throw (IOException)problem;
      }
      sendProcessingError(problem, response);
    }

    if (session != null) {
      session.commit();
    }
  }

  private boolean alreadyWrapped(ServletRequest request) {
    if (request instanceof RequestWrapper) {
      return true;
    }

    if (!(request instanceof ServletRequestWrapper)) {
      return false;
    }

    ServletRequest nestedRequest = ((ServletRequestWrapper)request).getRequest();

    if (nestedRequest == request) {
      return false;
    }

    return alreadyWrapped(nestedRequest);
  }

  public FilterConfig getFilterConfig() { return this.filterConfig; }

  public void setFilterConfig(FilterConfig filterConfig) { this.filterConfig = filterConfig; }

  public void destroy() {
    if (manager != null) {
      manager.stop();
    }
  }

  public void init(FilterConfig config) {
    LOG.info("Starting Session Filter initialization");
    registerInstantiators();
    this.filterConfig = config;

    if (started.getAndDecrement() > 0) {
      String managerClassStr = config.getInitParameter("session-manager-class");

      if (managerClassStr == null) {
        managerClassStr = org.apache.geode.modules.session.internal.filter.GemfireSessionManager.class.getName();
      }

      try {
        manager = (SessionManager)Class.forName(managerClassStr).newInstance();
        manager.start(config, getClass().getClassLoader());
      } catch (Exception ex) {
        LOG.error("Exception creating Session Manager", ex);
      }

      startingLatch.countDown();
    } else {
      try {
        startingLatch.await();
      } catch (InterruptedException interruptedException) {}


      LOG.debug("SessionManager and listener initialization skipped - already done.");
    }

    LOG.info("Session Filter initialization complete");
    LOG.debug("Filter class loader {}", getClass().getClassLoader());
  }

  private void registerInstantiators() {
    GemfireHttpSession.registerInstantiator();
    DeltaQueuedSessionAttributes.registerInstantiator();
    DeltaSessionAttributes.registerInstantiator();
  }

  public String toString() {
    if (this.filterConfig == null) {
      return "SessionCachingFilter()";
    }
    StringBuilder sb = new StringBuilder("SessionCachingFilter(");
    sb.append(this.filterConfig);
    sb.append(")");
    return sb.toString();
  }

  private void sendProcessingError(Throwable t, ServletResponse response) {
    String stackTrace = getStackTrace(t);

    if (stackTrace != null && !stackTrace.equals("")) {
      try {
        response.setContentType("text/html");
        PrintStream ps = new PrintStream(response.getOutputStream());
        PrintWriter pw = new PrintWriter(ps);
        pw.print("<html>\n<head>\n<title>Error</title>\n</head>\n<body>\n");


        pw.print("<h1>The resource did not process correctly</h1>\n<pre>\n");
        pw.print(stackTrace);
        pw.print("</pre></body>\n</html>");
        pw.close();
        ps.close();
        response.getOutputStream().close();
      } catch (Exception exception) {}
    } else {

      try {
        PrintStream ps = new PrintStream(response.getOutputStream());
        t.printStackTrace(ps);
        ps.close();
        response.getOutputStream().close();
      } catch (Exception exception) {}
    }
  }

  public static String getStackTrace(Throwable t) {
    String stackTrace = null;
    try {
      StringWriter sw = new StringWriter();
      PrintWriter pw = new PrintWriter(sw);
      t.printStackTrace(pw);
      pw.close();
      sw.close();
      stackTrace = sw.getBuffer().toString();
    } catch (Exception exception) {}

    return stackTrace;
  }

  public static SessionManager getSessionManager() { return manager; }
}
