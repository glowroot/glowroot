/**
 * Copyright 2013 the original author or authors.
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
package io.informant.core.util;

import checkers.igj.quals.ReadOnly;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;

/**
 * Convenience methods for working with {@link JsonElement}s.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class JsonElements {

    private JsonElements() {}

    @ReadOnly
    public static JsonArray getOptionalArray(@ReadOnly JsonObject jsonObject, String memberName)
            throws JsonSyntaxException {
        JsonElement memberElement = jsonObject.get(memberName);
        if (memberElement == null || memberElement.isJsonNull()) {
            return new JsonArray();
        } else if (memberElement.isJsonArray()) {
            return memberElement.getAsJsonArray();
        } else {
            throw new JsonSyntaxException("Expecting json array: " + memberName);
        }
    }

    @ReadOnly
    public static JsonArray getRequiredArray(@ReadOnly JsonObject jsonObject, String memberName)
            throws JsonSyntaxException {
        JsonElement memberElement = jsonObject.get(memberName);
        if (memberElement == null || memberElement.isJsonNull()) {
            throw new JsonSyntaxException("Missing required json array: " + memberName);
        } else if (memberElement.isJsonArray()) {
            return memberElement.getAsJsonArray();
        } else {
            throw new JsonSyntaxException("Expecting json array: " + memberName);
        }
    }

    @ReadOnly
    public static JsonObject getOptionalObject(@ReadOnly JsonObject jsonObject, String memberName)
            throws JsonSyntaxException {
        JsonElement memberElement = jsonObject.get(memberName);
        if (memberElement == null || memberElement.isJsonNull()) {
            return new JsonObject();
        } else if (memberElement.isJsonObject()) {
            return memberElement.getAsJsonObject();
        } else {
            throw new JsonSyntaxException("Expecting json object: " + memberName);
        }
    }

    public static String getRequiredString(@ReadOnly JsonObject jsonObject, String memberName)
            throws JsonSyntaxException {
        JsonElement memberElement = jsonObject.get(memberName);
        if (memberElement == null || memberElement.isJsonNull()) {
            throw new JsonSyntaxException("Missing required json string: " + memberName);
        }
        if (memberElement.isJsonPrimitive() && ((JsonPrimitive) memberElement).isString()) {
            return memberElement.getAsString();
        } else {
            throw new JsonSyntaxException("Expecting json string: " + memberName);
        }
    }
}
