/*
 * Copyright (c) 2016 Kevin Herron
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 *
 * The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *   http://www.eclipse.org/org/documents/edl-v10.html.
 */

package org.eclipse.milo.opcua.stack.client.handlers;

import java.nio.ByteOrder;
import java.security.cert.X509Certificate;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageCodec;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.Timeout;
import org.eclipse.milo.opcua.stack.client.UaTcpStackClient;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.UaRuntimeException;
import org.eclipse.milo.opcua.stack.core.UaSerializationException;
import org.eclipse.milo.opcua.stack.core.UaServiceFaultException;
import org.eclipse.milo.opcua.stack.core.application.CertificateValidator;
import org.eclipse.milo.opcua.stack.core.channel.ChannelSecurity;
import org.eclipse.milo.opcua.stack.core.channel.ChunkDecoder;
import org.eclipse.milo.opcua.stack.core.channel.ChunkEncoder;
import org.eclipse.milo.opcua.stack.core.channel.ClientSecureChannel;
import org.eclipse.milo.opcua.stack.core.channel.MessageAbortedException;
import org.eclipse.milo.opcua.stack.core.channel.SerializationQueue;
import org.eclipse.milo.opcua.stack.core.channel.headers.AsymmetricSecurityHeader;
import org.eclipse.milo.opcua.stack.core.channel.headers.HeaderDecoder;
import org.eclipse.milo.opcua.stack.core.channel.messages.ErrorMessage;
import org.eclipse.milo.opcua.stack.core.channel.messages.MessageType;
import org.eclipse.milo.opcua.stack.core.channel.messages.TcpMessageDecoder;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.serialization.UaResponseMessage;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.enumerated.SecurityTokenRequestType;
import org.eclipse.milo.opcua.stack.core.types.structured.ChannelSecurityToken;
import org.eclipse.milo.opcua.stack.core.types.structured.CloseSecureChannelRequest;
import org.eclipse.milo.opcua.stack.core.types.structured.OpenSecureChannelRequest;
import org.eclipse.milo.opcua.stack.core.types.structured.OpenSecureChannelResponse;
import org.eclipse.milo.opcua.stack.core.types.structured.RequestHeader;
import org.eclipse.milo.opcua.stack.core.types.structured.ServiceFault;
import org.eclipse.milo.opcua.stack.core.util.BufferUtil;
import org.eclipse.milo.opcua.stack.core.util.CertificateUtil;
import org.eclipse.milo.opcua.stack.core.util.LongSequence;
import org.eclipse.milo.opcua.stack.core.util.NonceUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

public class UaTcpClientMessageHandler extends ByteToMessageCodec<UaRequestFuture> implements HeaderDecoder {

    public static final AttributeKey<Map<Long, UaRequestFuture>> KEY_PENDING_REQUEST_FUTURES =
        AttributeKey.valueOf("pending-request-futures");

    public static final int SECURE_CHANNEL_TIMEOUT_SECONDS = 10;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private List<ByteBuf> chunkBuffers = new LinkedList<>();

    private final AtomicReference<AsymmetricSecurityHeader> headerRef = new AtomicReference<>();

    private ScheduledFuture renewFuture;
    private Timeout secureChannelTimeout;

    private final Map<Long, UaRequestFuture> pending;
    private final LongSequence requestIdSequence;

    private final UaTcpStackClient client;
    private final ClientSecureChannel secureChannel;
    private final SerializationQueue serializationQueue;
    private final CompletableFuture<ClientSecureChannel> handshakeFuture;

