package org.glowroot.agent.plugin.jsp;

import java.util.logging.Level;
import java.util.regex.Pattern;

public class HttpJspPages {
    public static final String ORG_APACHE_JSP = "org.apache.jsp.";
    public static final Pattern JSP_PATTERN = Pattern.compile("_jsp$");
    public static final Pattern WEB_INF_PATTERN = Pattern.compile("WEB_002dINF");

    public static String getFilename(Class<?> jspClass) {
        String name = jspClass.getName();
        if (name.startsWith(ORG_APACHE_JSP)) {
            name = name.substring(ORG_APACHE_JSP.length()).replace('.', '/');
            name = WEB_INF_PATTERN.matcher(name).replaceFirst("WEB-INF");
        } else {
            int e = name.lastIndexOf('.');
            if (e > 0) {
                name = name.substring(e + 1);
            }
        }
        name = JSP_PATTERN.matcher(name).replaceAll(".jsp");
        return name;
    }
}
