package org.example

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.selectUnbiased
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class HandlerImpl(
    private val client: Client,
    private val context: CoroutineContext,
) : Handler {

    private val timeOut = 15L.toDuration(DurationUnit.SECONDS)
    override fun performOperation(id: String): ApplicationStatusResponse {
        return runBlocking {
            try {
                withContext(context) {
                    withTimeout(timeOut) {
                        val service1 = async {
                            action { client.getApplicationStatus1(id) }
                        }

                        val service2 = async {
                            action { client.getApplicationStatus2(id) }
                        }

                        val services = listOf(service1, service2)

                        val result = selectUnbiased {
                            services.forEach { service ->
                                service.onAwait { response -> response }
                            }
                        }
                        result
                    }
                }
            } catch (e: Throwable) {
                ApplicationStatusResponse.Failure(timeOut, 0)
            }
        }
    }


    suspend fun action(body: () -> Response): ApplicationStatusResponse {
        var result = body()
        var retryCounter = 0
        var duration = Duration.ZERO
        while (result is Response.RetryAfter) {
            delay(result.delay)
            retryCounter++
            val start = System.nanoTime()
            result = body()
            val end = System.nanoTime()
            duration = (end - start).toDuration(DurationUnit.NANOSECONDS)
        }

        return when (result) {
            is Response.Failure -> ApplicationStatusResponse.Failure(duration, retryCounter)
            is Response.Success -> ApplicationStatusResponse.Success(result.applicationId, result.applicationStatus)
            is Response.RetryAfter -> TODO()
        }
    }


}

sealed interface Response {
    data class Success(val applicationStatus: String, val applicationId: String) : Response
    data class RetryAfter(val delay: Duration) : Response
    data class Failure(val ex: Throwable) : Response
}

sealed interface ApplicationStatusResponse {
    data class Failure(val lastRequestTime: Duration?, val retriesCount: Int) : ApplicationStatusResponse
    data class Success(val id: String, val status: String) : ApplicationStatusResponse
}

fun interface Handler {
    fun performOperation(id: String): ApplicationStatusResponse
}

interface Client {
    //блокирующий вызов сервиса 1 для получения статуса заявки
    fun getApplicationStatus1(id: String): Response

    //блокирующий вызов сервиса 2 для получения статуса заявки
    fun getApplicationStatus2(id: String): Response
}