    public UaTcpClientMessageHandler(
        UaTcpStackClient client,
        ClientSecureChannel secureChannel,
        SerializationQueue serializationQueue,
        CompletableFuture<ClientSecureChannel> handshakeFuture) {

        this.client = client;
        this.secureChannel = secureChannel;
        this.serializationQueue = serializationQueue;
        this.handshakeFuture = handshakeFuture;

        secureChannel
            .attr(KEY_PENDING_REQUEST_FUTURES)
            .setIfAbsent(Maps.newConcurrentMap());

        pending = secureChannel.attr(KEY_PENDING_REQUEST_FUTURES).get();

        secureChannel
            .attr(ClientSecureChannel.KEY_REQUEST_ID_SEQUENCE)
            .setIfAbsent(new LongSequence(1L, UInteger.MAX_VALUE));

        requestIdSequence = secureChannel.attr(ClientSecureChannel.KEY_REQUEST_ID_SEQUENCE).get();

        handshakeFuture.thenAccept(sc -> {
            Channel channel = sc.getChannel();

            channel.eventLoop().execute(() -> {
                List<UaRequestFuture> awaitingHandshake = channel.attr(
                    UaTcpClientAcknowledgeHandler.KEY_AWAITING_HANDSHAKE).get();

                if (awaitingHandshake != null) {
                    channel.attr(UaTcpClientAcknowledgeHandler.KEY_AWAITING_HANDSHAKE).remove();

                    logger.debug(
                        "{} message(s) queued before handshake completed; sending now.",
                        awaitingHandshake.size());

                    awaitingHandshake.forEach(channel::writeAndFlush);
                }
            });
        });
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        SecurityTokenRequestType requestType = secureChannel.getChannelId() == 0 ?
            SecurityTokenRequestType.Issue : SecurityTokenRequestType.Renew;

        secureChannelTimeout = client.getConfig().getWheelTimer().newTimeout(
            timeout -> {
                if (!timeout.isCancelled()) {
                    handshakeFuture.completeExceptionally(
                        new UaException(
                            StatusCodes.Bad_Timeout,
                            "timed out waiting for secure channel"));
                    ctx.close();
                }
            },
            SECURE_CHANNEL_TIMEOUT_SECONDS, TimeUnit.SECONDS
        );

        logger.debug("OpenSecureChannel timeout scheduled for +5s");

        sendOpenSecureChannelRequest(ctx, requestType);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (renewFuture != null) renewFuture.cancel(false);

        handshakeFuture.completeExceptionally(
            new UaException(StatusCodes.Bad_ConnectionClosed, "connection closed"));

        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error(
            "[remote={}] Exception caught: {}",
            ctx.channel().remoteAddress(), cause.getMessage(), cause);

        chunkBuffers.forEach(ReferenceCountUtil::safeRelease);
        chunkBuffers.clear();

        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof CloseSecureChannelRequest) {
            sendCloseSecureChannelRequest(ctx, (CloseSecureChannelRequest) evt);
        }
    }

    private void sendOpenSecureChannelRequest(ChannelHandlerContext ctx, SecurityTokenRequestType requestType) {
        ByteString clientNonce = secureChannel.isSymmetricSigningEnabled() ?
            NonceUtil.generateNonce(secureChannel.getSecurityPolicy()) :
            ByteString.NULL_VALUE;

        secureChannel.setLocalNonce(clientNonce);

        OpenSecureChannelRequest request = new OpenSecureChannelRequest(
            new RequestHeader(null, DateTime.now(), uint(0), uint(0), null, uint(0), null),
            uint(PROTOCOL_VERSION),
            requestType,
            secureChannel.getMessageSecurityMode(),
            secureChannel.getLocalNonce(),
            client.getChannelLifetime()
        );

        serializationQueue.encode((binaryEncoder, chunkEncoder) -> {
            ByteBuf messageBuffer = BufferUtil.buffer();

            try {
                binaryEncoder.setBuffer(messageBuffer);
                binaryEncoder.writeMessage(null, request);

                checkMessageSize(messageBuffer);

                chunkEncoder.encodeAsymmetric(
                    secureChannel,
                    requestIdSequence.getAndIncrement(),
                    messageBuffer,
                    MessageType.OpenSecureChannel,
                    new ChunkEncoder.Callback() {
                        @Override
                        public void onEncodingError(UaException ex) {
                            logger.error("Error encoding {}: {}", request, ex.getMessage(), ex);

                            ctx.close();
                        }

                        @Override
                        public void onMessageEncoded(List<ByteBuf> messageChunks, long requestId) {
                            CompositeByteBuf chunkComposite = BufferUtil.compositeBuffer();

                            for (ByteBuf chunk : messageChunks) {
                                chunkComposite.addComponent(chunk);
                                chunkComposite.writerIndex(chunkComposite.writerIndex() + chunk.readableBytes());
                            }

                            ctx.writeAndFlush(chunkComposite, ctx.voidPromise());

                            ChannelSecurity channelSecurity = secureChannel.getChannelSecurity();

                            long currentTokenId = -1L;
                            if (channelSecurity != null) {
                                currentTokenId = channelSecurity.getCurrentToken().getTokenId().longValue();
                            }

                            long previousTokenId = -1L;
                            if (channelSecurity != null) {
                                previousTokenId = channelSecurity.getPreviousToken()
                                    .map(token -> token.getTokenId().longValue())
                                    .orElse(-1L);
                            }

                            logger.debug(
                                "Sent OpenSecureChannelRequest ({}, id={}, currentToken={}, previousToken={}).",
                                request.getRequestType(),
                                secureChannel.getChannelId(),
                                currentTokenId,
                                previousTokenId
                            );
                        }
                    }
                );
            } finally {
                messageBuffer.release();
            }
        });
    }

    private void checkMessageSize(ByteBuf messageBuffer) throws UaSerializationException {
        int messageSize = messageBuffer.readableBytes();
        int remoteMaxMessageSize = serializationQueue.getParameters().getRemoteMaxMessageSize();

        if (remoteMaxMessageSize > 0 && messageSize > remoteMaxMessageSize) {
            throw new UaSerializationException(
                StatusCodes.Bad_RequestTooLarge,
                "request exceeds remote max message size: " +
                    messageSize + " > " + remoteMaxMessageSize);
        }
    }

    private void sendCloseSecureChannelRequest(ChannelHandlerContext ctx, CloseSecureChannelRequest request) {
        serializationQueue.encode((binaryEncoder, chunkEncoder) -> {
            ByteBuf messageBuffer = BufferUtil.buffer();

            try {
                binaryEncoder.setBuffer(messageBuffer);
                binaryEncoder.writeMessage(null, request);

                checkMessageSize(messageBuffer);

                chunkEncoder.encodeSymmetric(
                    secureChannel,
                    requestIdSequence.getAndIncrement(),
                    messageBuffer,
                    MessageType.CloseSecureChannel,
                    new ChunkEncoder.Callback() {
                        @Override
                        public void onEncodingError(UaException ex) {
                            logger.error("Error encoding {}: {}", request, ex.getMessage(), ex);

                            ctx.close();
                        }

                        @Override
                        public void onMessageEncoded(List<ByteBuf> messageChunks, long requestId) {
                            CompositeByteBuf chunkComposite = BufferUtil.compositeBuffer();

                            for (ByteBuf chunk : messageChunks) {
                                chunkComposite.addComponent(chunk);
                                chunkComposite.writerIndex(chunkComposite.writerIndex() + chunk.readableBytes());
                            }

                            ctx.writeAndFlush(chunkComposite).addListener(future -> ctx.close());

                            secureChannel.setChannelId(0);
                        }
                    }
                );
            } catch (UaSerializationException e) {
                handshakeFuture.completeExceptionally(e);
                ctx.close();
            } finally {
                messageBuffer.release();
            }
        });
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, UaRequestFuture request, ByteBuf buffer) throws Exception {
        serializationQueue.encode((binaryEncoder, chunkEncoder) -> {
            ByteBuf messageBuffer = BufferUtil.buffer();

            try {
                binaryEncoder.setBuffer(messageBuffer);
                binaryEncoder.writeMessage(null, request.getRequest());

                checkMessageSize(messageBuffer);

                chunkEncoder.encodeSymmetric(
                    secureChannel,
                    requestIdSequence.getAndIncrement(),
                    messageBuffer,
                    MessageType.SecureMessage,
                    new ChunkEncoder.Callback() {
                        @Override
                        public void onEncodingError(UaException ex) {
                            logger.error("Error encoding {}: {}",
                                request.getRequest(), ex.getMessage(), ex);

                            ctx.close();
                        }

                        @Override
                        public void onMessageEncoded(List<ByteBuf> messageChunks, long requestId) {
                            pending.put(requestId, request);

                            // No matter how we complete, make sure the entry in pending is removed.
                            // This covers the case where the request fails due to a timeout in the
                            // upper layers as well as normal completion.
                            request.getFuture().whenComplete((r, x) -> pending.remove(requestId));

                            CompositeByteBuf chunkComposite = BufferUtil.compositeBuffer();

                            for (ByteBuf chunk : messageChunks) {
                                chunkComposite.addComponent(chunk);
                                chunkComposite.writerIndex(chunkComposite.writerIndex() + chunk.readableBytes());
                            }

                            ctx.writeAndFlush(chunkComposite, ctx.voidPromise());
                        }
                    }
                );
            } catch (UaSerializationException e) {
                request.getFuture().completeExceptionally(e);
            } finally {
                messageBuffer.release();
            }
        });
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer, List<Object> out) throws Exception {
        if (buffer.readableBytes() >= HEADER_LENGTH) {
            buffer = buffer.order(ByteOrder.LITTLE_ENDIAN);

            if (buffer.readableBytes() >= getMessageLength(buffer)) {
                decodeMessage(ctx, buffer);
            }
        }
    }

    private void decodeMessage(ChannelHandlerContext ctx, ByteBuf buffer) throws UaException {
        int messageLength = getMessageLength(buffer);
        MessageType messageType = MessageType.fromMediumInt(buffer.getMedium(buffer.readerIndex()));

        switch (messageType) {
            case OpenSecureChannel:
                onOpenSecureChannel(ctx, buffer.readSlice(messageLength));
                break;

            case SecureMessage:
                onSecureMessage(ctx, buffer.readSlice(messageLength));
                break;

            case Error:
                onError(ctx, buffer.readSlice(messageLength));
                break;

            default:
                throw new UaException(
                    StatusCodes.Bad_TcpMessageTypeInvalid,
                    "unexpected MessageType: " + messageType);
        }
    }

    private boolean accumulateChunk(ByteBuf buffer) throws UaException {
        int maxChunkCount = serializationQueue.getParameters().getLocalMaxChunkCount();
        int maxChunkSize = serializationQueue.getParameters().getLocalReceiveBufferSize();

        int chunkSize = buffer.readerIndex(0).readableBytes();

        if (chunkSize > maxChunkSize) {
            throw new UaException(StatusCodes.Bad_TcpMessageTooLarge,
                String.format("max chunk size exceeded (%s)", maxChunkSize));
        }

        chunkBuffers.add(buffer.retain());

        if (maxChunkCount > 0 && chunkBuffers.size() > maxChunkCount) {
            throw new UaException(StatusCodes.Bad_TcpMessageTooLarge,
                String.format("max chunk count exceeded (%s)", maxChunkCount));
        }

        char chunkType = (char) buffer.getByte(3);

        return (chunkType == 'A' || chunkType == 'F');
    }

    private void onOpenSecureChannel(ChannelHandlerContext ctx, ByteBuf buffer) throws UaException {
        if (secureChannelTimeout != null) {
            if (secureChannelTimeout.cancel()) {
                logger.debug("OpenSecureChannel timeout canceled");

                secureChannelTimeout = null;
            } else {
                logger.warn("timed out waiting for secure channel");

                handshakeFuture.completeExceptionally(
                    new UaException(StatusCodes.Bad_Timeout,
                        "timed out waiting for secure channel"));
                ctx.close();
                return;
            }
        }

        buffer.skipBytes(3 + 1 + 4 + 4); // skip messageType, chunkType, messageSize, secureChannelId

        AsymmetricSecurityHeader securityHeader = AsymmetricSecurityHeader.decode(buffer);

        if (headerRef.compareAndSet(null, securityHeader)) {
            // first time we've received the header; validate and verify the server certificate
            CertificateValidator certificateValidator = client.getConfig().getCertificateValidator();

            SecurityPolicy securityPolicy = SecurityPolicy.fromUri(securityHeader.getSecurityPolicyUri());

            if (securityPolicy != SecurityPolicy.None) {
                ByteString serverCertificateBytes = securityHeader.getSenderCertificate();

                List<X509Certificate> serverCertificateChain =
                    CertificateUtil.decodeCertificates(serverCertificateBytes.bytesOrEmpty());

                certificateValidator.validate(serverCertificateChain.get(0));
                certificateValidator.verifyTrustChain(serverCertificateChain);
            }
        } else {
            if (!securityHeader.equals(headerRef.get())) {
                throw new UaException(StatusCodes.Bad_SecurityChecksFailed,
                    "subsequent AsymmetricSecurityHeader did not match");
            }
        }

        if (accumulateChunk(buffer)) {
            final List<ByteBuf> buffersToDecode = ImmutableList.copyOf(chunkBuffers);
            chunkBuffers = new LinkedList<>();

            serializationQueue.decode((binaryDecoder, chunkDecoder) ->
                chunkDecoder.decodeAsymmetric(secureChannel, buffersToDecode, new ChunkDecoder.Callback() {
                    @Override
                    public void onDecodingError(UaException ex) {
                        logger.error(
                            "Error decoding asymmetric message: {}",
                            ex.getMessage(), ex);

                        handshakeFuture.completeExceptionally(ex);

                        ctx.close();
                    }

                    @Override
                    public void onMessageAborted(MessageAbortedException ex) {
                        logger.warn(
                            "Asymmetric message aborted. error={} reason={}",
                            ex.getStatusCode(), ex.getMessage());
                    }

                    @Override
                    public void onMessageDecoded(ByteBuf message, long requestId) {
                        try {
                            UaResponseMessage response = (UaResponseMessage) binaryDecoder
                                .setBuffer(message)
                                .readMessage(null);

                            StatusCode serviceResult = response.getResponseHeader().getServiceResult();

                            if (serviceResult.isGood()) {
                                OpenSecureChannelResponse oscr = (OpenSecureChannelResponse) response;

                                secureChannel.setChannelId(oscr.getSecurityToken().getChannelId().longValue());
                                logger.debug("Received OpenSecureChannelResponse.");

                                installSecurityToken(ctx, oscr);

                                handshakeFuture.complete(secureChannel);
                            } else {
                                ServiceFault serviceFault = (response instanceof ServiceFault) ?
                                    (ServiceFault) response : new ServiceFault(response.getResponseHeader());

                                handshakeFuture.completeExceptionally(new UaServiceFaultException(serviceFault));
                                ctx.close();
                            }
                        } catch (Throwable t) {
                            logger.error("Error decoding OpenSecureChannelResponse", t);

                            handshakeFuture.completeExceptionally(t);
                            ctx.close();
                        } finally {
                            message.release();
                        }
                    }
                })
            );
        }
    }

    private void installSecurityToken(ChannelHandlerContext ctx, OpenSecureChannelResponse response) {
        ChannelSecurity.SecuritySecrets newKeys = null;
        if (response.getServerProtocolVersion().longValue() < PROTOCOL_VERSION) {
            throw new UaRuntimeException(StatusCodes.Bad_ProtocolVersionUnsupported,
                "server protocol version unsupported: " + response.getServerProtocolVersion());
        }

        ChannelSecurityToken newToken = response.getSecurityToken();

        if (secureChannel.isSymmetricSigningEnabled()) {
            secureChannel.setRemoteNonce(response.getServerNonce());

            newKeys = ChannelSecurity.generateKeyPair(
                secureChannel,
                secureChannel.getLocalNonce(),
                secureChannel.getRemoteNonce()
            );
        }

        ChannelSecurity oldSecrets = secureChannel.getChannelSecurity();
        ChannelSecurity.SecuritySecrets oldKeys = oldSecrets != null ? oldSecrets.getCurrentKeys() : null;
        ChannelSecurityToken oldToken = oldSecrets != null ? oldSecrets.getCurrentToken() : null;

        secureChannel.setChannelSecurity(new ChannelSecurity(newKeys, newToken, oldKeys, oldToken));

        DateTime createdAt = response.getSecurityToken().getCreatedAt();
        long revisedLifetime = response.getSecurityToken().getRevisedLifetime().longValue();

        if (revisedLifetime > 0) {
            long renewAt = (long) (revisedLifetime * 0.75);
            renewFuture = ctx.executor().schedule(
                () -> sendOpenSecureChannelRequest(ctx, SecurityTokenRequestType.Renew),
                renewAt, TimeUnit.MILLISECONDS);
        } else {
            logger.warn("Server revised secure channel lifetime to 0; renewal will not occur.");
        }

        ctx.executor().execute(() -> {
            // SecureChannel is ready; remove the acknowledge handler.
            if (ctx.pipeline().get(UaTcpClientAcknowledgeHandler.class) != null) {
                ctx.pipeline().remove(UaTcpClientAcknowledgeHandler.class);
            }
        });

        ChannelSecurity channelSecurity = secureChannel.getChannelSecurity();

        long currentTokenId = channelSecurity.getCurrentToken().getTokenId().longValue();

        long previousTokenId = channelSecurity.getPreviousToken()
            .map(t -> t.getTokenId().longValue()).orElse(-1L);

        logger.debug(
            "SecureChannel id={}, currentTokenId={}, previousTokenId={}, lifetime={}ms, createdAt={}",
            secureChannel.getChannelId(), currentTokenId, previousTokenId, revisedLifetime, createdAt);
    }

    private void onSecureMessage(ChannelHandlerContext ctx, ByteBuf buffer) throws UaException {
        buffer.skipBytes(3 + 1 + 4); // skip messageType, chunkType, messageSize

        long secureChannelId = buffer.readUnsignedInt();
        if (secureChannelId != secureChannel.getChannelId()) {
            throw new UaException(StatusCodes.Bad_SecureChannelIdInvalid,
                "invalid secure channel id: " + secureChannelId);
        }

        if (accumulateChunk(buffer)) {
            final List<ByteBuf> buffersToDecode = ImmutableList.copyOf(chunkBuffers);
            chunkBuffers = new LinkedList<>();

            serializationQueue.decode((decoder, chunkDecoder) -> {
                try {
                    validateChunkHeaders(buffersToDecode);
                } catch (UaException e) {
                    logger.error("Error validating chunk headers: {}", e.getMessage(), e);
                    buffersToDecode.forEach(ReferenceCountUtil::safeRelease);
                    ctx.close();
                    return;
                }

                chunkDecoder.decodeSymmetric(secureChannel, buffersToDecode, new ChunkDecoder.Callback() {
                    @Override
                    public void onDecodingError(UaException ex) {
                        logger.error(
                            "Error decoding symmetric message: {}",
                            ex.getMessage(), ex);

                        ctx.close();
                    }

                    @Override
                    public void onMessageAborted(MessageAbortedException ex) {
                        logger.warn(
                            "Received message abort chunk; error={}, reason={}",
                            ex.getStatusCode(), ex.getMessage());

                        long requestId = ex.getRequestId();
                        UaRequestFuture request = pending.remove(requestId);

                        if (request != null) {
                            client.getExecutorService().execute(
                                () -> request.getFuture().completeExceptionally(ex)
                            );
                        } else {
                            logger.warn("No UaRequestFuture for requestId={}", requestId);
                        }
                    }

                    @Override
                    public void onMessageDecoded(ByteBuf message, long requestId) {
                        UaRequestFuture request = pending.remove(requestId);

                        try {
                            if (request != null) {
                                UaResponseMessage response = (UaResponseMessage) decoder
                                    .setBuffer(message)
                                    .readMessage(null);

                                request.getFuture().complete(response);
                            } else {
                                logger.warn("No UaRequestFuture for requestId={}", requestId);
                            }
                        } catch (Throwable t) {
                            logger.error("Error decoding UaResponseMessage", t);

                            if (request != null) {
                                request.getFuture().completeExceptionally(t);
                            }
                        } finally {
                            message.release();
                        }
                    }
                });
            });
        }
    }

    private void validateChunkHeaders(List<ByteBuf> chunkBuffers) throws UaException {
        ChannelSecurity channelSecurity = secureChannel.getChannelSecurity();
        long currentTokenId = channelSecurity.getCurrentToken().getTokenId().longValue();
        long previousTokenId = channelSecurity.getPreviousToken()
            .map(t -> t.getTokenId().longValue())
            .orElse(-1L);

        for (ByteBuf chunkBuffer : chunkBuffers) {
            // tokenId starts after messageType + chunkType + messageSize + secureChannelId
            long tokenId = chunkBuffer.getUnsignedInt(3 + 1 + 4 + 4);

            if (tokenId != currentTokenId && tokenId != previousTokenId) {
                String message = String.format(
                    "received unknown secure channel token: " +
                        "tokenId=%s currentTokenId=%s previousTokenId=%s",
                    tokenId, currentTokenId, previousTokenId
                );

                throw new UaException(StatusCodes.Bad_SecureChannelTokenUnknown, message);
            }
        }
    }

    private void onError(ChannelHandlerContext ctx, ByteBuf buffer) {
        try {
            ErrorMessage errorMessage = TcpMessageDecoder.decodeError(buffer);
            StatusCode statusCode = errorMessage.getError();

            logger.error("[remote={}] errorMessage={}", ctx.channel().remoteAddress(), errorMessage);

            handshakeFuture.completeExceptionally(new UaException(statusCode, errorMessage.getReason()));
        } catch (UaException e) {
            logger.error(
                "[remote={}] An exception occurred while decoding an error message: {}",
                ctx.channel().remoteAddress(), e.getMessage(), e);

            handshakeFuture.completeExceptionally(e);
        } finally {
            ctx.close();
        }
    }

}
