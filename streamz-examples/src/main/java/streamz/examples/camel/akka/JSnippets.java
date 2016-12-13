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
import akka.stream.javadsl.Source;
import streamz.camel.StreamContext;
import streamz.camel.StreamMessage;
import streamz.camel.akka.javadsl.JavaDsl;

public class JSnippets implements JavaDsl {
    private ActorSystem actorSystem = ActorSystem.create("example");
    private ActorMaterializer actorMaterializer = ActorMaterializer.create(actorSystem);
    private StreamContext streamContext = StreamContext.create();

    @Override
    public StreamContext streamContext() {
        return streamContext;
    }

    public void snippets() {
        Source<StreamMessage<Integer>, NotUsed> s =
                // receive stream message from endpoint
                receive("seda:q1", String.class)
                        // in-only message exchange with endpoint and continue stream with in-message
                        .via(send("seda:q2"))
                        // in-out message exchange with endpoint and continue stream with out-message
                        .via(request("bean:service?method=weight", Integer.class));

        s.runForeach(System.out::println, actorMaterializer);

        Source<StreamMessage<String>, NotUsed> s1 = receive("seda:q1", String.class);
        Source<StreamMessage<String>, NotUsed> s2 = s1.via(send("seda:q2", 3));
        Source<StreamMessage<Integer>, NotUsed> s3 = s2.via(request("bean:service?method=weight", 3, Integer.class));

        Source<String, NotUsed> s1b = receiveBody("seda:q1", String.class);
        Source<String, NotUsed> s2b = s1b.via(sendBody("seda:q2"));
        Source<Integer, NotUsed> s3b = s2b.via(requestBody("bean:service?method=weight", Integer.class));
    }
}
