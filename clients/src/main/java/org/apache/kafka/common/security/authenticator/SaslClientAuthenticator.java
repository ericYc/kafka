/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.common.security.authenticator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.util.Arrays;
import java.util.Map;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.AuthorizeCallback;
import javax.security.sasl.RealmCallback;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslException;

import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.UnsupportedSaslMechanismException;
import org.apache.kafka.common.network.Authenticator;
import org.apache.kafka.common.network.Mode;
import org.apache.kafka.common.network.NetworkSend;
import org.apache.kafka.common.network.NetworkReceive;
import org.apache.kafka.common.network.TransportLayer;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.protocol.ProtoUtils;
import org.apache.kafka.common.protocol.types.SchemaException;
import org.apache.kafka.common.protocol.types.Struct;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.requests.RequestSend;
import org.apache.kafka.common.requests.ResponseHeader;
import org.apache.kafka.common.requests.SaslHandshakeRequest;
import org.apache.kafka.common.requests.SaslHandshakeResponse;
import org.apache.kafka.common.security.auth.AuthCallbackHandler;
import org.apache.kafka.common.security.auth.KafkaPrincipal;
import org.apache.kafka.common.security.auth.PrincipalBuilder;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SaslClientAuthenticator implements Authenticator {

    public enum SaslState {
        SEND_HANDSHAKE_REQUEST, RECEIVE_HANDSHAKE_RESPONSE, INITIAL, INTERMEDIATE, COMPLETE, FAILED
    }

    private static final Logger LOG = LoggerFactory.getLogger(SaslClientAuthenticator.class);

    private String mechanism;
    private final Subject subject;
    private final String servicePrincipal;
    private final String host;
    private final String node;

    // assigned in `configure`
    private SaslClient saslClient;
    private Map<String, ?> configs;
    private String clientPrincipalName;
    private AuthCallbackHandler callbackHandler;
    private TransportLayer transportLayer;

    // buffers used in `authenticate`
    private NetworkReceive netInBuffer;
    private NetworkSend netOutBuffer;

    private SaslState pendingSaslState;
    private SaslState saslState;
    private int correlationId;
    private RequestHeader currentRequestHeader;

    public SaslClientAuthenticator(String node, Subject subject, String servicePrincipal, String host) throws IOException {
        this.node = node;
        this.subject = subject;
        this.host = host;
        this.servicePrincipal = servicePrincipal;
        this.correlationId = -1;
    }

    public void configure(TransportLayer transportLayer, PrincipalBuilder principalBuilder, Map<String, ?> configs) throws KafkaException {
        try {
            this.transportLayer = transportLayer;
            this.configs = configs;
            mechanism = (String) this.configs.get(SaslConfigs.SASL_MECHANISM);
            if (mechanism == null)
                throw new IllegalArgumentException("SASL mechanism not specified");
            // Since 0.9.0.x servers expect to see GSSAPI packets without mechanism being sent first, send
            // mechanism to server only for non-GSSAPI.
            setSaslState(mechanism.equals(SaslConfigs.GSSAPI_MECHANISM) ? SaslState.INITIAL : SaslState.SEND_HANDSHAKE_REQUEST);

            // determine client principal from subject.
            if (!subject.getPrincipals().isEmpty()) {
                Principal clientPrincipal = subject.getPrincipals().iterator().next();
                this.clientPrincipalName = clientPrincipal.getName();
            } else {
                clientPrincipalName = null;
            }
            callbackHandler = new ClientCallbackHandler();

            saslClient = createSaslClient();
        } catch (Exception e) {
            throw new KafkaException("Failed to configure SaslClientAuthenticator", e);
        }
    }

    private SaslClient createSaslClient() {
        try {
            callbackHandler.configure(configs, Mode.CLIENT, subject, mechanism);
            return Subject.doAs(subject, new PrivilegedExceptionAction<SaslClient>() {
                public SaslClient run() throws SaslException {
                    String[] mechs = {mechanism};
                    LOG.debug("Creating SaslClient: client={};service={};serviceHostname={};mechs={}",
                        clientPrincipalName, servicePrincipal, host, Arrays.toString(mechs));
                    return Sasl.createSaslClient(mechs, clientPrincipalName, servicePrincipal, host, configs, callbackHandler);
                }
            });
        } catch (PrivilegedActionException e) {
            throw new KafkaException("Failed to create SaslClient with mechanism " + mechanism, e.getCause());
        }
    }

    /**
     * Sends an empty message to the server to initiate the authentication process. It then evaluates server challenges
     * via `SaslClient.evaluateChallenge` and returns client responses until authentication succeeds or fails.
     *
     * The messages are sent and received as size delimited bytes that consists of a 4 byte network-ordered size N
     * followed by N bytes representing the opaque payload.
     */
    public void authenticate() throws IOException {
        if (netOutBuffer != null && !flushNetOutBufferAndUpdateInterestOps())
            return;

        switch (saslState) {
            case SEND_HANDSHAKE_REQUEST:
                String clientId = (String) configs.get(CommonClientConfigs.CLIENT_ID_CONFIG);
                currentRequestHeader = new RequestHeader(ApiKeys.SASL_HANDSHAKE.id, clientId, correlationId++);
                SaslHandshakeRequest handshakeRequest = new SaslHandshakeRequest(mechanism);
                send(RequestSend.serialize(currentRequestHeader, handshakeRequest.toStruct()));
                setSaslState(SaslState.RECEIVE_HANDSHAKE_RESPONSE);
                break;
            case RECEIVE_HANDSHAKE_RESPONSE:
                byte[] responseBytes = receiveBytes();
                if (responseBytes == null)
                    break;
                else {
                    try {
                        handleKafkaResponse(currentRequestHeader, responseBytes);
                        currentRequestHeader = null;
                    } catch (Exception e) {
                        setSaslState(SaslState.FAILED);
                        throw e;
                    }
                    setSaslState(SaslState.INITIAL);
                    // Fall through and start SASL authentication using the configured client mechanism
                }
            case INITIAL:
                sendSaslToken(new byte[0], true);
                setSaslState(SaslState.INTERMEDIATE);
                break;
            case INTERMEDIATE:
                byte[] serverToken = receiveBytes();
                if (serverToken != null) {
                    sendSaslToken(serverToken, false);
                }
                if (saslClient.isComplete()) {
                    setSaslState(SaslState.COMPLETE);
                    transportLayer.removeInterestOps(SelectionKey.OP_WRITE);
                }
                break;
            case COMPLETE:
                break;
            case FAILED:
                throw new IOException("SASL handshake failed");
        }
    }

    private void setSaslState(SaslState saslState) {
        if (netOutBuffer != null && !netOutBuffer.completed())
            pendingSaslState = saslState;
        else {
            this.pendingSaslState = null;
            this.saslState = saslState;
            LOG.debug("Set SASL client state to " + saslState);
        }
    }

    private void sendSaslToken(byte[] serverToken, boolean isInitial) throws IOException {
        if (!saslClient.isComplete()) {
            try {
                byte[] saslToken = createSaslToken(serverToken, isInitial);
                if (saslToken != null) {
                    send(ByteBuffer.wrap(saslToken));
                }
            } catch (IOException e) {
                setSaslState(SaslState.FAILED);
                throw e;
            }
        }
    }

    private void send(ByteBuffer buffer) throws IOException {
        try {
            netOutBuffer = new NetworkSend(node, buffer);
            flushNetOutBufferAndUpdateInterestOps();
        } catch (IOException e) {
            setSaslState(SaslState.FAILED);
            throw e;
        }
    }

    private boolean flushNetOutBufferAndUpdateInterestOps() throws IOException {
        boolean flushedCompletely = flushNetOutBuffer();
        if (flushedCompletely) {
            transportLayer.removeInterestOps(SelectionKey.OP_WRITE);
            if (pendingSaslState != null)
                setSaslState(pendingSaslState);
        } else
            transportLayer.addInterestOps(SelectionKey.OP_WRITE);
        return flushedCompletely;
    }

    private byte[] receiveBytes() throws IOException {
        if (netInBuffer == null) netInBuffer = new NetworkReceive(node);
        netInBuffer.readFrom(transportLayer);
        byte[] serverToken = null;
        if (netInBuffer.complete()) {
            netInBuffer.payload().rewind();
            serverToken = new byte[netInBuffer.payload().remaining()];
            netInBuffer.payload().get(serverToken, 0, serverToken.length);
            netInBuffer = null; // reset the networkReceive as we read all the data.
        }
        return serverToken;
    }

    public Principal principal() {
        return new KafkaPrincipal(KafkaPrincipal.USER_TYPE, clientPrincipalName);
    }

    public boolean complete() {
        return saslState == SaslState.COMPLETE;
    }

    public void close() throws IOException {
        if (saslClient != null)
            saslClient.dispose();
        if (callbackHandler != null)
            callbackHandler.close();
    }

    private byte[] createSaslToken(final byte[] saslToken, boolean isInitial) throws SaslException {
        if (saslToken == null)
            throw new SaslException("Error authenticating with the Kafka Broker: received a `null` saslToken.");

        try {
            if (isInitial && !saslClient.hasInitialResponse())
                return saslToken;
            else
                return Subject.doAs(subject, new PrivilegedExceptionAction<byte[]>() {
                    public byte[] run() throws SaslException {
                        return saslClient.evaluateChallenge(saslToken);
                    }
                });
        } catch (PrivilegedActionException e) {
            String error = "An error: (" + e + ") occurred when evaluating SASL token received from the Kafka Broker.";
            // Try to provide hints to use about what went wrong so they can fix their configuration.
            // TODO: introspect about e: look for GSS information.
            final String unknownServerErrorText =
                "(Mechanism level: Server not found in Kerberos database (7) - UNKNOWN_SERVER)";
            if (e.toString().indexOf(unknownServerErrorText) > -1) {
                error += " This may be caused by Java's being unable to resolve the Kafka Broker's" +
                    " hostname correctly. You may want to try to adding" +
                    " '-Dsun.net.spi.nameservice.provider.1=dns,sun' to your client's JVMFLAGS environment." +
                    " Users must configure FQDN of kafka brokers when authenticating using SASL and" +
                    " `socketChannel.socket().getInetAddress().getHostName()` must match the hostname in `principal/hostname@realm`";
            }
            error += " Kafka Client will go to AUTH_FAILED state.";
            //Unwrap the SaslException inside `PrivilegedActionException`
            throw new SaslException(error, e.getCause());
        }
    }

    private boolean flushNetOutBuffer() throws IOException {
        if (!netOutBuffer.completed()) {
            netOutBuffer.writeTo(transportLayer);
        }
        return netOutBuffer.completed();
    }

    private void handleKafkaResponse(RequestHeader requestHeader, byte[] responseBytes) {
        ByteBuffer responseBuffer = ByteBuffer.wrap(responseBytes);
        ResponseHeader responseHeader = ResponseHeader.parse(responseBuffer);
        if (responseHeader.correlationId() != requestHeader.correlationId()) {
            throw new IllegalStateException("Correlation id for response (" + responseHeader.correlationId()
                    + ") does not match request (" + requestHeader.correlationId() + ")");
        }
        Struct struct;
        try {
            struct = ProtoUtils.responseSchema(requestHeader.apiKey(), requestHeader.apiVersion()).read(responseBuffer);
        } catch (SchemaException e) {
            LOG.debug("Invalid SASL mechanism response, server may be expecting only GSSAPI tokens");
            throw new AuthenticationException("Invalid SASL mechanism response", e);
        }
        ApiKeys apiKey = ApiKeys.forId(requestHeader.apiKey());
        switch (apiKey) {
            case SASL_HANDSHAKE:
                handleSaslHandshakeResponse(new SaslHandshakeResponse(struct));
                break;
            default:
                throw new IllegalStateException("Unexpected API key during handshake: " + apiKey);
        }
    }

    private void handleSaslHandshakeResponse(SaslHandshakeResponse response) {
        if (response.errorCode() == Errors.UNSUPPORTED_SASL_MECHANISM.code()) {
            throw new UnsupportedSaslMechanismException(String.format("Client saslMechanism '%s' not enabled in the server, enabled mechanisms are %s",
                    mechanism, response.enabledMechanisms()));
        } else if (response.errorCode() != 0) {
            throw new AuthenticationException(String.format("Unknown error code %d, client mechanism is %s, enabled mechanisms are %s",
                    response.errorCode(), mechanism, response.enabledMechanisms()));
        }
    }

    public static class ClientCallbackHandler implements AuthCallbackHandler {

        private boolean isKerberos;
        private Subject subject;

        @Override
        public void configure(Map<String, ?> configs, Mode mode, Subject subject, String mechanism) {
            this.isKerberos = mechanism.equals(SaslConfigs.GSSAPI_MECHANISM);
            this.subject = subject;
        }

        @Override
        public void handle(Callback[] callbacks) throws UnsupportedCallbackException {
            for (Callback callback : callbacks) {
                if (callback instanceof NameCallback) {
                    NameCallback nc = (NameCallback) callback;
                    if (!isKerberos && subject != null && !subject.getPublicCredentials(String.class).isEmpty()) {
                        nc.setName(subject.getPublicCredentials(String.class).iterator().next());
                    } else
                        nc.setName(nc.getDefaultName());
                } else if (callback instanceof PasswordCallback) {
                    if (!isKerberos && subject != null && !subject.getPrivateCredentials(String.class).isEmpty()) {
                        char [] password = subject.getPrivateCredentials(String.class).iterator().next().toCharArray();
                        ((PasswordCallback) callback).setPassword(password);
                    } else {
                        // Call `setPassword` once we support obtaining a password from the user and update message below
                        String errorMessage = "Could not login: the client is being asked for a password, but the Kafka" +
                                 " client code does not currently support obtaining a password from the user.";
                        if (isKerberos) {
                            errorMessage += " Make sure -Djava.security.auth.login.config property passed to JVM and" +
                                 " the client is configured to use a ticket cache (using" +
                                 " the JAAS configuration setting 'useTicketCache=true)'. Make sure you are using" +
                                 " FQDN of the Kafka broker you are trying to connect to.";
                        }
                        throw new UnsupportedCallbackException(callback, errorMessage);
                    }
                } else if (callback instanceof RealmCallback) {
                    RealmCallback rc = (RealmCallback) callback;
                    rc.setText(rc.getDefaultText());
                } else if (callback instanceof AuthorizeCallback) {
                    AuthorizeCallback ac = (AuthorizeCallback) callback;
                    String authId = ac.getAuthenticationID();
                    String authzId = ac.getAuthorizationID();
                    ac.setAuthorized(authId.equals(authzId));
                    if (ac.isAuthorized())
                        ac.setAuthorizedID(authzId);
                } else {
                    throw new UnsupportedCallbackException(callback, "Unrecognized SASL ClientCallback");
                }
            }
        }

        @Override
        public void close() {
        }
    }
}
