/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.ethsigner.core.requesthandler;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_GATEWAY;
import static io.netty.handler.codec.http.HttpResponseStatus.GATEWAY_TIMEOUT;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;

import tech.pegasys.ethsigner.core.requesthandler.sendtransaction.DownstreamPathCalculator;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLHandshakeException;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class VertxRequestTransmitter implements RequestTransmitter {

  private static final Logger LOG = LogManager.getLogger();

  private final Vertx vertx;
  private final Duration httpRequestTimeout;
  private final ResponseBodyHandler bodyHandler;
  private final HttpClient downStreamConnection;
  private final DownstreamPathCalculator downstreamPathCalculator;

  public VertxRequestTransmitter(
      final Vertx vertx,
      final HttpClient downStreamConnection,
      final Duration httpRequestTimeout,
      final DownstreamPathCalculator downstreamPathCalculator,
      final ResponseBodyHandler bodyHandler) {
    this.vertx = vertx;
    this.httpRequestTimeout = httpRequestTimeout;
    this.bodyHandler = bodyHandler;
    this.downStreamConnection = downStreamConnection;
    this.downstreamPathCalculator = downstreamPathCalculator;
  }

  @Override
  public void postRequest(final Map<String, String> headers, final String path, final String body) {
    final String fullPath = downstreamPathCalculator.calculateDownstreamPath(path);
    final HttpClientRequest request =
        downStreamConnection.request(HttpMethod.POST, fullPath, this::handleResponse);
    request.setTimeout(httpRequestTimeout.toMillis());
    request.exceptionHandler(this::handleException);
    request.headers().setAll(headers);
    request.setChunked(false);
    request.end(body);
  }

  private void handleException(final Throwable thrown) {
    vertx.executeBlocking(
        future -> {
          LOG.error("Transmission failed", thrown);
          if (thrown instanceof TimeoutException || thrown instanceof ConnectException) {
            bodyHandler.handleTransmissionFailure(GATEWAY_TIMEOUT, thrown);
          } else if (thrown instanceof SSLHandshakeException) {
            bodyHandler.handleTransmissionFailure(BAD_GATEWAY, thrown);
          } else {
            bodyHandler.handleTransmissionFailure(INTERNAL_SERVER_ERROR, thrown);
          }
        },
        false,
        res -> {
          if (res.failed()) {
            LOG.error("Reporting failure failed", res.cause());
          }
        });
  }

  private void handleResponse(final HttpClientResponse response) {
    logResponse(response);
    response.bodyHandler(
        body ->
            vertx.executeBlocking(
                future -> {
                  final Map<String, String> responseHeaders = new HashMap<>();
                  response
                      .headers()
                      .forEach(entry -> responseHeaders.put(entry.getKey(), entry.getValue()));
                  bodyHandler.handleResponseBody(
                      responseHeaders,
                      response.statusCode(),
                      body.toString(StandardCharsets.UTF_8));
                },
                false,
                res -> {
                  if (res.failed()) {
                    LOG.error(
                        "An unhandled error occurred while processing a response", res.cause());
                    // need to actually fail it
                    bodyHandler.handleTransmissionFailure(INTERNAL_SERVER_ERROR, res.cause());
                  }
                }));
  }

  private void logResponse(final HttpClientResponse response) {
    LOG.debug("Response status: {}", response.statusCode());
  }
}
