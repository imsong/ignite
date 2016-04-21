/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.rest.protocols.http.jetty;

import java.lang.reflect.Method;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.sf.json.JSONObject;
import net.sf.json.JsonConfig;
import net.sf.json.processors.JsonBeanProcessor;
import net.sf.json.processors.JsonBeanProcessorMatcher;
import net.sf.json.processors.JsonValueProcessor;
import net.sf.json.processors.JsonValueProcessorMatcher;
import org.apache.ignite.internal.processors.cache.query.GridCacheSqlIndexMetadata;
import org.apache.ignite.internal.processors.cache.query.GridCacheSqlMetadata;
import org.apache.ignite.lang.IgniteUuid;

/**
 * Jetty protocol json configuration.
 */
class GridJettyJsonConfig extends JsonConfig {
    /**
     * Class for finding a matching JsonBeanProcessor.
     */
    private static final JsonBeanProcessorMatcher LESS_NAMING_BEAN_MATCHER = new JsonBeanProcessorMatcher() {
        /** {@inheritDoc} */
        @Override public Object getMatch(Class target, Set keys) {
            return GridJettyJsonConfig.getMatch(target, keys);
        }
    };

    /**
     * Class for finding a matching JsonValueProcessor.
     */
    private static final JsonValueProcessorMatcher LESS_NAMING_VALUE_MATCHER = new JsonValueProcessorMatcher() {
        /** {@inheritDoc} */
        @Override public Object getMatch(Class target, Set keys) {
            return GridJettyJsonConfig.getMatch(target, keys);
        }
    };

    /**
     * Helper class for simple to-string conversion for {@link UUID}.
     */
    private static JsonValueProcessor UUID_PROCESSOR = new JsonValueProcessor() {
        /** {@inheritDoc} */
        @Override public Object processArrayValue(Object val, JsonConfig jsonCfg) {
            if (val == null)
                return new JSONObject(true);

            if (val instanceof UUID)
                return val.toString();

            throw new UnsupportedOperationException("Serialize value to json is not supported: " + val);
        }

        /** {@inheritDoc} */
        @Override public Object processObjectValue(String key, Object val, JsonConfig jsonCfg) {
            return processArrayValue(val, jsonCfg);
        }
    };

    /**
     * Helper class for simple to-string conversion for {@link IgniteUuid}.
     */
    private static JsonValueProcessor IGNITE_UUID_PROCESSOR = new JsonValueProcessor() {
        /** {@inheritDoc} */
        @Override public Object processArrayValue(Object val, JsonConfig jsonCfg) {
            if (val == null)
                return new JSONObject(true);

            if (val instanceof IgniteUuid)
                return val.toString();

            throw new UnsupportedOperationException("Serialize value to json is not supported: " + val);
        }

        /** {@inheritDoc} */
        @Override public Object processObjectValue(String key, Object val, JsonConfig jsonCfg) {
            return processArrayValue(val, jsonCfg);
        }
    };

    /**
     * Helper class for simple to-string conversion for {@link Date}.
     */
    private static JsonValueProcessor DATE_PROCESSOR = new JsonValueProcessor() {
        /** US date format. */
        private final DateFormat usFormat
            = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, Locale.US);

        /** {@inheritDoc} */
        @Override public synchronized Object processArrayValue(Object val, JsonConfig jsonCfg) {
            if (val == null)
                return new JSONObject(true);

            if (val instanceof Date)
                return usFormat.format(val);

            throw new UnsupportedOperationException("Serialize value to json is not supported: " + val);
        }

