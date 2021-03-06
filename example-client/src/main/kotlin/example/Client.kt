/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package example

import google.example.HelloServiceClient
import google.example.hiRequest
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.runBlocking

class Client {

    companion object {

        @JvmStatic
        fun main(args: Array<String>) {
            try {
                Client().runExample()
                System.exit(0)
            } catch (t: Throwable) {
                System.err.println("Failed: $t")
            }
            System.exit(1)
        }
    }

    fun runExample() = runBlocking {
        // create a client with an insecure channel
        val client = HelloServiceClient.create(
            channel = ManagedChannelBuilder.forAddress("localhost", 8080)
                .usePlaintext()
                .build()
        )

        // call the API
        val result = client.hiThere(hiRequest {
            // set a normal field
            query = "Hello!"

            // set a repeated field
            tags = listOf("greeting", "salutation")

            // set a map field
            flags = mapOf(
                "hello" to "hi",
                "later" to "bye"
            )
        })

        // print the result
        println("The response was: ${result.body.result}")

        // shutdown
        client.shutdownChannel()
    }
}
