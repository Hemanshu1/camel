/**
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
package org.apache.camel.http.common;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.ExpressionBuilder;
import org.apache.camel.processor.Pipeline;
import org.apache.camel.processor.SetHeaderProcessor;
import org.apache.camel.runtimecatalog.RuntimeCamelCatalog;
import org.apache.camel.spi.SendDynamicAware;
import org.apache.camel.util.StringHelper;
import org.apache.camel.util.URISupport;

/**
 * HTTP based {@link SendDynamicAware} which allows to optimise HTTP components
 * with the toD (dynamic to) DSL in Camel. This implementation optimises by allowing
 * to provide dynamic parameters via {@link Exchange#HTTP_PATH} and {@link Exchange#HTTP_QUERY} headers
 * instead of the endpoint uri. That allows to use a static endpoint and its producer to service
 * dynamic requests.
 */
public class HttpSendDynamicAware implements SendDynamicAware {

    private String scheme;

    @Override
    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    @Override
    public String getScheme() {
        return scheme;
    }

    @Override
    public DynamicAwareEntry prepare(Exchange exchange, String uri) throws Exception {
        RuntimeCamelCatalog catalog = exchange.getContext().getRuntimeCamelCatalog();
        Map<String, String> properties = catalog.endpointProperties(uri);
        Map<String, String> lenient = catalog.endpointLenientProperties(uri);
        return new DynamicAwareEntry(uri, properties, lenient);
    }

    @Override
    public String resolveStaticUri(Exchange exchange, DynamicAwareEntry entry) throws Exception {
        String[] hostAndPath = parseUri(entry.getOriginalUri());
        String host = hostAndPath[0];
        String path = hostAndPath[1];
        if (path != null || !entry.getLenientProperties().isEmpty()) {
            // the context path can be dynamic or any lenient properties
            // and therefore build a new static uri without path or lenient options
            Map<String, String> params = new LinkedHashMap<>(entry.getProperties());
            for (String k : entry.getLenientProperties().keySet()) {
                params.remove(k);
            }
            if (path != null) {
                // httpUri/httpURI contains the host and path, so replace it with just the host as the context-path is dynamic
                if (params.containsKey("httpUri")) {
                    params.put("httpUri", host);
                } else if (params.containsKey("httpURI")) {
                    params.put("httpURI", host);
                }
            }
            RuntimeCamelCatalog catalog = exchange.getContext().getRuntimeCamelCatalog();
            return catalog.asEndpointUri(scheme, params, false);
        } else {
            // no need for optimisation
            return null;
        }
    }

    @Override
    public Processor createPreProcessor(Exchange exchange, DynamicAwareEntry entry) throws Exception {
        Processor pathProcessor = null;
        Processor lenientProcessor = null;

        String[] hostAndPath = parseUri(entry.getOriginalUri());
        String path = hostAndPath[1];

        if (path != null) {
            pathProcessor = new SetHeaderProcessor(ExpressionBuilder.constantExpression(Exchange.HTTP_PATH), ExpressionBuilder.constantExpression(path));
        }

        if (!entry.getLenientProperties().isEmpty()) {
            // all lenient properties can be dynamic and provided in the HTTP_QUERY header
            String query = URISupport.createQueryString(new LinkedHashMap<>(entry.getLenientProperties()));
            lenientProcessor = new SetHeaderProcessor(ExpressionBuilder.constantExpression(Exchange.HTTP_QUERY), ExpressionBuilder.constantExpression(query));
        }

        if (pathProcessor != null || lenientProcessor != null) {
            List<Processor> list = new ArrayList<>(2);
            if (pathProcessor != null) {
                list.add(pathProcessor);
            }
            if (lenientProcessor != null) {
                list.add(lenientProcessor);
            }
            if (list.size() == 2) {
                return new Pipeline(exchange.getContext(), list);
            } else {
                return list.get(0);
            }
        }

        return null;
    }

    @Override
    public Processor createPostProcessor(Exchange exchange, DynamicAwareEntry entry) throws Exception {
        // no need to cleanup
        return null;
    }

    private String[] parseUri(String uri) {
        String u = uri;

        // remove scheme prefix (unless its camel-http or camel-http4)
        boolean httpComponent = "http".equals(scheme) || "https".equals(scheme) || "http4".equals(scheme) || "https4".equals(scheme);
        if (!httpComponent) {
            String prefix = scheme + "://";
            String prefix2 = scheme + ":";
            if (uri.startsWith(prefix)) {
                u = uri.substring(prefix.length());
            } else if (uri.startsWith(prefix2)) {
                u = uri.substring(prefix2.length());
            }
        }

        // remove query parameters
        if (u.indexOf('?') > 0) {
            u = StringHelper.before(u, "?");
        }

        // favour using java.net.URI for parsing into host and context-path
        try {
            URI parse = new URI(u);
            String host = parse.getHost();
            String path = parse.getPath();
            // if the path is just a trailing slash then skip it (eg it must be longer than just the slash itself)
            if (path != null && path.length() > 1) {
                int port = parse.getPort();
                if (port != 80 && port != 443) {
                    host += ":" + port;
                }
                if (!httpComponent) {
                    // include scheme for components that are not camel-http
                    String scheme = parse.getScheme();
                    if (scheme != null) {
                        host = scheme + "://" + host;
                    }
                }
                return new String[]{host, path};
            }
        } catch (URISyntaxException e) {
            // ignore
            return new String[]{u, null};
        }

        // no context path
        return new String[]{u, null};
    }
    
}
