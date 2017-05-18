/*
 * Copyright 2014 - 2017 the original author or authors.
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

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;

import static java.util.Arrays.asList;

public class JExample extends JExampleContext {
    private ActorMaterializer actorMaterializer;

    public JExample() throws Exception {
        super();
        ActorSystem actorSystem = ActorSystem.create("example");
        actorMaterializer = ActorMaterializer.create(actorSystem);
    }

    public Runnable setup() {
        Source<String, NotUsed> tcpLineSource =
                receiveBody(tcpEndpointUri, String.class);

        Source<String, NotUsed> fileLineSource =
                receiveBody(fileEndpointUri, String.class).mapConcat(s -> asList(s.split("\\r\\n|\\n|\\r")));

        Source<String, NotUsed> linePrefixSource =
                Source.range(1, Integer.MAX_VALUE).via(sendRequestBody(serviceEndpointUri, String.class));

        Source<String, NotUsed> stream =
                tcpLineSource
                        .merge(fileLineSource)
                        .zipWith(linePrefixSource, (l, n) -> n.concat(l))
                        .via(sendBody(printerEndpointUri));

        return () -> { stream.runWith(Sink.ignore(), actorMaterializer); };
    }

    public static void main(String... args) throws Exception {
        new JExample().setup().run();
    }
}
