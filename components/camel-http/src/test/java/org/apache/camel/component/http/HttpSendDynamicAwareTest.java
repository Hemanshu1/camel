/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.http;

import org.apache.camel.RoutesBuilder;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.http.handler.DrinkValidationHandler;
import org.apache.camel.test.AvailablePortFinder;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HttpSendDynamicAwareTest extends BaseHttpTest {

    private static final int PORT = AvailablePortFinder.getNextAvailable();
    private Server localServer;

    @Before
    @Override
    public void setUp() throws Exception {
        localServer = new Server(PORT);
        localServer.setHandler(handlers(
            contextHandler("/bar", new DrinkValidationHandler("GET", null, null, "drink"))
        ));
        localServer.start();

        super.setUp();
    }

    @After
    @Override
    public void tearDown() throws Exception {
        super.tearDown();

        if (localServer != null) {
            localServer.stop();
        }
    }

    @Override
    protected RoutesBuilder createRouteBuilder() throws Exception {
        return new RouteBuilder() {
            @Override
            public void configure() throws Exception {
                from("direct:start")
                    .toD("http://localhost:" + PORT + "/bar?throwExceptionOnFailure=false&drink=${header.drink}");
            }
        };
    }

    @Test
    public void testDynamicAware() throws Exception {
        String out = fluentTemplate.to("direct:start").withHeader("drink", "beer").request(String.class);
        assertEquals("Drinking beer", out);

        out = fluentTemplate.to("direct:start").withHeader("drink", "wine").request(String.class);
        assertEquals("Drinking wine", out);

        // and there should only be one http endpoint
        boolean found = context.getEndpointMap().containsKey("http://localhost:" + PORT + "/bar?throwExceptionOnFailure=false");
        assertTrue("Should find static uri", found);

        // we only have direct and http
        assertEquals(2, context.getEndpointMap().size());
    }

}
