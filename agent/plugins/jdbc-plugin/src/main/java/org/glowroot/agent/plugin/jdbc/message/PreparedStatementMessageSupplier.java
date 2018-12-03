/*
 * Copyright 2015-2018 the original author or authors.
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
package org.glowroot.agent.plugin.jdbc.message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Logger;
import org.glowroot.agent.plugin.api.QueryMessage;
import org.glowroot.agent.plugin.api.QueryMessageSupplier;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.util.ImmutableList;

public class PreparedStatementMessageSupplier extends QueryMessageSupplier {

    private static final Logger logger = Logger.getLogger(PreparedStatementMessageSupplier.class);

    private static final ConfigService configService = Agent.getConfigService("jdbc");

    private static List<Pattern> captureBindParametersIncludePatterns = Collections.emptyList();
    private static List<Pattern> captureBindParametersExcludePatterns = Collections.emptyList();

    static {
        configService.registerConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                captureBindParametersIncludePatterns =
                        buildPatternList("captureBindParametersIncludes");
                captureBindParametersExcludePatterns =
                        buildPatternList("captureBindParametersExcludes");
            }
        });
    }

    // cannot use ImmutableList for parameters since it can contain null elements
    private final @Nullable BindParameterList parameters;
    private final String queryText;

    public PreparedStatementMessageSupplier(@Nullable BindParameterList parameters,
            String queryText) {
        this.parameters = parameters;
        this.queryText = queryText;
    }

    @Override
    public QueryMessage get() {
        String suffix = "";
        if (parameters != null && !parameters.isEmpty() && capture()) {
            StringBuilder sb = new StringBuilder();
            appendParameters(sb, parameters);
            suffix = sb.toString();
        }
        return QueryMessage.create("jdbc query: ", suffix);
    }

    private boolean capture() {
        String queryTextTrimmed = queryText.trim();
        boolean include = false;
        for (Pattern includePattern : captureBindParametersIncludePatterns) {
            if (includePattern.matcher(queryTextTrimmed).matches()) {
                include = true;
                break;
            }
        }
        if (!include) {
            return false;
        }
        for (Pattern excludePattern : captureBindParametersExcludePatterns) {
            if (excludePattern.matcher(queryTextTrimmed).matches()) {
                return false;
            }
        }
        return true;
    }

    static void appendParameters(StringBuilder sb, BindParameterList parameters) {
        sb.append(" [");
        boolean first = true;
        for (Object parameter : parameters) {
            if (!first) {
                sb.append(", ");
            }
            if (parameter instanceof String) {
                sb.append("\'");
                sb.append((String) parameter);
                sb.append("\'");
            } else if (parameter == null) {
                sb.append("NULL");
            } else {
                sb.append(String.valueOf(parameter));
            }
            first = false;
        }
        sb.append("]");
    }

    private static List<Pattern> buildPatternList(String propertyName) {
        List<String> values = configService.getListProperty(propertyName).value();
        List<Pattern> patterns = new ArrayList<Pattern>();
        for (String value : values) {
            try {
                patterns.add(Pattern.compile(value.trim(), Pattern.DOTALL));
            } catch (PatternSyntaxException e) {
                logger.warn("the jdbc plugin configuration property {} contains an invalid regular"
                        + " expression: {}\n{}", propertyName, value.trim(), e.getMessage());
            }
        }
        return ImmutableList.copyOf(patterns);
    }
}
