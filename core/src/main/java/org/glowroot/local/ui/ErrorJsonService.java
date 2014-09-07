/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.local.ui;

import java.io.IOException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.local.store.ErrorAggregate;
import org.glowroot.local.store.ErrorAggregateQuery;
import org.glowroot.local.store.QueryResult;
import org.glowroot.local.store.TraceDao;
import org.glowroot.markers.Singleton;

/**
 * Json service to read error data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class ErrorJsonService {

    private static final Logger logger = LoggerFactory.getLogger(ErrorJsonService.class);

    private final TraceDao traceDao;

    ErrorJsonService(TraceDao traceDao) {
        this.traceDao = traceDao;
    }

    @GET("/backend/error/aggregates")
    String getErrorAggregates(String content) throws IOException {
        logger.debug("getErrorAggregates(): content={}", content);
        ObjectMapper mapper = ObjectMappers.create();
        mapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
        ErrorAggregateQuery query =
                ObjectMappers.readRequiredValue(mapper, content, ErrorAggregateQuery.class);
        QueryResult<ErrorAggregate> queryResult = traceDao.readErrorAggregates(query);
        return mapper.writeValueAsString(queryResult);
    }
}
