package local.glowroot.vt;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.VirtualThreadPool;

/**
 * Minimal Jetty app on virtual threads for smoke-testing Glowroot #1125.
 *
 * Endpoints:
 *   GET /slow?ms=2000  — burns CPU for ms milliseconds (default 2000)
 *   GET /info          — shows whether the request thread is virtual
 */
public class VtProfileSmokeMain {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8088;

        VirtualThreadPool threadPool = new VirtualThreadPool();
        threadPool.setName("vt-smoke");
        Server server = new Server(threadPool);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new SlowServlet()), "/slow");
        context.addServlet(new ServletHolder(new InfoServlet()), "/info");
        server.setHandler(context);

        org.eclipse.jetty.server.ServerConnector connector =
                new org.eclipse.jetty.server.ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        server.start();
        System.out.println("vt-profile-smoke listening on http://127.0.0.1:" + port);
        System.out.println("  GET /info");
        System.out.println("  GET /slow?ms=2000");
        System.out.println("Glowroot UI (embedded agent): http://127.0.0.1:4001");
        server.join();
    }

    static final class InfoServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            Thread t = Thread.currentThread();
            String body = "thread=" + t.getName()
                    + " id=" + t.threadId()
                    + " virtual=" + t.isVirtual()
                    + "\n";
            write(resp, 200, body);
        }
    }

    static final class SlowServlet extends HttpServlet {
        private final AtomicInteger hits = new AtomicInteger();

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            int ms = 2000;
            String msParam = req.getParameter("ms");
            if (msParam != null && !msParam.isEmpty()) {
                ms = Integer.parseInt(msParam);
            }
            Thread t = Thread.currentThread();
            long end = System.nanoTime() + ms * 1_000_000L;
            // busy-spin so the profiler sees RUNNABLE frames (sleep alone is weaker)
            int n = 0;
            while (System.nanoTime() < end) {
                n += busyWork(n);
            }
            int hit = hits.incrementAndGet();
            String body = "ok hit=" + hit
                    + " ms=" + ms
                    + " virtual=" + t.isVirtual()
                    + " n=" + n
                    + "\n";
            write(resp, 200, body);
        }

        private static int busyWork(int seed) {
            int x = seed;
            for (int i = 0; i < 1000; i++) {
                x = (x * 31) ^ i;
            }
            return x;
        }
    }

    private static void write(HttpServletResponse resp, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        resp.setStatus(status);
        resp.setContentType("text/plain; charset=UTF-8");
        resp.setContentLength(bytes.length);
        try (OutputStream out = resp.getOutputStream()) {
            out.write(bytes);
        }
    }
}
