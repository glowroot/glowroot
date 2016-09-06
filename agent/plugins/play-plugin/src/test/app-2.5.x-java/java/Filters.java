/*
 * Copyright 2016 the original author or authors.
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
import javax.inject.*;
import play.*;
import play.mvc.EssentialFilter;
import play.http.HttpFilters;
import play.mvc.*;

import filters.ExampleFilter;

@Singleton
public class Filters implements HttpFilters {

    private final Environment env;
    private final EssentialFilter exampleFilter;

    @Inject
    public Filters(Environment env, ExampleFilter exampleFilter) {
        this.env = env;
        this.exampleFilter = exampleFilter;
    }

    @Override
    public EssentialFilter[] filters() {
        // Use the example filter if we're running development mode. If
        // we're running in production or test mode then don't use any
        // filters at all.
        if (env.mode().equals(Mode.DEV)) {
            return new EssentialFilter[] {exampleFilter};
        } else {
            return new EssentialFilter[] {};
        }
    }
}
