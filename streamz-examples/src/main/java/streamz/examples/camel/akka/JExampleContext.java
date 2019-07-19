/*
 * Copyright 2014 - 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package streamz.examples.camel.akka;

import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.impl.SimpleRegistry;
import streamz.camel.StreamContext;
import streamz.camel.akka.javadsl.JavaDsl;

public class JExampleContext implements JavaDsl {
    public static String tcpEndpointUri =
            "netty4:tcp://localhost:5150?sync=false&textline=true&encoding=utf-8";

    public static String fileEndpointUri =
            "file:input?charset=utf-8";

    public static String serviceEndpointUri =
            "bean:exampleService?method=linePrefix";

    public static String printerEndpointUri =
            "stream:out";

    private StreamContext streamContext;

    public JExampleContext() throws Exception {
        SimpleRegistry camelRegistry = new SimpleRegistry();
        DefaultCamelContext camelContext = new DefaultCamelContext();

        camelRegistry.put("exampleService", new JExampleService());
        camelContext.setRegistry(camelRegistry);
        camelContext.start();

        streamContext = StreamContext.create(camelContext);
    }

    @Override
    public StreamContext streamContext() {
        return streamContext;
    }
}