        /** {@inheritDoc} */
        @Override public synchronized Object processObjectValue(String key, Object val, JsonConfig jsonCfg) {
            return processArrayValue(val, jsonCfg);
        }
    };

    /**
     * Class for finding a matching JsonValueProcessor.
     */
    private static final LessNamingProcessor LESS_NAMING_PROCESSOR = new LessNamingProcessor();

    /**
     * Constructs default jetty json config.
     */
    GridJettyJsonConfig() {
        setAllowNonStringKeys(true);

        registerJsonValueProcessor(UUID.class, UUID_PROCESSOR);
        registerJsonValueProcessor(IgniteUuid.class, IGNITE_UUID_PROCESSOR);
        registerJsonValueProcessor(Date.class, DATE_PROCESSOR);
        registerJsonValueProcessor(java.sql.Date.class, DATE_PROCESSOR);

        registerJsonBeanProcessor(LessNamingProcessor.class, LESS_NAMING_PROCESSOR);
        registerJsonValueProcessor(LessNamingProcessor.class, LESS_NAMING_PROCESSOR);

        setJsonBeanProcessorMatcher(LESS_NAMING_BEAN_MATCHER);
        setJsonValueProcessorMatcher(LESS_NAMING_VALUE_MATCHER);
    }

    /**
     * Returns the matching class calculated with the target class and the provided set. Matches the target class with
     * instanceOf, for Visor classes return custom processor class.
     *
     * @param target the target class to match
     * @param keys a set of possible matches
     */
    private static Object getMatch(Class target, Set keys) {
        if (target == null || keys == null)
            return null;

        if (target.getSimpleName().startsWith("Visor") ||
            target == GridCacheSqlMetadata.class || target == GridCacheSqlIndexMetadata.class)
            return LessNamingProcessor.class;

        if (keys.contains(target))
            return target;

        for (Object key : keys) {
            Class<?> clazz = (Class<?>)key;

            if (clazz.isAssignableFrom(target))
                return key;
        }

        return null;
    }

    /**
     * Helper class for simple to-json conversion for Visor classes.
     */
    private static class LessNamingProcessor implements JsonBeanProcessor, JsonValueProcessor {
        /** */
        private static final Map<Class<?>, Collection<Method>> classCache = new HashMap<>();

        /** */
        private static final ReadWriteLock rwLock = new ReentrantReadWriteLock();

        /** {@inheritDoc} */
        @Override public JSONObject processBean(Object bean, JsonConfig jsonCfg) {
            if (bean == null)
                return new JSONObject(true);

            final JSONObject ret = new JSONObject();

            Collection<Method> methods;

            Class<?> cls = bean.getClass();

            // Get descriptor from cache.
            rwLock.readLock().lock();

            try {
                methods = classCache.get(cls);
            }
            finally {
                rwLock.readLock().unlock();
            }

            // If missing in cache - build descriptor
            if (methods == null) {
                Method[] publicMtds = cls.getMethods();

                methods = new ArrayList<>(publicMtds.length);

                for (Method mtd : publicMtds) {
                    if (mtd.getParameterTypes().length != 0 ||
                        mtd.getName().equals("toString") ||
                        mtd.getName().equals("hashCode") ||
                        mtd.getName().equals("clone") ||
                        mtd.getName().equals("getClass") ||
                        mtd.getReturnType() == void.class ||
                        mtd.getReturnType() == cls)
                        continue;

                    methods.add(mtd);
                }

                /*
                 * Allow multiple puts for the same class - they will simply override.
                 */
                rwLock.writeLock().lock();

                try {
                    classCache.put(cls, methods);
                }
                finally {
                    rwLock.writeLock().unlock();
                }
            }

            // Extract fields values using descriptor and build JSONObject.
            for (Method mtd : methods) {
                try {
                    ret.element(mtd.getName(), mtd.invoke(bean), jsonCfg);
                }
                catch (Exception ignored) {
                    // No-op.
                }
            }

            return ret;
        }

        /** {@inheritDoc} */
        @Override public Object processArrayValue(Object val, JsonConfig jsonCfg) {
            return processBean(val, jsonCfg);
        }

        /** {@inheritDoc} */
        @Override public Object processObjectValue(String key, Object val, JsonConfig jsonCfg) {
            return processArrayValue(val, jsonCfg);
        }
    }
}
